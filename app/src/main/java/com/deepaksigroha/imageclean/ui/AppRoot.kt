package com.deepaksigroha.imageclean.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deepaksigroha.imageclean.MainActivity
import com.deepaksigroha.imageclean.ui.screens.*
import kotlinx.coroutines.launch

enum class Dest(val route: String, val title: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    INSPECTOR("inspector", "Image Inspector", Icons.Default.ManageSearch),
    CLEANER("cleaner", "Metadata Cleaner", Icons.Default.CleaningServices),
    C2PA("c2pa", "C2PA Inspector", Icons.Default.VerifiedUser),
    AI("ai", "AI Provenance Scanner", Icons.Default.AutoAwesome),
    WATERMARK("watermark", "Watermark Detector", Icons.Default.BrandingWatermark),
    INSTAGRAM("instagram", "Instagram Checker", Icons.Default.PhotoCamera),
    EXPORT("export", "Social Media Export", Icons.Default.IosShare),
    BATCH("batch", "Batch Processing", Icons.Default.Collections),
    REPORTS("reports", "Reports", Icons.Default.Description),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
    ABOUT("about", "About Developer", Icons.Default.Person),
}

/** Callbacks every screen may need. */
class AppActions(
    val pickImage: () -> Unit,
    val pickFile: () -> Unit,
    val navigate: (Dest) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> uri?.let(vm::open) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(vm::open) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { MainActivity.MEDIA_LOCATION_PERMISSION?.let { runCatching { permission.launch(it) } } }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val actions = remember {
        AppActions(
            pickImage = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            pickFile = { filePicker.launch(arrayOf("image/*")) },
            navigate = { d -> nav.navigate(d.route) { launchSingleTop = true; popUpTo(Dest.HOME.route) } },
        )
    }
    val backStack by nav.currentBackStackEntryAsState()
    val current = Dest.entries.firstOrNull { it.route == backStack?.destination?.route } ?: Dest.HOME

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("ImageClean Pro", Modifier.padding(start = 24.dp, top = 24.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("AI & Metadata Inspector", Modifier.padding(start = 24.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    Dest.entries.forEach { d ->
                        NavigationDrawerItem(
                            label = { Text(d.title) }, icon = { Icon(d.icon, null) }, selected = d == current,
                            onClick = { scope.launch { drawer.close() }; actions.navigate(d) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    Text("Developed by Deepak Sigroha", Modifier.padding(24.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (current == Dest.HOME) "ImageClean Pro" else current.title) },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") } },
                    actions = {
                        if (current != Dest.HOME) IconButton(onClick = actions.pickImage) { Icon(Icons.Default.AddPhotoAlternate, "Select image") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) { AppNavHost(nav, vm, actions) }
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, vm: MainViewModel, a: AppActions) {
    NavHost(nav, startDestination = Dest.HOME.route) {
        composable(Dest.HOME.route) { Scroll { HomeScreen(vm, a) } }
        composable(Dest.INSPECTOR.route) { Scroll { InspectorScreen(vm, a) } }
        composable(Dest.CLEANER.route) { Scroll { CleanerScreen(vm, a) } }
        composable(Dest.C2PA.route) { Scroll { C2paScreen(vm, a) } }
        composable(Dest.AI.route) { Scroll { AiScreen(vm, a) } }
        composable(Dest.WATERMARK.route) { Scroll { WatermarkScreen(vm, a) } }
        composable(Dest.INSTAGRAM.route) { Scroll { InstagramScreen(vm, a) } }
        composable(Dest.EXPORT.route) { Scroll { ExportScreen(vm, a) } }
        composable(Dest.BATCH.route) { Scroll { BatchScreen(vm, a) } }
        composable(Dest.REPORTS.route) { Scroll { ReportsScreen(vm, a) } }
        composable(Dest.SETTINGS.route) { Scroll { SettingsScreen(vm, a) } }
        composable(Dest.ABOUT.route) { Scroll { AboutScreen() } }
    }
}

@Composable
private fun Scroll(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
}

/** Convenience: collect the selected image state. */
@Composable
fun rememberAnalysis(vm: MainViewModel) = vm.analysis.collectAsStateWithLifecycle()
