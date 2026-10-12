package com.classschedule.app.data.backup

import com.classschedule.app.data.Course
import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.WeekParity

/**
 * JSON 整库备份文件的解析（F-34，格式约定见计划文档 10.2.1）。
 *
 * 结构：`{"format":"class-schedule-backup","version":1,"terms":[...],"periods":[...],"courses":[...]}`，
 * 字段与 Room 实体一一对应。解析时逐字段校验，任何结构问题都给出可读原因，
 * 绝不把残缺数据写进库。
 *
 * 导出方向（serialize）曾在此文件中，0.10.0 随导出功能移除一并删除；
 * 版本号仍服务于导入方向的兼容判断（比本 App 新的备份拒绝导入）。
 */
object BackupJson {

    const val FORMAT = "class-schedule-backup"
    const val VERSION = 1L

    /** 只解析不写入：用于导入前的条数预览。结构有问题时返回 error 文本。 */
    fun inspect(text: String): JsonPreview = try {
        val data = decode(text)
        JsonPreview(data.terms.size, data.periods.size, data.courses.size)
    } catch (e: JsonException) {
        JsonPreview(0, 0, 0, "备份文件结构有问题：${e.message}")
    } catch (e: IllegalArgumentException) {
        JsonPreview(0, 0, 0, "备份文件内容不合法：${e.message}")
    }

    /** 解析备份文件；结构问题抛 JsonException / IllegalArgumentException */
    fun decode(text: String): BackupData {
        val root = MiniJson.parse(text) as? Map<*, *>
            ?: throw JsonException("根节点不是对象")
        val format = root["format"] as? String
            ?: throw JsonException("缺少 format 字段")
        if (format != FORMAT) throw JsonException("不是本 App 的备份文件（format = $format）")
        val version = (root["version"] as? Number)?.toLong()
            ?: throw JsonException("缺少 version 字段")
        if (version > VERSION) throw JsonException("备份文件版本（$version）比本 App 支持的版本（$VERSION）新，请先升级 App")

        val terms = (root["terms"] as? List<*>)?.mapIndexed { i, raw -> decodeTerm(raw, i) }
            ?: throw JsonException("缺少 terms 数组")
        val periods = (root["periods"] as? List<*>)?.mapIndexed { i, raw -> decodePeriod(raw, i) }
            ?: throw JsonException("缺少 periods 数组")
        val courses = (root["courses"] as? List<*>)?.mapIndexed { i, raw -> decodeCourse(raw, i) }
            ?: throw JsonException("缺少 courses 数组")

        // 外键完整性：period/course 引用的 termId 必须存在
        val termIds = terms.map { it.id }.toSet()
        periods.forEach { p ->
            if (p.termId !in termIds) throw IllegalArgumentException("节次（第 ${p.index} 节）引用了不存在的学期 id=${p.termId}")
        }
        courses.forEach { c ->
            if (c.termId !in termIds) throw IllegalArgumentException("课程「${c.name}」引用了不存在的学期 id=${c.termId}")
        }
        // 不变量：有学期时"有且仅有一个当前学期"。第三方工具可能省略 isCurrent，
        // 或历史数据残留多个；按 observeCurrent（开学日倒序取第一条）的口径统一成
        // "开学最晚的那个为当前"。否则该字段缺失的备份会一路通过确认、恢复后却查不到
        // 当前学期 → 课表永久空白，而 App 内没有任何入口能找回来。
        val normalizedTerms = normalizeCurrent(terms)
        return BackupData(normalizedTerms, periods, courses)
    }

    /**
     * 把 isCurrent 归一成"恰好一个"（学期非空时）：
     * 取 (startEpochDay, id, 下标) 最大的那条为当前，其余全部置 false。
     * 用下标兜底是为防 id 缺失时多条同分，保证结果确定且真的只有一个 true。
     */
    private fun normalizeCurrent(terms: List<Term>): List<Term> {
        if (terms.isEmpty()) return terms
        val currentIdx = terms.indices.maxWithOrNull(
            compareBy({ terms[it].startEpochDay }, { terms[it].id }, { it }),
        ) ?: return terms
        return terms.mapIndexed { i, t -> t.copy(isCurrent = i == currentIdx) }
    }

