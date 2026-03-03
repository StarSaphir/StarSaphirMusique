package com.ytmusic.feature.player

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Convertit un chemin absolu de thumbnail en content:// URI lisible par SystemUI et Android Auto.
 * Les URI file:// privés (/data/user/0/...) sont inaccessibles aux autres processus.
 */
object ThumbnailProvider {

    fun toContentUri(context: Context, absolutePath: String?): Uri? {
        if (absolutePath == null) return null
        val file = File(absolutePath)
        if (!file.exists()) return null  // Évite le FileNotFoundException dans SystemUI
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            null
        }
    }
}
