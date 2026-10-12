#!/usr/bin/env node
/**
 * push-auto.mjs — 自动选路推送：git push 不通时自动改走 GitHub Git Data API。
 *
 * 为什么需要它
 * ------------
 * 有些环境（例如带沙箱代理的开发壳）会给 https 请求注入 http_proxy，并按**域名白名单**
 * 放行。实测：`api.github.com` / `codeload` / `raw.githubusercontent` 常通，而
 * `github.com:443` 会在"放行"与"502 CONNECT tunnel failed"之间来回切；想绕开代理直连
 * 又会被沙箱拦住（20 s 超时）。
 * 偏偏 `git push` 只能走 `github.com:443/git-receive-pack` —— 于是它时好时坏、极易失败，
 * 且**重试只是在赌时刻**，跟账号密码毫无关系。
 * 而 `api.github.com` 是稳的，Git Data API 可以在服务端把提交重建出来，效果等同一次 push。
 *
 * 本脚本的做法：先试 git push（通道通时最快）→ 不通就自动降级到 API 重建 → 最后核对。
 * 重建时逐层校验 SHA：上传的 blob、造出的 tree、造出的 commit 都必须与本地**逐位一致**，
 * 任何一步对不上就拒绝更新远端 ref，绝不制造本地/远端分叉。
 *
 * 用法
 * ----
 *   node tools/push-auto.mjs                 # 自动：先 git push，失败改走 API，再核对
 *   node tools/push-auto.mjs --api-only      # 直接走 API（已知 github.com 不通时更快）
 *   node tools/push-auto.mjs --git-only      # 只用 git push
 *   node tools/push-auto.mjs --dry-run       # 演练：只打印将重建哪些提交，绝不改远端
 *   node tools/push-auto.mjs -m "提交信息"    # 先把工作区改动提交，再推
 *   node tools/push-auto.mjs --repo owner/name --branch main --tries 3
 *
 * 限制
 * ----
 * 只支持**线性历史**的**快进**推送（本地领先远端若干提交）。遇到 merge 提交、或远端已有
 * 本地没有的提交（非快进）时会中止，要求人工处理。
 *
 * 实现注意
 * --------
 * 本沙箱禁止 Node 以「管道」方式 spawn 子进程（spawnSync 直接 EPERM），所以所有子进程的
 * stdout/stderr 都接到临时文件句柄上再读回来——与 verify-remote.mjs 同一套做法。
 */

