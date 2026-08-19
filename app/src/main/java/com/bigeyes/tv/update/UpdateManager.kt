package com.bigeyes.tv.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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

    companion object {
        private const val TAG = "UpdateManager"
        const val GITHUB_RELEASE_API =
            "https://api.github.com/repos/CFM503/BigEyesTV/releases/latest"

        private var pendingApkFile: File? = null
        private var isWaitingForInstallPermission = false
        private var isLifecycleRegistered = false

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

    private fun ensureLifecycleObserver(activity: Activity) {
        if (!isLifecycleRegistered) {
            isLifecycleRegistered = true
            activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(act: Activity) {
                    checkAndResumePendingInstall(act)
                }
                override fun onActivityCreated(act: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(act: Activity) {}
                override fun onActivityPaused(act: Activity) {}
                override fun onActivityStopped(act: Activity) {}
                override fun onActivitySaveInstanceState(act: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(act: Activity) {}
            })
        }
    }

    fun checkAndResumePendingInstall(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val apk = pendingApkFile
            if (isWaitingForInstallPermission && apk != null && apk.exists()) {
                if (activity.packageManager.canRequestPackageInstalls()) {
                    Log.i(TAG, "Install permission granted on resume. Auto-continuing installation for: ${apk.name}")
                    isWaitingForInstallPermission = false
                    installApk(activity, apk)
                }
            }
        }
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
     * Downloads APK file with progress reporting and caching.
     */
    fun downloadApk(release: ReleaseInfo, listener: DownloadListener) {
        executor.execute {
            var apkFile: File? = null
            try {
                val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.cacheDir
                if (!downloadDir.exists()) downloadDir.mkdirs()

                apkFile = File(downloadDir, "BigEyesTV-${release.versionClean}.apk")

                // If cached complete file already exists, directly complete
                if (apkFile.exists() && release.apkSize > 0 && apkFile.length() == release.apkSize) {
                    Log.i(TAG, "Cached complete APK found (${apkFile.length()} bytes). Skipping download.")
                    val cachedFile = apkFile
                    postOnMain { listener.onDownloadComplete(cachedFile) }
                    return@execute
                }

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
            Toast.makeText(activity, "安装包文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        ensureLifecycleObserver(activity)

        // On Android 8.0+ (Oreo), check REQUEST_INSTALL_PACKAGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = activity.packageManager.canRequestPackageInstalls()
            if (!canInstall) {
                Log.w(TAG, "App cannot request package installs. Saving pending state and redirecting to settings...")
                pendingApkFile = apkFile
                isWaitingForInstallPermission = true
                Toast.makeText(activity, "请允许 BigEyes-TV 安装应用权限，返回后将自动继续安装", Toast.LENGTH_LONG).show()

                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                return
            }
        }

        // Permission granted or not required
        isWaitingForInstallPermission = false
        pendingApkFile = null

        try {
            val authority = "${activity.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(activity, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }

            // Grant read permission to all matched package installer activities
            val resInfoList = activity.packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val pkgName = resolveInfo.activityInfo.packageName
                activity.grantUriPermission(pkgName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(installIntent)
            Log.i(TAG, "Launched package installer for: ${apkFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(activity, "无法调起安装器: ${e.message}", Toast.LENGTH_LONG).show()
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
}
