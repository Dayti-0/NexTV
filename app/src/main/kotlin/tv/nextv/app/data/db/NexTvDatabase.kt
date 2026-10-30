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
    ],
    version = 6,
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

        fun create(context: Context): NexTvDatabase =
            Room.databaseBuilder(context.applicationContext, NexTvDatabase::class.java, "nextv.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // Versions de test antérieures (1) : les données sont rechargées depuis la playlist.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
