package com.zenith.glyphstudio.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.zenith.glyphstudio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Serializable
private data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class ReadyToInstall(val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** GitHub Releases self-updater: check → DownloadManager → signature check → system installer via FileProvider. */
class AppUpdater(context: Context) {
    private val ctx = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val dm = ctx.getSystemService(DownloadManager::class.java)

    private val apiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
    private val allowedHosts = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "GlyphStudio-Updater")
            }
            try {
                when (conn.responseCode) {
                    404 -> return@runCatching UpdateState.Error("No releases published yet")
                    !in 200..299 -> return@runCatching UpdateState.Error("GitHub API HTTP ${conn.responseCode}")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val rel = json.decodeFromString<GhRelease>(body)
                if (rel.draft || rel.prerelease) return@runCatching UpdateState.UpToDate
                val remote = rel.tag.removePrefix("v").removePrefix("V")
                if (compareVersions(remote, BuildConfig.VERSION_NAME) <= 0) return@runCatching UpdateState.UpToDate
                val apk = rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: return@runCatching UpdateState.Error("Release $remote has no .apk asset")
                val host = Uri.parse(apk.url).host
                if (!apk.url.startsWith("https://") || host !in allowedHosts)
                    return@runCatching UpdateState.Error("Untrusted download host: $host")
                UpdateState.Available(UpdateInfo(remote, rel.body.orEmpty().take(2000), apk.url, sanitizeName(apk.name), apk.size))
            } finally { conn.disconnect() }
        }.getOrElse { UpdateState.Error(it.message ?: "Update check failed") }
    }

    /** Enqueues the APK in DownloadManager and emits progress until done. */
    fun download(info: UpdateInfo): Flow<UpdateState> = flow {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the newest download
        val target = File(dir, info.apkName)
        val req = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Glyph Studio ${info.version}")
            .setDescription("Downloading update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
        val id = dm.enqueue(req)
        emit(UpdateState.Downloading(0f))
        while (true) {
            delay(400)
            val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: break
            c.use {
                if (!it.moveToFirst()) { emit(UpdateState.Error("Download cancelled")); return@flow }
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val verdict = verifyApk(target)
                        emit(if (verdict == null) UpdateState.ReadyToInstall(target) else { target.delete(); UpdateState.Error(verdict) })
                        return@flow
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        emit(UpdateState.Error("Download failed ($reason)")); return@flow
                    }
                    else -> emit(UpdateState.Downloading(if (total > 0) done.toFloat() / total else 0f))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Returns null if valid; otherwise an error. Requires same package name + same signing certificate. */
    private fun verifyApk(file: File): String? {
        if (!file.exists() || file.length() == 0L) return "Downloaded file missing"
        val pm = ctx.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return "Not a valid APK"
        if (archive.packageName != ctx.packageName) return "APK package mismatch"
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        fun digests(si: android.content.pm.SigningInfo?) =
            si?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList() }?.toSet().orEmpty()
        val a = digests(archive.signingInfo); val b = digests(installed.signingInfo)
        if (a.isEmpty() || a != b) return "Signature mismatch — refusing to install"
        if (archive.longVersionCode <= installed.longVersionCode) return "Downloaded APK is not newer (versionCode)"
        return null
    }

    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"

        private fun sanitizeName(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "update.apk" }

        /** Numeric semver compare: "1.10.0" > "1.9.3". Non-numeric suffixes ignored. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val pb = b.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
