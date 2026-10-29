package tv.nextv.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SourceEntity::class, ChannelEntity::class, ProgrammeEntity::class, CredentialEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NexTvDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun channelDao(): ChannelDao
    abstract fun programmeDao(): ProgrammeDao
    abstract fun credentialDao(): CredentialDao

    companion object {
        fun create(context: Context): NexTvDatabase =
            Room.databaseBuilder(context.applicationContext, NexTvDatabase::class.java, "nextv.db").build()
    }
}
