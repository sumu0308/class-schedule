/**
 * setup-gradle-wrapper.mjs — 准备 Gradle 运行环境与 wrapper。
 *
 * 做三件事：
 *   1. 从腾讯云镜像下载指定版本的 Gradle 发行版（比官方源快很多）
 *   2. 从发行版里取出 gradle-wrapper.jar，生成 gradle/wrapper/ 与 gradlew / gradlew.bat
 *   3. 预置到 GRADLE_USER_HOME 的 wrapper 缓存，让首次构建不再重复下载
 *
 * 用法：
 *   node tools/setup-gradle-wrapper.mjs
 *   node tools/setup-gradle-wrapper.mjs --version 9.8.1
 */

import { createWriteStream, existsSync } from 'node:fs';
import { mkdir, rm, stat, readdir, copyFile, writeFile, readFile, rename, chmod } from 'node:fs/promises';
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

const VERSION = flag('--version', '9.8.1');
const PROJECT = process.cwd();
const GRADLE_HOME = path.resolve(flag('--gradle-home', path.join(PROJECT, '.gradle-home')));
const MIRROR = `https://mirrors.cloud.tencent.com/gradle/gradle-${VERSION}-bin.zip`;
const OFFLICIAL = `https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip`;
const CACHE = path.join(GRADLE_HOME, '_dist');

const log = (...a) => console.log(...a);
const mb = (b) => (b / 1024 / 1024).toFixed(1);

/** 单次下载尝试，支持断点续传 */
async function tryDownload(url, dest) {
  await mkdir(path.dirname(dest), { recursive: true });
  let have = 0;
  try { have = (await stat(dest)).size; } catch { /* 新文件 */ }

  let res;
  try {
    res = await fetch(url, { headers: have > 0 ? { Range: `bytes=${have}-` } : {}, redirect: 'follow' });
  } catch (e) {
    return { ok: false, error: e.message, got: have };
  }
  if (!res.ok && res.status !== 206) return { ok: false, error: `HTTP ${res.status}`, got: have };
  if (have > 0 && res.status === 200) { have = 0; await rm(dest, { force: true }); }

  const total = Number(res.headers.get('content-length') ?? 0) + have;
  let got = have, last = 0;
  try {
    const body = Readable.fromWeb(res.body);
    body.on('data', (c) => {
      got += c.length;
      const now = Date.now();
      if (now - last < 500) return;
      last = now;
      process.stdout.write(`\r   ${mb(got)}/${total ? mb(total) : '?'} MB (${total ? ((got / total) * 100).toFixed(0) : '?'}%)   `);
    });
    await pipeline(body, createWriteStream(dest, { flags: have > 0 ? 'a' : 'w' }));
  } catch (e) {
    // 连接被重置：保留已下载的部分，交给上层重试
    return { ok: false, error: e.message, got, total };
  }
  process.stdout.write('\r' + ' '.repeat(50) + '\r');
  return { ok: true, size: (await stat(dest)).size };
}

/** 带重试的下载：多个来源轮换，每次从断点继续 */
async function downloadWithRetry(urls, dest, { attempts = 12, waitMs = 2500 } = {}) {
  let lastError = '';
  for (let i = 1; i <= attempts; i++) {
    const url = urls[(i - 1) % urls.length];
    const src = url.includes('tencent') ? '腾讯镜像' : '官方源';
    const r = await tryDownload(url, dest);
    if (r.ok) return r;

    lastError = r.error;
    const size = existsSync(dest) ? (await stat(dest)).size : 0;
    process.stdout.write('\r' + ' '.repeat(60) + '\r');
    log(`   第 ${i}/${attempts} 次中断（${src}：${r.error}），已保留 ${mb(size)} MB，${(waitMs / 1000).toFixed(1)}s 后续传…`);
    await new Promise((res) => setTimeout(res, waitMs));
  }
  return { ok: false, error: `重试 ${attempts} 次仍失败：${lastError}` };
}

function unzip(zip, dest) {
  if (!existsSync(dest)) spawnSync('cmd', ['/c', 'mkdir', dest], { stdio: 'ignore' });
  if (spawnSync('tar', ['-xf', zip, '-C', dest], { stdio: 'inherit' }).status === 0) return true;
  return spawnSync('powershell', ['-NoProfile', '-Command',
    `Expand-Archive -LiteralPath '${zip}' -DestinationPath '${dest}' -Force`], { stdio: 'inherit' }).status === 0;
}

