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

async function api(path) {
  const r = await fetch(API + path, {
    headers: { 'User-Agent': 'class-schedule-verify', Accept: 'application/vnd.github+json' },
  });
  if (!r.ok) throw new Error(`HTTP ${r.status} ${r.statusText} — ${path}`);
  return r.json();
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
line(`\n【文件统计】`);
line(`  文件总数: ${files.length}（本地 git 跟踪 52 个）`);
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
  ['app/src/main/java/com/classschedule/app/ui/ScheduleGrid.kt', 'ScheduleGrid.kt', 'TARGET_TYPICAL_ROW_DP'],
  // 注意：AGP 9 起包名不写在 AndroidManifest.xml，而是在 app/build.gradle.kts 的
  // namespace 里；Manifest 中用 ".MainActivity" 这类相对写法。别在这里断言包名。
  ['app/src/main/AndroidManifest.xml', 'AndroidManifest.xml', '.MainActivity'],
  ['app/build.gradle.kts', 'app/build.gradle.kts', 'versionName = "0.4.0"'],
];

let allOk = true;
for (const [remotePath, label, mustContain] of checks) {
  const meta = await api(`/contents/${encodeURI(remotePath)}`);
  const text = Buffer.from(meta.content, 'base64').toString('utf8');
  const local = await readFile(remotePath, 'utf8');
  const same = text.replace(/\r\n/g, '\n') === local.replace(/\r\n/g, '\n');
  const has = text.includes(mustContain);
  line(`  ${same ? '✓' : '✗'} ${label.padEnd(22)} 与本地一致=${same}  含关键内容=${has}`);
  if (!same || !has) allOk = false;
}

rule();
line(allOk ? '全部核实通过' : '存在不一致，需要检查');
process.exit(allOk ? 0 : 1);
