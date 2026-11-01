import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------- version ----------
// versionName comes from version.properties; versionCode = offset + build number (GitHub Actions run number).
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val appVersionName: String = versionProps.getProperty("versionName", "1.0.0")
val buildNumber: Int = (System.getenv("BUILD_NUMBER") ?: providers.gradleProperty("buildNumber").orNull)?.toIntOrNull() ?: 0
val appVersionCode: Int = versionProps.getProperty("versionCodeOffset", "100").toInt() + buildNumber

// ---------- update source ----------
// "owner/repo" whose GitHub Releases the in-app updater reads. Actions sets GITHUB_REPOSITORY itself.
val updateRepo: String = System.getenv("GITHUB_REPOSITORY") ?: providers.gradleProperty("updateRepo").orNull ?: "CHANGE_ME/Youtubly"

// ---------- release signing ----------
// CI: KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD (decoded from repository secrets).
// Local: keystore.properties (git-ignored). Without either, release builds are debug-signed (not updatable).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)
val releaseStoreFile: File? = signingValue("KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }
val hasReleaseKeystore = releaseStoreFile != null

/** The only place to change when YouTube breaks playback: bump `newpipe` in gradle/libs.versions.toml. */
val newPipeVersion: String = libs.versions.newpipe.get()

android {
    namespace = "com.youtubly.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.youtubly.app"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "NEWPIPE_VERSION", "\"$newPipeVersion\"")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The TFLite gender models are memory-mapped, so they must be stored uncompressed.
    androidResources { noCompress += "tflite" }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/INDEX.LIST", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    testOptions {
        // JVM unit tests call code that logs through android.util.Log; return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // CI should not fail on style hints; real errors still fail the build.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    // Video editor: on-device trim/export (hardware codecs, no native FFmpeg binary)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Content blur: bundled on-device face detection (offline, no Play Services), optional user-imported
    // gender model (LiteRT = the new name of TensorFlow Lite), and Task.await() for ML Kit.
    implementation(libs.mlkit.face.detection)
    implementation(libs.litert)
    implementation(libs.kotlinx.coroutines.play.services)

    // Home-screen widget (Continue watching + now-playing controls).
    implementation(libs.androidx.glance.appwidget)

    implementation(libs.newpipe.extractor)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
