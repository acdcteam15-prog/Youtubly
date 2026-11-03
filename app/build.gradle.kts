import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------- version (same scheme as FamilySafeTube) ----------
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val appVersionName: String = versionProps.getProperty("versionName", "1.0.0")
val buildNumber: Int = (System.getenv("BUILD_NUMBER") ?: providers.gradleProperty("buildNumber").orNull)?.toIntOrNull() ?: 0
val appVersionCode: Int = versionProps.getProperty("versionCodeOffset", "100").toInt() + buildNumber
val updateRepo: String = System.getenv("GITHUB_REPOSITORY") ?: providers.gradleProperty("updateRepo").orNull ?: "CHANGE_ME/Remoty"

// ---------- release signing (CI secrets, or keystore.properties locally) ----------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)
val releaseStoreFile: File? = signingValue("KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.example.remoty"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.remoty"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        buildConfigField("String", "VERSION_LABEL", "\"$appVersionName (build $appVersionCode)\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        // One fixed key for every build (CI and local): without it each GitHub run signs with a new random debug key and
        // Android refuses to install a build over the previous one. A personal sideloaded app does not need more.
        create("remotyDebug") {
            storeFile = rootProject.file("signing/remoty-debug.keystore")
            storePassword = "remoty-debug"
            keyAlias = "remoty"
            keyPassword = "remoty-debug"
        }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("remotyDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseStoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("remotyDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES") }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// ZeroTier is embedded in the app through libzt. CI builds libzt-release.aar (.github/workflows/libzt.yml) and downloads it
// into the project; look for it anywhere under the project so it does not matter which sub-folder the download used.
val libzt: File? = fileTree(rootProject.projectDir) {
    include("**/libzt-release.aar")
    exclude("**/build/**", "**/.git/**", "**/.gradle/**")
}.files.firstOrNull()
if (libzt == null) {
    throw GradleException(
        "libzt-release.aar was not found anywhere under ${rootProject.projectDir}. Run the 'Build libzt' workflow and " +
            "download its artifact into app/libs/ (the Build workflow does this by itself)."
    )
}

dependencies {
    implementation(files(libzt))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
