// 用 Git Data API 重建整条提交链并强推远端 ref（git push 走不通 github.com:443 时的兜底）。
// 用途：历史重写后，本地提交 SHA 全部变化且无法快进，必须把新链整体搬到远端。
//
// **先读这条**：本脚本只能保证「文件内容与本地一致」，**无法保证 commit SHA 与本地一致**。
// GitHub 的 POST /git/commits 会规范化提交信息（实测会去掉结尾换行），
// 所以即使 message/tree/author 都逐字节正确，SHA 也必然不同。
// 因此：历史重写后的强推**首选 `git push --force`**（字节级一致），
// 本脚本只用于「git push 长时间不通、但急着把干净历史搬上去」的兜底场景。
//
// 关键点（每一条都是实际踩过的坑）：
// 1. 读子进程输出时，`cat-file blob` 必须按 latin1 读以保字节；
//    `ls-tree` / `log` 必须按 utf8 读，否则中文路径会被读坏。
// 2. `ls-tree` 必须加 `-z`。不加时含中文的路径会被 git 输出成八进制转义
//    "\344\275\240..."，直接当路径上传会建出字面量名全错的文件。
// 3. 不要用 base_tree。它是「增量合并」语义（保留基线里未列出的条目），
//    而本地 tree 是完整快照，语义不等价 —— v0.11.1 上就因此算出不同 SHA。
//    每棵树都从零全量重建。
// 4. mode 必须是 040000（tree）/ 100644 / 100755（blob），传 40000 会报错。
// 5. 提交信息用 `git cat-file commit` 取原始字节，不能用 `%B`（会多补一个尾部换行）。
// 6. 逐层校验 tree SHA 与本地一致才继续，宁可不推也不推错。
//
// 用法：
//   node tools/push-rewrite.cjs           # 全量重建 + 强推
//   ONLY=<sha> node tools/push-rewrite.cjs  # 只重建单个提交（定点调试，不推送）
const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const REPO = 'repos/sumu0308/class-schedule';
const BRANCH = 'main';

function run(cmd, args, input, binary) {
  const out = path.join(os.tmpdir(), `ga_${Date.now()}_${Math.random().toString(36).slice(2)}.out`);
  const fd = fs.openSync(out, 'w');
  // 不能用管道式 spawnSync：沙箱下直接 EPERM，必须接文件句柄再读回
  const r = spawnSync(cmd, args, { input, stdio: [input ? 'pipe' : 'ignore', fd, fd] });
  fs.closeSync(fd);
  // binary=true 时必须按 latin1 读：它是「字节↔字符」一一映射的无损映射，
  // 保证 Buffer.from(txt,'latin1') 能还原出原始字节。若按默认 utf8 读，
  // 中文等多字节序列会被替换成 U+FFFD，blob 内容直接损坏（tree SHA 校验会失败）。
  const txt = fs.readFileSync(out, binary ? 'latin1' : 'utf8');
  fs.unlinkSync(out);
  return { code: r.status, txt };
}

function git(args, binary) {
  const r = run('git', args, undefined, binary);
  if (r.code !== 0) throw new Error(`git ${args.join(' ')} 失败:\n${r.txt}`);
  return r.txt;
}

// 解析 `git ls-tree -z` 的输出。
//
// 关键：必须加 -z。不加时 git 会把含非 ASCII 字符的路径（仓库里有
// 「单机版安卓课表App-计划文档.md」这类中文文件名）输出成带引号的八进制转义，
// 例如 "\344\275\240..."。若直接拿去当路径上传，GitHub 上会建出
// 字面量名为 \344\275\240… 的文件，tree SHA 必然与本地对不上。
function parseLsTreeZ(raw) {
  const entries = [];
  for (const rec of raw.split('\0')) {
    if (!rec) continue;
    const tab = rec.indexOf('\t');
    if (tab < 0) continue;
    const [mode, type, sha] = rec.slice(0, tab).split(' ');
    entries.push({ path: rec.slice(tab + 1), mode, type, sha });
  }
  return entries;
}

async function api(method, apiPath, body) {
  for (let attempt = 1; attempt <= 4; attempt++) {
    // 注意：gh api 的端点必须是完整的 repos/<owner>/<repo>/... 形式，
    // 少了 repos/ 前缀会直接 404 Not Found。
    const args = ['api', '-X', method, `${REPO}${apiPath}`];
    if (body !== undefined) args.push('--input', '-');
    const r = run('gh', args, body === undefined ? undefined : JSON.stringify(body));
    if (r.code === 0) return JSON.parse(r.txt || '{}');
    if (attempt === 4) throw new Error(`API ${method} ${apiPath} 失败:\n${r.txt}`);
    await new Promise((s) => setTimeout(s, attempt * 1500));
  }
}

// 用稳定的映射缓存，避免同一 blob 被反复上传
const blobCache = new Map();

async function uploadBlob(buf) {
  const key = require('crypto').createHash('sha1').update(buf).digest('hex');
  if (blobCache.has(key)) return blobCache.get(key);
  const res = await api('POST', '/git/blobs', {
    content: buf.toString('base64'),
    encoding: 'base64',
  });
  blobCache.set(key, res.sha);
  return res.sha;
}

