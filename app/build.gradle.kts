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
// Versioning / release numbers / build numbers
// ---------------------------------------------------------------------------
// Scheme (single source of truth: <root>/version.properties):
//
//     versionName = VERSION_MAJOR.VERSION_MINOR.<release number>
//     versionCode = build number = GitHub Actions run number
//
// The patch component IS the sequential release number, so every GitHub
// Release maps 1:1 to a version: release #2 -> 1.0.2, release #3 -> 1.0.3 …
// The number is computed by scripts/version.sh from the published vX.Y.Z tags
// and handed to Gradle by CI. Locally the fallback is
// "MAJOR.MINOR.<LAST_RELEASE>-dev".
//
// Override order: environment (CI) -> -P Gradle properties -> version.properties.
// See docs/03-CI-CD.md and docs/04-RELEASE.md.
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun versionProp(name: String, fallback: String): String =
    versionProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

fun buildSetting(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(propName) as String?)?.takeIf { it.isNotBlank() }

val versionMajor = versionProp("VERSION_MAJOR", "1")
val versionMinor = versionProp("VERSION_MINOR", "0")
val lastRelease = versionProp("LAST_RELEASE", "1")

val appVersionCode = buildSetting("APP_VERSION_CODE", "APP_VERSION_CODE")?.toIntOrNull() ?: 1
val appVersionName = (
    buildSetting("APP_VERSION_NAME", "APP_VERSION_NAME")
        ?: "$versionMajor.$versionMinor.$lastRelease-dev"
    ).removePrefix("v")

// Sequential release number; defaults to the patch component of the version.
val appReleaseNumber = buildSetting("APP_RELEASE_NUMBER", "APP_RELEASE_NUMBER")?.toIntOrNull()
    ?: appVersionName.substringBefore('-').substringAfterLast('.').toIntOrNull()
    ?: lastRelease.toIntOrNull()
    ?: 0

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
        buildConfigField("int", "RELEASE_NUMBER", "$appReleaseNumber")
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

// Prints the resolved version triple (used by CI logs and the release
// checklist):  ./gradlew -q :app:printVersion
tasks.register("printVersion") {
    group = "help"
    description = "Prints versionName / release number / build number of this build."
    val name = appVersionName
    val release = appReleaseNumber
    val code = appVersionCode
    doLast {
        println("versionName=$name")
        println("releaseNumber=$release")
        println("versionCode=$code")
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
