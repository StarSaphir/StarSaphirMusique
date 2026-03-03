package com.ytmusic.core.database

import androidx.room.TypeConverter
import com.ytmusic.core.database.entity.DownloadStatus

class Converters {
    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus): String = status.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus =
        DownloadStatus.valueOf(value)
}
