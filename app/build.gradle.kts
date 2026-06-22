plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.nexgus.jiudge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.nexgus.jiudge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // mapsforge: render RudyMap .map with its bundled theme + hillshade from .hgt DEM.
    // mapsforge-map-android pulls in com.caverock:androidsvg transitively for SVG symbols -
    // do not add androidsvg explicitly or its classes collide (duplicate-class build error).
    implementation(libs.mapsforge.map.android)
    implementation(libs.mapsforge.map.reader)
    implementation(libs.mapsforge.themes)
}
