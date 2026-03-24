package com.ytmusic.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ytmusic.core.database.dao.*
import com.ytmusic.core.database.entity.*

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        DownloadJobEntity::class,
        PlaybackSessionEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun statsDao(): StatsDao

    companion object {
        /**
         * v1 → v2 : ajout du champ replayGainDb dans la table tracks.
         * DEFAULT 0.0 = pas de correction pour les morceaux existants.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN replayGainDb REAL NOT NULL DEFAULT 0.0"
                )
            }
        }
    }
}