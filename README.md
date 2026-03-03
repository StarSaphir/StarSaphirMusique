# YTMusicApp — Application musicale hors-ligne

Architecture Android native Kotlin, MVVM + Clean Architecture + Jetpack Compose.

## Modules

| Module | Responsabilité |
|--------|---------------|
| `:core:database` | Room DB, entités, DAOs |
| `:core:domain` | Modèles métier, interfaces repository, implémentations |
| `:core:common` | Extensions partagées |
| `:feature:browser` | WebView YouTube + détection vidéo + barre téléchargement |
| `:feature:downloader` | WorkManager, DownloadWorker, file de téléchargements |
| `:feature:library` | Archive musicale, métadonnées, trim logique |
| `:feature:player` | ExoPlayer, Foreground Service, MediaSession, stats |
| `:feature:playlists` | Playlists + super-playlist avec déduplication |
| `:feature:favorites` | Favoris morceaux & playlists |
| `:feature:stats` | Statistiques temps réel d'écoute |
| `:feature:settings` | Préférences utilisateur (DataStore) |

## ⚠️ Étape obligatoire : binaire yt-dlp

L'extraction audio/vidéo depuis YouTube repose sur **yt-dlp**.

### Télécharger le binaire ARM64

```bash
# Binaire ARM64 (appareils modernes)
wget https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64 \
     -O feature/downloader/src/main/assets/yt-dlp
chmod +x feature/downloader/src/main/assets/yt-dlp
```

Pour supporter les appareils 32-bit, ajouter aussi `yt-dlp_linux_armv7l` et gérer l'ABI dans `DownloadWorker.extractYtDlpBinary()`.

### Vérifier l'ABI de l'appareil cible

```kotlin
// Dans extractYtDlpBinary() — sélection automatique selon ABI
val abi = Build.SUPPORTED_ABIS.first()
val assetName = when {
    abi.startsWith("arm64") -> "yt-dlp"        // arm64-v8a
    abi.startsWith("arm")   -> "yt-dlp-armv7"  // armeabi-v7a
    else                    -> "yt-dlp"
}
```

## Mise à jour automatique de yt-dlp

YouTube modifie régulièrement ses endpoints. Prévoir un mécanisme de mise à jour du binaire (GitHub Releases API).

## Structure des fichiers stockés

```
/data/data/com.ytmusic.app/files/
└── tracks/
    ├── {uuid}.m4a       # audio uniquement
    ├── {uuid}.mp4       # audio + vidéo
    └── {uuid}.jpg       # miniature (téléchargée avec yt-dlp --write-thumbnail)
```

## Permissions requises

- `INTERNET` — téléchargement + WebView
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — lecture audio continue
- `POST_NOTIFICATIONS` — notifications de téléchargement (Android 13+)

## Dépendances clés

- **Media3 1.3+** — ExoPlayer, MediaSession, ClippingConfiguration
- **Room 2.6** — Base de données locale réactive (Flow)
- **WorkManager 2.9** — Téléchargements robustes en arrière-plan
- **Hilt 2.51** — Injection de dépendances
- **DataStore** — Persistance état lecture + paramètres
- **Coil** — Chargement miniatures
- **Jetpack Compose** — UI déclarative, thème sombre uniquement

## Architecture Flow complet

```
[BrowserScreen / WebView]
      │ URL change
      ▼
[VideoDetectionManager]  ──►  StateFlow<DetectedVideo?>
      │                              │
      ▼                              ▼
[BrowserViewModel]          [DownloadBar - UI]
      │ download()
      ▼
[DownloadRepository.enqueue()]
      │
      ▼
[WorkManager → DownloadWorker]
      │ yt-dlp binary
      │ stdout parsing → progress
      ▼
[TrackDao.insertTrack()]  ──►  Room DB  ──►  Flow<List<Track>>
                                                    │
                                                    ▼
                                          [LibraryScreen]
                                                    │ play
                                                    ▼
                                          [PlayerController]
                                                    │
                                                    ▼
                                          [PlayerService (ExoPlayer)]
                                                    │
                                          [StatsTracker] ──► [StatsDao]
```
