package com.classschedule.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
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
    @Query("SELECT * FROM term WHERE isCurrent = 1 LIMIT 1")
    fun observeCurrent(): Flow<Term?>

    @Query("SELECT * FROM term WHERE isCurrent = 1 LIMIT 1")
    suspend fun current(): Term?

    @Query("SELECT COUNT(*) FROM term")
    suspend fun count(): Int

    @Insert
    suspend fun insert(term: Term): Long

    @Update
    suspend fun update(term: Term)

    @Query("UPDATE term SET isCurrent = 0")
    suspend fun clearCurrentFlags()

    @Query("UPDATE term SET isCurrent = 1 WHERE id = :id")
    suspend fun markCurrent(id: Long)
}

@Dao
interface PeriodDao {
    @Query("SELECT * FROM period WHERE termId = :termId ORDER BY `index` ASC")
    fun observeByTerm(termId: Long): Flow<List<Period>>

    @Query("SELECT * FROM period WHERE termId = :termId ORDER BY `index` ASC")
    suspend fun listByTerm(termId: Long): List<Period>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: Course): Long

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
    exportSchema = false,
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
