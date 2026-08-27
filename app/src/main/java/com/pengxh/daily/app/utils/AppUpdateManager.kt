package com.pengxh.daily.app.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.pengxh.daily.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val version: String,
    val title: String,
    val notes: String,
    val downloadUrl: String
)

data class UpdateDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val sourceName: String,
    val sourceIndex: Int,
    val sourceCount: Int
) {
    val percent: Int
        get() = if (totalBytes > 0L) {
            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            -1
        }
}

internal data class UpdateDownloadSource(
    val name: String,
    val url: String
)

sealed class UpdateCheckResult {
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data object NoPublishedRelease : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {
    private const val latestReleaseUrl =
        "https://api.github.com/repos/HUAIDAO1104/DAILY2026.08.26/releases/latest"
    private const val checkIntervalMs = 24 * 60 * 60 * 1000L
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.MINUTES)
        .build()

    @Volatile
    private var pendingApk: File? = null

    suspend fun check(context: Context, force: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - preferences.getLong("last_check", 0L) < checkIntervalMs) {
            return@withContext UpdateCheckResult.UpToDate
        }
        preferences.edit().putLong("last_check", now).apply()

        runCatching {
            val request = Request.Builder()
                .url(latestReleaseUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DailyTask/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext UpdateCheckResult.NoPublishedRelease
                val raw = response.body.string()
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val root = JsonParser.parseString(raw).asJsonObject
                val version = root.get("tag_name")?.asString.orEmpty().trimStart('v', 'V')
                val apkAsset = root.getAsJsonArray("assets")
                    ?.map { it.asJsonObject }
                    ?.firstOrNull { it.get("name")?.asString?.endsWith(".apk", true) == true }
                    ?: error("最新版本没有 APK 安装包")
                if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) {
                    UpdateCheckResult.UpToDate
                } else {
                    UpdateCheckResult.Available(
                        AppUpdateInfo(
                            version = version,
                            title = root.get("name")?.asString.orEmpty().ifBlank { "DailyTask $version" },
                            notes = root.get("body")?.asString.orEmpty().take(1200),
                            downloadUrl = apkAsset.get("browser_download_url").asString
                        )
                    )
                }
            }
        }.getOrElse { UpdateCheckResult.Error(it.message ?: "更新检查失败") }
    }

    suspend fun download(
        context: Context,
        info: AppUpdateInfo,
        onProgress: (UpdateDownloadProgress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        ConfigSnapshotManager.create(context, "更新到 ${info.version} 前自动备份")
        val directory = File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "DailyTask-${info.version}.apk")
        val temp = File(directory, "DailyTask-${info.version}.download")
        val sources = buildDownloadSources(info.downloadUrl)
        var lastFailure: Throwable? = null

        sources.forEachIndexed { index, source ->
            temp.delete()
            target.delete()
            onProgress(UpdateDownloadProgress(0L, -1L, source.name, index + 1, sources.size))
            try {
                downloadFromSource(source, temp, index, sources.size, onProgress)
                check(temp.length() > 0L) { "下载的安装包为空" }
                check(temp.renameTo(target)) { "安装包保存失败" }
                check(isValidUpgrade(context, target)) { "安装包校验失败" }
                pendingApk = target
                return@withContext target
            } catch (failure: Throwable) {
                lastFailure = failure
                temp.delete()
                target.delete()
            }
        }

        error(
            "国内加速线路与备用线路均下载失败：${lastFailure?.message ?: "网络连接失败"}"
        )
    }

    private fun downloadFromSource(
        source: UpdateDownloadSource,
        temp: File,
        sourceIndex: Int,
        sourceCount: Int,
        onProgress: (UpdateDownloadProgress) -> Unit
    ) {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "DailyTask/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body
            val total = body.contentLength()
            var downloaded = 0L
            var lastUpdateAt = 0L
            body.byteStream().use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateAt >= 100L || (total > 0L && downloaded >= total)) {
                            lastUpdateAt = now
                            onProgress(
                                UpdateDownloadProgress(
                                    downloaded,
                                    total,
                                    source.name,
                                    sourceIndex + 1,
                                    sourceCount
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isValidUpgrade(context: Context, file: File): Boolean {
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            archiveInfo?.versionCode?.toLong() ?: 0L
        }
        return archiveInfo?.packageName == context.packageName &&
            archiveVersionCode > BuildConfig.VERSION_CODE
    }

    fun installOrRequestPermission(context: Context, apk: File? = pendingApk): Boolean {
        val file = apk?.takeIf { it.exists() } ?: return false
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        pendingApk = null
        return true
    }

    fun hasPendingInstall(): Boolean = pendingApk?.exists() == true

    internal fun buildDownloadSources(officialUrl: String): List<UpdateDownloadSource> {
        val normalized = officialUrl.trim()
        return listOf(
            UpdateDownloadSource("国内加速线路 1", "https://ghfast.top/$normalized"),
            UpdateDownloadSource("国内加速线路 2", "https://gh-proxy.com/$normalized"),
            UpdateDownloadSource("GitHub 备用线路", normalized)
        ).distinctBy { it.url }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split(Regex("[^0-9]+")).filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
        val b = right.split(Regex("[^0-9]+")).filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
            if (difference != 0) return difference
        }
        return 0
    }
}