import { spawnSync } from 'node:child_process';
import { openSync, closeSync, readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// ── 参数 ────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const has = (f) => argv.includes(f);
const opt = (f, d) => {
  const i = argv.indexOf(f);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : d;
};

const REPO = opt('--repo', 'sumu0308/class-schedule');
const BRANCH = opt('--branch', 'main');
const API = 'https://api.github.com';
const API_ONLY = has('--api-only');
const GIT_ONLY = has('--git-only');
const DRY = has('--dry-run');
const COMMIT_MSG = opt('-m', null);
const PUSH_TRIES = Number(opt('--tries', '2'));
// 代理环境下 api.github.com 也会偶发抖动（实测见过 "fetch failed"），所以 API 调用也要重试
const API_TRIES = Number(opt('--api-tries', '4'));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 子进程：stdout/stderr 走临时文件（沙箱禁管道） ───────────
const scratchDir = mkdtempSync(join(tmpdir(), 'push-auto-'));
let seq = 0;

function run(command, args, { binary = false, inherit = false } = {}) {
  if (inherit) {
    return spawnSync(command, args, { stdio: 'inherit' });
  }
  const outFile = join(scratchDir, `o-${seq++}`);
  const errFile = join(scratchDir, `e-${seq++}`);
  const fo = openSync(outFile, 'w');
  const fe = openSync(errFile, 'w');
  const r = spawnSync(command, args, { stdio: ['ignore', fo, fe] });
  closeSync(fo);
  closeSync(fe);
  if (r.error) throw new Error(`${command} 起不来：${r.error.code ?? r.error.message}`);
  return {
    status: r.status,
    stdout: binary ? readFileSync(outFile) : readFileSync(outFile, 'utf8'),
    stderr: readFileSync(errFile, 'utf8'),
  };
}

function git(args, { binary = false } = {}) {
  const r = run('git', ['-c', 'core.quotepath=false', ...args], { binary });
  if (r.status !== 0) {
    throw new Error(`git ${args.join(' ')} 退出码 ${r.status}\n${r.stderr.trim()}`);
  }
  return r.stdout;
}

function finish(code) {
  rmSync(scratchDir, { recursive: true, force: true });
  process.exit(code);
}

// 未预期错误也要清理临时目录，别把 libuv 拖到断言崩溃（verify-remote.mjs 见过同类问题）
process.on('uncaughtException', (e) => {
  console.error(`\n错误：${e?.message ?? e}`);
  finish(1);
});
process.on('unhandledRejection', (e) => {
  console.error(`\n错误：${e?.message ?? e}`);
  finish(1);
});

// ── 令牌与 API ──────────────────────────────────────────────
let TOKEN = null;
function token() {
  if (TOKEN) return TOKEN;
  const r = run('gh', ['auth', 'token']);
  TOKEN = r.stdout.trim();
  if (!TOKEN) throw new Error('取不到 gh 令牌：先运行 "gh auth login"');
  return TOKEN;
}

/** 失败日志里把令牌打码，避免把凭据写进终端记录 */
function mask(s) {
  return TOKEN ? s.split(TOKEN).join('***') : s;
}

async function apiOnce(method, path, payload, { ok404 = false } = {}) {
  const headers = {
    Authorization: `Bearer ${token()}`,
    Accept: 'application/vnd.github+json',
    'User-Agent': 'class-schedule-push-auto',
  };
  const init = { method, headers, cache: 'no-store' };
  if (payload !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(payload);
  }
  let r;
  try {
    r = await fetch(API + path, init);
  } catch (e) {
    // 网络层失败（DNS / TLS / 代理抖动）：可重试
    const err = new Error(`API ${method} ${path} 网络失败：${e.cause?.message ?? e.message}`);
    err.transient = true;
    throw err;
  }
  const text = await r.text().catch(() => '');
  let body;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  if (ok404 && r.status === 404) return null;
  if (!r.ok) {
    const err = new Error(
      `API ${method} ${path} -> HTTP ${r.status}\n${typeof body === 'string' ? body : JSON.stringify(body)}`,
    );
    // 5xx / 429 是暂时性的；4xx 是确定性错误（令牌、权限、参数），重试没意义
    err.transient = r.status >= 500 || r.status === 429;
    throw err;
  }
  return body;
}

/** 带退避重试的 API 调用 */
async function api(method, path, payload, opts) {
  let last;
  for (let i = 1; i <= API_TRIES; i++) {
    try {
      return await apiOnce(method, path, payload, opts);
    } catch (e) {
      last = e;
      if (!e.transient) throw e;
      if (i < API_TRIES) {
        console.log(`    API 抖动（${e.message.split('\n')[0]}），第 ${i}/${API_TRIES} 次后重试 ...`);
        await sleep(800 * i);
      }
    }
  }
  throw last;
}

// ── 读本地提交对象 ──────────────────────────────────────────
const pad = (n, w = 2) => String(n).padStart(w, '0');

/**
 * 把 `Name <email> 1700000000 +0800` 转成 API 接受的 ISO 8601。
 * 必须保留原始秒数与时区偏移，否则 GitHub 算出的 commit SHA 会与本地不一致。
 */
function parsePerson(line) {
  const m = /^(.*) <([^>]*)> (\d+) ([+-]\d{4})$/.exec(line);
  if (!m) throw new Error(`无法解析签名行：${line}`);
  const [, name, email, ts, tz] = m;
  const offMin = (tz[0] === '+' ? 1 : -1) * (Number(tz.slice(1, 3)) * 60 + Number(tz.slice(3, 5)));
  const d = new Date((Number(ts) + offMin * 60) * 1000);
  const date =
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}` +
    `T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}` +
    `${offMin >= 0 ? '+' : '-'}${pad(Math.floor(Math.abs(offMin) / 60))}:${pad(Math.abs(offMin) % 60)}`;
  return { name, email, date };
}

function commitInfo(sha) {
  const raw = git(['cat-file', 'commit', sha], { binary: true }).toString('utf8');
  const cut = raw.indexOf('\n\n');
  const header = raw.slice(0, cut);
  const info = { tree: null, parents: [], author: null, committer: null, message: raw.slice(cut + 2) };
  for (const line of header.split('\n')) {
    if (line.startsWith('tree ')) info.tree = line.slice(5);
    else if (line.startsWith('parent ')) info.parents.push(line.slice(7));
    else if (line.startsWith('author ')) info.author = parsePerson(line.slice(7));
    else if (line.startsWith('committer ')) info.committer = parsePerson(line.slice(9));
  }
  return info;
}

async function remoteHead() {
  const ref = await api('GET', `/repos/${REPO}/git/ref/heads/${BRANCH}`, undefined, { ok404: true });
  return ref ? ref.object.sha : null;
}

async function remoteTree(sha) {
  const c = await api('GET', `/repos/${REPO}/git/commits/${sha}`);
  return c.tree.sha;
}

function tryGitPush() {
  const url = `https://x-access-token:${token()}@github.com/${REPO}.git`;
  for (let i = 1; i <= PUSH_TRIES; i++) {
    console.log(`[1] git push 第 ${i}/${PUSH_TRIES} 次 ...`);
    const r = run('git', [
      '-c', 'credential.helper=',
      '-c', 'http.sslBackend=openssl',
      'push', url, `${BRANCH}:${BRANCH}`,
    ]);
    if (r.status === 0) return true;
    const detail = (mask(r.stderr).trim() || mask(r.stdout).trim()).split('\n').filter(Boolean).pop();
    console.log(`    失败: ${detail ?? '(无输出)'}`);
  }
  return false;
}

