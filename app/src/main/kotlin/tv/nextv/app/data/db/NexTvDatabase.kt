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
        MeasuredQualityEntity::class,
    ],
    version = NexTvDatabase.VERSION,
    // Schéma de chaque version gardé dans app/schemas : il sert à écrire et à tester la migration suivante.
    exportSchema = true,
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

        /** Images des programmes du guide : nouvelle colonne, remplie au prochain import du guide. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE programmes ADD COLUMN imageUrl TEXT")
            }
        }

        /** Langue de la bande-annonce des fiches TMDB : nouvelle colonne, les fiches avec bande-annonce sont relues. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tmdb_cache ADD COLUMN trailerLanguage TEXT")
            }
        }

        /** Qualité des films et épisodes sans mention dans leur nom (lecture, fiche du serveur) : nouvelle table. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MEASURED_QUALITY_SQL)
            }
        }

        /**
         * « Rassembler les chaînes » : son annoncé par le nom, ordre rangé et rang affiché de chaque chaîne. Rien n'est
         * rangé avant la relecture de la playlist (QualityParser.VERSION) : l'ordre reste celui de la playlist.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN audio INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE channels ADD COLUMN lineup INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE channels ADD COLUMN listOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE channels SET lineup = position, listOrder = position")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_listOrder` ON `channels` (`sourceId`, `listOrder`)")
            }
        }

        internal const val MEASURED_QUALITY_SQL ="CREATE TABLE IF NOT EXISTS `measured_quality` (`sourceId` INTEGER NOT NULL, " +
            "`streamUrl` TEXT NOT NULL, `quality` TEXT NOT NULL, `fromPlayback` INTEGER NOT NULL, `measuredAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`sourceId`, `streamUrl`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

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

        /**
         * Version du schéma. L'augmenter demande d'ajouter sa migration à [MIGRATIONS] : sans elle, l'application
         * refuse de démarrer (et le test des migrations échoue) au lieu d'effacer en silence playlists, favoris et
         * identifiants.
         */
        const val VERSION = 12

        /** Première version dont les données sont conservées (la 1, version de test, est rechargée depuis la playlist). */
        const val FIRST_MIGRATED_VERSION = 2

        /** Une migration par version, sans trou de [FIRST_MIGRATED_VERSION] à [VERSION]. */
        val MIGRATIONS: List<Migration> =
            listOf(
                MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                MIGRATION_10_11, MIGRATION_11_12,
            )

        fun create(context: Context): NexTvDatabase =
            Room.databaseBuilder(context.applicationContext, NexTvDatabase::class.java, "nextv.db")
                .addMigrations(*MIGRATIONS.toTypedArray())
                // Version de test antérieure (1) seulement : les données sont rechargées depuis la playlist.
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                // Version plus ancienne de l'application installée par-dessus une plus récente : elle ne sait
                // pas lire la base, qui est recréée.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
