package tv.nextv.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class,
        ChannelEntity::class,
        ProgrammeEntity::class,
        CredentialEntity::class,
        FavoriteEntity::class,
        TmdbCacheEntity::class,
        HiddenGroupEntity::class,
        GuideOnlyChannelEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class NexTvDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun channelDao(): ChannelDao
    abstract fun programmeDao(): ProgrammeDao
    abstract fun credentialDao(): CredentialDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun tmdbDao(): TmdbDao
    abstract fun hiddenGroupDao(): HiddenGroupDao
    abstract fun guideOnlyChannelDao(): GuideOnlyChannelDao

    companion object {
        /** Année des programmes du guide : nouvelle colonne, les données existantes sont conservées. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE programmes ADD COLUMN year INTEGER")
            }
        }

        /** Catégories masquées : nouvelle table, les données existantes sont conservées. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hidden_groups` (`sourceId` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `kind`, `title`), " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        /** Serveurs de secours des playlists : nouvelle colonne, les données existantes sont conservées. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN mirrors TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Replay des chaînes : nouvelle colonne, les données existantes sont conservées. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN catchupDays INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Listes plus rapides : index par clé (épisodes d'une série, favoris), et une ligne par série
         * (son premier épisode, qui porte le nombre d'épisodes) au lieu d'un regroupement à chaque page.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_channels_sourceId_kind_seriesName`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_channels_sourceId_kind_itemKey_position` " +
                        "ON `channels` (`sourceId`, `kind`, `itemKey`, `position`)",
                )
                db.execSQL(SERIES_PRIMARY_SQL)
            }
        }

        /** Chaînes du guide absentes de la playlist (section Foot) : nouvelle table, rien à convertir. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `guide_only_channels` (`sourceId` INTEGER NOT NULL, `epgChannelId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `epgChannelId`), " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        /**
         * Premier épisode de chaque série (ordre de la playlist) et nombre d'épisodes, comme à l'enregistrement.
         * `position + 0` : le minimum est lu dans l'index par clé, pas en parcourant l'index des positions
         * (sans statistiques, SQLite pourrait le choisir, au prix d'un parcours par épisode).
         */
        internal const val SERIES_PRIMARY_SQL =
            "UPDATE channels SET " +
                "isPrimary = (position = (SELECT MIN(e.position + 0) FROM channels e " +
                "WHERE e.sourceId = channels.sourceId AND e.kind = 'SERIES' AND e.itemKey = channels.itemKey)), " +
                "variantCount = (SELECT COUNT(*) FROM channels e " +
                "WHERE e.sourceId = channels.sourceId AND e.kind = 'SERIES' AND e.itemKey = channels.itemKey) " +
                "WHERE kind = 'SERIES'"

        fun create(context: Context): NexTvDatabase =
            Room.databaseBuilder(context.applicationContext, NexTvDatabase::class.java, "nextv.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                // Versions de test antérieures (1) : les données sont rechargées depuis la playlist.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
