/**
 * 读取 Compose BOM 的 pom，列出它锁定的 compose 版本。
 * 用途：挑选与 compileSdk 匹配的 BOM，避免"库要求更高 API"的构建失败。
 *
 * 用法：node tools/show-bom.mjs 2025.06.01 2025.09.00
 */

const REPO = 'https://maven.aliyun.com/repository/google/';
const FALLBACK = 'https://dl.google.com/dl/android/maven2/';

const versions = process.argv.slice(2);
if (!versions.length) {
  console.log('用法：node tools/show-bom.mjs <bom版本> [更多版本…]');
  process.exit(1);
}

for (const v of versions) {
  const path = `androidx/compose/compose-bom/${v}/compose-bom-${v}.pom`;
  let text = null;
  for (const base of [REPO, FALLBACK]) {
    try {
      const r = await fetch(base + path);
      if (r.ok) { text = await r.text(); break; }
    } catch { /* 试下一个源 */ }
  }
  if (!text) { console.log(`BOM ${v}：取不到 pom`); continue; }

  const deps = [...text.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map((m) => m[1]);
  const pick = (artifactId) => {
    for (const d of deps) {
      const a = d.match(/<artifactId>([^<]+)<\/artifactId>/);
      const ver = d.match(/<version>([^<]+)<\/version>/);
      if (a && ver && a[1] === artifactId) return ver[1];
    }
    return null;
  };

  console.log(`BOM ${v}:  ui=${pick('ui')}  material3=${pick('material3')}  foundation=${pick('foundation')}  runtime=${pick('runtime')}`);
}
