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

// ---------------------------------------------------------------------------
// 取令牌
// ---------------------------------------------------------------------------
// 为什么必须带令牌：匿名访问 api.github.com 只有 60 次/小时，本脚本一轮就要十几次
// （对象树 + 每个抽查文件的读重试），跑几轮就会撞上
// `HTTP 403 rate limit exceeded`——而这跟"仓库内容对不对"毫无关系，纯属误报。
// 走 gh 的令牌则有 5000 次/小时。
//
// 为什么不直接把 gh 的输出用管道读回来：DSH 沙箱禁止 Node 以「管道」方式
// spawn 子进程（spawnSync EPERM）。所以统一把子进程 stdout 接到文件句柄上再读。
const { spawnSync } = await import('node:child_process');
const { openSync, readFileSync, mkdtempSync, rmSync } = await import('node:fs');
const { tmpdir } = await import('node:os');
const { join } = await import('node:path');

const scratchDir = mkdtempSync(join(tmpdir(), 'verify-remote-'));
let scratchSeq = 0;

/** 跑一条命令，把 stdout 收到临时文件再读（避开沙箱的管道限制）。 */
function capture(command, args) {
  const file = join(scratchDir, `out-${scratchSeq++}.txt`);
  const fd = openSync(file, 'w');
  const r = spawnSync(command, args, { stdio: ['ignore', fd, 'inherit'] });
  const text = readFileSync(file, 'utf8');
  if (r.error) throw new Error(`${command} 起不来：${r.error.code ?? r.error.message}`);
  if (r.status !== 0) throw new Error(`${command} ${args.join(' ')} 退出码 ${r.status}`);
  return text.trim();
}

let TOKEN = null;
try {
  TOKEN = capture('gh', ['auth', 'token']) || null;
} catch {
  TOKEN = null;
}
console.log(
  TOKEN
    ? '（已用 gh 令牌访问 API，配额 5000 次/小时）'
    : '（未取到 gh 令牌，改用匿名访问；匿名配额只有 60 次/小时，可能中途报限流）',
);

function headers() {
  const h = { 'User-Agent': 'class-schedule-verify', Accept: 'application/vnd.github+json' };
  if (TOKEN) h.Authorization = `Bearer ${TOKEN}`;
  return h;
}

// 最后一层保险：无论如何都要退出，不要让未处理的拒绝把 libuv 拖到断言崩溃
// （见过 `Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)`）。
process.on('uncaughtException', (e) => {
  console.error(`\n未预期的错误：${e.message}`);
  process.exit(1);
});
process.on('unhandledRejection', (e) => {
  console.error(`\n未预期的错误：${e?.message ?? e}`);
  process.exit(1);
});

async function api(path) {
  // cache: 'no-store' 很关键：刚 push 完就读，不加这个可能读回 CDN 缓存的旧内容，
  // 于是核对结果会假报"与本地不一致"。
  const r = await fetch(API + path, { cache: 'no-store', headers: headers() });
  if (r.status === 403 && r.headers.get('x-ratelimit-remaining') === '0') {
    const reset = Number(r.headers.get('x-ratelimit-reset') ?? 0);
    const when = reset ? new Date(reset * 1000).toLocaleString() : '未知';
    throw new Error(`API 限流（配额 ${r.headers.get('x-ratelimit-limit')}，${when} 重置）— ${path}`);
  }
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
      // 限流重试没意义，直接抛出，别把真实原因盖掉
      if (/限流/.test(e.message)) throw e;
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
const checks = [
  ['README.md', 'README.md', '单机版安卓课表 App'],
  // 别写死常量名：排版算法改过几轮（TARGET_TYPICAL_ROW_DP → TARGET_ROW_DP →
  // FALLBACK_PX_PER_MINUTE），每次改名核对就红一次，而红的跟"推没推上去"毫无关系。
  // 改成从本地文件里现取一个全大写常量名当"指纹"，随代码自动跟随。
  ['app/src/main/java/com/classschedule/app/ui/ScheduleGrid.kt', 'ScheduleGrid.kt', (local) => {
    const names = [...new Set(local.match(/\b[A-Z][A-Z0-9_]{6,}\b/g) ?? [])];
    return names.length > 0 ? names.sort()[0] : null;
  }],
  // 注意：AGP 9 起包名不写在 AndroidManifest.xml，而是在 app/build.gradle.kts 的
  // namespace 里；Manifest 中用 ".MainActivity" 这类相对写法。别在这里断言包名。
  ['app/src/main/AndroidManifest.xml', 'AndroidManifest.xml', '.MainActivity'],
  ['app/build.gradle.kts', 'app/build.gradle.kts', (local) => {
    // 不写死版本号——否则每次发版核对都会红一次（0.5.0 发版时就踩过）。
    // 断言的是"远程的版本号与本地这份文件里的一致"，随版本自动跟随。
    const m = /versionName\s*=\s*"([^"]+)"/.exec(local);
    return m ? m[0] : null;
  }],
];

let allOk = true;
for (const [remotePath, label, expected] of checks) {
  const text = await readRemoteText(remotePath);
  const local = await readFile(remotePath, 'utf8');
  const same = text.replace(/\r\n/g, '\n') === local.replace(/\r\n/g, '\n');
  const mustContain = typeof expected === 'function' ? expected(local) : expected;
  const has = mustContain == null ? same : text.includes(mustContain);
  const shown = mustContain == null ? '（无法从本地推出，跳过）' : String(has);
  line(`  ${same ? '✓' : '✗'} ${label.padEnd(22)} 与本地一致=${same}  含关键内容=${shown}`);
  if (!same || !has) allOk = false;
}

// 本地是否有已提交但还没推送的改动 —— 这类"不一致"最容易让人白查半天。
// 走 capture()（stdio 接文件句柄），所以在 DSH 沙箱里也能读到 git 的输出。
try {
  const localHead = capture('git', ['rev-parse', 'HEAD']);
  const dirty = capture('git', ['status', '--porcelain']);
  const tracked = capture('git', ['ls-files']).split('\n').filter(Boolean);
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
}

rule();
line(allOk ? '全部核实通过' : '存在不一致，需要检查');
rmSync(scratchDir, { recursive: true, force: true });
process.exit(allOk ? 0 : 1);
