package com.classschedule.app.data.backup

/**
 * 「教师 / 教室」这类自由文本字段的启发式识别。
 *
 * 抽成公共对象的原因：XLSX 矩阵格子与粘贴文本导入都要判断"这个 token 更像教室
 * 还是更像教师"，两处必须用同一套口径，否则同一个词在不同入口会被归到不同字段。
 *
 * 规则宁可保守：判不出就交回调用方按位置或默认值处理，不硬猜。
 */
internal object FieldHeuristics {

    private val ROOM_KEYWORDS = listOf(
        "楼", "室", "馆", "场", "房", "阶梯", "实验", "语音", "多媒体",
        "机房", "礼堂", "操场", "篮球", "足球", "排球", "羽毛球", "校区", "远程",
    )

    /** 教室代码：`XC514` / `XD1` / `XA3`（教务系统常用字母+数字编号，位数不定） */
    private val ROOM_CODE = Regex("^[A-Za-z]{1,3}\\d{1,4}$")

    /** 像不像教室：含楼 / 室 / 馆等关键词，或是"字母+数字"的房间号 */
    fun looksLikeRoom(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return ROOM_KEYWORDS.any { t.contains(it) } || ROOM_CODE.matchEntire(t) != null
    }

    /**
     * 像不像教师：短文本，或逗号分隔的多个短名（"赵小峰,钱思琪"）。
     * 每段 ≤6 字才认——长句（备注）不误伤。
     */
    fun looksLikeTeacher(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 12) return false
        val parts = t.split(',', '，')
        return parts.all { it.isNotBlank() && it.length <= 6 }
    }
}
