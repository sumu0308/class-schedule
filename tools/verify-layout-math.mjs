/**
 * verify-layout-math.mjs — 独立复算课表排版的关键数值，用于核对文档里的断言。
 *
 * 这里刻意用 Node 把 Kotlin 里的算法重写一遍（而非直接读 Kotlin 输出），
 * 目的是"两套独立实现互相验证"：如果两边算出的数不一致，说明文档或代码有一方是错的。
 *
 * 用法：node tools/verify-layout-math.mjs
 */

// ── 与 Kotlin 侧保持一致的常量（ScheduleGrid.kt / GridMetrics）──────────
const MIN_PX_PER_MINUTE = 0.9;
const MAX_PX_PER_MINUTE = 2.2;
const TARGET_TYPICAL_ROW_DP = 80;
const HEADER_HEIGHT_DP = 34;

// ── 与 Kotlin 侧保持一致的默认节次（CourseRepository.defaultPeriods）────
const PERIODS = [
  ['08:00', '08:40'],
  ['09:00', '09:40'],
  ['10:00', '10:40'],
  ['11:00', '11:40'],
  ['14:30', '15:10'],
  ['15:30', '16:10'],
  ['16:30', '17:10'],
  ['17:30', '18:10'],
];

const minutesOf = (t) => {
  const [h, m] = t.split(':').map(Number);
  return h * 60 + m;
};

function nameTextSizeSp(blockHeightDp) {
  if (blockHeightDp >= 150) return 14;
  if (blockHeightDp >= 110) return 13;
  if (blockHeightDp >= 80) return 12;
  if (blockHeightDp >= 62) return 11;
  if (blockHeightDp >= 48) return 10;
  return 9;
}

function compute(availableHeightDp = 0) {
  const starts = PERIODS.map(([s]) => minutesOf(s));
  const ends = PERIODS.map(([, e]) => minutesOf(e));
  const durations = PERIODS.map(([s, e]) => minutesOf(e) - minutesOf(s));

  const sortedDurations = [...durations].sort((a, b) => a - b);
  const typicalMinutes = sortedDurations[Math.floor(sortedDurations.length / 2)];

  let pxPerMinute = TARGET_TYPICAL_ROW_DP / typicalMinutes;
  if (availableHeightDp > 0) {
    const span = Math.max(...ends) - Math.min(...starts);
    const fit = (availableHeightDp - HEADER_HEIGHT_DP * 2) / span;
    if (fit > pxPerMinute) pxPerMinute = fit;
  }
  pxPerMinute = Math.min(MAX_PX_PER_MINUTE, Math.max(MIN_PX_PER_MINUTE, pxPerMinute));

  const heights = durations.map((d) => d * pxPerMinute);
  const offsets = starts.map((s) => (s - Math.min(...starts)) * pxPerMinute);
  const contentHeight = offsets[offsets.length - 1] + heights[heights.length - 1];

  return { pxPerMinute, typicalMinutes, heights, offsets, contentHeight, durations };
}

// ── 旧算法（0.3.0 的缺陷版本），用于对照 ────────────────────────────────
function computeOld() {
  const starts = PERIODS.map(([s]) => minutesOf(s));
  const ends = PERIODS.map(([, e]) => minutesOf(e));
  const gaps = [];
  for (let i = 1; i < starts.length; i++) {
    const g = starts[i] - ends[i - 1];
    if (g > 0) gaps.push(g);
  }
  const sorted = [...gaps].sort((a, b) => a - b);
  const medianGap = sorted[Math.floor(sorted.length / 2)];
  const raw = (HEADER_HEIGHT_DP * 2) / medianGap;
  const pxPerMinute = Math.min(2.2, Math.max(0.75, raw));
  return { gaps, sorted, medianGap, raw, pxPerMinute };
}

const line = (s) => console.log(s);
const rule = () => line('─'.repeat(66));

rule();
line('课表排版数值复算（用于核对 README / 计划文档里的断言）');
rule();

const old = computeOld();
line('\n【旧算法 — 0.3.0 用法，用于对照】');
line(`  节次间隔：${old.gaps.join(', ')} 分钟`);
line(`  排序后：  ${old.sorted.join(', ')} 分钟`);
line(`  取索引 ${Math.floor(old.sorted.length / 2)}：${old.medianGap} 分钟`);
line(`  推算系数：34 ÷ ${old.medianGap} = ${old.raw.toFixed(2)}  → 被夹到 ${old.pxPerMinute}`);
line(`  40 分钟行高：${(40 * old.pxPerMinute).toFixed(0)} dp`);
line('  注：旧算法并非"被午休压扁"——7 个间隔排序后索引 3 是 20 分钟，中午休排在最后。');
line('  两者行高接近（88 vs 80 dp），换算法的真正理由是：旧写法依赖"休息时长"这个与行高');
line('  无关的量，且缺少屏幕自适应能力。');

const now = compute();
line('\n【新算法 — 0.4.0】');
line(`  节次时长：${now.durations.join(', ')} 分钟`);
line(`  中位时长：${now.typicalMinutes} 分钟`);
line(`  比例系数：${TARGET_TYPICAL_ROW_DP} ÷ ${now.typicalMinutes} = ${now.pxPerMinute.toFixed(2)}`);
line(`  40 分钟行高：${now.heights[0].toFixed(0)} dp  → 课名字号 ${nameTextSizeSp(now.heights[0])} sp`);
line(`  整段 100 分钟行高：${(100 * now.pxPerMinute).toFixed(0)} dp  → 课名字号 ${nameTextSizeSp(100 * now.pxPerMinute)} sp`);
line(`  全天内容高度：${now.contentHeight.toFixed(0)} dp`);

line('\n【不同屏幕高度下的放大效果（第二段自适应）】');
for (const h of [500, 600, 700, 800, 1000, 3000]) {
  const r = compute(h);
  line(
    `  可用 ${String(h).padStart(4)} dp → 系数 ${r.pxPerMinute.toFixed(2)}  40 分钟行 ${r.heights[0].toFixed(0)} dp` +
      `  全天 ${r.contentHeight.toFixed(0)} dp${r.contentHeight > h ? '（需滚动）' : '（一屏放下）'}`,
  );
}

line('\n【文档断言核对】');
const checks = [
  ['新算法 比例系数 = 2.0', Math.abs(now.pxPerMinute - 2.0) < 0.001],
  ['新算法 40 分钟行高 = 80 dp', Math.abs(now.heights[0] - 80) < 0.5],
  ['新算法 40 分钟行对应字号 = 12 sp', nameTextSizeSp(now.heights[0]) === 12],
  ['新算法 100 分钟行高 = 200 dp', Math.abs(100 * now.pxPerMinute - 200) < 0.5],
  ['旧算法 40 分钟行高 = 88 dp（对照用）', Math.abs(40 * old.pxPerMinute - 88) < 0.5],
  ['旧算法的中位数是 20 分钟而非午休 170（纠正早前的误判）', old.medianGap === 20],
  ['新系数不再落在下限', now.pxPerMinute > MIN_PX_PER_MINUTE + 0.01],
  ['系数不超过上限', compute(99999).pxPerMinute <= MAX_PX_PER_MINUTE + 0.001],
];
let ok = true;
for (const [label, pass] of checks) {
  line(`  ${pass ? '✓' : '✗'} ${label}`);
  if (!pass) ok = false;
}
rule();
line(ok ? '全部核对通过：文档数值与算法一致' : '存在不一致，需要修正文档或代码');
process.exit(ok ? 0 : 1);
