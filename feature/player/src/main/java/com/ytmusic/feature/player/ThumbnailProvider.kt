package com.ytmusic.feature.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Convertit un chemin absolu de thumbnail en content:// URI lisible par SystemUI et Android Auto.
 * Les URI file:// privés (/data/user/0/...) sont inaccessibles aux autres processus.
 *
 * android:grantUriPermissions="true" dans le Manifest autorise les permissions temporaires,
 * mais elles doivent être accordées EXPLICITEMENT par code à chaque processus externe.
 * Sans grantUriPermission(), gearhead reçoit un SecurityException même avec grantUriPermissions="true".
 */
object ThumbnailProvider {

    // Packages qui ont besoin d'accéder aux thumbnails
    private val MEDIA_CONSUMERS = listOf(
        "com.google.android.projection.gearhead", // Android Auto
        "com.google.android.carassistant",         // Automotive OS
        "com.android.systemui",                    // Notification SystemUI
    )

    fun toContentUri(context: Context, absolutePath: String?): Uri? {
        if (absolutePath == null) return null
        val file = File(absolutePath)
        if (!file.exists()) return null
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            // Accorder la permission de lecture à chaque consommateur connu.
            // FLAG_GRANT_READ_URI_PERMISSION est nécessaire car le FileProvider
            // n'est pas exported (android:exported="false" dans le Manifest).
            MEDIA_CONSUMERS.forEach { pkg ->
                try {
                    context.grantUriPermission(
                        pkg,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Le package peut ne pas être installé — on ignore silencieusement
                }
            }
            uri
        } catch (e: Exception) {
            null
        }
    }
}