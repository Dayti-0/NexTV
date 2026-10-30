package tv.nextv.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class,
        ChannelEntity::class,
        ProgrammeEntity::class,
        CredentialEntity::class,
        FavoriteEntity::class,
        TmdbCacheEntity::class,
    ],
    version = 2,
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
        fun create(context: Context): NexTvDatabase =
            Room.databaseBuilder(context.applicationContext, NexTvDatabase::class.java, "nextv.db")
                // Versions de test : le schéma évolue encore, les données sont rechargées depuis la playlist.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
