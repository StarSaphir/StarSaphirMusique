package com.ytmusic.feature.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.ytmusic.core.domain.model.Track
import com.ytmusic.core.domain.repository.TrackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scanner de réindexation : parcourt files/tracks/, extrait les métadonnées
 * via MediaMetadataRetriever, et insère les pistes manquantes en base.
 *
 * Utilisation : appeler scan() depuis un ViewModel ou un bouton dans les Settings.
 * Les pistes déjà en base (même filePath) ne sont pas dupliquées.
 */
@Singleton
class LibraryScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackRepository: TrackRepository
) {

    companion object {
        private const val TAG = "LibraryScanner"
    }

    data class ScanResult(
        val scanned: Int,
        val inserted: Int,
        val skipped: Int,
        val errors: Int
    )

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val tracksDir = File(context.filesDir, "tracks")
        val thumbsDir = File(context.filesDir, "thumbs")

        if (!tracksDir.exists()) {
            Log.w(TAG, "Dossier tracks introuvable: ${tracksDir.absolutePath}")
            return@withContext ScanResult(0, 0, 0, 0)
        }

        // Récupérer les chemins déjà en base pour éviter les doublons
        val existingPaths = trackRepository.getAllTracks()
            .first()
            .map { it.filePath }
            .toHashSet()

        Log.d(TAG, "Tracks en base: ${existingPaths.size}")

        val audioExtensions = setOf("m4a", "webm", "mp3", "mp4", "ogg", "aac", "opus")
        val files = tracksDir.listFiles()
            ?.filter { it.extension.lowercase() in audioExtensions }
            ?: emptyList()

        Log.d(TAG, "Fichiers trouvés: ${files.size}")

        var inserted = 0
        var skipped  = 0
        var errors   = 0

        for (file in files) {
            if (file.absolutePath in existingPaths) {
                skipped++
                continue
            }

            try {
                val track = extractMetadata(file, thumbsDir)
                trackRepository.insertTrack(track)
                inserted++
                Log.d(TAG, "Indexé: ${track.title} (${track.artist})")
            } catch (e: Exception) {
                errors++
                Log.e(TAG, "Erreur sur ${file.name}: ${e.message}")
            }
        }

        Log.d(TAG, "Scan terminé: $inserted insérés, $skipped ignorés, $errors erreurs")
        ScanResult(files.size, inserted, skipped, errors)
    }

    private fun extractMetadata(file: File, thumbsDir: File): Track {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)

            val title  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension

            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.takeIf { it.isNotBlank() }
                ?: "Inconnu"

            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            // Chercher la miniature correspondante (même nom de base que le fichier audio,
            // ou par videoId si le nom du fichier est un UUID sans videoId connu)
            val trackId = UUID.randomUUID().toString()
            val thumbFile = findThumbnail(file, thumbsDir)

            return Track(
                id             = trackId,
                title          = title,
                artist         = artist,
                filePath       = file.absolutePath,
                thumbnailPath  = thumbFile?.absolutePath,
                durationMs     = durationMs,
                downloadedAt   = file.lastModified()
            )
        } finally {
            mmr.release()
        }
    }

    /**
     * Cherche la miniature associée à un fichier audio.
     * Stratégie : même nom de base (ex: abc123.m4a → abc123.jpg)
     */
    private fun findThumbnail(audioFile: File, thumbsDir: File): File? {
        if (!thumbsDir.exists()) return null
        val baseName = audioFile.nameWithoutExtension
        return listOf("$baseName.jpg", "$baseName.jpeg", "$baseName.png")
            .map { File(thumbsDir, it) }
            .firstOrNull { it.exists() }
    }
}