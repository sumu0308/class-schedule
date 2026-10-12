/**
 * verify-layout-math.mjs — 独立复算课表排版的关键数值。
 *
 * 这里刻意用 Node 把 Kotlin 里的算法重写一遍（而非直接读 Kotlin 输出），
 * 目的是"两套独立实现互相验证"：两边算出的数不一致，说明有一方是错的。
 * 镜像对象：`app/src/main/java/com/classschedule/app/ui/ScheduleGrid.kt`
 *   GridMetrics       → ScheduleGrid.kt:65-134
 *   GridGeometry.build → ScheduleGrid.kt:242-306
 *
 * 口径以现行代码为准（v0.7.0，ScheduleGrid.kt 的注释里也写了同一套）：
 *   ① 一行 = 一个大节（PERIODS_PER_BLOCK = 2 个小节两两配对），行高按真实时长成比例；
 *   ② 兜底系数 FALLBACK_PX_PER_MINUTE = 1.45 —— 40 分钟的小节正好 58 dp；
 *   ③ 屏幕放不下时**向下**压缩，但不低于 MIN_PX_PER_MINUTE = 0.95，再放不下就滚动；
 *   ④ 屏幕再高也**不放大**（MAX_PX_PER_MINUTE = 2.2 只是兜底上限）。
 *
 * ⚠ 0.4.0–0.6.0 的旧口径（系数 2.0、40 分钟 = 80 dp、全天 1220 dp、屏幕越高行越高）
 *   已被 0.7.0 废弃，本脚本不再复算它 —— 旧公式与现行代码对不上，跑出来的"通过"
 *   是假绿灯。要对照旧口径请看 `第一版问题.md` 第三轮反馈。
 *
 * 用法：node tools/verify-layout-math.mjs
 */

// ── 与 Kotlin 侧保持一致的常量（ScheduleGrid.kt / GridMetrics）──────────
const FALLBACK_PX_PER_MINUTE = 1.45; // GridMetrics.FALLBACK_PX_PER_MINUTE
const MIN_PX_PER_MINUTE = 0.95; // GridMetrics.MIN_PX_PER_MINUTE
const MAX_PX_PER_MINUTE = 2.2; // GridMetrics.MAX_PX_PER_MINUTE
const PERIODS_PER_BLOCK = 2; // GridMetrics.PERIODS_PER_BLOCK

// ── 与 Kotlin 侧保持一致的默认节次（CourseRepository.defaultPeriods）────
const PERIODS = [
  { index: 1, startTime: '08:00', endTime: '08:40' },
  { index: 2, startTime: '09:00', endTime: '09:40' },
  { index: 3, startTime: '10:00', endTime: '10:40' },
  { index: 4, startTime: '11:00', endTime: '11:40' },
  { index: 5, startTime: '14:30', endTime: '15:10' },
  { index: 6, startTime: '15:30', endTime: '16:10' },
  { index: 7, startTime: '16:30', endTime: '17:10' },
  { index: 8, startTime: '17:30', endTime: '18:10' },
];

