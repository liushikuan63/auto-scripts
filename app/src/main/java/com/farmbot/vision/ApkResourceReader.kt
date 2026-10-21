package com.farmbot.vision

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * APK 图片资源读取
 *
 * 扫描 APK 内的图片资源、按关键词或扩展名过滤、解码为 OpenCV Mat，
 * 也可读取设备上已安装应用的 APK 并列出其中的图片。
 */
class ApkResourceReader private constructor() {

    companion object {
        private const val TAG = "ApkResourceReader"

        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

        private val RESOURCE_DIR_PATTERNS = listOf(
            "res/drawable",
            "res/drawable-hdpi",
            "res/drawable-mdpi",
            "res/drawable-xhdpi",
            "res/drawable-xxhdpi",
            "res/drawable-xxxhdpi",
            "res/mipmap",
            "res/mipmap-hdpi",
            "res/mipmap-mdpi",
            "res/mipmap-xhdpi",
            "res/mipmap-xxhdpi",
            "res/mipmap-xxxhdpi",
            "assets"
        )

        private val FARM_APP_PACKAGE_NAMES = listOf(
            "com.tencent.tmgp.sgame",
            "com.tencent.tmgp.sgameoverseas",
            "com.sgame.tmgp.oversea"
        )

        @Volatile
        private var instance: ApkResourceReader? = null

        fun getInstance(): ApkResourceReader {
            return instance ?: synchronized(this) {
                instance ?: ApkResourceReader().also { instance = it }
            }
        }
    }

    data class ResourceEntry(
        val path: String,
        val name: String,
        val size: Long,
        val bytes: ByteArray
    ) {
        val extension: String
            get() = name.substringAfterLast('.', "").lowercase()

        val isImage: Boolean
            get() = extension in IMAGE_EXTENSIONS

        fun toMat(): Mat {
            return Imgcodecs.imdecode(MatOfByte(bytes), Imgcodecs.IMREAD_COLOR)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ResourceEntry) return false
            return path == other.path
        }

