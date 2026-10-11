/**
 * verify-remote.mjs — 核实 GitHub 上的仓库内容与本地是否一致。
 *
 * 为什么单独写脚本：PowerShell 内联脚本处理 GitHub API 的 base64 内容会出问题，
 * 用 Node 读 API 更稳，也顺带验证"未登录状态下能否读到"（即仓库确实是公开的）。
 *
 * 用法：node tools/verify-remote.mjs [owner/repo]
 */

const REPO = process.argv[2] ?? 'sumu0308/class-schedule';
const API = `https://api.github.com/repos/${REPO}`;
const DEFAULT_BRANCH = 'main';

async function api(path) {
  // cache: 'no-store' 很关键：刚 push 完就读，不加这个可能读回 CDN 缓存的旧内容，
  // 于是核对结果会假报"与本地不一致"。
  const r = await fetch(API + path, {
    cache: 'no-store',
    headers: { 'User-Agent': 'class-schedule-verify', Accept: 'application/vnd.github+json' },
  });
  if (!r.ok) throw new Error(`HTTP ${r.status} ${r.statusText} — ${path}`);
  return r.json();
}

/** 读单个文件内容；刚 push 完时 GitHub 可能短暂返回旧内容，用 cache-buster 重试。 */
async function readRemoteText(filePath, attempts = 4) {
  let last;
  for (let i = 0; i < attempts; i++) {
    try {
      const meta = await api(`/contents/${encodeURI(filePath)}?ref=${DEFAULT_BRANCH}&cb=${Date.now()}-${i}`);
      return Buffer.from(meta.content, 'base64').toString('utf8');
    } catch (e) {
      last = e;
      await new Promise((r) => setTimeout(r, 400 * (i + 1)));
    }
  }
  throw last;
}

const line = (s) => console.log(s);
const rule = () => line('─'.repeat(64));

rule();
line(`核实远程仓库：${REPO}`);
rule();

const info = await api('');
line(`\n【仓库信息】`);
line(`  名称    : ${info.full_name}`);
line(`  可见性  : ${info.visibility}${info.visibility === 'PUBLIC' ? '（公开，未登录也能读）' : ''}`);
line(`  默认分支: ${info.default_branch}`);
line(`  体积    : 约 ${(info.size / 1024).toFixed(2)} MB`);
line(`  地址    : ${info.html_url}`);
if (info.description) line(`  描述    : ${info.description}`);

const commit = await api('/commits?per_page=1');
const head = commit[0];
line(`\n【最新提交】`);
line(`  ${head.sha.slice(0, 7)}  ${head.commit.message.split('\n')[0]}`);
line(`  作者    : ${head.commit.author.name} <${head.commit.author.email}>`);
line(`  时间    : ${head.commit.author.date}`);

const tree = await api(`/git/trees/${info.default_branch}?recursive=1`);
const files = tree.tree.filter((t) => t.type === 'blob');
let localTrackedCount = null; // 后面读本地 git 时填上，用来发现"忘了 push"
line(`\n【文件统计】`);
line(`  远程文件数: ${files.length}`);
line(`  构建产物: ${files.filter((f) => /\.apk$/.test(f.name)).length} 个 .apk（应为 0）`);
line(`  SDK/缓存 : ${files.filter((f) => /^\.android-sdk\/|^\.gradle-home\/|^\.gradle\//.test(f.path)).length} 个（应为 0）`);

line(`\n【顶层结构】`);
const top = new Map();
for (const f of files) {
  const seg = f.path.split('/');
  const key = seg.length === 1 ? '(根文件)' : seg[0] + '/';
  top.set(key, (top.get(key) ?? 0) + 1);
}
for (const [k, v] of [...top.entries()].sort((a, b) => b[1] - a[1])) {
  line(`  ${k.padEnd(22)} ${String(v).padStart(3)} 个文件`);
}

