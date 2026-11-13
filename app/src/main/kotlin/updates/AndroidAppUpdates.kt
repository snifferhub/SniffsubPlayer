package com.sniffsub.sniff_sub_player.updates

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val UPDATE_FILE_PATH = "updates/update.apk"

internal fun updateApkFile(context: Context): File = File(
    requireNotNull(context.getExternalFilesDir(null)) { "更新下载目录不可用" }, UPDATE_FILE_PATH,
)

class SharedPreferencesAppUpdateStore(context: Context) : PendingAppUpdateStore {
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    override fun read(): PendingAppUpdate? {
        val json = preferences.getString("pending", null) ?: return null
        return runCatching { Json.decodeFromString<PendingAppUpdate>(json) }.getOrElse { clear(); null }
    }
    override fun write(update: PendingAppUpdate) {
        preferences.edit().putString("pending", Json.encodeToString(update)).apply()
    }
    override fun clear() {
        preferences.edit().remove("pending").apply()
    }
}

class AndroidAppUpdateDownloader(private val context: Context) : AppUpdateDownloader {
    private val manager = context.getSystemService(DownloadManager::class.java)
    override suspend fun enqueue(release: AppRelease): Long = withContext(Dispatchers.IO) {
        val file = updateApkFile(context)
        if (file.exists()) check(file.delete()) { "无法清理旧更新文件" }
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) { "无法创建更新目录" }
        manager.enqueue(
            DownloadManager.Request(Uri.parse(release.downloadUrl))
                .setTitle("SniffSubPlayer 更新 ${release.displayVersion}")
                .setDescription("下载完成后返回应用安装")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, null, UPDATE_FILE_PATH),
        )
    }

    override suspend fun query(id: Long): UpdateDownloadSnapshot = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext UpdateDownloadSnapshot(UpdateDownloadStatus.FAILED)
            val status = when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadStatus.COMPLETE
                DownloadManager.STATUS_FAILED -> UpdateDownloadStatus.FAILED
                DownloadManager.STATUS_RUNNING -> UpdateDownloadStatus.RUNNING
                else -> UpdateDownloadStatus.WAITING
            }
            UpdateDownloadSnapshot(
                status,
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    override suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        manager.remove(id)
        updateApkFile(context).delete()
        Unit
    }
}

internal fun verifyUpdateDigest(file: File, release: AppRelease) {
    check(file.isFile && file.length() == release.size) { "更新包大小不匹配，请重新下载" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    val expected = release.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    check(MessageDigest.isEqual(digest.digest(), expected)) { "更新包校验失败，请重新下载" }
}

class AndroidAppUpdateVerifier(private val context: Context) : AppUpdateVerifier {
    override suspend fun verify(release: AppRelease) = withContext(Dispatchers.IO) {
        val file = updateApkFile(context)
        verifyUpdateDigest(file, release)
        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("无法读取更新包，请重新下载")
        val installed = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(archive.packageName == context.packageName) { "更新包不属于本应用" }
        check(archive.versionName == release.version && archive.longVersionCode == release.versionCode &&
            archive.longVersionCode > installed.longVersionCode) { "更新包版本不匹配或不是新版本" }
        val installedSignatures = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val updateSignatures = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        check(!installedSignatures.isNullOrEmpty() && installedSignatures == updateSignatures) {
            "更新包签名与当前应用不同，请使用正式签名版本"
        }
    }
}

internal fun updateInstallIntent(context: Context): Intent {
    val file = updateApkFile(context)
    check(file.isFile) { "更新包已被清理，请重新下载" }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = android.content.ClipData.newRawUri("更新 APK", uri) }
}
