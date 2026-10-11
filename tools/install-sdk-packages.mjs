/**
 * install-sdk-packages.mjs — 不依赖 sdkmanager，直接下载并安装 Android SDK 组件。
 *
 * 为什么不用 sdkmanager：本机环境实测 sdkmanager 无法拉取包清单（工具内部超时策略
 * 在移动网络下失败），而 Node 直连 dl.google.com 稳定快速。
 *
 * 做法：下载官方清单 repository2-3.xml → 解析出组件 zip 的真实地址与 SHA1
 *      → 下载（支持断点续传）→ 校验 SHA1 → 解压到正确的 SDK 目录结构。
 *
 * 用法：
 *   node tools/install-sdk-packages.mjs
 *   node tools/install-sdk-packages.mjs --sdk E:\path\to\.android-sdk
 *   node tools/install-sdk-packages.mjs --platform android-35   # 指定平台版本
 */

import { createWriteStream, existsSync } from 'node:fs';
import { mkdir, rm, stat, readdir, rename, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { pipeline } from 'node:stream/promises';
import { Readable } from 'node:stream';
import path from 'node:path';

const argv = process.argv.slice(2);
const flag = (name, fallback) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] ? argv[i + 1] : fallback;
};

const SDK = path.resolve(flag('--sdk', path.join(process.cwd(), '.android-sdk')));
const DL = path.join(SDK, '_downloads');
const BASE = 'https://dl.google.com/android/repository/';
const MANIFEST_URL = BASE + 'repository2-3.xml';

// 平台版本优先级：优先用户指定，否则按序取清单里存在的第一个
// 注意 platforms;android-3x-extN 是扩展平台（如 36-ext19），不是主平台，要排除
const PLATFORM_PREF = flag('--platform', '')
  ? [flag('--platform')]
  : ['android-37.2', 'android-37.1', 'android-37.0', 'android-36'];
// build-tools 优先级
const BUILDTOOLS_PREF = ['37.0.0', '36.1.0', '36.0.0'];

const log = (...a) => console.log(...a);
const hr = (n = 64) => log('─'.repeat(n));
const mb = (b) => (b / 1024 / 1024).toFixed(1);

async function download(url, dest) {
  await mkdir(path.dirname(dest), { recursive: true });
  let have = 0;
  try { have = (await stat(dest)).size; } catch { /* 新文件 */ }

  const res = await fetch(url, { headers: have > 0 ? { Range: `bytes=${have}-` } : {}, redirect: 'follow' });
  if (!res.ok && res.status !== 206) throw new Error(`HTTP ${res.status} ${res.statusText}`);
  if (have > 0 && res.status === 200) { have = 0; await rm(dest, { force: true }); }

  const total = Number(res.headers.get('content-length') ?? 0) + have;
  let got = have;
  let last = 0;
  const body = Readable.fromWeb(res.body);
  body.on('data', (c) => {
    got += c.length;
    const now = Date.now();
    if (now - last < 400) return;
    last = now;
    process.stdout.write(`\r   ${mb(got)}/${total ? mb(total) : '?'} MB (${total ? ((got / total) * 100).toFixed(0) : '?'}%)   `);
  });
  await pipeline(body, createWriteStream(dest, { flags: have > 0 ? 'a' : 'w' }));
  process.stdout.write('\r' + ' '.repeat(50) + '\r');
}

const sha1 = async (f) => createHash('sha1').update(await readFile(f)).digest('hex');

function unzip(zip, dest) {
  if (!existsSync(dest)) spawnSync('cmd', ['/c', 'mkdir', dest], { stdio: 'ignore' });
  if (spawnSync('tar', ['-xf', zip, '-C', dest], { stdio: 'inherit' }).status === 0) return true;
  return spawnSync('powershell', ['-NoProfile', '-Command',
    `Expand-Archive -LiteralPath '${zip}' -DestinationPath '${dest}' -Force`], { stdio: 'inherit' }).status === 0;
}

/** 取出某个 path 的通用（非宿主特定）zip 下载信息 */
function parseEntry(xml, wantedPath) {
  for (const chunk of xml.split('<remotePackage ')) {
    const seg = chunk.split('</remotePackage>')[0];
    const pm = seg.match(/path="([^"]+)"/);
    if (!pm || pm[1] !== wantedPath) continue;

    const archBlock = seg.match(/<archives>([\s\S]*?)<\/archives>/);
    if (!archBlock) return null;

    for (const a of archBlock[1].match(/<archive>([\s\S]*?)<\/archive>/g) ?? []) {
      const hostOs = a.match(/<host-os>([^<]+)<\/host-os>/);
      if (hostOs && !/windows/i.test(hostOs[1])) continue;
      const url = a.match(/<url>([^<]+)<\/url>/);
      if (!url) continue;
      const size = a.match(/<size>(\d+)<\/size>/);
      const sum = a.match(/<checksum[^>]*type="sha1"[^>]*>([0-9a-f]{40})<\/checksum>/)
        ?? a.match(/<sha1>([0-9a-f]{40})<\/sha1>/);
      return {
        url: url[1].startsWith('http') ? url[1] : BASE + url[1],
        size: size ? Number(size[1]) : 0,
        sha1: sum ? sum[1] : '',
        name: url[1].split('/').pop(),
      };
    }
  }
  return null;
}