log(`准备 Gradle ${VERSION}`);
log(`  项目目录：${PROJECT}`);
log(`  GRADLE_USER_HOME：${GRADLE_HOME}`);
log('');

// ── 1. 下载发行版 ────────────────────────────────────────────────
const zip = path.join(CACHE, `gradle-${VERSION}-bin.zip`);
const isComplete = existsSync(zip) && (await stat(zip)).size > 100 * 1024 * 1024;
if (isComplete) {
  log('① 发行版已完整下载，跳过');
} else {
  log('① 下载 Gradle 发行版（腾讯云镜像，官方源兜底；断线自动续传）…');
  const r = await downloadWithRetry([MIRROR, OFFLICIAL], zip);
  if (!r.ok) { log(`   ✗ ${r.error}`); process.exit(1); }
  log(`   ✓ ${mb(r.size)} MB`);
}

// ── 2. 解压并取出 wrapper ────────────────────────────────────────
const distDir = path.join(CACHE, `gradle-${VERSION}`);
const extracted = path.join(distDir, `gradle-${VERSION}`);
if (!existsSync(path.join(extracted, 'bin', 'gradle.bat'))) {
  log('② 解压发行版…');
  await rm(distDir, { recursive: true, force: true });
  await mkdir(distDir, { recursive: true });
  if (!unzip(zip, distDir)) { log('   ✗ 解压失败'); process.exit(1); }
} else {
  log('② 发行版已解压');
}

const wrapperJarSrc = path.join(extracted, 'lib', 'plugins', 'gradle-wrapper-main-' + VERSION + '.jar');
const wrapperJarAlt = path.join(extracted, 'lib', 'gradle-wrapper-main-' + VERSION + '.jar');
let jarSource = null;
for (const cand of [wrapperJarSrc, wrapperJarAlt]) {
  if (existsSync(cand)) { jarSource = cand; break; }
}
if (!jarSource) {
  // 退而求其次：在 lib 目录里找带 wrapper 字样的 jar
  const libDir = path.join(extracted, 'lib');
  const found = (await readdir(libDir)).find((f) => /wrapper/i.test(f) && f.endsWith('.jar'));
  if (found) {
    jarSource = path.join(libDir, found);
  } else {
    const pluginsDir = path.join(libDir, 'plugins');
    if (existsSync(pluginsDir)) {
      const f2 = (await readdir(pluginsDir)).find((f) => /wrapper.*\.jar$/i.test(f));
      if (f2) jarSource = path.join(pluginsDir, f2);
    }
  }
}
if (!jarSource) { log('   ✗ 发行版里找不到 gradle-wrapper jar'); process.exit(1); }
log(`   ✓ 找到 wrapper：${path.basename(jarSource)}`);

// ── 3. 生成项目 wrapper 文件 ─────────────────────────────────────
// 注意：发行版 lib 里的 gradle-wrapper-main-*.jar 不是给项目用的 wrapper jar
// （它缺少 org.gradle.wrapper.IDownload 等类）。正确做法是用这份 Gradle 自己
// 执行 wrapper 任务来生成，这样 jar 与脚本都是官方匹配的版本。
log('③ 用这份 Gradle 生成项目 wrapper（gradle wrapper 任务）…');
const gradleBin = path.join(extracted, 'bin', 'gradle.bat');
const gen = spawnSync(gradleBin, [
  'wrapper',
  `--gradle-version=${VERSION}`,
  `--gradle-distribution-url=${MIRROR}`,
  '--no-daemon',
], {
  stdio: 'inherit',
  cwd: PROJECT,
  env: { ...process.env, GRADLE_USER_HOME: GRADLE_HOME },
  shell: true,
});
if (gen.status !== 0) {
  log(`   ✗ 生成 wrapper 失败，退出码 ${gen.status}`);
  process.exit(gen.status ?? 1);
}

const wrapperDir = path.join(PROJECT, 'gradle', 'wrapper');
const projectJar = path.join(wrapperDir, 'gradle-wrapper.jar');
if (!existsSync(projectJar)) {
  log('   ✗ 没有生成 gradle/wrapper/gradle-wrapper.jar');
  process.exit(1);
}