async function buildTree(localTree, baseTreeSha) {
  // 注意 binary 只用于下面 cat-file blob 取原始字节。
  // ls-tree 的输出是文本（含 UTF-8 中文路径），必须按 utf8 读：
  // 若按 latin1 读，「单机版安卓课表App-计划文档.md」会被解成 U+00E4 之类的
  // 单字节字符，JSON 序列化再发出去就变成完全不同的字节序列，
  // GitHub 建出的 tree 名字全错，SHA 自然对不上（且不报错，只在校验时才暴露）。
  const entries = parseLsTreeZ(git(['ls-tree', '-z', localTree]));
  const items = [];
  for (const { path: filePath, mode, type, sha } of entries) {
    if (type === 'blob') {
      const buf = git(['cat-file', 'blob', sha], true);
      items.push({
        path: filePath,
        mode,
        type: 'blob',
        sha: await uploadBlob(Buffer.from(buf, 'latin1')),
      });
    } else {
      // 子树：必须递归重建，不能只换顶层（只有一层会漏掉深层目录里的旧内容）
      items.push({
        path: filePath,
        mode,
        type: 'tree',
        sha: await buildTree(sha, undefined),
      });
    }
  }
  const res = await api('POST', '/git/trees', {
    base_tree: baseTreeSha,
    tree: items,
  });
  return res.sha;
}

(async () => {
  // 拓扑序 + 时间正序：从根提交开始逐个重建
  const all = git(['rev-list', '--reverse', '--topo-order', `refs/heads/${BRANCH}`])
    .split('\n')
    .filter(Boolean);
  // ONLY=<sha> 只处理这一个提交（调试用）；默认全量
  const shas = process.env.ONLY ? [process.env.ONLY] : all;

  const oldRemote = '63019ec60c874d253244ab0bde4270025a49019a';
  let parentSha; // 新链上的父提交 SHA（root commit 为 undefined）

  for (const old of shas) {
    // 提交信息必须与本地字节完全一致，commit SHA 才会相同。
    // **不能用 `git log -1 --format=%B`**：它会在消息末尾额外补一个换行
    // （本地 commit 对象里消息已自带结尾 \n，再补一个 SHA 就变了）。
    // 正确做法是直接读原始 commit 对象，取第一个空行之后的部分——那是原始字节。
    const rawCommit = git(['cat-file', 'commit', old], true);
    const sep = rawCommit.indexOf('\n\n');
    if (sep < 0) throw new Error(`提交 ${old} 里找不到空行分隔符`);
    const msg = Buffer.from(rawCommit.slice(sep + 2), 'latin1');
    const meta = git([
      'log', '-1', '--format=%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI', old,
    ]).split('\0');
    const localTree = git(['rev-parse', `${old}^{tree}`]).trim();

    // 始终从零重建整棵树，不用 base_tree。
    // base_tree 是「增量合并」语义：GitHub 把它当基线再叠加你给的条目，
    // 一旦基线里有你本次没列出的条目，它就会被保留下来——
    // 而本地 tree 是该提交的**完整**快照。两者语义不等价，
    // 实测在 v0.11.1（.gitignore 与文件删除同时发生）就因此算出了不同的 SHA。
    // 完整重建慢一点，但结果必然与本地一致。
    const newTree = await buildTree(localTree, undefined);

    // 校验：本地与远端算出的树必须一致，否则说明编码/内容有偏差，中止
    if (newTree !== localTree) {
      throw new Error(`提交 ${old} 的 tree 不一致\n  本地 ${localTree}\n  远端 ${newTree}`);
    }

    const body = {
      message: msg.toString('utf8'),
      tree: newTree,
      author: { name: meta[0], email: meta[1], date: meta[2] },
      committer: { name: meta[3], email: meta[4], date: meta[5] },
    };
    if (parentSha) body.parents = [parentSha];

    const c = await api('POST', '/git/commits', body);
    // tree 已在 buildTree 里逐层与本地校验过，这里内容必然一致。
    // commit SHA 预期与本地不同（GitHub 会规范化提交信息），
    // 故只提示差异，不中断 —— 链首校验处会再做一次最终比对。
    if (c.sha !== old) {
      process.stdout.write(`  （SHA 与本地不同：${c.sha.slice(0, 7)} ≠ ${old.slice(0, 7)}，属预期）\n`);
    }
    parentSha = c.sha;
    console.log(`${old.slice(0, 7)} -> ${c.sha.slice(0, 7)}  ${msg.toString('utf8').split('\n')[0].slice(0, 40)}`);
  }

  if (process.env.ONLY) {
    console.log('\nONLY 模式：仅重建指定提交，不推送');
    return;
  }

  // 链首校验：比内容而不是比 SHA。
  // SHA 必然与本地不同（GitHub 规范化提交信息），所以这里校验远端链首的
  // tree 是否等于本地 HEAD 的 tree —— 内容一致才算真的搬对了。
  const localHead = git(['rev-parse', `refs/heads/${BRANCH}`]).trim();
  const localHeadTree = git(['rev-parse', `${localHead}^{tree}`]).trim();
  const remoteHead = await api('GET', `/git/commits/${parentSha}`);
  if (remoteHead.tree.sha !== localHeadTree) {
    throw new Error(`链首内容不一致\n  本地 tree ${localHeadTree}\n  远端 tree ${remoteHead.tree.sha}`);
  }
  console.log(`\n内容核对通过：远端链首 tree = 本地 HEAD tree = ${localHeadTree.slice(0, 8)}`);
  if (parentSha !== localHead) {
    console.log(`注意：commit SHA 与本地不同（本地 ${localHead.slice(0, 7)} / 远端 ${parentSha.slice(0, 7)}）。`);
    console.log('这是 GitHub 规范化提交信息导致的，内容一致但字节不一致。');
    console.log('想要 SHA 也完全一致，请改用 git push --force。');
  }

  // 用 force=true 强推（历史重写后无法快进）
  const upd = await api('PATCH', `/git/refs/heads/${BRANCH}`, {
    sha: parentSha,
    force: true,
  });
  console.log(`\n远端 ${BRANCH}: ${oldRemote} -> ${upd.object.sha}`);
})().catch((e) => {
  console.error('失败:', e.message);
  process.exit(1);
});