plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ktlint, run via the CLI rather than the ktlint-gradle plugin (see libs.versions.toml note).
val ktlint: Configuration by configurations.creating

// Git build metadata, resolved at configuration time and baked into BuildConfig so the About
// dialog can show exactly which commit an APK was built from. `gitHash` is the short SHA;
// `gitDirty` flags an uncommitted working tree. Both degrade gracefully (to "unknown"/false) when
// git is unavailable, so a source-only build still compiles.
fun git(vararg args: String): String? =
    runCatching {
        providers
            .exec { commandLine("git", *args) }
            .standardOutput.asText
            .get()
            .trim()
    }.getOrNull()

val gitHash: String = git("rev-parse", "--short", "HEAD")?.takeIf { it.isNotEmpty() } ?: "unknown"
val gitDirty: Boolean = !git("status", "--porcelain").isNullOrEmpty()

android {
    namespace = "io.github.nexgus.jiudge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.nexgus.jiudge"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.6.0-rc.1"

        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
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
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // SAF (DocumentFile) for storing user routes in a user-picked folder that survives uninstall.
    implementation(libs.androidx.documentfile)

    // mapsforge: render RudyMap .map with its bundled theme + hillshade from .hgt DEM.
    // mapsforge-map-android pulls in com.caverock:androidsvg transitively for SVG symbols -
    // do not add androidsvg explicitly or its classes collide (duplicate-class build error).
    implementation(libs.mapsforge.map.android)
    implementation(libs.mapsforge.map.reader)
    implementation(libs.mapsforge.themes)

    // BRouter: offline route planning, called in-process via brouter-core (mirrors how
    // brouter-routing-app's BRouterWorker drives the engine). Routing data is BRouter .rd5
    // segments + a .brf profile on disk - separate from the rendering .map.
    implementation(libs.brouter.core)
    implementation(libs.brouter.mapaccess)
    implementation(libs.brouter.expressions)
    implementation(libs.brouter.util)
    implementation(libs.brouter.codec)

    ktlint(libs.ktlint.cli)

    testImplementation(libs.junit)
}

// Lint/format Kotlin sources with the ktlint CLI. `ktlintCheck` is wired into `check`.
val ktlintArgs = listOf("src/**/*.kt", "**/*.kts", "!**/build/**")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Check Kotlin code style with ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args = ktlintArgs
    // ktlint needs deep reflection into the Kotlin compiler internals on JDK 17+.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Auto-format Kotlin code with ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args = listOf("-F") + ktlintArgs
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

tasks.named("check") { dependsOn("ktlintCheck") }
