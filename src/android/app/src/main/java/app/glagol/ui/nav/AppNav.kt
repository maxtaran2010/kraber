package app.glagol.ui.nav

import android.app.Application
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.glagol.GlagolApp
import app.glagol.ui.detail.DetailScreen
import app.glagol.ui.detail.DetailViewModel
import app.glagol.ui.home.HomeScreen
import app.glagol.ui.home.HomeViewModel
import app.glagol.ui.record.RecordScreen
import app.glagol.ui.record.RecordViewModel
import app.glagol.ui.record.WatchLiveScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable data class DetailRoute(val id: String)
@Serializable object RecordRoute
@Serializable object WatchLiveRoute

@Composable
fun AppNav(pendingImport: Uri?, onPendingImportConsumed: () -> Unit, watchLiveRequest: Int = 0) {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as GlagolApp
    val container = app.container

    // Часы начали запись — сами открываем живой экран (если пользователь не пишет сейчас с телефона).
    // Ключ — id сессии: после «Назад» в ту же сессию не возвращаем
    fun openWatchLive() {
        val dest = nav.currentDestination
        if (dest?.hasRoute(RecordRoute::class) == true || dest?.hasRoute(WatchLiveRoute::class) == true) return
        nav.navigate(WatchLiveRoute) { launchSingleTop = true }
    }
    LaunchedEffect(Unit) {
        container.watch.live
            .map { it?.takeUnless { s -> s.broken }?.sessionId }
            .distinctUntilChanged()
            .filterNotNull()
            .collect { openWatchLive() }
    }
    // Тап по уведомлению «Часы записывают»
    LaunchedEffect(watchLiveRequest) { if (watchLiveRequest > 0) openWatchLive() }

    NavHost(
        navController = nav,
        startDestination = HomeRoute,
        enterTransition = { slideInHorizontally(tween(320)) { it / 6 } + fadeIn(tween(320)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(250)) },
        popExitTransition = { slideOutHorizontally(tween(260)) { it / 6 } + fadeOut(tween(260)) },
    ) {
        composable<HomeRoute> {
            HomeScreen(
                vm = viewModel { HomeViewModel(app as Application, container) },
                pendingImport = pendingImport,
                onPendingImportConsumed = onPendingImportConsumed,
                onOpen = { id -> nav.navigate(DetailRoute(id)) { launchSingleTop = true } },
                onRecord = { nav.navigate(RecordRoute) { launchSingleTop = true } },
            )
        }
        composable<DetailRoute> { entry ->
            val id = entry.toRoute<DetailRoute>().id
            DetailScreen(
                vm = viewModel(key = id) { DetailViewModel(app, container, id) },
                onBack = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() },
            )
        }
        composable<WatchLiveRoute> {
            WatchLiveScreen(
                hub = container.watch,
                onClose = { nav.popBackStack() },
                onOpen = { id -> nav.navigate(DetailRoute(id)) { popUpTo(HomeRoute) } },
            )
        }
        composable<RecordRoute> {
            RecordScreen(
                vm = viewModel { RecordViewModel(app.cacheDir, container, app.assets) },
                onClose = { nav.popBackStack() },
                onTranscribe = { id ->
                    nav.navigate(DetailRoute(id)) { popUpTo(HomeRoute) }
                },
            )
        }
    }
}
