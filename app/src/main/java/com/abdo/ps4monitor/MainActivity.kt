@file:OptIn(ExperimentalMaterial3Api::class)
package com.abdo.ps4monitor
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Regex("""https?://\S+""").find(t)?.value?.let { Inbox.url.value = it }
        }
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Browser.init(applicationContext)
        if (Browser.tabs.isEmpty()) Browser.restore()
        runCatching { DownloadMonitor.restore() }
        handleShare(intent)
        setContent { Root() }
    }
    override fun onStop() { super.onStop(); Browser.save() }
}

@Composable fun Root() {
    val dark = when (Store.theme.intValue) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    // Phone (Material You) colours when available and enabled; otherwise a purple fallback.
    val cs = if (Store.dynamic.value && Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
             else if (dark) darkColorScheme(primary = Color(0xFFCFBCFF), secondary = Color(0xFFCCC2DC), tertiary = Color(0xFFEFB8C8))
             else lightColorScheme(primary = Color(0xFF6A3DE8), secondary = Color(0xFF625B71), tertiary = Color(0xFF7D5260))
    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window ?: return@SideEffect
        w.statusBarColor = cs.surface.toArgb(); w.navigationBarColor = cs.surfaceContainer.toArgb()
        WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    val shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp))
    CompositionLocalProvider(LocalLayoutDirection provides if (Lang.isAr) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = cs, shapes = shapes) { Surface(Modifier.fillMaxSize(), color = cs.surface) { AppNav() } }
    }
}

private val TABS = listOf(Triple("home", R.drawable.ic_home, "Home" to "الرئيسية"), Triple("browser", R.drawable.ic_globe, "Browser" to "المتصفح"),
    Triple("downloads", R.drawable.ic_download, "Downloads" to "التحميلات"), Triple("settings", R.drawable.ic_settings, "Settings" to "الإعدادات"))

@Composable fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = TABS.any { it.first == route }
    val active by DownloadRepo.all.collectAsState()
    val activeCount = active.count { it.state.active }
    Scaffold(bottomBar = {
        if (topLevel) NavigationBar {
            TABS.forEach { (r, icon, label) ->
                NavigationBarItem(selected = route == r, label = { Lbl(tr(label.first, label.second), style = MaterialTheme.typography.labelMedium) },
                    icon = { BadgedBox(badge = { if (r == "downloads" && activeCount > 0) Badge { Text("$activeCount") } }) { Ico(icon) } },
                    onClick = { nav.navigate(r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } })
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(nav) }
            composable("browser") { BrowserScreen() }
            composable("downloads") { DownloadsScreen(nav) }
            composable("downloads/{id}", listOf(navArgument("id") { type = NavType.StringType })) { DownloadDetail(it.arguments?.getString("id").orEmpty(), nav) }
            composable("files") { FilesScreen(nav) }
            composable("text/{ps4}/{path}", listOf(navArgument("ps4") { type = NavType.StringType }, navArgument("path") { type = NavType.StringType })) {
                TextScreen(it.arguments?.getString("ps4").orEmpty(), it.arguments?.getString("path").orEmpty(), nav) }
            composable("pkg/{ps4}/{path}", listOf(navArgument("ps4") { type = NavType.StringType }, navArgument("path") { type = NavType.StringType })) {
                PkgScreen(it.arguments?.getString("ps4").orEmpty(), it.arguments?.getString("path").orEmpty(), nav) }
            composable("settings") { SettingsScreen(nav) }
            composable("settings/ps4s") { Ps4ListScreen(nav) }
            composable("settings/ps4/{id}", listOf(navArgument("id") { type = NavType.StringType })) { Ps4EditScreen(it.arguments?.getString("id").orEmpty(), nav) }
            composable("settings/advanced") { AdvancedScreen(nav) }
            composable("settings/log") { LogScreen(nav) }
            composable("settings/history") { HistoryScreen(nav) }
        }
    }
    Inbox.url.value?.let { u ->
        SendDialog(initialUrl = u, onAddPs4 = { Inbox.url.value = null; nav.navigate("settings/ps4/new") },
            onSent = { nav.navigate("downloads") { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
            close = { Inbox.url.value = null })
    }
}
