package com.zenith.glyphstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zenith.glyphstudio.ui.GalleryScreen
import com.zenith.glyphstudio.ui.SmartHudScreen
import com.zenith.glyphstudio.ui.StudioScreen

private enum class Tab(val title: String, val icon: ImageVector) {
    STUDIO("Studio", Icons.Default.Brush),
    HUD("Smart HUD", Icons.Default.Dashboard),
    GALLERY("Gallery & Updates", Icons.Default.Collections),
}

private val NothingScheme = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A3A3A), onPrimaryContainer = Color.White,
    secondary = Color(0xFFD71921), tertiary = Color(0xFFD71921),
    background = Color.Black, surface = Color(0xFF0E0E0E),
    surfaceVariant = Color(0xFF1C1C1C), onSurface = Color.White,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = NothingScheme) { App() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.STUDIO) }
    val msg by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = {
                Text("GLYPH STUDIO", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(4f, androidx.compose.ui.unit.TextUnitType.Sp))
            })
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(t == tab, { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.title) })
                }
            }
        },
    ) { pad ->
        Surface(Modifier.padding(pad)) {
            when (tab) {
                Tab.STUDIO -> StudioScreen(vm)
                Tab.HUD -> SmartHudScreen(vm)
                Tab.GALLERY -> GalleryScreen(vm) { tab = Tab.STUDIO }
            }
        }
    }
}
