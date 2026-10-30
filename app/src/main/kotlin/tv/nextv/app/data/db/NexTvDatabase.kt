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
    ],
    version = 3,
    exportSchema = false,
)
abstract class NexTvDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun channelDao(): ChannelDao
    abstract fun programmeDao(): ProgrammeDao
    abstract fun credentialDao(): CredentialDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun tmdbDao(): TmdbDao

    companion object {
        /** Année des programmes du guide : nouvelle colonne, les données existantes sont conservées. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE programmes ADD COLUMN year INTEGER")
            }
        }

        fun create(context: Context): NexTvDatabase =
            Room.databaseBuilder(context.applicationContext, NexTvDatabase::class.java, "nextv.db")
                .addMigrations(MIGRATION_2_3)
                // Versions de test antérieures (1) : les données sont rechargées depuis la playlist.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
