package com.classschedule.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter
    fun parityToString(p: WeekParity): String = p.name

    @TypeConverter
    fun stringToParity(s: String): WeekParity =
        runCatching { WeekParity.valueOf(s) }.getOrDefault(WeekParity.EVERY)
}

@Dao
interface TermDao {
    /**
     * 当前学期。按开学日倒序取第一条：万一历史数据里残留了多条 isCurrent = 1
     * （老版本没有约束时可能发生），行为至少是确定的——取开学最晚的那个。
     */
    @Query("SELECT * FROM term WHERE isCurrent = 1 ORDER BY startEpochDay DESC, id DESC LIMIT 1")
    fun observeCurrent(): Flow<Term?>

    @Query("SELECT * FROM term WHERE isCurrent = 1 ORDER BY startEpochDay DESC, id DESC LIMIT 1")
    suspend fun current(): Term?

    @Query("SELECT COUNT(*) FROM term")
    suspend fun count(): Int

    /** 整库导出用：全部学期 */
    @Query("SELECT * FROM term")
    suspend fun all(): List<Term>

    @Insert
    suspend fun insert(term: Term): Long

    /** JSON 整库恢复用：批量插入（保留文件里的 id，保持外键引用不变） */
    @Insert
    suspend fun insertAll(terms: List<Term>)

    /** JSON 整库恢复用：清空 */
    @Query("DELETE FROM term")
    suspend fun clearAll()

    /**
     * 只更新学期档案字段（名称 / 开学日 / 总周数）。
     * 刻意不提供整行 @Update：isCurrent 的变更只能走 [insertAsCurrent]，
     * 避免任何调用方在改档案时误碰当前学期标志。
     */
    @Query("UPDATE term SET name = :name, startEpochDay = :startEpochDay, totalWeeks = :totalWeeks WHERE id = :id")
    suspend fun updateProfile(id: Long, name: String, startEpochDay: Long, totalWeeks: Int)

    @Query("UPDATE term SET isCurrent = 0")
    suspend fun clearCurrentFlags()

    @Query("UPDATE term SET isCurrent = 1 WHERE id = :id")
    suspend fun markCurrent(id: Long)

    /**
     * 新建学期并立即设为当前学期。
     * 「清掉旧标志 → 插入新行」在同一事务里完成，保证任意时刻至多一条 isCurrent = 1；
     * 万一插入失败，事务回滚，不会留下"没有当前学期"的中间态。
     */
    @Transaction
    suspend fun insertAsCurrent(term: Term): Long {
        clearCurrentFlags()
        return insert(term)
    }
}

@Dao
interface PeriodDao {
    @Query("SELECT * FROM period WHERE termId = :termId ORDER BY `index` ASC")
    fun observeByTerm(termId: Long): Flow<List<Period>>

    @Query("SELECT * FROM period WHERE termId = :termId ORDER BY `index` ASC")
    suspend fun listByTerm(termId: Long): List<Period>

    /** 整库导出用：全部节次 */
    @Query("SELECT * FROM period")
    suspend fun all(): List<Period>

    /** JSON 整库恢复用：清空 */
    @Query("DELETE FROM period")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM period WHERE termId = :termId")
    suspend fun countByTerm(termId: Long): Int

    @Insert
    suspend fun insertAll(periods: List<Period>)

    @Update
    suspend fun update(period: Period)

    @Delete
    suspend fun delete(period: Period)
}

@Dao
interface CourseDao {
    @Query("SELECT * FROM course WHERE termId = :termId ORDER BY dayOfWeek ASC, startPeriodIndex ASC")
    fun observeByTerm(termId: Long): Flow<List<Course>>

    @Query("SELECT * FROM course WHERE termId = :termId")
    suspend fun listByTerm(termId: Long): List<Course>

    /**
     * 单条插入。用默认的 ABORT（与 [insertAll] 一致），刻意不用 REPLACE：
     * REPLACE 的语义是"删旧行再插"，在有外键子表时可能连带级联删除；这里的 id
     * 由自增分配，正常路径不会有主键冲突，真冲突就该显式失败而非默默删数据。
     */
    @Insert
    suspend fun insert(course: Course): Long

    /** 批量插入：文件导入一次写多行 */
    @Insert
    suspend fun insertAll(courses: List<Course>)

    /** 整库导出用：全部课程 */
    @Query("SELECT * FROM course")
    suspend fun all(): List<Course>

    /** JSON 整库恢复用：清空 */
    @Query("DELETE FROM course")
    suspend fun clearAll()

    /** 覆盖本学期导入用：只删当前学期的课程 */
    @Query("DELETE FROM course WHERE termId = :termId")
    suspend fun clearByTerm(termId: Long)

    @Update
    suspend fun update(course: Course)

    @Delete
    suspend fun delete(course: Course)

    @Query("DELETE FROM course WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM app_setting WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSetting)
}

@Database(
    entities = [Term::class, Period::class, Course::class, AppSetting::class],
    version = AppDatabase.VERSION,
    // 打开 schema 导出（见 build.gradle.kts 的 room.schemaLocation）：
    // 有了历史 schema 才能用 MigrationTestHelper 校验迁移，也方便 review 结构变更。
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun termDao(): TermDao
    abstract fun periodDao(): PeriodDao
    abstract fun courseDao(): CourseDao
    abstract fun settingDao(): SettingDao

    companion object {
        const val NAME = "class_schedule.db"

        /** 结构版本。改动表结构时 +1，并在 MIGRATIONS 里补一条迁移。 */
        const val VERSION = 2

        /** 键值表的键名集中放这里，避免各处的字符串写错 */
        const val KEY_DEFAULT_PERIODS_MIGRATED = "default_periods_migrated_to_v2"

        /**
         * 迁移列表。刻意不使用 fallbackToDestructiveMigration：
         * 用户的课表是手工录进去的，升级时清库不可接受。
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `app_setting` " +
                            "(`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
                    )
                }
            },
        )
    }
}