// Gradle 9.x 把 wrapper 拆成两个 jar：
//   lib/plugins/gradle-wrapper-main-<v>.jar   —— GradleWrapperMain 等主类
//   lib/gradle-wrapper-shared-<v>.jar         —— IDownload / Download 等共享类
// 项目的 gradle-wrapper.jar 必须同时包含两者，否则运行时
// 报 NoClassDefFoundError: org/gradle/wrapper/IDownload。
// 所以这里把两个 jar 合并成一个。
const mainJar = path.join(extracted, 'lib', 'plugins', `gradle-wrapper-main-${VERSION}.jar`);
const sharedJar = path.join(extracted, 'lib', `gradle-wrapper-shared-${VERSION}.jar`);
if (existsSync(mainJar) && existsSync(sharedJar)) {
  log('   合并 gradle-wrapper-main 与 gradle-wrapper-shared…');
  const stage = path.join(CACHE, `wrapper-merge-${VERSION}`);
  await rm(stage, { recursive: true, force: true });
  await mkdir(stage, { recursive: true });
  for (const [jar, label] of [[sharedJar, 'shared'], [mainJar, 'main']]) {
    const r = spawnSync('jar', ['xf', jar], { cwd: stage, stdio: 'inherit', shell: true });
    if (r.status !== 0) { log(`   ✗ 解压 ${label} 失败`); process.exit(1); }
  }
  await rm(path.join(stage, 'META-INF'), { recursive: true, force: true });
  const r = spawnSync('jar', ['cf', projectJar, '-C', stage, '.'], { stdio: 'inherit', shell: true });
  if (r.status !== 0) { log('   ✗ 重新打包失败'); process.exit(1); }
  await rm(stage, { recursive: true, force: true });
}

// 自检：确认关键类确实在 jar 里（这一步能挡住上面刚踩过的坑）
const listing = spawnSync('jar', ['tf', projectJar], { encoding: 'utf8', shell: true });
const has = (name) => (listing.stdout ?? '').includes(name);
if (!has('org/gradle/wrapper/GradleWrapperMain.class') || !has('org/gradle/wrapper/IDownload.class')) {
  log('   ✗ wrapper jar 自检未通过（缺 GradleWrapperMain 或 IDownload）');
  process.exit(1);
}
const jarSize = (await stat(projectJar)).size;
log(`   ✓ gradle-wrapper.jar (${(jarSize / 1024).toFixed(1)} KB，含 IDownload，自检通过)`);

// 校验脚本存在，并确保 gradlew.bat 是纯 ASCII（中文注释会让 cmd.exe 解析错乱）
for (const f of ['gradlew', 'gradlew.bat']) {
  const p = path.join(PROJECT, f);
  if (!existsSync(p)) { log(`   ✗ 缺少 ${f}`); process.exit(1); }
  const content = await readFile(p);
  const nonAscii = [...content].some((b) => b > 127);
  if (f === 'gradlew.bat' && nonAscii) {
    log(`   ! ${f} 含非 ASCII 字节，cmd.exe 可能解析失败`);
  }
}
log('   ✓ gradlew / gradlew.bat 已生成');

// ── 4. 预置 wrapper 缓存，让首次构建不再下载 ─────────────────────
// Gradle 用发行版 URL 的 MD5 作为缓存目录名
const hash = createHash('md5').update(
  `https\\://mirrors.cloud.tencent.com/gradle/gradle-${VERSION}-bin.zip`
).digest('hex');
const distCache = path.join(GRADLE_HOME, 'wrapper', 'dists', `gradle-${VERSION}-bin`, hash);
await mkdir(distCache, { recursive: true });
const cachedZip = path.join(distCache, `gradle-${VERSION}-bin.zip`);
if (!existsSync(cachedZip)) {
  await copyFile(zip, cachedZip);
  log(`④ 已预置 wrapper 缓存：${distCache}`);
} else {
  log('④ wrapper 缓存已存在');
}

log('');
log('✓ 完成。');
log(`  Gradle 可执行：${path.join(extracted, 'bin', 'gradle.bat')}`);
log('  项目内运行：  .\\gradlew.bat assembleDebug');
