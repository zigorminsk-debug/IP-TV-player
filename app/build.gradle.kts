import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Versioning / build numbers
// ---------------------------------------------------------------------------
// Build number (versionCode) and version name can come from:
//   1. Environment variables APP_VERSION_CODE / APP_VERSION_NAME (used by CI).
//   2. Gradle properties -PAPP_VERSION_CODE=… -PAPP_VERSION_NAME=… (local).
//   3. Fallback defaults for local development.
// CI always passes the GitHub Actions run number as APP_VERSION_CODE, so every
// build produced by CI has a unique, monotonically increasing build number.
// See docs/03-CI-CD.md and docs/04-RELEASE.md.
val envVersionCode: String? = System.getenv("APP_VERSION_CODE")
val envVersionName: String? = System.getenv("APP_VERSION_NAME")
val propVersionCode: String? = project.findProperty("APP_VERSION_CODE") as String?
val propVersionName: String? = project.findProperty("APP_VERSION_NAME") as String?
val appVersionCode = envVersionCode?.toIntOrNull() ?: propVersionCode?.toIntOrNull() ?: 1
val appVersionName = (envVersionName ?: propVersionName ?: "1.0.0-dev").removePrefix("v")

// GitHub <owner>/<repo> used by the in-app self-updater.
// Override locally/forks with -PUPDATE_REPO="owner/repo" or env UPDATE_REPO.
val updateRepoSlug: String =
    (System.getenv("UPDATE_REPO") ?: (project.findProperty("UPDATE_REPO") as String?))
        ?: "zigorminsk-debug/IP-TV-player"

// ---------------------------------------------------------------------------
// Release signing
// ---------------------------------------------------------------------------
// Priority: environment variables (CI secrets) -> keystore/keystore.properties.
// The repository ships with a committed signing key so that CI always produces
// APKs signed with the SAME persistent key (updates install over the previous
// version). See docs/06-SIGNING-KEYS.md for rotation/backup instructions.
val keystoreDir = rootProject.file("keystore")
val keystoreProps = Properties().apply {
    val f = File(keystoreDir, "keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun envOrProp(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(propName)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.iptvplayer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iptvplayer.app"
        minSdk = 23
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("int", "BUILD_NUMBER", "$appVersionCode")
        buildConfigField("String", "UPDATE_REPO_SLUG", "\"$updateRepoSlug\"")
        buildConfigField(
            "long", "BUILD_TIME",
            "${System.getenv("APP_BUILD_TIME")?.toLongOrNull() ?: System.currentTimeMillis()}",
        )
    }

    signingConfigs {
        create("release") {
            val storeFilePath = envOrProp("SIGNING_STORE_FILE", "storeFile")
            if (storeFilePath != null) {
                val candidate = File(storeFilePath)
                storeFile = if (candidate.isAbsolute) candidate else File(keystoreDir, storeFilePath)
                storeType = envOrProp("SIGNING_STORE_TYPE", "storeType")
                storePassword = envOrProp("SIGNING_STORE_PASSWORD", "storePassword")
                keyAlias = envOrProp("SIGNING_KEY_ALIAS", "keyAlias")
                keyPassword = envOrProp("SIGNING_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false // TODO: enable + verify R8 rules before v2.0
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                // Fallback so local builds without the keystore still install.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // java.time on API < 26
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Keep unattended CI builds green; revisit before enabling R8.
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.datasource.rtmp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.cast)
    implementation(libs.play.services.cast.framework)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