    private fun decodeTerm(raw: Any?, i: Int): Term {
        val m = raw as? Map<*, *> ?: throw JsonException("terms[$i] 不是对象")
        val name = m["name"] as? String ?: throw JsonException("terms[$i] 缺少 name")
        if (name.isBlank()) throw JsonException("terms[$i] 的 name 为空")
        val start = (m["startEpochDay"] as? Number)?.toLong() ?: throw JsonException("terms[$i] 缺少 startEpochDay")
        val weeks = (m["totalWeeks"] as? Number)?.toInt() ?: throw JsonException("terms[$i] 缺少 totalWeeks")
        return Term(
            id = (m["id"] as? Number)?.toLong() ?: 0,
            name = name,
            startEpochDay = start,
            totalWeeks = weeks.coerceIn(1, 60),
            isCurrent = m["isCurrent"] as? Boolean ?: false,
        )
    }

    private fun decodePeriod(raw: Any?, i: Int): Period {
        val m = raw as? Map<*, *> ?: throw JsonException("periods[$i] 不是对象")
        return Period(
            id = (m["id"] as? Number)?.toLong() ?: 0,
            termId = (m["termId"] as? Number)?.toLong() ?: throw JsonException("periods[$i] 缺少 termId"),
            index = ((m["index"] as? Number)?.toInt() ?: throw JsonException("periods[$i] 缺少 index")).coerceAtLeast(1),
            startTime = m["startTime"] as? String ?: throw JsonException("periods[$i] 缺少 startTime"),
            endTime = m["endTime"] as? String ?: throw JsonException("periods[$i] 缺少 endTime"),
        )
    }

    private fun decodeCourse(raw: Any?, i: Int): Course {
        val m = raw as? Map<*, *> ?: throw JsonException("courses[$i] 不是对象")
        val name = m["name"] as? String ?: throw JsonException("courses[$i] 缺少 name")
        if (name.isBlank()) throw JsonException("courses[$i] 的 name 为空")
        val day = (m["dayOfWeek"] as? Number)?.toInt() ?: throw JsonException("courses[$i] 缺少 dayOfWeek")
        if (day !in 1..7) throw JsonException("courses[$i] 的 dayOfWeek=$day 超出 1–7")
        return Course(
            id = (m["id"] as? Number)?.toLong() ?: 0,
            termId = (m["termId"] as? Number)?.toLong() ?: throw JsonException("courses[$i] 缺少 termId"),
            name = name,
            teacher = m["teacher"] as? String,
            room = m["room"] as? String,
            dayOfWeek = day,
            startPeriodIndex = ((m["startPeriodIndex"] as? Number)?.toInt()
                ?: throw JsonException("courses[$i] 缺少 startPeriodIndex")).coerceAtLeast(1),
            periodCount = ((m["periodCount"] as? Number)?.toInt()
                ?: throw JsonException("courses[$i] 缺少 periodCount")).coerceAtLeast(1),
            weekParity = parityOf(m["weekParity"]),
            weekRanges = m["weekRanges"] as? String ?: throw JsonException("courses[$i] 缺少 weekRanges"),
            colorArgb = (m["colorArgb"] as? Number)?.toLong(),
            note = m["note"] as? String,
            source = m["source"] as? String ?: Course.SOURCE_FILE,
        )
    }

    private fun parityOf(raw: Any?): WeekParity = when (raw) {
        null -> WeekParity.EVERY
        is String -> runCatching { WeekParity.valueOf(raw) }.getOrDefault(WeekParity.EVERY)
        else -> WeekParity.EVERY
    }
}
