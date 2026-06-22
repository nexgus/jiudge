plugins {
    // AGP 9.0+ ships built-in Kotlin support, so no separate kotlin-android plugin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