function verifyRemote() {
  return run('node', ['tools/verify-remote.mjs'], { inherit: true });
}

// ── 主流程 ──────────────────────────────────────────────────
if (COMMIT_MSG) {
  console.log(`[0] 提交工作区改动：${COMMIT_MSG}`);
  git(['add', '-A']);
  const r = run('git', ['commit', '-m', COMMIT_MSG]);
  console.log(`    ${r.stdout.trim().split('\n')[0] ?? '(无输出)'}`);
}

const localHead = git(['rev-parse', 'HEAD']).trim();
console.log(`本地 HEAD : ${localHead.slice(0, 12)}`);

const rh = await remoteHead();
console.log(`远端 ${BRANCH.padEnd(6)}: ${(rh ?? '(分支不存在)').slice(0, 12)}`);

if (rh === localHead) {
  console.log('\n已同步，无需推送。');
  finish(0);
}
if (!rh) {
  throw new Error(`远端没有 ${BRANCH} 分支：本脚本只处理已存在分支的快进推送`);
}

// 快进校验：远端必须是本地的祖先，否则可能是别人先推了
const anc = run('git', ['merge-base', '--is-ancestor', rh, localHead]);
if (anc.status !== 0) {
  throw new Error(`远端 ${BRANCH} 不是本地 HEAD 的祖先（非快进，可能已有他人提交）：请先 git pull --rebase`);
}

const chain = git(['rev-list', '--reverse', `${rh}..${localHead}`]).trim().split('\n').filter(Boolean);
console.log(`待推送提交: ${chain.length} 个`);
for (const sha of chain) {
  console.log(`   ${git(['log', '-1', '--format=%h %s', sha]).trim()}`);
  if (commitInfo(sha).parents.length > 1) {
    throw new Error(`提交 ${sha.slice(0, 12)} 是 merge 提交：本脚本只支持线性历史，请人工处理`);
  }
}

