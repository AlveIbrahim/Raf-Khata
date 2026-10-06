package com.rafkhata.app.ui.nav

import kotlinx.serialization.Serializable

@Serializable
data object SignInRoute

@Serializable
data class ProfileRoute(val onboarding: Boolean = false)

@Serializable
data object HomeRoute

@Serializable
data object CoursesRoute

@Serializable
data class CourseRoute(val id: String)

/** [id] null creates a new course. */
@Serializable
data class CourseEditRoute(val id: String? = null)

@Serializable
data object SpacesRoute

@Serializable
data class SpaceRoute(val id: String)

@Serializable
data class RecordRoute(val courseId: String? = null)

/** [seekToMs] -1 = don't seek. [tab]: 0 notes, 1 transcript, 2 study. */
@Serializable
data class LectureRoute(val lectureId: String, val seekToMs: Long = -1L, val tab: Int = 0)

@Serializable
data object DeadlinesRoute

@Serializable
data object SearchRoute

@Serializable
data object SettingsRoute
