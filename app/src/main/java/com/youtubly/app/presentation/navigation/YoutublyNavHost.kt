package com.youtubly.app.presentation.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import com.youtubly.app.playback.PlayerController
import com.youtubly.app.presentation.components.MiniPlayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.youtubly.app.playback.PipState
import com.youtubly.app.presentation.channel.ChannelScreen
import com.youtubly.app.presentation.downloads.DownloadsScreen
import com.youtubly.app.presentation.downloads.LocalPlayerScreen
import com.youtubly.app.presentation.home.HomeScreen
import com.youtubly.app.presentation.library.LibraryScreen
import androidx.compose.ui.graphics.graphicsLayer
import com.youtubly.app.presentation.search.SearchScreen
import com.youtubly.app.presentation.signin.SignInScreen
import com.youtubly.app.presentation.components.OfflineBanner

private val tabs = listOf(
    NavTab(Screen.Home.route, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    NavTab(Screen.Search.route, "Search", Icons.Outlined.Search, Icons.Rounded.Search),
    NavTab(Screen.Downloads.route, "Library", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
    NavTab(Screen.Editor.route, "Edit", Icons.Outlined.ContentCut, Icons.Rounded.ContentCut),
    NavTab(Screen.Library.route, "You", Icons.Outlined.PersonOutline, Icons.Rounded.Person)
)
private val tabRoutes = tabs.map { it.route }.toSet()

private const val DUR = 380

// Tabs: subtle fade-through with a tiny scale.
/** +1 when moving to a tab further right in the nav bar, -1 further left, 0 if either isn't a tab. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabDirection(): Int {
    val from = tabs.indexOfFirst { it.route == initialState.destination.route }
    val to = tabs.indexOfFirst { it.route == targetState.destination.route }
    return if (from < 0 || to < 0 || from == to) 0 else if (to > from) 1 else -1
}

// Tab ↔ tab: slide in the direction of the tab you tapped (a short 1/5-width slide + fade,
// so it reads as direction without feeling like a page flip). Anything else: the old fade/scale.
private val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition = {
    when (val d = tabDirection()) {
        0 -> fadeIn(tween(DUR, delayMillis = 60)) + scaleIn(tween(DUR, easing = FastOutSlowInEasing), initialScale = 0.96f)
        else -> androidx.compose.animation.slideInHorizontally(tween(DUR, easing = FastOutSlowInEasing)) { w -> d * w / 5 } + fadeIn(tween(DUR))
    }
}
private val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition = {
    when (val d = tabDirection()) {
        0 -> fadeOut(tween(160))
        else -> androidx.compose.animation.slideOutHorizontally(tween(DUR, easing = FastOutSlowInEasing)) { w -> -d * w / 5 } + fadeOut(tween(160))
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun YoutublyNavHost(
    playerController: PlayerController,
    pendingLink: kotlinx.coroutines.flow.StateFlow<com.youtubly.app.core.DeepLink?>? = null,
    onLinkHandled: () -> Unit = {}
) {
    val nav = rememberNavController()
    // Phase 7: the player is an app-wide sheet, not a destination. Everything that opens a
    // video calls playerSheet.open(url).
    val playerSheet = com.youtubly.app.presentation.player.rememberPlayerSheetState()
    // YouTube links opened in / shared to Youtubly (see MainActivity.handleIntent).
    val link = pendingLink?.collectAsState()?.value
    // A link shared into the app waits here until the user picks what to do with it.
    var shared by remember { mutableStateOf<com.youtubly.app.core.DeepLink.Shared?>(null) }
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        // Cold start from a link: wait until the NavHost has set its graph (start destination
        // on the back stack) before navigating, otherwise navigate() has no graph to use.
        nav.currentBackStackEntryFlow.first()
        when (link) {
            is com.youtubly.app.core.DeepLink.Video -> playerSheet.open(link.url, link.startMs)
            is com.youtubly.app.core.DeepLink.Channel -> { playerSheet.collapse(); nav.navigate(Screen.Channel.createRoute(link.url)) }
            is com.youtubly.app.core.DeepLink.Playlist -> { playerSheet.collapse(); nav.navigate(Screen.YoutubePlaylist.createRoute(link.url)) }
            com.youtubly.app.core.DeepLink.OpenEditor -> {
                // (navigateTab is declared further down, so navigate the same way inline.)
                playerSheet.collapse()
                nav.navigate(Screen.Editor.route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            is com.youtubly.app.core.DeepLink.Shared -> shared = link
            is com.youtubly.app.core.DeepLink.Open -> {
                playerSheet.collapse()
                val isTab = tabs.any { it.route == link.route }
                nav.navigate(link.route) {
                    if (isTab) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        restoreState = true
                    }
                    launchSingleTop = true
                }
            }
            null -> return@LaunchedEffect
        }
        onLinkHandled()
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val inPip by PipState.inPip
    val isLocalPlayerRoute = route == Screen.LocalPlayer.route
    // Phase 8: tablets / unfolded foldables get a side rail instead of the floating bottom bar.
    val wide = com.youtubly.app.presentation.components.LocalWideLayout.current
    val showRail = wide && !inPip && !isLocalPlayerRoute
    val navigateTab: (NavTab) -> Unit = { tab ->
        if (tab.route == Screen.Home.route) com.youtubly.app.presentation.home.HomeRefresh.request()
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val streamingId by playerController.nowPlayingId.collectAsState()

    // Downloads still play on their own screen; remember it so the floating mini player
    // (kept for downloaded files only) can return to it.
    LaunchedEffect(entry) {
        val e = entry ?: return@LaunchedEffect
        if (e.destination.route == Screen.LocalPlayer.route) {
            PipState.lastPlayerRoute.value = Screen.LocalPlayer.createRoute(e.arguments?.getString("downloadId").orEmpty())
        }
    }

    val showBanner = !inPip && !isLocalPlayerRoute && !playerSheet.isExpanded

    // Floating nav bar hides while you scroll down a list and returns when you scroll up
    // (or switch screens). Small scrolls are ignored so it doesn't flicker.
    var navShown by remember { mutableStateOf(true) }
    LaunchedEffect(route) { navShown = true }
    val navScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -6f) navShown = false else if (available.y > 6f) navShown = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    // The mini bar sits above the nav bar (tabs) or the system nav bar (other screens), on top of the
    // list: lists reserve its height + the system inset so the last item is never hidden behind it.
    val miniNavInset = with(androidx.compose.ui.platform.LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val miniClearance = androidx.compose.animation.core.animateDpAsState(
        targetValue = if (playerSheet.request != null && !inPip) com.youtubly.app.presentation.player.MiniBarHeight + miniNavInset else 0.dp,
        label = "miniBarClearance"
    )
    androidx.compose.runtime.CompositionLocalProvider(
        com.youtubly.app.presentation.components.LocalOpenChannel provides { url: String -> playerSheet.collapse(); nav.navigate(Screen.Channel.createRoute(url)) },
        com.youtubly.app.presentation.components.LocalMiniBarClearance provides miniClearance
    ) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Shared elements (card thumbnail → player) need every destination inside one scope.
        SharedTransitionLayout(
            Modifier.fillMaxSize().nestedScroll(navScroll).padding(start = if (showRail) com.youtubly.app.presentation.components.NavRailWidth else 0.dp)
        ) {
            CompositionLocalProvider(com.youtubly.app.presentation.components.LocalSharedTransitionScope provides this) {
                NavHost(
                    navController = nav,
                    startDestination = Screen.Home.route,
                    enterTransition = tabEnter,
                    exitTransition = tabExit,
                    popEnterTransition = tabEnter,
                    popExitTransition = tabExit,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Screen.Home.route) {
                        ProvideNavScope {
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                                com.youtubly.app.presentation.update.UpdateBanner(onOpen = { nav.navigate(Screen.Updates.route) })
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                    HomeScreen(
                                        onVideoClick = { playerSheet.open(it.url) },
                                        onOpenGroup = { g -> nav.navigate(Screen.ChannelGroup.createRoute(g)) },
                                        onResumeQueue = { url, ms -> playerSheet.open(url, ms) },
                                        playerActive = playerSheet.request != null
                                    )
                                }
                            }
                        }
                    }
                    composable(Screen.Search.route) {
                        ProvideNavScope {
                            SearchScreen(
                                onVideoClick = { playerSheet.open(it.url) },
                                onOpenPlaylist = { id -> nav.navigate(Screen.PlaylistDetail.createRoute(id)) },
                                onOpenDownload = { id -> nav.navigate(Screen.LocalPlayer.createRoute(id)) },
                                onOpenLink = { l ->
                                    when (l) {
                                        is com.youtubly.app.core.DeepLink.Video -> playerSheet.open(l.url, l.startMs)
                                        is com.youtubly.app.core.DeepLink.Channel -> nav.navigate(Screen.Channel.createRoute(l.url))
                                        is com.youtubly.app.core.DeepLink.Playlist -> nav.navigate(Screen.YoutubePlaylist.createRoute(l.url))
                                        else -> Unit
                                    }
                                }
                            )
                        }
                    }
                    composable(Screen.Downloads.route) {
                        // One "Library" tab: Downloads and Playlists behind two chips.
                        com.youtubly.app.presentation.library.LibraryHubScreen(
                            onOpenAudio = { nav.navigate(Screen.AudioLibrary.route) },
                            onOpenLocal = { id -> nav.navigate(Screen.LocalPlayer.createRoute(id)) },
                            onOpenEditor = { navigateTab(tabs.first { it.route == Screen.Editor.route }) },
                            onOpenPlaylist = { id -> nav.navigate(Screen.PlaylistDetail.createRoute(id)) },
                            onOpenYoutubePlaylist = { url -> nav.navigate(Screen.YoutubePlaylist.createRoute(url)) }
                        )
                    }
                    composable(Screen.Editor.route) {
                        com.youtubly.app.presentation.editor.EditorScreen()
                    }
                    composable(Screen.Library.route) {
                        ProvideNavScope {
                            LibraryScreen(
                                onVideoClick = { playerSheet.open(it.url) },
                                onChannelClick = { nav.navigate(Screen.Channel.createRoute(it)) },
                                onSignIn = { nav.navigate(Screen.SignIn.route) },
                                onOpenHistory = { nav.navigate(Screen.History.route) },
                                onOpenHidden = { nav.navigate(Screen.Hidden.route) },
                                onOpenPlaylists = { nav.navigate(Screen.Playlists.route) },
                                onOpenSubscriptions = { nav.navigate(Screen.Subscriptions.route) },
                                onOpenBlur = { nav.navigate(Screen.BlurSettings.route) },
                                onOpenSettings = { nav.navigate(Screen.Settings.route) },
                                onOpenRoute = { nav.navigate(it) }
                            )
                        }
                    }
                    composable(
                        Screen.SignIn.route,
                        enterTransition = { slideInVertically(tween(DUR, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(DUR)) },
                        popExitTransition = { slideOutVertically(tween(DUR, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(DUR)) }
                    ) {
                        SignInScreen(
                            onDone = { nav.popBackStack(); nav.navigate(Screen.Home.route) { popUpTo(Screen.Home.route) { inclusive = true } } },
                            onCancel = { nav.popBackStack() }
                        )
                    }

                    // Detail screens slide up like sheets.
                    composable(
                        Screen.LocalPlayer.route,
                        arguments = listOf(navArgument("downloadId") { type = NavType.StringType }),
                        enterTransition = { slideInVertically(tween(DUR, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutVertically(tween(DUR, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(DUR)) }
                    ) { backStack ->
                        LocalPlayerScreen(
                            downloadId = backStack.arguments?.getString("downloadId").orEmpty(),
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable(
                        Screen.Settings.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.library.SettingsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenRoute = { nav.navigate(it) }
                        )
                    }
                    composable(
                        Screen.MusicRemover.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.music.MusicRemoverScreen(
                            onBack = { nav.popBackStack() },
                            onOpenWhitelist = { nav.navigate(Screen.MusicExcluded.route) }
                        )
                    }
                    composable(
                        Screen.MusicExcluded.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.music.MusicWhitelistScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        Screen.BlurSettings.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.blur.BlurSettingsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenWhitelist = { nav.navigate(Screen.BlurExcluded.route) }
                        )
                    }
                    composable(
                        Screen.BlurExcluded.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.blur.WhitelistScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        Screen.Diagnostics.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.system.DiagnosticsScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        Screen.Storage.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.system.StorageScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        Screen.ChannelGroups.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.library.ChannelGroupsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenGroup = { g -> nav.navigate(Screen.ChannelGroup.createRoute(g)) }
                        )
                    }
                    composable(
                        Screen.ChannelGroup.route,
                        arguments = listOf(navArgument("name") { type = NavType.StringType }),
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) { backStack ->
                        com.youtubly.app.presentation.library.GroupFeedScreen(
                            group = backStack.arguments?.getString("name").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onVideoClick = { v -> playerSheet.open(v.url) }
                        )
                    }
                    composable(
                        Screen.Updates.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.update.UpdatesScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        Screen.Backup.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.system.BackupScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        Screen.SponsorBlock.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.system.SponsorBlockScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        Screen.History.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        ProvideNavScope {
                            com.youtubly.app.presentation.history.HistoryScreen(
                                onBack = { nav.popBackStack() },
                                onVideoClick = { playerSheet.open(it.url) }
                            )
                        }
                    }
                    composable(Screen.Playlists.route) {
                        com.youtubly.app.presentation.playlists.PlaylistsScreen(
                            onBack = { nav.popBackStack() },
                            onOpen = { id -> nav.navigate(Screen.PlaylistDetail.createRoute(id)) },
                            onOpenYoutubePlaylist = { url -> nav.navigate(Screen.YoutubePlaylist.createRoute(url)) }
                        )
                    }
                    composable(
                        Screen.PlaylistDetail.route,
                        arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.playlists.PlaylistDetailScreen(
                            onBack = { nav.popBackStack() },
                            onPlay = { v -> playerSheet.open(v.url) }
                        )
                    }
                    composable(
                        Screen.AudioLibrary.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.audio.AudioLibraryScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        Screen.Subscriptions.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.subscriptions.SubscriptionsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenChannel = { url -> nav.navigate(Screen.Channel.createRoute(url)) },
                            onSignIn = { nav.navigate(Screen.SignIn.route) }
                        )
                    }
                    composable(
                        Screen.Hidden.route,
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) {
                        com.youtubly.app.presentation.hidden.HiddenScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        Screen.Channel.route,
                        arguments = listOf(navArgument("channelUrl") { type = NavType.StringType }),
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) { backStack ->
                        ProvideNavScope {
                            ChannelScreen(
                                channelUrl = backStack.arguments?.getString("channelUrl").orEmpty(),
                                onBack = { nav.popBackStack() },
                                onVideoClick = { playerSheet.open(it.url) },
                                onPlaylistClick = { nav.navigate(Screen.YoutubePlaylist.createRoute(it.url)) }
                            )
                        }
                    }
                    composable(
                        Screen.YoutubePlaylist.route,
                        arguments = listOf(navArgument("playlistUrl") { type = NavType.StringType }),
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DUR)) + fadeIn(tween(DUR)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DUR)) + fadeOut(tween(DUR)) }
                    ) { backStack ->
                        ProvideNavScope {
                            com.youtubly.app.presentation.channel.RemotePlaylistScreen(
                                playlistUrl = backStack.arguments?.getString("playlistUrl").orEmpty(),
                                onBack = { nav.popBackStack() },
                                onPlay = { v -> playerSheet.open(v.url) },
                                onOpenChannel = { url -> nav.navigate(Screen.Channel.createRoute(url)) }
                            )
                        }
                    }
                }
            }
        }

        if (showBanner) {
            OfflineBanner(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding())
        }
        // Incognito reminder: a small pill at the top of every screen; tap to turn it off.
        val incognito by com.youtubly.app.core.Incognito.on.collectAsState()
        AnimatedVisibility(
            visible = incognito && !inPip,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 6.dp, end = 12.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color(0xFF26262E))
                    .clickable { com.youtubly.app.core.Incognito.set(false) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.VisibilityOff, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                androidx.compose.material3.Text("Incognito", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }

        // Floating mini player: downloaded files only (streamed videos use the player sheet).
        // Not on the Audio library either: it has its own now-playing bar, so both showed at once.
        if (!isLocalPlayerRoute && route != Screen.AudioLibrary.route && playerSheet.request == null && streamingId == null) {
            MiniPlayer(
                controller = playerController,
                systemPip = inPip,
                bottomInset = if (route in tabRoutes && !wide) 88.dp else 0.dp,
                onExpand = {
                    val target = PipState.lastPlayerRoute.value ?: return@MiniPlayer
                    // Re-opens the player; it re-attaches to the already-playing video
                    // instead of restarting it.
                    nav.navigate(target) { launchSingleTop = true }
                },
                modifier = if (inPip) Modifier else Modifier.statusBarsPadding().navigationBarsPadding()
            )
        }

        AnimatedVisibility(
            visible = !inPip && route in tabRoutes && !wide && navShown,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            // Fades out as the player sheet expands over it.
            modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = 1f - playerSheet.expandedFraction }
        ) {
            FloatingNavBar(
                tabs = tabs,
                selectedRoute = route,
                onSelect = { tab ->
                    if (tab.route == Screen.Home.route) com.youtubly.app.presentation.home.HomeRefresh.request()
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }

        if (showRail) {
            androidx.compose.material3.NavigationRail(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(com.youtubly.app.presentation.components.NavRailWidth)
                    .graphicsLayer { alpha = 1f - playerSheet.expandedFraction },
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                tabs.forEach { tab ->
                    val selected = route == tab.route
                    androidx.compose.material3.NavigationRailItem(
                        selected = selected,
                        onClick = { navigateTab(tab) },
                        icon = { androidx.compose.material3.Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                        label = { androidx.compose.material3.Text(tab.label) },
                        colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
        }

        // The player sheet sits above everything, including the nav bar.
        com.youtubly.app.presentation.player.PlayerSheet(
            state = playerSheet,
            controller = playerController,
            bottomInset = if (route in tabRoutes && !wide) 88.dp else 0.dp,
            onChannelClick = { url -> nav.navigate(Screen.Channel.createRoute(url)) }
        )

        shared?.let { sh ->
            com.youtubly.app.presentation.share.ShareIntakeSheet(
                url = sh.url,
                onPlay = { playerSheet.open(sh.url, sh.startMs) },
                onDismiss = { shared = null }
            )
        }
    }
}
}

/** Exposes this destination's AnimatedContentScope to shared-element modifiers below it. */
@Composable
private fun AnimatedContentScope.ProvideNavScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(com.youtubly.app.presentation.components.LocalNavAnimatedScope provides this, content = content)
}