const minutesOf = (t) => {
  const [h, m] = String(t).split(':').map(Number);
  return h * 60 + (m || 0);
};
/** GridClock.formatMinutesCompact：480 → "8:00" */
const compact = (total) => `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;

/** 逐行镜像 GridGeometry.build(periods, availableHeightDp, periodsPerBlock) */
function build(periods = PERIODS, availableHeightDp = 0, periodsPerBlock = PERIODS_PER_BLOCK) {
  if (periods.length === 0) {
    return {
      rows: [], offsets: [], heights: [],
      pxPerMinute: FALLBACK_PX_PER_MINUTE, contentHeight: 0, hourTicks: [],
    };
  }

  const sorted = [...periods].sort((a, b) => a.index - b.index);
  const per = Math.max(1, periodsPerBlock);
  const chunks = [];
  for (let i = 0; i < sorted.length; i += per) chunks.push(sorted.slice(i, i + per));

  const rows = chunks.map((chunk) => {
    const starts = chunk.map((p) => minutesOf(p.startTime));
    const ends = chunk.map((p) => minutesOf(p.endTime));
    const start = Math.min(...starts);
    // 脏数据（结束早于开始）兜成正数，避免行高或刻度算出负值
    const span = Math.max(1, Math.max(...ends) - start);
    return { startMinutes: start, endMinutes: start + span, periodIndices: chunk.map((p) => p.index) };
  });

  const baseMinute = Math.min(...rows.map((r) => r.startMinutes));
  const lastMinute = Math.max(...rows.map((r) => r.endMinutes));
  const spanMinutes = Math.max(1, lastMinute - baseMinute);

  // 兜底系数优先；屏幕装不下才压缩，但不再低于可读下限（注意：不放大）
  let pxPerMinute = FALLBACK_PX_PER_MINUTE;
  if (availableHeightDp > 0) {
    const fit = availableHeightDp / spanMinutes;
    if (fit < pxPerMinute) pxPerMinute = fit;
  }
  pxPerMinute = Math.min(MAX_PX_PER_MINUTE, Math.max(MIN_PX_PER_MINUTE, pxPerMinute));

  const offsets = rows.map((r) => (r.startMinutes - baseMinute) * pxPerMinute);
  const heights = rows.map((r) => (r.endMinutes - r.startMinutes) * pxPerMinute);
  const contentHeight = offsets[offsets.length - 1] + heights[heights.length - 1];

  // 整点刻度：从第一个节次所在的那个整点开始，每小时一条直到最晚结束
  const hourTicks = [];
  for (let m = Math.floor(baseMinute / 60) * 60; m <= lastMinute; m += 60) {
    hourTicks.push([compact(m), (m - baseMinute) * pxPerMinute]);
  }

  return { rows, offsets, heights, pxPerMinute, contentHeight, hourTicks, spanMinutes, baseMinute, lastMinute };
}

const line = (s) => console.log(s);
const rule = () => line('─'.repeat(74));

rule();
line('课表排版数值复算（与 ScheduleGrid.kt 的 Kotlin 实现互为独立实现）');
rule();

const now = build();
line('\n【现行算法 — v0.7.0】');
line(`  一天 ${PERIODS.length} 个小节 → 每 ${PERIODS_PER_BLOCK} 小节一行 = ${now.rows.length} 行（一个大节一行）`);
line(`  整天跨度（首行起 → 末行止）：${now.spanMinutes} 分钟（${compact(now.baseMinute)} → ${compact(now.lastMinute)}）`);
line(`  比例系数：无屏幕约束 → 兜底 ${FALLBACK_PX_PER_MINUTE}（可压缩到 ${MIN_PX_PER_MINUTE}，上限 ${MAX_PX_PER_MINUTE}）`);
line(`  40 分钟的小节：${(40 * now.pxPerMinute).toFixed(1)} dp`);
line(`  100 分钟的大节：${now.heights[0].toFixed(1)} dp`);
line(`  全天内容高度：${now.spanMinutes} × ${now.pxPerMinute} = ${now.contentHeight.toFixed(1)} dp`);
line(`  整点刻度：${now.hourTicks.length} 条（${now.hourTicks[0][0]} … ${now.hourTicks.at(-1)[0]}）`);

line('\n【逐行几何】');
line('  行  小节      起止           偏移(dp)   行高(dp)   行内空档');
let prevBottom = 0;
for (let i = 0; i < now.rows.length; i++) {
  const top = now.offsets[i];
  const gap = top - prevBottom;
  line(
    `  ${String(i + 1).padStart(2)}  ${now.rows[i].periodIndices.join(',').padEnd(7)} ` +
      `${(compact(now.rows[i].startMinutes) + '-' + compact(now.rows[i].endMinutes)).padEnd(14)} ` +
      `${top.toFixed(1).padStart(8)} ${now.heights[i].toFixed(1).padStart(9)}` +
      (i > 0 && gap > 0.01 ? `   ${gap.toFixed(1)} dp（上一行与本行之间）` : ''),
  );
  prevBottom = top + now.heights[i];
}

line('\n【整点刻度位置】');
line(now.hourTicks.map(([label, y]) => `${label}@${y.toFixed(0)}`).join('  '));

line('\n【不同可用高度下的行为（第二段：只压缩，不放大）】');
for (const h of [100, 400, 610, 885, 1200, 100000]) {
  const r = build(PERIODS, h);
  const verdict = r.pxPerMinute === MIN_PX_PER_MINUTE && r.contentHeight > h + 0.5
    ? '（撞可读下限，靠滚动）'
    : r.contentHeight > h + 0.5
      ? '（需滚动）'
      : '（一屏放下）';
  line(
    `  可用 ${String(h).padStart(6)} dp → 系数 ${r.pxPerMinute.toFixed(2)}  ` +
      `40 分钟小节的 1/2.5 = ${(40 * r.pxPerMinute).toFixed(1)} dp  ` +
      `全天 ${r.contentHeight.toFixed(1)} dp ${verdict}`,
  );
}

line('\n【断言：与 Kotlin 实现对齐】');
const squeeze610 = build(PERIODS, 610);
const tiny = build(PERIODS, 100);
const huge = build(PERIODS, 100000);
const empty = build([]);
const checks = [
  ['兜底系数 = 1.45（不受屏幕尺寸影响）', now.pxPerMinute === FALLBACK_PX_PER_MINUTE],
  ['40 分钟的小节 = 58 dp', Math.abs(40 * now.pxPerMinute - 58) < 0.01],
  ['100 分钟的大节 = 145 dp', Math.abs(now.heights[0] - 145) < 0.01],
  ['8 小节两两成行 = 4 行', now.rows.length === 4],
  ['每行跨度 100 分钟（含 20 分钟课间）', now.rows.every((r) => r.endMinutes - r.startMinutes === 100)],
  ['整天跨度 = 610 分钟', now.spanMinutes === 610],
  ['全天内容高度 = 610 × 1.45 = 884.5 dp', Math.abs(now.contentHeight - 884.5) < 0.01],
  ['整点刻度 8:00→18:00 共 11 条', now.hourTicks.length === 11 && now.hourTicks[0][0] === '8:00' && now.hourTicks.at(-1)[0] === '18:00'],
  ['首条整点刻度贴顶（偏移 0）', Math.abs(now.hourTicks[0][1]) < 0.01],
  ['可用 610 dp → 系数 1.00，整天正好装下', Math.abs(squeeze610.pxPerMinute - 1) < 0.001 && Math.abs(squeeze610.contentHeight - 610) < 0.5],
  ['可用 100 dp → 撞下限 0.95，靠滚动看全', Math.abs(tiny.pxPerMinute - MIN_PX_PER_MINUTE) < 0.001 && tiny.contentHeight > 100],
  ['屏幕再高也不放大（100000 dp 仍是兜底系数）', huge.pxPerMinute === FALLBACK_PX_PER_MINUTE],
  ['系数永不突破上限 2.2', [now, squeeze610, tiny, huge].every((g) => g.pxPerMinute <= MAX_PX_PER_MINUTE)],
  ['空节次表不崩溃且内容高度为 0', empty.contentHeight === 0 && empty.rows.length === 0],
];
let ok = true;
for (const [label, pass] of checks) {
  line(`  ${pass ? '✓' : '✗'} ${label}`);
  if (!pass) ok = false;
}
rule();
line(ok ? '全部复算通过：Node 侧与 Kotlin 侧口径一致' : '存在不一致：Node 脚本或 ScheduleGrid.kt 有一方是错的');
process.exit(ok ? 0 : 1);
