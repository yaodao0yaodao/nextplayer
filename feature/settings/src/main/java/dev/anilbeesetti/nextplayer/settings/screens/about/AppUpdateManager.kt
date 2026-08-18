package dev.anilbeesetti.nextplayer.settings.screens.about

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val LATEST_RELEASE_API = "https://api.github.com/repos/yaodao0yaodao/nextplayer/releases/latest"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

internal data class AppRelease(
    val versionName: String,
    val apkUrl: String,
    val apkFileName: String,
    val releaseNotes: String,
)

internal data class PendingAppDownload(
    val id: Long,
    val file: File,
)

internal sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val release: AppRelease) : UpdateCheckResult
    data class Failed(val cause: Throwable) : UpdateCheckResult
}

internal object AppUpdateManager {

    suspend fun check(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "NextPlayer-Personal-Updater")
                check(connection.responseCode in 200..299) {
                    "GitHub returned HTTP ${connection.responseCode}"
                }

                val releaseJson = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
                    .jsonObject
                val versionName = releaseJson.getValue("tag_name").jsonPrimitive.content.removePrefix("v")
                val asset = releaseJson.getValue("assets")
                    .jsonArray
                    .map { it.jsonObject }
                    .firstOrNull { assetJson ->
                        val name = assetJson.getValue("name").jsonPrimitive.content
                        name.endsWith(".apk", ignoreCase = true) &&
                            (name.contains("arm64-v8a", ignoreCase = true) || name.contains("arm64", ignoreCase = true))
                    }
                    ?: error("The release does not contain an ARM64 APK")
                val installedVersion = context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName
                    .orEmpty()

                if (!isVersionNewer(versionName, installedVersion)) {
                    UpdateCheckResult.UpToDate
                } else {
                    UpdateCheckResult.Available(
                        AppRelease(
                            versionName = versionName,
                            apkUrl = asset.getValue("browser_download_url").jsonPrimitive.content,
                            apkFileName = asset.getValue("name").jsonPrimitive.content,
                            releaseNotes = releaseJson["body"]?.jsonPrimitive?.content.orEmpty(),
                        ),
                    )
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse(UpdateCheckResult::Failed)
    }

    fun download(context: Context, release: AppRelease): PendingAppDownload {
        val downloadsDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("External downloads directory is unavailable")
        val destination = File(downloadsDirectory, release.apkFileName.substringAfterLast('/'))
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle(release.apkFileName)
            .setDescription("NextPlayer ${release.versionName}")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationUri(Uri.fromFile(destination))
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        return PendingAppDownload(id, destination)
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    fun installPermissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )
}

internal fun isVersionNewer(candidate: String, installed: String): Boolean {
    val candidateParts = candidate.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val installedParts = installed.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    repeat(maxOf(candidateParts.size, installedParts.size)) { index ->
        val candidatePart = candidateParts.getOrElse(index) { 0 }
        val installedPart = installedParts.getOrElse(index) { 0 }
        if (candidatePart != installedPart) return candidatePart > installedPart
    }
    return false
}
