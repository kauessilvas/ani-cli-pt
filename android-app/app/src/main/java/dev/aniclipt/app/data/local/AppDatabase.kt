package dev.aniclipt.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WatchHistoryEntity::class, CompletedEpisodeEntity::class, WatchedEpisodeEntity::class,
        FavoriteEntity::class, FavoriteMetadataEntity::class, IdentityLinkEntity::class,
        IdentityAliasEntity::class, CachedMetadataEntity::class, CachedEpisodeEntity::class,
        FavoriteEpisodeSnapshotEntity::class, FavoriteEpisodeSnapshotStateEntity::class,
        RecentSearchEntity::class, MigrationMarkerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ani-cli-pt.db")
                .build().also { instance = it }
        }
    }
}
