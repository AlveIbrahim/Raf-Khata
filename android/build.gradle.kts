// AGP 9 ships built-in Kotlin. Pin the Kotlin Gradle plugin so the compiler, the Compose compiler
// plugin and the serialization plugin all use the same Kotlin version.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
