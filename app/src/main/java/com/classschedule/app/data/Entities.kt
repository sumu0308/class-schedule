package com.classschedule.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单双周类型。与「周次范围」是两个独立维度，判定时求交集：
 * 第 N 周要显示某门课，必须同时满足「N 在 weekRanges 内」且「N 的奇偶性符合 weekParity」。
 */
enum class WeekParity {
    /** 每周都上 */
    EVERY,

    /** 单周（第 1、3、5… 周） */
    ODD,

    /** 双周（第 2、4、6… 周） */
    EVEN,
}

/** 学期。startEpochDay 为第 1 周周一，用于推算"今天是第几周"。 */
@Entity(tableName = "term")
data class Term(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 第 1 周周一，以 1970-01-01 为 0 的天数。用 epochDay 而非 LocalDate，避免 API 26 以下不可用。 */
    val startEpochDay: Long,
    val totalWeeks: Int = 20,
    val isCurrent: Boolean = true,
)

/** 节次。每节记录序号与起止时间，供课表网格的行与时间列使用。 */
@Entity(
    tableName = "period",
    foreignKeys = [
        ForeignKey(
            entity = Term::class,
            parentColumns = ["id"],
            childColumns = ["termId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("termId")],
)
data class Period(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: Long,
    /** 第几节，从 1 开始；同时决定网格行号与排序 */
    val index: Int,
    val startTime: String,
    val endTime: String,
)

/**
 * 一条具体的上课安排。
 *
 * 设计取向：直接存"安排级"记录（星期 + 节次 + 周次规则 + 教师 + 教室），
 * 而不是"课程实体 + 多个时间段"两级模型。见计划文档第四章 4.1 的说明。
 */
@Entity(
    tableName = "course",
    foreignKeys = [
        ForeignKey(
            entity = Term::class,
            parentColumns = ["id"],
            childColumns = ["termId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["termId", "dayOfWeek"]), Index(value = ["termId", "name"])],
)
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: Long,
    val name: String,
    val teacher: String? = null,
    val room: String? = null,
    /** 1 = 周一 … 7 = 周日 */
    val dayOfWeek: Int,
    /** 起始节次，对应 Period.index */
    val startPeriodIndex: Int,
    /** 连续占用几节，至少 1 */
    val periodCount: Int = 1,
    val weekParity: WeekParity = WeekParity.EVERY,
    /** 规范化的周次表达式，如 "1-8,10-16" */
    val weekRanges: String,
    val colorArgb: Long? = null,
    val note: String? = null,
    /** 来源：手工录入 / 教务导入 / 文件导入，便于后续按来源清理 */
    val source: String = SOURCE_MANUAL,
) {
    companion object {
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_IMPORT = "IMPORT"
        const val SOURCE_FILE = "FILE"
    }
}

/**
 * 简单的键值表，用于记录"一次性迁移是否已执行"这类状态。
 * 单独建表而不是塞进 SharedPreferences，是为了让备份/恢复能一起带走。
 */
@Entity(tableName = "app_setting")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String,
)