        override fun hashCode(): Int = path.hashCode()
    }

    data class ApkResourceSummary(
        val apkPath: String,
        val totalEntries: Int,
        val imageEntries: Int,
        val totalImageSizeBytes: Long,
        val imageEntriesList: List<ResourceEntry>
    )

    data class InstalledAppInfo(
        val packageName: String,
        val appName: String,
        val versionName: String,
        val versionCode: Long,
        val apkPath: String,
        val icon: Drawable?,
        val isFarmApp: Boolean
    )

    fun scanApk(apkPath: String): ApkResourceSummary? {
        val file = File(apkPath)
        if (!file.exists() || !file.canRead()) {
            Log.e(TAG, "APK file not found or not readable: $apkPath")
            return null
        }

        val allEntries = mutableListOf<ResourceEntry>()
        val imageEntries = mutableListOf<ResourceEntry>()

        try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        val bytes = zis.readBytes()

                        val resourceEntry = ResourceEntry(
                            path = name,
                            name = name.substringAfterLast('/'),
                            size = bytes.size.toLong(),
                            bytes = bytes
                        )

                        allEntries.add(resourceEntry)

                        if (isInResourceDir(name) && resourceEntry.isImage) {
                            imageEntries.add(resourceEntry)
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            val totalImageSize = imageEntries.sumOf { it.size }

            Log.i(TAG, "Scan complete: ${allEntries.size} entries, ${imageEntries.size} images found in $apkPath")

            return ApkResourceSummary(
                apkPath = apkPath,
                totalEntries = allEntries.size,
                imageEntries = imageEntries.size,
                totalImageSizeBytes = totalImageSize,
                imageEntriesList = imageEntries
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan APK: $apkPath", e)
            return null
        }
    }

    fun listImageResources(apkPath: String): List<ResourceEntry> {
        return scanApk(apkPath)?.imageEntriesList ?: emptyList()
    }

    fun extractResource(apkPath: String, resourcePath: String): ResourceEntry? {
        val file = File(apkPath)
        if (!file.exists() || !file.canRead()) {
            Log.e(TAG, "APK file not found: $apkPath")
            return null
        }

        try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == resourcePath && !entry.isDirectory) {
                        val bytes = zis.readBytes()
                        val name = entry.name.substringAfterLast('/')
                        return ResourceEntry(
                            path = entry.name,
                            name = name,
                            size = bytes.size.toLong(),
                            bytes = bytes
                        )
                    }
                    entry = zis.nextEntry
                }
            }
            Log.w(TAG, "Resource not found: $resourcePath in $apkPath")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract resource: $resourcePath from $apkPath", e)
            return null
        }
    }

    fun extractAndDecodeToMat(apkPath: String, resourcePath: String): Mat? {
        val entry = extractResource(apkPath, resourcePath) ?: return null
        return try {
            val mat = entry.toMat()
            if (mat.empty()) {
                Log.e(TAG, "Failed to decode image: $resourcePath")
                null
            } else {
                Log.d(TAG, "Decoded $resourcePath: ${mat.cols()}x${mat.rows()}")
                mat
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode Mat from: $resourcePath", e)
            null
        }
    }

    fun filterByKeyword(entries: List<ResourceEntry>, keyword: String): List<ResourceEntry> {
        val lowerKeyword = keyword.lowercase()
        return entries.filter {
            it.name.lowercase().contains(lowerKeyword) ||
            it.path.lowercase().contains(lowerKeyword)
        }
    }

    fun filterByExtension(entries: List<ResourceEntry>, extension: String): List<ResourceEntry> {
        val ext = extension.lowercase().trimStart('.')
        return entries.filter { it.extension == ext }
    }

    fun getInstalledFarmApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val apps = mutableListOf<InstalledAppInfo>()

        try {
            val packages: List<PackageInfo> = pm.getInstalledPackages(0)

            for (pkgInfo in packages) {
                val packageName = pkgInfo.packageName

                val isFarmApp = packageName in FARM_APP_PACKAGE_NAMES ||
                    packageName.contains("sgame") ||
                    packageName.contains("honor") ||
                    packageName.contains("king")

                if (!isFarmApp) continue

                try {
                    val appInfo: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(packageName)

                    apps.add(
                        InstalledAppInfo(
                            packageName = packageName,
                            appName = appName,
                            versionName = pkgInfo.versionName ?: "unknown",
                            versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                pkgInfo.longVersionCode
                            } else {
                                @Suppress("DEPRECATION")
                                pkgInfo.versionCode.toLong()
                            },
                            apkPath = appInfo.sourceDir ?: "",
                            icon = icon,
                            isFarmApp = true
                        )
                    )

                    Log.d(TAG, "Found farm app: $appName ($packageName) at ${appInfo.sourceDir}")
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping package $packageName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list installed apps", e)
        }

        Log.i(TAG, "Found ${apps.size} farm-related apps installed")
        return apps
    }

    fun getInstalledAppApkPath(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appInfo.sourceDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get APK path for $packageName", e)
            null
        }
    }

    fun scanInstalledApp(context: Context, packageName: String): ApkResourceSummary? {
        val apkPath = getInstalledAppApkPath(context, packageName)
        if (apkPath == null) {
            Log.e(TAG, "Cannot find APK path for $packageName")
            return null
        }
        return scanApk(apkPath)
    }

    fun listInstalledAppImages(context: Context, packageName: String): List<ResourceEntry> {
        return scanInstalledApp(context, packageName)?.imageEntriesList ?: emptyList()
    }

    fun extractResourceFromInstalledApp(
        context: Context,
        packageName: String,
        resourcePath: String
    ): ResourceEntry? {
        val apkPath = getInstalledAppApkPath(context, packageName) ?: return null
        return extractResource(apkPath, resourcePath)
    }

    private fun isInResourceDir(path: String): Boolean {
        val normalizedPath = path.replace('\\', '/')
        return RESOURCE_DIR_PATTERNS.any { prefix ->
            normalizedPath.startsWith("$prefix/")
        }
    }
}
