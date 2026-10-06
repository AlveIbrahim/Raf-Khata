# kotlinx.serialization, Retrofit, OkHttp, Room, Media3 and Firebase ship their own consumer rules.
# Keep the API models' names stable for easier debugging of release builds.
-keepnames class com.rafkhata.app.data.api.** { *; }
