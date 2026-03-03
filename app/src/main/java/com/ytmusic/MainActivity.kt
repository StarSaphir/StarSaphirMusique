package com.ytmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.ytmusic.feature.player.FullPlayerScreen
import com.ytmusic.feature.player.MiniPlayerBar
import com.ytmusic.feature.player.PlayerViewModel
import com.ytmusic.navigation.AppNavigation
import com.ytmusic.ui.theme.YTMusicTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YTMusicTheme {
                MainScaffold()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold() {
    val playerViewModel   = hiltViewModel<PlayerViewModel>()
    val playbackState     = playerViewModel.playbackState.collectAsState().value
    var showFullPlayer    by remember { mutableStateOf(false) }
    val hasTrack          = playbackState.currentTrack != null

    // Le full player s'ouvre uniquement sur action explicite (tap sur mini-player)

    Surface(modifier = Modifier.fillMaxSize()) {
        // navigationBarsPadding pousse le contenu AU-DESSUS de la barre système
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    AppNavigation(playerViewModel = playerViewModel)
                }
                // Mini-player seulement si une piste est chargée
                if (hasTrack && !showFullPlayer) {
                    MiniPlayerBar(
                        viewModel = playerViewModel,
                        onExpand  = { showFullPlayer = true }
                    )
                }
            }
        }
    }

    if (showFullPlayer) {
        FullPlayerScreen(
            viewModel = playerViewModel,
            onDismiss = { showFullPlayer = false }
        )
    }
}
