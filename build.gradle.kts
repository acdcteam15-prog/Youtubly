// AGP 9 has Kotlin built in, so there is no "kotlin-android" plugin any more. The Kotlin plugins below
// (compose, serialization) pin the Kotlin Gradle plugin version that AGP uses.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
