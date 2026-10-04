package com.iptvplayer.app.data.repo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.iptvplayer.app.BuildConfig
import com.iptvplayer.app.data.remote.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest

/**
 * In-app self-update via GitHub Releases.
 *
 * The CI pipeline publishes, together with every release APK, an
 * `app-update.json` manifest (see scripts/make_update_manifest.py). The app
 * downloads `https://github.com/<owner>/<repo>/releases/latest/download/app-update.json`,
 * compares build numbers (versionCode) and offers to install the update.
 */
class UpdateRepository(
    private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) {

    @Serializable
    data class UpdateManifest(
        val versionCode: Int = 0,
        val versionName: String = "",
        /** Sequential release number; equals the patch component of versionName. */
        val releaseNumber: Int = 0,
        val buildNumber: Int = 0,
        val apkUrl: String = "",
        val apkSize: Long = 0,
        val apkSha256: String? = null,
        val releaseNotes: String = "",
        val releaseDate: String = "",
        val minSdk: Int = 0,
    )

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data class Available(
            val manifest: UpdateManifest,
            val currentVersionName: String,
            val currentVersionCode: Int,
            val currentReleaseNumber: Int,
        ) : UpdateState()

        data object UpToDate : UpdateState()
        data class Downloading(
            val manifest: UpdateManifest,
            val progress: Float,
            val receivedBytes: Long,
            val totalBytes: Long?,
        ) : UpdateState()

        data class NeedInstallPermission(val manifest: UpdateManifest) : UpdateState()
        data class ReadyToInstall(val manifest: UpdateManifest, val file: File) : UpdateState()
        data class Failed(val message: String) : UpdateState()
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Self-update works only for release builds (debug has a different appId). */
    val supported: Boolean
        get() = context.packageName == "com.iptvplayer.app"

    /** Update channel: <owner>/<repo> on GitHub. */
    suspend fun updateRepoSlug(): String {
        settings.settings.first().updateRepoOverride.takeIf { it.isNotBlank() }
            ?.let { return it }
        return BuildConfig.UPDATE_REPO_SLUG
    }

    private suspend fun manifestUrl(): String =
        "https://github.com/${updateRepoSlug()}/releases/latest/download/app-update.json"

    fun currentVersionCode(): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (e: PackageManager.NameNotFoundException) {
        BuildConfig.BUILD_NUMBER
    }

    /** Sequential release number this build was produced from. */
    fun currentReleaseNumber(): Int = BuildConfig.RELEASE_NUMBER

    fun currentVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        BuildConfig.VERSION_NAME
    }

    /**
     * Checks GitHub for a newer version.
     * @param force when true the result is always reported (manual check).
     */
    suspend fun check(force: Boolean): UpdateState {
        if (!supported) return UpdateState.Idle
        if (!force) {
            val s = settings.settings.first()
            if (!s.autoUpdateApp) return _state.value
            if (System.currentTimeMillis() - s.lastUpdateCheck < CHECK_INTERVAL_MS) return _state.value
        }
        _state.value = UpdateState.Checking
        val result = try {
            val manifest = fetchManifest()
            settings.setLastUpdateCheck(System.currentTimeMillis())
            if (manifest == null || manifest.apkUrl.isBlank()) {
                UpdateState.UpToDate
            } else if (manifest.versionCode > currentVersionCode()) {
                UpdateState.Available(
                    manifest,
                    currentVersionName(),
                    currentVersionCode(),
                    currentReleaseNumber(),
                )
            } else {
                UpdateState.UpToDate
            }
        } catch (e: Exception) {
            if (force) UpdateState.Failed(e.message ?: e.javaClass.simpleName) else UpdateState.Idle
        }
        _state.value = result
        return result
    }

    /** Silent auto-check (called on app start). */
    suspend fun maybeAutoCheck() {
        check(force = false)
    }

    private suspend fun fetchManifest(): UpdateManifest? {
        val primary = runCatching {
            Http.getString(http, manifestUrl())
        }.getOrNull()
        if (!primary.isNullOrBlank()) {
            runCatching { return json.decodeFromString(UpdateManifest.serializer(), primary) }
        }
        // Fallback: GitHub API latest release -> asset link.
        val api = runCatching {
            Http.getString(
                http,
                "https://api.github.com/repos/${updateRepoSlug()}/releases/latest",
            )
        }.getOrNull() ?: return null
        val element = runCatching { json.parseToJsonElement(api) }.getOrNull() ?: return null
        val assets = (element as? kotlinx.serialization.json.JsonObject)
            ?.get("assets") as? kotlinx.serialization.json.JsonArray ?: return null
        for (asset in assets) {
            val obj = asset as? kotlinx.serialization.json.JsonObject ?: continue
            val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (name == "app-update.json") {
                val url = (obj["browser_download_url"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content ?: continue
                val body = runCatching { Http.getString(http, url) }.getOrNull() ?: continue
                return runCatching {
                    json.decodeFromString(UpdateManifest.serializer(), body)
                }.getOrNull()
            }
        }
        return null
    }

    /** Downloads the APK (with progress) and verifies its checksum. */
    suspend fun download(manifest: UpdateManifest): UpdateState {
        val target = File(
            File(context.cacheDir, "updates"),
            "iptv-player-${manifest.versionCode}.apk",
        )
        if (target.exists() && target.length() > 0) {
            if (matchesChecksum(target, manifest)) {
                val ready = UpdateState.ReadyToInstall(manifest, target)
                _state.value = ready
                return ready
            }
            target.delete()
        }
        _state.value = UpdateState.Downloading(manifest, 0f, 0, null)
        return try {
            Http.downloadToFile(
                http,
                manifest.apkUrl,
                target,
            ) { read, total ->
                _state.value = UpdateState.Downloading(
                    manifest,
                    if (total != null && total > 0) read.toFloat() / total else 0f,
                    read,
                    total,
                )
            }
            if (!matchesChecksum(target, manifest)) {
                target.delete()
                UpdateState.Failed("Checksum mismatch — download corrupted").also { _state.value = it }
            } else if (target.length() != manifest.apkSize && manifest.apkSize > 0) {
                target.delete()
                UpdateState.Failed("Size mismatch — download corrupted").also { _state.value = it }
            } else {
                UpdateState.ReadyToInstall(manifest, target).also { _state.value = it }
            }
        } catch (e: Exception) {
            target.delete()
            UpdateState.Failed(e.message ?: e.javaClass.simpleName).also { _state.value = it }
        }
    }

    private fun matchesChecksum(file: File, manifest: UpdateManifest): Boolean {
        val expected = manifest.apkSha256?.lowercase() ?: return true
        return sha256(file).equals(expected, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Starts the system installer for a downloaded APK. When the "install
     * unknown apps" permission is missing the state becomes
     * [UpdateState.NeedInstallPermission] and the UI should call
     * [openInstallPermissionSettings].
     */
    fun install(file: File, manifest: UpdateManifest) {
        try {
            if (Build.VERSION.SDK_INT >= 26 &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                _state.value = UpdateState.NeedInstallPermission(manifest)
                return
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Unable to start installer")
        }
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    fun reset() {
        if (_state.value is UpdateState.Failed || _state.value is UpdateState.UpToDate) {
            _state.value = UpdateState.Idle
        }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 12 * 3600 * 1000L
    }
}