hr();
log('安装 Android SDK 组件（直连官方源，不依赖 sdkmanager）');
log('  SDK 位置：' + SDK);
hr();

log('\n[1/4] 下载官方包清单…');
const mres = await fetch(MANIFEST_URL, { redirect: 'follow' });
if (!mres.ok) {
  log(`  ✗ HTTP ${mres.status}。若需代理，请先启用代理后重跑。`);
  process.exit(1);
}
const xml = await mres.text();
const allPaths = [...xml.matchAll(/<remotePackage path="([^"]+)"/g)].map((m) => m[1]);
log(`  ✓ 清单 ${(xml.length / 1024).toFixed(0)} KB，共 ${allPaths.length} 个包`);

log('\n[2/4] 选择版本…');
const platform = PLATFORM_PREF.find((p) => allPaths.includes('platforms;' + p));
const buildTools = BUILDTOOLS_PREF.find((b) => allPaths.includes('build-tools;' + b));
if (!platform) { log('  ✗ 清单中没有可用的平台版本'); process.exit(1); }
if (!buildTools) { log('  ✗ 清单中没有可用的构建工具版本'); process.exit(1); }

// 允许只装其中某几项，例如 --only platform-tools
const only = flag('--only', '').split(',').map((s) => s.trim()).filter(Boolean);

const WANTED = [
  { path: 'platform-tools', dir: 'platform-tools', desc: 'adb 等平台工具' },
  { path: 'platforms;' + platform, dir: path.join('platforms', platform), desc: `Android 平台 ${platform}` },
  { path: 'build-tools;' + buildTools, dir: path.join('build-tools', buildTools), desc: `构建工具 ${buildTools}` },
].filter((w) => !only.length || only.includes(w.path));

const jobs = [];
for (const w of WANTED) {
  const e = parseEntry(xml, w.path);
  if (!e) { log(`  ✗ 解析不到 ${w.path} 的下载地址`); continue; }
  log(`  ✓ ${w.path.padEnd(26)} ${(e.size ? mb(e.size) + ' MB' : '?').padStart(9)}   ${e.name}`);
  jobs.push({ ...w, ...e });
}
if (jobs.length !== WANTED.length) { log('  ✗ 有组件无法解析，已停止'); process.exit(1); }

log('\n[3/4] 下载、校验、解压…');
for (const j of jobs) {
  const target = path.join(SDK, j.dir);
  const zip = path.join(DL, j.name);

  log(`\n── ${j.path}（${j.desc}）`);
  if (existsSync(path.join(target, '.installed'))) { log('   已安装，跳过'); continue; }

  const needDownload = !existsSync(zip) || (j.size > 0 && (await stat(zip)).size !== j.size);
  if (needDownload) await download(j.url, zip);
  else log(`   复用已下载的 ${j.name}`);

  if (j.sha1) {
    const actual = await sha1(zip);
    if (actual !== j.sha1) {
      log(`   ✗ SHA1 不匹配（期望 ${j.sha1.slice(0, 12)}…，实际 ${actual.slice(0, 12)}…）`);
      await rm(zip, { force: true });
      log('   已删除损坏文件，请重跑。');
      process.exit(1);
    }
    log('   ✓ SHA1 校验通过');
  } else {
    log('   ! 清单未提供 SHA1，跳过校验');
  }

  log('   解压…');
  const tmp = zip + '.tmp';
  await rm(tmp, { recursive: true, force: true });
  if (!unzip(zip, tmp)) { log('   ✗ 解压失败'); process.exit(1); }

  // zip 内层若只有一层目录，则提升该目录
  const entries = await readdir(tmp, { withFileTypes: true });
  const dirs = entries.filter((e) => e.isDirectory());
  const files = entries.filter((e) => e.isFile());
  const source = dirs.length === 1 && files.length === 0 ? path.join(tmp, dirs[0].name) : tmp;

  await rm(target, { recursive: true, force: true });
  await mkdir(path.dirname(target), { recursive: true });
  await rename(source, target);
  await rm(tmp, { recursive: true, force: true });
  await writeFile(path.join(target, '.installed'), new Date().toISOString());
  log(`   ✓ 已安装到 ${target}`);
}

await rm(DL, { recursive: true, force: true });

log('\n[4/4] 结果');
hr();
for (const d of ['cmdline-tools', 'platform-tools', 'platforms', 'build-tools']) {
  const p = path.join(SDK, d);
  if (!existsSync(p)) { log(`   ${d}/  缺失`); continue; }
  const subs = (await readdir(p)).filter((x) => !x.startsWith('.'));
  log(`   ${d}/  →  ${subs.join(', ') || '(空)'}`);
}
hr();
log('adb：' + path.join(SDK, 'platform-tools', 'adb.exe'));
log('完成，可以开始构建项目了。');
