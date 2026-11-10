package com.chen.doudoutv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.SourceEntity

@Database(
    entities = [SourceEntity::class, MediaEntity::class, HotTitleEntity::class, ScrapedMetaEntity::class],
    version = 13,
    // schema 落档到 app/schemas/：以后加索引之类的改动能用 Room 的自动迁移，
    // 也能拿历史 JSON 对比出漏写的迁移
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun mediaDao(): MediaDao
    abstract fun hotTitleDao(): HotTitleDao
    abstract fun scrapedMetaDao(): ScrapedMetaDao

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

        /**
         * v7 → v8：新增 pinyinInitials（拼音首字母）和 sourceName（来源名称）。
         * 拼音首字母用于运行时 LIKE 前缀匹配，支持拼音搜索。
         * sourceName 用于搜索结果按来源分组展示。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN pinyinInitials TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media ADD COLUMN sourceName TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v8 → v9：新增 hot_titles（豆瓣热门榜片名索引）。
         * 只给搜索联想补数据，不参与媒体库与播放流程。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hot_titles` (" +
                        "`title` TEXT NOT NULL, " +
                        "`pinyinInitials` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`rank` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`title`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hot_titles_pinyinInitials` " +
                        "ON `hot_titles` (`pinyinInitials`)"
                )
            }
        }

        /**
         * v9 → v10：新增 actors / directors / remarks 三列（TVBox 源的演职员与更新备注）。
         * CatVod 协议里这些都是逗号分隔的纯文本，入库后详情页不用再回源重拉。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN actors TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media ADD COLUMN directors TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media ADD COLUMN remarks TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v12 → v13：新增 libraryId / libraryName（Emby 媒体库归属）。
         * 动态分类改按媒体库分区（NSFW 等是 CollectionFolder，不是题材）。
         * ADD COLUMN 增量迁移，默认空串：老条目升级后库归属为空，
         * runSync 检测到「有 Emby 条目无库归属」会强制全量一次补齐并打标（A①回填）。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN libraryId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media ADD COLUMN libraryName TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v10 → v11：新增 audioType 列（音轨类型，杜比类音轨的展示位）。
         * 视频侧的 resolution/hdr/codec 三列 v6 就有，这次只补音轨，同样用 ADD COLUMN 增量迁移。
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN audioType TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v11 → v12：新增 scraped_meta（刮削结果表，按清洗后的片名归并）。
         *
         * 顺手把 media.scrapedAt 归零一次：那张标记的语义是「这一行已经刮过、7 天内别再联网」，
         * 而新表是空的 —— 不清的话，老条目会被这道拦截挡在新机制外面，
         * 既进不了表、也补不齐演职员，等于升级了个寂寞。
         * 归零只是重新联网一次，不删任何已经写进 media 的海报/简介。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scraped_meta` (" +
                        "`scrapeKey` TEXT NOT NULL, " +
                        "`titleKey` TEXT NOT NULL, " +
                        "`queryTitle` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`year` INTEGER NOT NULL, " +
                        "`overview` TEXT NOT NULL, " +
                        "`genres` TEXT NOT NULL, " +
                        "`rating` REAL NOT NULL, " +
                        "`posterUrl` TEXT NOT NULL, " +
                        "`backdropUrl` TEXT NOT NULL, " +
                        "`logoUrl` TEXT NOT NULL, " +
                        "`tagline` TEXT NOT NULL, " +
                        "`directorsJson` TEXT NOT NULL, " +
                        "`castJson` TEXT NOT NULL, " +
                        "`scrapedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`scrapeKey`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scraped_meta_titleKey` " +
                        "ON `scraped_meta` (`titleKey`)"
                )
                db.execSQL("UPDATE media SET scrapedAt = 0 WHERE scrapedAt > 0")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "doudoutv.db"
                )
                    .addMigrations(
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                        MIGRATION_11_12, MIGRATION_12_13
                    )
                    // 这里刻意不调 fallbackToDestructiveMigration()：
                    // 它会在「漏写某段迁移」时静默整库重建，观看进度和收藏全丢且没有任何迹象，
                    // 排查时只会看成「数据莫名其妙没了」。缺迁移现在会直接抛
                    // IllegalStateException（logcat 里写清 from/to 版本），宁可炸得看得见。
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
