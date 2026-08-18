package com.bigeyes.tv.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class ReleaseInfo(
    val tagName: String,
    val versionClean: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSize: Long
)

class UpdateManager(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface UpdateCheckListener {
        fun onUpdateAvailable(release: ReleaseInfo)
        fun onNoUpdateAvailable()
        fun onError(error: String)
    }

    interface DownloadListener {
        fun onProgress(percent: Int, downloadedBytes: Long, totalBytes: Long)
        fun onDownloadComplete(file: File)
        fun onDownloadError(error: String)
    }

    /**
     * Asynchronously checks GitHub Releases API for updates.
     */
    fun checkForUpdates(listener: UpdateCheckListener) {
        executor.execute {
            try {
                val url = URL(GITHUB_RELEASE_API)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "BigEyesTV-App")
                }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val msg = if (responseCode == 403) {
                        "GitHub API 请求受限 (HTTP 403)"
                    } else {
                        "检查更新失败: HTTP $responseCode"
                    }
                    postOnMain { listener.onError(msg) }
                    return@execute
                }

                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)

                val tagName = root.optString("tag_name", "")
                val releaseTitle = root.optString("name", tagName)
                val releaseNotes = root.optString("body", "")

                // Find APK asset
                val assets = root.optJSONArray("assets")
                var apkUrl: String? = null
                var apkName = "BigEyesTV-latest.apk"
                var apkSize = 0L

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", "")
                            apkName = name
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (apkUrl.isNullOrBlank()) {
                    Log.w(TAG, "No APK asset found in latest release: $tagName")
                    postOnMain { listener.onNoUpdateAvailable() }
                    return@execute
                }

                val currentVersion = getAppVersionName()
                val isNewer = isNewerVersion(currentVersion, tagName)

                Log.i(TAG, "Current app version: $currentVersion, Latest release tag: $tagName, isNewer: $isNewer")

                if (isNewer) {
                    val cleanVer = cleanVersionTag(tagName)
                    val release = ReleaseInfo(
                        tagName = tagName,
                        versionClean = cleanVer,
                        releaseTitle = releaseTitle,
                        releaseNotes = releaseNotes,
                        apkDownloadUrl = apkUrl,
                        apkFileName = apkName,
                        apkSize = apkSize
                    )
                    postOnMain { listener.onUpdateAvailable(release) }
                } else {
                    postOnMain { listener.onNoUpdateAvailable() }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Check update exception: ${e.message}", e)
                postOnMain { listener.onError("检查更新失败: ${e.message ?: "网络连接异常"}") }
            }
        }
    }

    /**
     * Downloads APK file with progress reporting.
     */
    fun downloadApk(release: ReleaseInfo, listener: DownloadListener) {
        executor.execute {
            var apkFile: File? = null
            try {
                val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.cacheDir
                if (!downloadDir.exists()) downloadDir.mkdirs()

                apkFile = File(downloadDir, "BigEyesTV-${release.versionClean}.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val url = URL(release.apkDownloadUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "BigEyesTV-App")
                }

                val totalLength = if (release.apkSize > 0) release.apkSize else conn.contentLength.toLong()

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var lastProgress = 0
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            if (totalLength > 0) {
                                val progress = ((downloaded * 100) / totalLength).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    postOnMain {
                                        listener.onProgress(progress, downloaded, totalLength)
                                    }
                                }
                            }
                        }
                    }
                }

                if (apkFile.exists() && apkFile.length() > 0) {
                    val finalFile = apkFile
                    postOnMain { listener.onDownloadComplete(finalFile) }
                } else {
                    postOnMain { listener.onDownloadError("下载文件为空或失败") }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download APK failed: ${e.message}", e)
                apkFile?.delete()
                postOnMain { listener.onDownloadError("下载失败: ${e.message}") }
            }
        }
    }

    /**
     * Checks unknown source install permission and launches system package installer.
     */
    fun installApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "Install failed: APK file does not exist or is empty")
            return
        }

        // On Android 8.0+ (Oreo), check REQUEST_INSTALL_PACKAGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = activity.packageManager.canRequestPackageInstalls()
            if (!canInstall) {
                Log.w(TAG, "App cannot request package installs. Redirecting to settings...")
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                return
            }
        }

        try {
            val authority = "${activity.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(activity, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(installIntent)
            Log.i(TAG, "Launched package installer for: ${apkFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
        }
    }

    fun getAppVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private fun postOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        const val GITHUB_RELEASE_API =
            "https://api.github.com/repos/CFM503/BigEyesTV/releases/latest"

        /**
         * Cleans version string (e.g., 'bigeyes-tv-v1.0.2' -> '1.0.2', 'v1.0.1' -> '1.0.1')
         */
        fun cleanVersionTag(tag: String): String {
            var v = tag.trim()
            if (v.contains("bigeyes-tv-", ignoreCase = true)) {
                v = v.substringAfter("bigeyes-tv-", "")
            }
            if (v.startsWith("v", ignoreCase = true)) {
                v = v.substring(1)
            }
            return v.trim()
        }

        /**
         * Compares current app version with remote release tag.
         * Returns true if remote version is strictly newer.
         */
        fun isNewerVersion(currentVer: String, remoteTag: String): Boolean {
            val cleanCurrent = cleanVersionTag(currentVer)
            val cleanRemote = cleanVersionTag(remoteTag)

            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
            val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(currentParts.size, remoteParts.size)
            for (i in 0 until maxLen) {
                val c = currentParts.getOrElse(i) { 0 }
                val r = remoteParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }
}
