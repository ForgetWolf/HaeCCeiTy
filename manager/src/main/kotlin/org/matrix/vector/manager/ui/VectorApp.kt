package org.matrix.vector.manager.ui

import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import org.matrix.vector.manager.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.matrix.vector.manager.data.repository.VectorLogSource
import org.matrix.vector.manager.data.repository.VectorStoreModuleInfo
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.navigation.Canary
import org.matrix.vector.manager.ui.navigation.CrashTrace
import org.matrix.vector.manager.ui.navigation.DeepLink
import org.matrix.vector.manager.ui.navigation.FrameworkUpdate
import org.matrix.vector.manager.ui.navigation.LogTrace
import org.matrix.vector.manager.ui.navigation.Scope
import org.matrix.vector.manager.ui.navigation.StoreDetail
import org.matrix.vector.manager.ui.navigation.SystemStatus
import org.matrix.vector.manager.ui.navigation.TOP_LEVEL_DESTINATIONS
import org.matrix.vector.manager.ui.navigation.TopLevelRoute
import org.matrix.vector.manager.ui.navigation.Troubleshoot
import org.matrix.vector.manager.ui.navigation.VectorNavPanelStore
import org.matrix.vector.manager.ui.navigation.Web
import org.matrix.vector.manager.ui.screens.canary.CanaryScreen
import org.matrix.vector.manager.ui.screens.home.CrashTraceScreen
import org.matrix.vector.manager.ui.screens.home.HomeScreen
import org.matrix.vector.manager.ui.screens.home.SystemStatusScreen
import org.matrix.vector.manager.ui.screens.modules.ModulesScreen
import org.matrix.vector.manager.ui.screens.modules.ScopeScreen
import org.matrix.vector.manager.ui.screens.report.TroubleshootScreen
import org.matrix.vector.manager.ui.screens.update.FrameworkUpdateScreen
import org.matrix.vector.manager.ui.screens.web.WebScreen
import org.matrix.vector.manager.ui.screens.web.fetchStoreSubresource
import org.matrix.vector.manager.ui.screens.web.forWebView
import org.matrix.vector.ui.logs.LogTraceScreen
import org.matrix.vector.ui.logs.LogsScreen
import org.matrix.vector.ui.navigation.LocalNavigator
import org.matrix.vector.ui.navigation.Navigator
import org.matrix.vector.ui.navigation.rememberNavigator
import org.matrix.vector.ui.store.RepoDetailsScreen
import org.matrix.vector.ui.store.RepoScreen

