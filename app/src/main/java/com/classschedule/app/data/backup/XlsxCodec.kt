package com.classschedule.app.data.backup

import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.WeekParity
import com.classschedule.app.util.GridClock
import com.classschedule.app.util.WeekRanges
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * XLSX（教务系统导出的课表）导入（F-31 / F-38，规则见计划文档 10.2.5）。
 *
 * 零第三方依赖：xlsx 本质是个 zip + xml 包，用 JDK 自带的
 * `ZipInputStream` 与 DOM 解析，不引 Apache POI（对单机课表 App 太重）。
 *
 * 布局识别（按内容，不信任扩展名与 sheet 名）：
 *   1. **矩阵布局**：某行 ≥3 个格子命中星期词（表头行），某列 ≥2 个格子
 *      命中节次词（节次列）。列 = 星期、行 = 起止节次，格子 = 课程。
 *   2. **列表布局**：一行一门课（表头命中 CSV 提示词），转记录流后
 *      直接复用 [CsvCodec] 的行解析。
 *
 * 保真度声明（10.2.5）：格子内认不出的文本原样进 note，不静默丢弃；
 * 不支持 .xls（旧 OLE 格式），嗅探在 VM 层做，这里只管 zip。
 * 导出方向不存在——0.10.0 起导出功能已移除。
 */
object XlsxCodec {

    private val OOXML_RELS_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    // ── 入口 ────────────────────────────────────────────────

    /**
     * @param term   当前学期（矩阵格子缺周次时兜底整学期；时间行头匹配也依赖它）
     * @param periods 当前学期节次（行头是时间段时按墙上时间匹配，同 ICS 规则）
     */
    fun parse(bytes: ByteArray, term: Term, periods: List<Period>): ParseReport {
        val entries = try {
            readZipEntries(bytes)
        } catch (e: IllegalStateException) {
            // 解压上限被触发：给出具体原因，别一律说成"结构损坏"
            return ParseReport("XLSX", emptyList(), listOf(ParseIssue("文件", e.message ?: "无法读取 xlsx")))
        } catch (e: Exception) {
            return ParseReport("XLSX", emptyList(), listOf(ParseIssue("文件", "无法读取 xlsx（zip 结构损坏或不是 xlsx）")))
        }
        if (entries.isEmpty()) {
            return ParseReport("XLSX", emptyList(), listOf(ParseIssue("文件", "压缩包里没有任何文件，不是有效的 xlsx")))
        }

        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheets = resolveSheets(entries)
        if (sheets.isEmpty()) {
            return ParseReport("XLSX", emptyList(), listOf(ParseIssue("文件", "找不到工作表（xl/workbook.xml 缺失或损坏）")))
        }

        val issues = mutableListOf<ParseIssue>()
        for (sheet in sheets) {
            val grid = try {
                parseSheet(entries[sheet.path] ?: byteArrayOf(), shared)
            } catch (e: Exception) {
                issues += ParseIssue("工作表「${sheet.name}」", "内容损坏无法解析")
                continue
            }
            if (grid.nonEmptyCount() == 0) continue // 空 sheet（常见：第一个 sheet 是说明页）

            val matrix = detectMatrix(grid, periods)
            val report = if (matrix != null) {
                parseMatrix(grid, matrix, term, periods, sheet.name)
            } else {
                parseListLayout(grid, sheet.name, term, periods)
            }
            if (report.courses.isNotEmpty()) {
                return report
            }
            issues += if (report.issues.isNotEmpty()) {
                report.issues.map { ParseIssue("工作表「${sheet.name}」", it.reason) }
            } else {
                listOf(ParseIssue("工作表「${sheet.name}」", "没有解析到课程"))
            }
        }
        if (issues.isEmpty()) {
            issues += ParseIssue("文件", "所有工作表都是空的，没有可导入的内容")
        }
        return ParseReport("XLSX", emptyList(), issues)
    }

    // ── zip 与 xml ──────────────────────────────────────────

    /** 解压上限：xlsx 内容不可信，异常文件可解出超大条目把内存吃光（纯本地风险低，仍设几行兜底） */
    private const val MAX_ZIP_ENTRIES = 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024