// ── 步骤 1：先试 git push ───────────────────────────────────
if (DRY) {
  // 演练绝不能改远端：连 git push 都要跳过（它一旦成功就是真推送）
  console.log('[1] --dry-run：跳过 git push，直接演练 API 重建\n');
} else if (GIT_ONLY) {
  if (!tryGitPush()) throw new Error('--git-only：git push 没成功（去掉该参数即自动降级到 API）');
  console.log('\n[OK] git push 成功。');
  finish(verifyRemote().status ?? 1);
} else if (!API_ONLY) {
  if (tryGitPush()) {
    console.log('\n[OK] git push 成功。');
    finish(verifyRemote().status ?? 1);
  }
  console.log('[1] git push 不通 -> 降级到 Git Data API（github.com:443 被拦/超时的典型情形）\n');
}

// ── 步骤 2：API 逐个重建提交 ────────────────────────────────
console.log(`[2] 通过 Git Data API 重建提交${DRY ? '（演练模式，不改远端 ref）' : ''}`);
let parentSha = rh;
let parentTree = await remoteTree(rh);

for (const sha of chain) {
  const ci = commitInfo(sha);
  if (ci.parents.length !== 1 || ci.parents[0] !== parentSha) {
    throw new Error(`提交 ${sha.slice(0, 12)} 的父不是预期值（历史非线性的迹象），中止`);
  }

  // 变更清单（-z：NUL 分隔，避免中文/空格路径被转义）
  const parts = git(['diff', '--name-status', '-z', '--no-renames', parentSha, sha], { binary: true })
    .toString('utf8')
    .split('\0');
  const entries = [];
  for (let i = 0; i + 1 < parts.length; i += 2) {
    if (parts[i]) entries.push([parts[i], parts[i + 1]]);
  }

  const treeEntries = [];
  for (const [status, path] of entries) {
    if (status === 'D') {
      treeEntries.push({ path, mode: '100644', type: 'blob', sha: null });
      continue;
    }
    const [meta] = git(['ls-tree', sha, '--', path]).trim().split('\t');
    const [mode, , blobSha] = meta.split(' ');
    const content = git(['cat-file', 'blob', blobSha], { binary: true });
    if (!DRY) {
      const got = await api('POST', `/repos/${REPO}/git/blobs`, {
        content: content.toString('base64'),
        encoding: 'base64',
      });
      if (got.sha !== blobSha) throw new Error(`上传的 blob 与本地不一致：${path}`);
    }
    treeEntries.push({ path, mode, type: 'blob', sha: blobSha });
  }

  if (DRY) {
    console.log(`    ${sha.slice(0, 12)}  将改 ${entries.length} 个文件`);
    parentSha = sha;
    parentTree = ci.tree;
    continue;
  }

  const newTree = await api('POST', `/repos/${REPO}/git/trees`, { base_tree: parentTree, tree: treeEntries });
  if (newTree.sha !== ci.tree) {
    throw new Error(`重建的树与本地不一致（${sha.slice(0, 12)}）：可能 CRLF/文件模式差异，中止`);
  }

  const newCommit = await api('POST', `/repos/${REPO}/git/commits`, {
    message: ci.message,
    tree: newTree.sha,
    parents: [parentSha],
    author: ci.author,
    committer: ci.committer,
  });
  if (newCommit.sha !== sha) {
    throw new Error(`重建的提交 SHA 与本地不一致，中止（避免远端分叉）`);
  }
  console.log(`    ${sha.slice(0, 12)} 重建成功`);
  parentSha = newCommit.sha;
  parentTree = newTree.sha;
}

if (DRY) {
  console.log('\n（演练结束，未改远端 ref）');
  finish(0);
}

if (parentSha !== localHead) {
  throw new Error(`重建后的 HEAD(${parentSha.slice(0, 12)}) != 本地 HEAD，中止`);
}

await api('PATCH', `/repos/${REPO}/git/refs/heads/${BRANCH}`, { sha: parentSha, force: false });
console.log(`\n[OK] 远端 ${BRANCH} -> ${parentSha.slice(0, 12)}`);

// ── 步骤 3：核对 ────────────────────────────────────────────
// 把核对结果当作本脚本的退出码传出去：万一 git push 口是心非（退出 0 却没真推上去），
// verify-remote 会发现"本地有未推送的提交"并返回 1 —— 这里不能当成成功。
console.log('[3] 核对远程与本地 ...');
finish(verifyRemote().status ?? 1);