/** Fixed Material navigation for the four manager destinations. */
@Composable
fun VectorApp() {
    val navigator = rememberNavigator(VectorNavPanelStore, TOP_LEVEL_DESTINATIONS)

    // Where the launch intent asked to open. The activity has no back stack to act on, so it leaves
    // the destination here and this is the first place there is one — on a cold start the splash is
    // still playing when the intent arrives.
    val pending by DeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val destination = DeepLink.consume() ?: return@LaunchedEffect
        // Already there, so nothing to do — and doing it anyway would not be nothing: switching
        // tabs empties the back stack and builds it again, and the scope editor's draft lives in a
        // ViewModel scoped to the entry that would be thrown away with it. The reader who taps the
        // notification of the module already open in front of them is the case this covers.
        //
        // It is not what keeps a rotation harmless. Whether an offer is a launch or a recreation
        // replaying the intent it was created with is decided in DeepLink, which knows what it last
        // applied; here there is only where the reader is standing.
        if (navigator.current == (destination.detail ?: destination.tab)) return@LaunchedEffect
        // The tab goes down first and the screen on top of it: a notification about a module opens
        // that module's scope editor, and back from there should be the module list rather than the
        // door out of the app it just opened. Switching also discards whatever detail screen was
        // already up, so the reader is not left with a stale one buried underneath.
        navigator.switchTo(destination.tab)
        destination.detail?.let { navigator.go(it) }
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        val atRoot = !navigator.canGoBack
        val wide = LocalConfiguration.current.screenWidthDp >= 600

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (atRoot && !wide) {
                    NavigationBar {
                        TOP_LEVEL_DESTINATIONS.forEach { destination ->
                            NavigationBarItem(
                                selected = navigator.currentTopLevel == destination.route,
                                onClick = { navigator.switchTo(destination.route) },
                                icon = { Icon(destination.icon, null) },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (atRoot && wide) {
                    NavigationRail {
                        TOP_LEVEL_DESTINATIONS.forEach { destination ->
                            NavigationRailItem(
                                selected = navigator.currentTopLevel == destination.route,
                                onClick = { navigator.switchTo(destination.route) },
                                icon = { Icon(destination.icon, null) },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                    }
                }
                NavDisplay(
                    backStack = navigator.backStack,
                    onBack = { navigator.back() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = entryProvider { registerRoutes(navigator) },
                )
            }
        }
    }
}

/**
 * Every destination, registered.
 *
 * All four panels keep their entry whether or not the reader has hidden them. A saved stack names
 * its keys by class, and entryProvider throws for one it was never given, so dropping the
 * registration of a hidden panel would turn a stale saved stack into a crash.
 */
private fun EntryProviderScope<NavKey>.registerRoutes(navigator: Navigator) {
    entry<TopLevelRoute.Home> {
        HomeScreen(
            onOpenStatus = { navigator.go(SystemStatus) },
            onOpenUrl = { url -> navigator.go(Web(url)) },
            onOpenReport = { navigator.go(Troubleshoot) },
            onOpenUpdate = { navigator.go(FrameworkUpdate()) },
        )
    }
    entry<TopLevelRoute.Modules> {
        ModulesScreen(
            onModuleClick = { packageName, userId -> navigator.go(Scope(packageName, userId)) },
            onOpenStore = { packageName -> navigator.go(StoreDetail(packageName)) },
        )
    }
    entry<TopLevelRoute.Store> {
        RepoScreen(
            onModuleClick = { packageName -> navigator.go(StoreDetail(packageName)) },
            dataSource = ServiceLocator.store,
            settings = ServiceLocator.settings,
        )
    }
    entry<TopLevelRoute.Logs> {
        val logSource = remember { VectorLogSource() }
        LogsScreen(source = logSource, onOpenTrace = { text -> navigator.go(LogTrace(text)) })
    }

    entry<Scope> { route ->
        ScopeScreen(
            packageName = route.packageName,
            userId = route.userId,
            onNavigateBack = { navigator.back() },
        )
    }
    entry<StoreDetail> { route ->
        val context = LocalContext.current
        RepoDetailsScreen(
            packageName = route.packageName,
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
            dataSource = ServiceLocator.store,
            settings = ServiceLocator.settings,
            onOpenDownload = { url ->
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.store_no_browser, Toast.LENGTH_SHORT).show()
                }
            },
            host = remember(route.packageName) { VectorStoreModuleInfo(route.packageName) },
            fetchSubresource = { fetchStoreSubresource(ServiceLocator.http, it) },
            contextForWebView = { ctx, dark -> ctx.forWebView(dark) },
        )
    }
    entry<SystemStatus> {
        SystemStatusScreen(
            onNavigateBack = { navigator.back() },
            onOpenCrash = { navigator.go(CrashTrace) },
        )
    }
    entry<CrashTrace> { CrashTraceScreen(onNavigateBack = { navigator.back() }) }
    entry<LogTrace> { route ->
        LogTraceScreen(text = route.text, onNavigateBack = { navigator.back() })
    }
    entry<Troubleshoot> {
        TroubleshootScreen(
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
            onOpenCanary = { navigator.go(Canary) },
        )
    }
    entry<Canary> {
        CanaryScreen(
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
            onInstall = { versionCode -> navigator.go(FrameworkUpdate(versionCode)) },
            onOpenReport = { navigator.go(Troubleshoot) },
        )
    }
    entry<FrameworkUpdate> { route ->
        FrameworkUpdateScreen(
            openOnVersionCode = route.versionCode.takeIf { it > 0 },
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
        )
    }
    entry<Web> { route -> WebScreen(url = route.url, onNavigateBack = { navigator.back() }) }
}
