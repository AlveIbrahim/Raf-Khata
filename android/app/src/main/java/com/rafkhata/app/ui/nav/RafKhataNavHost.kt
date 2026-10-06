package com.rafkhata.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.courses.CourseEditScreen
import com.rafkhata.app.ui.courses.CourseScreen
import com.rafkhata.app.ui.courses.CoursesScreen
import com.rafkhata.app.ui.deadlines.DeadlinesScreen
import com.rafkhata.app.ui.home.HomeScreen
import com.rafkhata.app.ui.lecture.LectureScreen
import com.rafkhata.app.ui.profile.ProfileScreen
import com.rafkhata.app.ui.record.RecordScreen
import com.rafkhata.app.ui.search.SearchScreen
import com.rafkhata.app.ui.settings.SettingsScreen
import com.rafkhata.app.ui.signin.SignInScreen
import com.rafkhata.app.ui.spaces.SpaceScreen
import com.rafkhata.app.ui.spaces.SpacesScreen
import kotlinx.coroutines.flow.MutableStateFlow

/** Where a notification tap should lead. */
sealed interface DeepLink {
    data class Lecture(val id: String) : DeepLink

    data object Record : DeepLink

    data object Deadlines : DeepLink
}

@Composable
fun RafKhataNavHost(container: AppContainer, deepLinks: MutableStateFlow<DeepLink?>) {
    val navController = rememberNavController()
    val start: Any = remember {
        val user = container.auth.currentUser()
        when {
            !container.auth.isSignedIn || user == null -> SignInRoute
            !user.onboarded -> ProfileRoute(onboarding = true)
            else -> HomeRoute
        }
    }

    LaunchedEffect(Unit) {
        container.auth.sessionExpired.collect {
            navController.navigateFresh(SignInRoute)
        }
    }

    val link by deepLinks.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        val target = link ?: return@LaunchedEffect
        deepLinks.value = null
        if (!container.auth.isSignedIn) return@LaunchedEffect
        when (target) {
            is DeepLink.Lecture -> navController.navigate(LectureRoute(target.id))
            DeepLink.Record -> navController.navigate(RecordRoute()) { launchSingleTop = true }
            DeepLink.Deadlines -> navController.openTab(MainTab.DEADLINES)
        }
    }

    NavHost(navController = navController, startDestination = start) {
        composable<SignInRoute> {
            SignInScreen(
                onSignedIn = { onboarded ->
                    navController.navigateFresh(if (onboarded) HomeRoute else ProfileRoute(onboarding = true))
                },
            )
        }
        composable<ProfileRoute> { entry ->
            val route = entry.toRoute<ProfileRoute>()
            ProfileScreen(
                onboarding = route.onboarding,
                onDone = {
                    if (route.onboarding) {
                        navController.navigateFresh(HomeRoute)
                    } else {
                        navController.popBackStack()
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<HomeRoute> {
            HomeScreen(
                onOpenTab = navController::openTab,
                onRecord = { courseId -> navController.navigate(RecordRoute(courseId)) },
                onOpenLecture = { navController.navigate(LectureRoute(it)) },
                onSearch = { navController.navigate(SearchRoute) },
                onSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<CoursesRoute> {
            CoursesScreen(
                onOpenTab = navController::openTab,
                onOpenCourse = { navController.navigate(CourseRoute(it)) },
                onAddCourse = { navController.navigate(CourseEditRoute()) },
            )
        }
        composable<CourseRoute> { entry ->
            val route = entry.toRoute<CourseRoute>()
            CourseScreen(
                courseId = route.id,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(CourseEditRoute(route.id)) },
                onRecord = { navController.navigate(RecordRoute(route.id)) },
                onOpenLecture = { navController.navigate(LectureRoute(it)) },
            )
        }
        composable<CourseEditRoute> { entry ->
            val route = entry.toRoute<CourseEditRoute>()
            CourseEditScreen(
                courseId = route.id,
                onBack = { navController.popBackStack() },
                onSaved = { saved ->
                    navController.popBackStack()
                    if (route.id == null) navController.navigate(CourseRoute(saved))
                },
            )
        }
        composable<SpacesRoute> {
            SpacesScreen(
                onOpenTab = navController::openTab,
                onOpenSpace = { navController.navigate(SpaceRoute(it)) },
            )
        }
        composable<SpaceRoute> { entry ->
            SpaceScreen(
                spaceId = entry.toRoute<SpaceRoute>().id,
                onBack = { navController.popBackStack() },
                onLeft = { navController.popBackStack() },
            )
        }
        composable<RecordRoute> { entry ->
            RecordScreen(
                courseId = entry.toRoute<RecordRoute>().courseId,
                onBack = { navController.popBackStack() },
                onSaved = {
                    if (!navController.popBackStack(HomeRoute, inclusive = false)) {
                        navController.navigateFresh(HomeRoute)
                    }
                },
            )
        }
        composable<LectureRoute> { entry ->
            val route = entry.toRoute<LectureRoute>()
            LectureScreen(
                lectureId = route.lectureId,
                initialTab = route.tab,
                seekToMs = route.seekToMs,
                onBack = { navController.popBackStack() },
            )
        }
        composable<DeadlinesRoute> {
            DeadlinesScreen(
                onOpenTab = navController::openTab,
                onOpenLecture = { navController.navigate(LectureRoute(it)) },
            )
        }
        composable<SearchRoute> {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenHit = { lectureId, seekMs, tab -> navController.navigate(LectureRoute(lectureId, seekMs, tab)) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onEditProfile = { navController.navigate(ProfileRoute()) },
                onSignedOut = { navController.navigateFresh(SignInRoute) },
            )
        }
    }
}

/** Navigate to [route] and drop everything else from the back stack. */
fun NavHostController.navigateFresh(route: Any) {
    val graphId = graph.id
    navigate(route) {
        popUpTo(graphId) { inclusive = true }
        launchSingleTop = true
    }
}

/** Switch between the bottom-bar screens, keeping one copy of each on the back stack. */
fun NavHostController.openTab(tab: MainTab) {
    val route: Any = when (tab) {
        MainTab.HOME -> HomeRoute
        MainTab.COURSES -> CoursesRoute
        MainTab.SPACES -> SpacesRoute
        MainTab.DEADLINES -> DeadlinesRoute
    }
    navigate(route) {
        popUpTo<HomeRoute> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