line(`\n【关键文件内容抽查（从 GitHub 下载，与本地比对）】`);
const { readFile } = await import('node:fs/promises');
const { execFileSync } = await import('node:child_process');
const checks = [
  ['README.md', 'README.md', '单机版安卓课表 App'],
  ['app/src/main/java/com/classschedule/app/ui/ScheduleGrid.kt', 'ScheduleGrid.kt', 'TARGET_TYPICAL_ROW_DP'],
  // 注意：AGP 9 起包名不写在 AndroidManifest.xml，而是在 app/build.gradle.kts 的
  // namespace 里；Manifest 中用 ".MainActivity" 这类相对写法。别在这里断言包名。
  ['app/src/main/AndroidManifest.xml', 'AndroidManifest.xml', '.MainActivity'],
  ['app/build.gradle.kts', 'app/build.gradle.kts', 'versionName = "0.4.0"'],
];

let allOk = true;
for (const [remotePath, label, mustContain] of checks) {
  const text = await readRemoteText(remotePath);
  const local = await readFile(remotePath, 'utf8');
  const same = text.replace(/\r\n/g, '\n') === local.replace(/\r\n/g, '\n');
  const has = text.includes(mustContain);
  line(`  ${same ? '✓' : '✗'} ${label.padEnd(22)} 与本地一致=${same}  含关键内容=${has}`);
  if (!same || !has) allOk = false;
}

// 本地是否有已提交但还没推送的改动 —— 这类"不一致"最容易让人白查半天。
// 但注意：DSH 沙箱不允许 Node 以管道方式启动子进程，git 会以 spawnSync EPERM 失败。
// 那是环境限制、不是"本地和远程不一致"，所以要区分对待，不能报警。
let gitReady = true;
try {
  execFileSync('git', ['--version'], { encoding: 'utf8' });
} catch {
  gitReady = false;
}

if (gitReady) {
  try {
    const localHead = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
    const dirty = execFileSync('git', ['status', '--porcelain'], { encoding: 'utf8' }).trim();
    const tracked = execFileSync('git', ['ls-files'], { encoding: 'utf8' }).trim().split('\n').filter(Boolean);
    localTrackedCount = tracked.length;
    const sameCommit = localHead === head.sha;
    line(`\n【本地与远程的同步状态】`);
    line(`  本地 HEAD : ${localHead.slice(0, 7)}`);
    line(`  远程 HEAD : ${head.sha.slice(0, 7)}`);
    line(`  ${sameCommit ? '✓ 提交一致' : '✗ 提交不一致 —— 本地有未推送的提交'}`);
    line(`  本地跟踪文件: ${localTrackedCount} 个${localTrackedCount === files.length ? '（与远程一致）' : '（与远程不一致 ✗）'}`);
    if (localTrackedCount !== files.length) allOk = false;
    line(`  ${dirty ? '✗ 工作区有未提交的改动：' : '✓ 工作区干净'}`);
    if (dirty) for (const l of dirty.split('\n').slice(0, 8)) line(`      ${l}`);
    if (!sameCommit) allOk = false;
  } catch (e) {
    // 不吞错误：以前这里静默显示"不是 git 仓库"，把真正的失败原因藏掉了
    line(`\n【本地与远程的同步状态】`);
    line(`  ! 读取本地 git 状态失败：${e.message.split('\n')[0]}`);
    line(`    无法判断"是否有未推送的提交"；远程内容核对不受影响。`);
    line(`    常见原因：DSH 沙箱禁止 Node 以管道方式启动子进程（EPERM）。`);
  }
} else {
  line(`\n【本地与远程的同步状态】`);
  line(`  ! 本环境起不了 git 子进程（DSH 沙箱限制），跳过"是否有未推送提交"的检查`);
  line(`    如需确认：在本机终端里跑 git status / git log origin/main..HEAD`);
}

rule();
line(allOk ? '全部核实通过' : '存在不一致，需要检查');
process.exit(allOk ? 0 : 1);
