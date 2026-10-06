package com.rafkhata.app.di

import android.app.Application
import android.content.Context
import com.rafkhata.app.BuildConfig
import com.rafkhata.app.data.api.AuthApi
import com.rafkhata.app.data.api.AuthInterceptor
import com.rafkhata.app.data.api.DeadlineDto
import com.rafkhata.app.data.api.RafKhataApi
import com.rafkhata.app.data.api.TokenAuthenticator
import com.rafkhata.app.data.auth.TokenStore
import com.rafkhata.app.data.db.AppDatabase
import com.rafkhata.app.data.db.RecordingState
import com.rafkhata.app.data.repo.AuthRepository
import com.rafkhata.app.data.repo.CacheStore
import com.rafkhata.app.data.repo.CourseRepository
import com.rafkhata.app.data.repo.LectureRepository
import com.rafkhata.app.data.repo.RecordingRepository
import com.rafkhata.app.data.repo.SpaceRepository
import com.rafkhata.app.data.settings.SettingsStore
import com.rafkhata.app.notify.PushManager
import com.rafkhata.app.notify.ReminderScheduler
import com.rafkhata.app.recording.RecordingStateHolder
import com.rafkhata.app.upload.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Hand-written dependency wiring: one instance of each service for the whole app. */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val appContext: Context get() = app

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    val settings = SettingsStore(app)
    val tokens = TokenStore(app, json)
    val database = AppDatabase.create(app)

    private val baseUrl = BuildConfig.API_BASE_URL.let { if (it.endsWith("/")) it else "$it/" }
    private val converter = json.asConverterFactory("application/json".toMediaType())

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** For presigned storage URLs: no auth header, long write timeout for 5-minute segments. */
    val uploadClient: OkHttpClient = baseClient.newBuilder()
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    private val authApi: AuthApi by lazy {
        Retrofit.Builder().baseUrl(baseUrl).client(baseClient).addConverterFactory(converter).build()
            .create(AuthApi::class.java)
    }

    val api: RafKhataApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(
            baseClient.newBuilder()
                .addInterceptor(AuthInterceptor(tokens))
                .authenticator(TokenAuthenticator(tokens, { authApi }, { auth.notifySessionExpired() }))
                .apply {
                    if (BuildConfig.DEBUG) {
                        addInterceptor(
                            HttpLoggingInterceptor().apply {
                                level = HttpLoggingInterceptor.Level.BASIC
                                redactHeader("Authorization")
                            },
                        )
                    }
                }
                .build(),
        )
        .addConverterFactory(converter)
        .build()
        .create(RafKhataApi::class.java)

    private val cache = CacheStore(database.cache(), json)

    val auth = AuthRepository(api, tokens)
    val courses = CourseRepository(api, cache)
    val lectures = LectureRepository(api, cache, json)
    val spaces = SpaceRepository(api, cache)
    val recordings = RecordingRepository(app, database)
    val uploads = UploadScheduler(app, settings, recordings)
    val recordingState = RecordingStateHolder()
    val push = PushManager(app, api)

    /** Lecture ids whose notes just became ready (from push messages), so open screens refresh. */
    val lectureEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** After any sign-in: register for pushes and resume uploads that were waiting for an account. */
    suspend fun onSignedIn() {
        push.registerDevice()
        recordings.withStates(RecordingState.FAILED).forEach { uploads.enqueue(it.id) }
        uploads.requeueAll(replace = false)
    }

    /** Sign out and remove this account's data from the phone, including unsent recordings. */
    suspend fun signOut() {
        push.unregisterDevice()
        auth.endSession()
        clearLocalData()
    }

    /** Schedule reminders for [deadlines] according to the user's settings. */
    suspend fun syncReminders(deadlines: List<DeadlineDto>) {
        val current = settings.current()
        ReminderScheduler.sync(app, deadlines, current.deadlineReminders, current.mutedDeadlines)
    }

    suspend fun clearLocalData() {
        uploads.cancelAll()
        ReminderScheduler.cancelAll(app)
        recordings.deleteAll()
        cache.clear()
        courses.clearLocal()
        settings.clear()
    }
}