    /** 读 zip 内全部条目为字节数组；entry 名归一（去前导 ./），并施加条目数/体积上限 */
    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        var total = 0
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    if (++count > MAX_ZIP_ENTRIES) throw IllegalStateException("压缩包条目过多，不是有效的 xlsx")
                    val data = readEntryCapped(zip, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total)
                    total += data.size
                    out[entry.name.removePrefix("./")] = data
                }
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** 读当前 zip 条目（带上限）；超限抛 IllegalStateException，由 parse 转成可读提示 */
    private fun readEntryCapped(zip: ZipInputStream, perEntry: Int, totalLeft: Int): ByteArray {
        val limit = minOf(perEntry, totalLeft)
        if (limit <= 0) throw IllegalStateException("压缩包解压后过大，不是有效的 xlsx")
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var size = 0
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            size += n
            if (size > limit) throw IllegalStateException("压缩包解压后过大，不是有效的 xlsx")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 禁用 DTD / 外部实体的 DOM 工厂：xlsx 内容不可信，防 XXE */
    private fun newBuilder() = try {
        DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl",
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
            ).forEach { f -> try { setFeature(f, false) } catch (_: Exception) {} }
        }.newDocumentBuilder()
    } catch (e: Exception) {
        throw IllegalStateException("XML 解析器不可用", e)
    }

    private fun Document.byTag(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    /** 按属性名取（容错命名空间前缀：r:id / id 都可能） */
    private fun Element.attr(vararg names: String): String? {
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            val local = a.nodeName.substringAfter(':')
            if (local in names && a.nodeValue != null) return a.nodeValue
        }
        return null
    }

    /** 元素全部后代文本拼接（si 的多个 run、is 的多个 t 都靠它） */
    private fun Element.deepText(): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) {
                sb.append(n.nodeValue)
            }
            for (i in 0 until n.childNodes.length) walk(n.childNodes.item(i))
        }
        walk(this)
        return sb.toString()
    }

    /** sharedStrings.xml → 字符串表；si 内多个 run 的文本要拼接 */
    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val doc = newBuilder().parse(ByteArrayInputStream(bytes))
        return doc.byTag("si").map { it.deepText() }
    }

    private class SheetRef(val name: String, val path: String)

    /** workbook.xml + rels → sheet 名与 worksheet 路径的真实映射 */
    private fun resolveSheets(entries: Map<String, ByteArray>): List<SheetRef> {
        val wb = entries["xl/workbook.xml"] ?: return emptyList()
        val rels = entries["xl/_rels/workbook.xml.rels"]?.let { parseRels(it) } ?: emptyMap()
        val doc = newBuilder().parse(ByteArrayInputStream(wb))
        return doc.byTag("sheet").mapNotNull { el ->
            val name = el.attr("name") ?: return@mapNotNull null
            val rid = el.attr("id") ?: return@mapNotNull null
            val target = rels[rid] ?: return@mapNotNull null
            SheetRef(name, target)
        }
    }

    /** rels：rId → 归一后的 zip 条目路径 */
    private fun parseRels(bytes: ByteArray): Map<String, String> {
        val doc = newBuilder().parse(ByteArrayInputStream(bytes))
        return doc.byTag("Relationship").mapNotNull { el ->
            val id = el.attr("Id") ?: return@mapNotNull null
            val target = el.attr("Target") ?: return@mapNotNull null
            val path = when {
                target.startsWith("/") -> target.removePrefix("/")
                target.startsWith("xl/") -> target
                else -> "xl/$target"
            }
            id to path
        }.toMap()
    }

    // ── 工作表 → 二维网格 ───────────────────────────────────

    private class Grid(val rows: List<List<String>>) {
        val height: Int get() = rows.size
        val width: Int get() = rows.maxOfOrNull { it.size } ?: 0
        fun at(r: Int, c: Int): String = rows.getOrNull(r)?.getOrNull(c)?.trim() ?: ""

        /** 非空单元格数量（判断 sheet 有没有内容） */
        fun nonEmptyCount(): Int = rows.sumOf { row -> row.count { it.isNotBlank() } }
    }

    /**
     * worksheet xml → 网格。按 cell 的 r 属性（A1）定位行列——
     * 不能假设连续，空列空行都真实存在。t="s" 查共享串，
     * t="str"/inlineStr 取字面值，其余（数字）原样保留。
     */
    private fun parseSheet(bytes: ByteArray, shared: List<String>): Grid {
        val doc = newBuilder().parse(ByteArrayInputStream(bytes))
        val rows = mutableListOf<MutableList<String>>()
        fun ensureRow(r: Int): MutableList<String> {
            while (rows.size <= r) rows.add(mutableListOf())
            return rows[r]
        }
        fun ensureCell(row: MutableList<String>, c: Int) {
            while (row.size <= c) row.add("")
        }

        for (rowEl in doc.byTag("row")) {
            val r = (rowEl.attr("r")?.toIntOrNull() ?: (rows.size + 1)) - 1
            val row = ensureRow(r)
            for (cellEl in rowEl.childrenByTag("c")) {
                val ref = cellEl.attr("r") ?: continue
                val col = columnOf(ref) ?: continue
                ensureCell(row, col)
                val t = cellEl.attr("t")
                val v = cellEl.childrenByTag("v").firstOrNull()?.deepText()
                val inline = if (t == "inlineStr") cellEl.childrenByTag("is").firstOrNull()?.deepText() else null
                row[col] = sanitizeCell(
                    when (t) {
                        "s" -> v?.toIntOrNull()?.takeIf { it in shared.indices }?.let { shared[it] } ?: ""
                        "inlineStr" -> inline ?: ""
                        "str" -> v ?: ""
                        "b" -> if (v == "1") "TRUE" else "FALSE"
                        else -> v ?: ""
                    },
                )
            }
        }

        // 合并单元格：左上角的值广播到区域内每个格子（跨列标题行依赖这个）
        for (mergeEl in doc.byTag("mergeCells")) {
            for (m in mergeEl.childrenByTag("mergeCell")) {
                val ref = m.attr("ref") ?: continue
                val range = parseRange(ref) ?: continue
                val value = rows.getOrNull(range.first)?.getOrNull(range.second) ?: ""
                for (r in range.first..range.third) {
                    if (r >= rows.size) break
                    for (c in range.second..range.fourth) {
                        ensureCell(rows[r], c)
                        rows[r][c] = value
                    }
                }
            }
        }
        return Grid(rows)
    }

    /** 直接子元素里按标签名（忽略命名空间前缀）收集 */
    private fun Element.childrenByTag(name: String): List<Element> {
        val out = mutableListOf<Element>()
        for (i in 0 until childNodes.length) {
            val n = childNodes.item(i)
            if (n is Element && n.tagName.substringAfter(':') == name) out.add(n)
        }
        return out
    }

    /** "BC12" → 列索引（0-based，A=0）；失败返回 null */
    private fun columnOf(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return null
        var col = 0
        for (ch in letters) col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
        return col - 1
    }

    /**
     * 单元格清洗：Excel 把单元格内的 CR 存成字面量 `_x000D_`（DOM 不会还原它），
     * 教务系统导出的多行格子因此带着 `_x000D_` 残留——不清掉会让课程名带后缀、
     * 周次行匹配不上。CR 直接删掉，保留 \n 作行分隔。
     */
    private fun sanitizeCell(raw: String): String =
        raw.replace("_x000D_", "").replace("\r", "")

    /** "B2:D5" → (startRow, startCol, endRow, endCol)，全部 0-based */
    private fun parseRange(ref: String): Quadruple? {
        val parts = ref.split(':')
        if (parts.size != 2) return null
        val a = parts[0].trim()
        val b = parts[1].trim()
        val aCol = columnOf(a) ?: return null
        val bCol = columnOf(b) ?: return null
        val aRow = a.dropWhile { it.isLetter() }.toIntOrNull() ?: return null
        val bRow = b.dropWhile { it.isLetter() }.toIntOrNull() ?: return null
        return Quadruple(aRow - 1, aCol, bRow - 1, bCol)
    }

    private class Quadruple(val first: Int, val second: Int, val third: Int, val fourth: Int)

    // ── 矩阵布局识别 ────────────────────────────────────────

    private class MatrixLayout(
        val headerRow: Int,
        /** 列 → 星期（1–7）；同一天多列只保留第一列，避免一门课插两次 */
        val dayOfColumn: Map<Int, Int>,
        /** 行 → (起始节次, 占用节数) */
        val periodOfRow: Map<Int, Pair<Int, Int>>,
        val periodColumn: Int,
    )

    private val WEEKDAY_WORDS: Map<String, Int> = buildMap {
        val cn = listOf("一", "二", "三", "四", "五", "六", "日")
        for (i in cn.indices) {
            put("星期${cn[i]}", i + 1)
            put("周${cn[i]}", i + 1)
            put("礼拜${cn[i]}", i + 1)
        }
        val en = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        for (i in en.indices) {
            put(en[i], i + 1)
            put(en[i].take(3), i + 1)
        }
    }

    /** 单元格文本 → 星期（1–7）；认不出返回 null。"周一 3.9" 这种取第一段再匹配 */
    internal fun weekdayOf(cell: String): Int? {
        val v = cell.trim()
        if (v.isEmpty()) return null
        WEEKDAY_WORDS[v.lowercase()]?.let { return it }
        val first = v.split(Regex("[\\s,，、/|(（]+")).firstOrNull()?.trim() ?: return null
        return WEEKDAY_WORDS[first.lowercase()]
    }

    // 节次词：整体匹配（纯 "1-2" / "第三节" / "3"）与**前缀匹配**
    // （合体写法 "1-2节08:00:00-09:40:00"——真实教务系统导出就这样，见 10.2.5）。
    // "节"字一律可选：纯 "1-2" 与合体 "1-2节…" 都要认。
    private val PERIOD_RANGE_PREFIX = Regex("^第?\\s*(\\d{1,2})\\s*[${WeekRanges.RANGE_SEPARATOR_CLASS}]\\s*(\\d{1,2})\\s*节?")
    private val PERIOD_LIST_PREFIX = Regex("^第?\\s*(\\d{1,2})\\s*[、,，]\\s*(\\d{1,2})\\s*节?")
    private val PERIOD_BIG_PREFIX = Regex("^第?\\s*([一二三四五六七八九十]{1,2})\\s*大节")
    private val PERIOD_CN_PREFIX = Regex("^第?\\s*([一二三四五六七八九十]{1,3})\\s*节?")
    private val PERIOD_NUM_PREFIX = Regex("^第?\\s*(\\d{1,2})\\s*节")
    private val PERIOD_NUM = Regex("^(\\d{1,2})$")
    private val PERIOD_TIME = Regex("^(\\d{1,2}):(\\d{2})\\s*[${WeekRanges.RANGE_SEPARATOR_CLASS}]\\s*(\\d{1,2}):(\\d{2})$")

    private val CN_NUM = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
        "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
    )

    private fun cnNum(s: String): Int? = when (s.length) {
        1 -> CN_NUM[s]
        2 -> when {
            s.startsWith("十") -> 10 + (CN_NUM[s[1].toString()] ?: return null)
            s.endsWith("十") -> (CN_NUM[s[0].toString()] ?: return null) * 10
            else -> null
        }
        3 -> if (s[1] == '十') {
            (CN_NUM[s[0].toString()] ?: return null) * 10 + (CN_NUM[s[2].toString()] ?: return null)
        } else null
        else -> null
    }

    /**
     * 行头 token → (起始节次, 占用节数)。支持：
     *   - 合体 `1-2节08:00:00-09:40:00`（真实教务导出：节次与时间写在同一格，
     *     前缀优先——节次序号比时间推导更可靠）
     *   - 纯节次 `1-2`、`1、2`、`第三节`、`3`、`第一大节`（按默认配对 1-2/3-4）
     *   - 纯时间段 `08:00-09:40`（用当前学期节次按墙上时间匹配，同 ICS 规则）
     */
    private fun periodToken(token: String, periods: List<Period>): Pair<Int, Int>? {
        val t = token.trim()
        if (t.isEmpty()) return null

        // 纯时间段（整体）：用当前学期节次匹配
        PERIOD_TIME.matchEntire(t)?.let { m ->
            val startMin = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            val endMin = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
            val sorted = periods.sortedBy { it.index }
            val idx = sorted.indexOfFirst { GridClock.minutesOf(it.startTime) == startMin }
            if (idx >= 0) {
                var i = idx
                while (i < sorted.size) {
                    if (GridClock.minutesOf(sorted[i].endTime) >= endMin) {
                        return (idx + 1) to (i - idx + 1)
                    }
                    i++
                }
                return (idx + 1) to (sorted.size - idx).coerceAtLeast(1)
            }
        }

        // 合体/前缀匹配：抓到 "N-M节" 前缀就够，后面跟的时间忽略
        PERIOD_RANGE_PREFIX.find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return minOf(a, b) to (maxOf(a, b) - minOf(a, b) + 1)
        }
        PERIOD_LIST_PREFIX.find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return minOf(a, b) to (maxOf(a, b) - minOf(a, b) + 1)
        }
        PERIOD_BIG_PREFIX.find(t)?.let { m -> cnNum(m.groupValues[1])?.let { return (2 * it - 1) to 2 } }
        PERIOD_CN_PREFIX.find(t)?.let { m -> cnNum(m.groupValues[1])?.let { return it to 1 } }
        PERIOD_NUM_PREFIX.find(t)?.let { m -> return m.groupValues[1].toInt() to 1 }
        PERIOD_NUM.matchEntire(t)?.let { m -> return m.groupValues[1].toInt() to 1 }
        return null
    }

    /**
     * 矩阵布局识别：表头行（≥3 个不同星期）+ 节次列（≥1 个节次词，
     * 且至少一个节次行在某个星期列上有非空格子——交叉验证防列表布局误判）。
     * 认不出返回 null（调用方改走列表布局）。
     */
    private fun detectMatrix(grid: Grid, periods: List<Period>): MatrixLayout? {
        var headerRow = -1
        var dayOfColumn = emptyMap<Int, Int>()
        for (r in 0 until grid.height) {
            val days = LinkedHashMap<Int, Int>()
            for (c in 0 until grid.width) {
                val cell = grid.at(r, c)
                if (cell.isNotEmpty()) {
                    weekdayOf(cell)?.let { day ->
                        if (day !in days.values) days[c] = day
                    }
                }
            }
            if (days.size >= 3) {
                headerRow = r
                dayOfColumn = days
                break
            }
        }
        if (headerRow < 0) return null

        for (c in 0 until grid.width) {
            if (c in dayOfColumn.keys) continue
            val found = LinkedHashMap<Int, Pair<Int, Int>>()
            for (r in 0 until grid.height) {
                if (r == headerRow) continue
                val cell = grid.at(r, c)
                if (cell.isNotEmpty()) periodToken(cell, periods)?.let { found[r] = it }
            }
            if (found.isEmpty()) continue
            // 交叉验证：节次行 × 星期列至少要有一个非空格子，否则是列表布局的误判
            val hasCross = found.keys.any { r -> dayOfColumn.keys.any { col -> grid.at(r, col).isNotEmpty() } }
            if (hasCross) {
                return MatrixLayout(headerRow, dayOfColumn, found, c)
            }
        }
        return null
    }

    // ── 矩阵 → 课程 ─────────────────────────────────────────

    private fun parseMatrix(
        grid: Grid,
        m: MatrixLayout,
        term: Term,
        periods: List<Period>,
        sheetName: String,
    ): ParseReport {
        val courses = mutableListOf<ParsedCourse>()
        val issues = mutableListOf<ParseIssue>()
        val maxPeriod = periods.maxOfOrNull { it.index } ?: 1
        for ((row, span) in m.periodOfRow) {
            val (start, count) = span
            // 行头节次超出当前节次表 → 该行的课会静默不可见，逐行报错（口径同 CSV/粘贴）
            if (start < 1 || start + count - 1 > maxPeriod) {
                if (m.dayOfColumn.keys.any { grid.at(row, it).isNotEmpty() }) {
                    issues += ParseIssue(
                        "工作表「$sheetName」第 ${row + 1} 行",
                        "节次 $start-${start + count - 1} 超出当前学期的 1–$maxPeriod 节",
                    )
                }
                continue
            }
            for ((col, day) in m.dayOfColumn) {
                val cell = grid.at(row, col)
                if (cell.isEmpty()) continue
                val at = "工作表「$sheetName」第 ${row + 1} 行"
                val parsed = parseCellBlocks(cell, day, start, count, term)
                if (parsed.isEmpty()) {
                    issues += ParseIssue(at, "格子「${cell.take(20)}」解析不出课程")
                } else {
                    courses += parsed
                }
            }
        }
        return ParseReport("XLSX（$sheetName）", courses, issues)
    }

    /**
     * 矩阵格子 → 0..n 门课。按空行拆块（一个格子可以有多门课），
     * 块内第一行 = 课程名，其余行按特征归类（10.2.5）。
     */
    private fun parseCellBlocks(
        text: String,
        day: Int,
        start: Int,
        count: Int,
        term: Term,
    ): List<ParsedCourse> {
        val blocks = text.split(Regex("\\n\\s*\\n"))
        return blocks.mapNotNull { block -> parseBlock(block, day, start, count, term) }
    }

    private fun parseBlock(
        block: String,
        day: Int,
        start: Int,
        count: Int,
        term: Term,
    ): ParsedCourse? {
        val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        // 单行且含空格：按 token 分类（"大学英语 张三 A101 1-8周"）
        val units: List<String> =
            if (lines.size == 1 && lines[0].contains(Regex("\\s"))) {
                lines[0].split(Regex("\\s+")).filter { it.isNotEmpty() }
            } else {
                lines
            }
        val name = units.first()
        if (name.isEmpty()) return null

        var teacher: String? = null
        var room: String? = null
        var weeks: String? = null
        var parity: WeekParity = WeekParity.EVERY
        val leftovers = mutableListOf<String>()

        for (unit in units.drop(1)) {
            when (val kind = classifyUnit(unit)) {
                is UnitKind.Week -> if (weeks == null) weeks = kind.expr else leftovers += unit
                is UnitKind.Parity -> parity = kind.parity
                is UnitKind.Room -> if (room == null) room = unit else leftovers += unit
                is UnitKind.Teacher -> if (teacher == null) teacher = unit else leftovers += unit
                is UnitKind.Other -> leftovers += unit
            }
        }

        // 周次按学期总周数裁剪；裁剪后为空（全超范围）才兜底整学期
        val clampedWeeks = weeks?.let { WeekRanges.clamp(it, term.totalWeeks) }?.takeIf { it.isNotEmpty() }
        return ParsedCourse(
            name = name,
            teacher = teacher,
            room = room,
            dayOfWeek = day,
            startPeriodIndex = start,
            periodCount = count,
            weekRanges = clampedWeeks ?: "1-${term.totalWeeks}",
            weekParity = parity,
            note = leftovers.joinToString("；").ifEmpty { null },
        )
    }

    private sealed class UnitKind {
        class Week(val expr: String) : UnitKind()
        class Parity(val parity: WeekParity) : UnitKind()
        class Room(val text: String) : UnitKind()
        class Teacher(val text: String) : UnitKind()
        class Other(val text: String) : UnitKind()
    }

    private val WEEK_TOKEN = Regex("^第?\\s*\\d{1,2}(\\s*[${WeekRanges.RANGE_SEPARATOR_CLASS},，、]\\s*\\d{1,2})+\\s*周?$")

    private fun classifyUnit(unit: String): UnitKind {
        val u = unit.trim()
        if (u.isEmpty()) return UnitKind.Other(u)
        // 周次：先剥掉常见的"第/周"包装再判
        val bare = u.removePrefix("第").removeSuffix("周").trim()
        if (WEEK_TOKEN.matchEntire(u) != null || WEEK_TOKEN.matchEntire(bare) != null) {
            if (!WeekRanges.isBlankOrInvalid(bare)) return UnitKind.Week(bare)
        }
        val parity = when {
            u == "单周" || u == "单" || u == "(单)" || u == "（单）" -> WeekParity.ODD
            u == "双周" || u == "双" || u == "(双)" || u == "（双）" -> WeekParity.EVEN
            else -> null
        }
        if (parity != null) return UnitKind.Parity(parity)
        if (FieldHeuristics.looksLikeRoom(u)) return UnitKind.Room(u)
        if (FieldHeuristics.looksLikeTeacher(u)) return UnitKind.Teacher(u)
        return UnitKind.Other(u)
    }

    // ── 列表布局（复用 CSV 行解析） ──────────────────────────

    /**
     * 列表布局：网格行转记录流喂 [CsvCodec.parseRecords]。
     * 预处理：dayOfWeek 列（第 4 列）若是"周一/一/Monday"文本先转数字——
     * 教务系统的列表导出常用文本星期，CsvCodec 只认 1–7。
     */
    private fun parseListLayout(grid: Grid, sheetName: String, term: Term, periods: List<Period>): ParseReport {
        val records = grid.rows
            .filter { row -> row.any { it.isNotBlank() } }
            .map { row ->
                row.toMutableList().also { r ->
                    if (r.size > 3) {
                        weekdayOf(r[3])?.let { r[3] = it.toString() }
                    }
                }.toList()
            }
        // 与手工录入同口径：周次裁剪到学期总周数、节次收敛到节次表
        val report = CsvCodec.parseRecords(records, term.totalWeeks, periods.maxOfOrNull { it.index } ?: 1)
        return report.copy(
            format = "XLSX（$sheetName）",
            issues = report.issues.map { ParseIssue("工作表「$sheetName」${it.location}", it.reason) },
        )
    }
}
