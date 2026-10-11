#!/usr/bin/env node
/**
 * wifi-share — 在电脑上开一个只供局域网访问的静态文件服务器。
 * 用途：手机和电脑连同一个 WiFi 时，用手机浏览器把电脑上的 APK 下载到手机。
 *
 * 用法：
 *   node tools/wifi-share.mjs                  # 共享当前目录
 *   node tools/wifi-share.mjs --dir app/build/outputs/apk/release
 *   node tools/wifi-share.mjs --port 8000
 *
 * 安全说明：只监听并服务本机文件，不连接外网、不上传任何数据。
 * 用完按 Ctrl + C 关闭。
 */

import { createServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import { createReadStream, promises as fsp } from 'node:fs';
import path from 'node:path';

const MIME = {
  '.apk': 'application/vnd.android.package-archive',
  '.html': 'text/html; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.zip': 'application/zip',
};

function argValue(flag, fallback) {
  const i = process.argv.indexOf(flag);
  return i !== -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const root = path.resolve(argValue('--dir', process.cwd()));
const port = Number(argValue('--port', '8000'));

function human(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function htmlEscape(s) {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/** 列出目录内容，返回 HTML 或 null（表示不是目录） */
async function renderListing(urlPath) {
  const dir = path.join(root, decodeURIComponent(urlPath));
  const rel = path.relative(root, dir);
  if (rel.startsWith('..') || path.isAbsolute(rel)) return null; // 防目录穿越
  try {
    const entries = await fsp.readdir(dir, { withFileTypes: true });
    const rows = [];
    if (urlPath !== '/') rows.push(`<li><a href="../">⬆ 返回上级</a></li>`);
    for (const e of entries.sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name))) {
      if (e.name.startsWith('.')) continue;
      const child = path.join(urlPath, e.name).replace(/\\/g, '/');
      const href = encodeURI(child) + (e.isDirectory() ? '/' : '');
      let size = '';
      try {
        size = e.isDirectory() ? '目录' : human((await fsp.stat(path.join(dir, e.name))).size);
      } catch { /* 忽略无法读取的文件 */ }
      rows.push(`<li><a href="${href}">${htmlEscape(e.name)}</a> <span class="s">${size}</span></li>`);
    }
    return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>文件传输 · ${htmlEscape(urlPath)}</title><style>
body{font-family:system-ui,"Microsoft YaHei",sans-serif;margin:0;padding:20px;background:#f6f7f9;color:#1a1a1a}
h1{font-size:17px;margin:0 0 4px}p.hint{color:#666;font-size:13px;margin:0 0 16px}
ul{list-style:none;padding:0;margin:0}
li{background:#fff;border:1px solid #e3e6ea;border-radius:10px;padding:14px 16px;margin-bottom:8px;
   display:flex;justify-content:space-between;align-items:center;gap:12px}
a{color:#0b62d0;text-decoration:none;font-size:16px;word-break:break-all}
.s{color:#888;font-size:13px;white-space:nowrap}
</style></head><body>
<h1>局域网文件传输</h1>
<p class="hint">点文件名即可下载。装 APK 时若提示"未知来源"，选择允许即可。</p>
<ul>${rows.join('')}</ul></body></html>`;
  } catch {
    return null;
  }
}

const server = createServer(async (req, res) => {
  let urlPath;
  try {
    urlPath = new URL(req.url, 'http://localhost').pathname;
  } catch {
    res.writeHead(400).end('Bad Request');
    return;
  }

  const target = path.join(root, decodeURIComponent(urlPath));
  const rel = path.relative(root, target);
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    res.writeHead(403).end('Forbidden');
    return;
  }

  let stat = null;
  try {
    stat = await fsp.stat(target);
  } catch { /* 不存在 */ }

  if (stat?.isFile()) {
    const headers = {
      'Content-Type': MIME[path.extname(target).toLowerCase()] ?? 'application/octet-stream',
      'Content-Length': stat.size,
      'Accept-Ranges': 'bytes',
      'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(path.basename(target))}`,
    };
    res.writeHead(200, headers);
    createReadStream(target).pipe(res);
    const who = req.socket.remoteAddress?.replace('::ffff:', '') ?? '?';
    console.log(`  ↓ ${who} 下载 ${path.basename(target)} (${human(stat.size)})`);
    return;
  }

  // 目录 → 列目录；目录但没有结尾斜杠 → 跳转，保证相对链接正确
  const listing = stat?.isDirectory()
    ? (urlPath.endsWith('/') ? await renderListing(urlPath) : null)
    : null;
  if (stat?.isDirectory() && !urlPath.endsWith('/')) {
    res.writeHead(302, { Location: encodeURI(urlPath) + '/' }).end();
    return;
  }
  if (listing) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': Buffer.byteLength(listing) });
    res.end(listing);
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end('<h1>404</h1><p>文件或目录不存在。</p>');
});

/** 找出电脑在局域网里的地址，过滤掉虚拟网卡 */
function lanAddresses() {
  const out = [];
  for (const [name, addrs] of Object.entries(networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      if (/vmware|virtualbox|vethernet|hyper-v|loopback|radmin|tailscale|zerotier|wsl|docker/i.test(name)) continue;
      const priv = /^192\.168\./.test(a.address) || /^10\./.test(a.address) || /^172\.(1[6-9]|2\d|3[01])\./.test(a.address);
      out.push({ name, address: a.address, priv });
    }
  }
  return out.sort((x, y) => Number(y.priv) - Number(x.priv));
}

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`\n端口 ${port} 已被占用。换一个端口再试：node tools/wifi-share.mjs --port 8001\n`);
  } else {
    console.error('\n启动失败：', err.message, '\n');
  }
  process.exit(1);
});

server.listen(port, '0.0.0.0', () => {
  const addrs = lanAddresses();
  console.log('\n局域网文件服务器已启动');
  console.log('共享目录：' + root);
  console.log('\n在手机浏览器里打开下面任意一个地址（手机和电脑要连同一个 WiFi）：');
  for (const a of addrs) console.log(`   http://${a.address}:${port}/     [${a.name}]`);
  if (!addrs.length) console.log('   （没找到局域网地址，请检查 WiFi 连接）');
  console.log('\n若浏览器打不开：把电脑网络设为"专用网络"，并允许 Node.js 通过 Windows 防火墙。');
  console.log('用完按 Ctrl + C 关闭。\n');

  if (process.env.WIFI_SHARE_SELFTEST === '1') {
    // 自检模式：启动后立即退出，仅供自动化验证使用
    setTimeout(() => process.exit(0), 300);
  }
});

// 有请求进来时记录一次，方便判断手机是否真的连上了
server.on('connection', (socket) => {
  if (process.env.WIFI_SHARE_VERBOSE === '1') {
    console.log('  · 收到连接：' + (socket.remoteAddress ?? '?'));
  }
});
