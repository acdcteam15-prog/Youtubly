// AGP 9 has Kotlin built in (same setup as FamilySafeTube); the Compose plugin pins the Kotlin version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
