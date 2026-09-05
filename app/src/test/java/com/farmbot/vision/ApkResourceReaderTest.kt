package com.farmbot.vision

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Robolectric 集成测试：首次运行需下载 android-all-instrumented JAR。
 */
@RunWith(RobolectricTestRunner::class)
class ApkResourceReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockApk: File
    private lateinit var reader: ApkResourceReader

    @Before
    fun setUp() {
        reader = ApkResourceReader.getInstance()
        mockApk = createMockApk()
    }

    private fun createMockApk(): File {
        val file = tempFolder.newFile("mock_game.apk")
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            addEntry(zos, "AndroidManifest.xml", "manifest_content".toByteArray())
            addEntry(zos, "resources.arsc", "arsc_content".toByteArray())

            // Drawable resources - these simulate game image assets
            addImageEntry(zos, "res/drawable/btn_water_can.png", 100, 50)
            addImageEntry(zos, "res/drawable/btn_harvest.png", 80, 40)
            addImageEntry(zos, "res/drawable/btn_steal.png", 60, 30)
            addImageEntry(zos, "res/drawable/btn_bless.png", 70, 35)
            addImageEntry(zos, "res/drawable/crop_ripe_wheat.png", 120, 60)
            addImageEntry(zos, "res/drawable/crop_ripe_rice.png", 110, 55)
            addImageEntry(zos, "res/drawable/scarecrow.png", 90, 45)
            addImageEntry(zos, "res/drawable/btn_battle_entry.png", 100, 50)
            addImageEntry(zos, "res/drawable/btn_surrender.png", 50, 25)
            addImageEntry(zos, "res/drawable/home_icon.png", 80, 80)
            addImageEntry(zos, "res/drawable/friend_list_icon.png", 40, 40)
            addImageEntry(zos, "res/drawable/btn_confirm.png", 60, 30)
            addImageEntry(zos, "res/drawable/btn_close.png", 50, 25)
            addImageEntry(zos, "res/drawable/shop_icon.png", 60, 60)
            addImageEntry(zos, "res/drawable/gold_coin.png", 40, 40)
            addImageEntry(zos, "res/drawable/diamond_icon.png", 50, 50)
            addImageEntry(zos, "res/drawable/vip_badge.png", 70, 35)

            // HDPI resources (alternative resolutions)
            addImageEntry(zos, "res/drawable-hdpi/btn_water_can.png", 150, 75)
            addImageEntry(zos, "res/drawable-hdpi/btn_harvest.png", 120, 60)
            addImageEntry(zos, "res/drawable-hdpi/crop_ripe_wheat.png", 180, 90)

            // Mipmap resources
            addImageEntry(zos, "res/mipmap/ic_launcher.png", 48, 48)
            addImageEntry(zos, "res/mipmap-hdpi/ic_launcher.png", 72, 72)

            // Non-image files in resource dirs
            addEntry(zos, "res/drawable/some_layout.xml", "<layout/>".toByteArray())
            addEntry(zos, "res/drawable/colors.xml", "<colors/>".toByteArray())

            // Assets directory
            addImageEntry(zos, "assets/loading_screen.png", 200, 100)
            addImageEntry(zos, "assets/background_main.jpg", 1920, 1080)

            // Other non-resource files
            addEntry(zos, "classes.dex", "dex_content".toByteArray())
            addEntry(zos, "lib/arm64/libnative.so", "native_lib".toByteArray())
        }
        return file
    }

    private fun addEntry(zos: ZipOutputStream, path: String, data: ByteArray) {
        val entry = ZipEntry(path)
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun addImageEntry(zos: ZipOutputStream, path: String, width: Int, height: Int) {
        // Create minimal valid PNG-like data (not actually valid PNG, but enough for testing)
        val fakePngData = "fake_png_data_${width}x${height}_${path.hashCode()}".toByteArray()
        addEntry(zos, path, fakePngData)
    }

    @Test
    fun `scanApk - should find all image resources`() {
        val summary = reader.scanApk(mockApk.absolutePath)

        assertNotNull("Scan summary should not be null", summary)
        assertEquals("APK path should match", mockApk.absolutePath, summary!!.apkPath)

        // Total entries includes non-image files too
        assertTrue("Should have total entries", summary.totalEntries > 0)

        // Should find image files in resource directories
        assertTrue("Should find image entries", summary.imageEntries > 0)

        // Should include images from all resource dirs
        val imagePaths = summary.imageEntriesList.map { it.path }
        assertTrue("Should include drawable images",
            imagePaths.any { it.startsWith("res/drawable/") && it.endsWith(".png") })
        assertTrue("Should include hdpi images",
            imagePaths.any { it.startsWith("res/drawable-hdpi/") })
        assertTrue("Should include mipmap images",
            imagePaths.any { it.startsWith("res/mipmap/") })
        assertTrue("Should include assets images",
            imagePaths.any { it.startsWith("assets/") })
    }

    @Test
    fun `scanApk - should exclude non-image and non-resource files`() {
        val summary = reader.scanApk(mockApk.absolutePath)
        assertNotNull(summary)

        val imagePaths = summary!!.imageEntriesList.map { it.path }

        // Non-image files in resource dirs should be excluded
        assertFalse("XML files should not be in image entries",
            imagePaths.any { it.endsWith(".xml") })

        // Non-resource files should be excluded
        assertFalse("classes.dex should not be in image entries",
            imagePaths.any { it == "classes.dex" })
        assertFalse("lib files should not be in image entries",
            imagePaths.any { it.startsWith("lib/") })
    }

    @Test
    fun `scanApk - should find specific template images`() {
        val summary = reader.scanApk(mockApk.absolutePath)
        assertNotNull(summary)

        val imageNames = summary!!.imageEntriesList.map { it.name }

        // Check that expected template images are found
        assertTrue("Should find water can button",
            imageNames.any { it.contains("water_can") })
        assertTrue("Should find harvest button",
            imageNames.any { it.contains("harvest") })
        assertTrue("Should find steal button",
            imageNames.any { it.contains("steal") })
        assertTrue("Should find scarecrow",
            imageNames.any { it.contains("scarecrow") })
        assertTrue("Should find battle entry",
            imageNames.any { it.contains("battle_entry") })
        assertTrue("Should find home icon",
            imageNames.any { it.contains("home_icon") })
    }

    @Test
    fun `scanApk - should handle non-existent file`() {
        val result = reader.scanApk("/non/existent/path.apk")
        assertNull("Should return null for non-existent file", result)
    }

    @Test
    fun `filterByKeyword - should filter correctly`() {
        val summary = reader.scanApk(mockApk.absolutePath)
        assertNotNull(summary)

        val entries = summary!!.imageEntriesList

        // Filter by "water" keyword
        val waterResults = reader.filterByKeyword(entries, "water")
        assertTrue("Should find water-related images", waterResults.isNotEmpty())
        assertTrue("All results should contain 'water'",
            waterResults.all { it.name.lowercase().contains("water") || it.path.lowercase().contains("water") })

        // Filter by "crop" keyword
        val cropResults = reader.filterByKeyword(entries, "crop")
        assertTrue("Should find crop-related images", cropResults.isNotEmpty())

        // Filter by non-existent keyword
        val emptyResults = reader.filterByKeyword(entries, "nonexistent_xyz_123")
        assertTrue("Should return empty for non-existent keyword", emptyResults.isEmpty())
    }

    @Test
    fun `filterByExtension - should filter correctly`() {
        val summary = reader.scanApk(mockApk.absolutePath)
        assertNotNull(summary)

        val entries = summary!!.imageEntriesList

        val pngResults = reader.filterByExtension(entries, "png")
        assertTrue("Should find PNG files", pngResults.isNotEmpty())

        val jpgResults = reader.filterByExtension(entries, "jpg")
        assertTrue("Should find JPG files", jpgResults.isNotEmpty())

        val webpResults = reader.filterByExtension(entries, "webp")
        assertTrue("Should return empty for webp", webpResults.isEmpty())
    }

    @Test
    fun `extractResource - should extract specific resource`() {
        val entry = reader.extractResource(mockApk.absolutePath, "res/drawable/btn_water_can.png")

        assertNotNull("Should find resource by exact path", entry)
        assertEquals("Path should match", "res/drawable/btn_water_can.png", entry!!.path)
        assertTrue("Should have bytes", entry.bytes.isNotEmpty())
        assertTrue("Should have size", entry.size > 0)
    }

    @Test
    fun `extractResource - should return null for non-existent`() {
        val entry = reader.extractResource(mockApk.absolutePath, "res/drawable/nonexistent.png")
        assertNull("Should return null for non-existent resource", entry)
    }

    @Test
    fun `ResourceEntry - should parse correctly`() {
        val entry = reader.extractResource(mockApk.absolutePath, "res/drawable/btn_harvest.png")
        assertNotNull(entry)

        assertEquals("btn_harvest.png", entry!!.name)
        assertEquals("res/drawable/btn_harvest.png", entry.path)
        assertEquals("png", entry.extension)
        assertTrue("Is image", entry.isImage)
    }

    @Test
    fun `scanApk - should count resources correctly`() {
        val summary = reader.scanApk(mockApk.absolutePath)
        assertNotNull(summary)

        // We have specific number of image entries
        // Count: drawable(17) + hdpi(3) + mipmap(2) + assets(2) = 24 image files
        // But non-image XML files in drawable should be excluded
        val expectedImageCount = 24
        assertEquals("Should find correct number of images",
            expectedImageCount, summary!!.imageEntries)

        // Total entries includes everything
        assertTrue("Total entries should be more than images",
            summary.totalEntries > summary.imageEntries)
    }
}
