package com.tabbit.myvidhub.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.SourceEntity

@Database(
    entities = [SourceEntity::class, MediaEntity::class],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun mediaDao(): MediaDao

    companion object {
        /**
         * v4 → v5：新增 scrapedAt 列（TMDB 刮削时间戳）。
         * 用 ADD COLUMN 增量迁移，而不是全局的破坏式重建，
         * 这样老用户升级后媒体库、播放进度、收藏都不会丢。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN scrapedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN videoResolution TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media ADD COLUMN hdrType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media ADD COLUMN videoCodec TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v6 → v7：新增 playSource 列（TVBox 播放源 / 云盘名称）。
         * 同一部剧多条云盘的分集共用 seriesKey，靠 playSource 区分来源以支持线路切换。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN playSource TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "myvidhub.db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
