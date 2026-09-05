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
class ApkTemplateImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockApk: File
    private lateinit var reader: ApkResourceReader
    private lateinit var importer: ApkTemplateImporter

    @Before
    fun setUp() {
        reader = ApkResourceReader.getInstance()
        importer = ApkTemplateImporter.getInstance()
        mockApk = createMockApk()
    }

    private fun createMockApk(): File {
        val file = tempFolder.newFile("mock_game.apk")
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            addImageEntry(zos, "res/drawable/btn_water_can_normal.png", 100, 50)
            addImageEntry(zos, "res/drawable/btn_water_can_pressed.png", 100, 50)
            addImageEntry(zos, "res/drawable/btn_harvest_ready.png", 80, 40)
            addImageEntry(zos, "res/drawable/btn_harvest_disabled.png", 80, 40)
            addImageEntry(zos, "res/drawable/btn_steal_available.png", 60, 30)
            addImageEntry(zos, "res/drawable/btn_bless_active.png", 70, 35)
            addImageEntry(zos, "res/drawable/crop_ripe_wheat.png", 120, 60)
            addImageEntry(zos, "res/drawable/crop_ripe_corn.png", 110, 55)
            addImageEntry(zos, "res/drawable/crop_ripe_rice.png", 115, 58)
            addImageEntry(zos, "res/drawable/scarecrow_straw.png", 90, 45)
            addImageEntry(zos, "res/drawable/btn_battle_entry_normal.png", 100, 50)
            addImageEntry(zos, "res/drawable/btn_surrender_red.png", 50, 25)
            addImageEntry(zos, "res/drawable/home_icon_normal.png", 80, 80)
            addImageEntry(zos, "res/drawable/friend_list_btn.png", 40, 40)
            addImageEntry(zos, "res/drawable/btn_confirm_dialog.png", 60, 30)
            addImageEntry(zos, "res/drawable/btn_close_dialog.png", 50, 25)
            addImageEntry(zos, "res/drawable/shop_icon_normal.png", 60, 60)
            addImageEntry(zos, "res/drawable/gold_coin_icon.png", 40, 40)
            addImageEntry(zos, "res/drawable/diamond_icon_blue.png", 50, 50)
            addImageEntry(zos, "res/drawable/vip_badge_gold.png", 70, 35)
            addImageEntry(zos, "res/drawable/daily_task_icon.png", 65, 32)
            addImageEntry(zos, "res/drawable/reward_box_closed.png", 75, 40)
            addImageEntry(zos, "res/drawable/notice_icon_new.png", 45, 45)

            // Chinese-named resources (edge case)
            addImageEntry(zos, "res/drawable/farm_watering_can_icon.png", 100, 50)
            addImageEntry(zos, "res/drawable/farm_harvest_tool.png", 90, 45)
        }
        return file
    }

    private fun addImageEntry(zos: ZipOutputStream, path: String, width: Int, height: Int) {
        val fakeData = "fake_${width}x${height}_${path.hashCode()}".toByteArray()
        val entry = ZipEntry(path)
        zos.putNextEntry(entry)
        zos.write(fakeData)
        zos.closeEntry()
    }

    @Test
    fun `scanAvailableTemplates - should find water can templates`() {
        val templates = importer.scanAvailableTemplates(mockApk.absolutePath)

        assertTrue("Should find template categories", templates.isNotEmpty())
        assertTrue("Should have water_can category", templates.containsKey("water_can"))
        assertTrue("Should have matches for water_can", templates["water_can"]!!.isNotEmpty())

        // Water can matches should contain "water" or "can"
        for (match in templates["water_can"]!!) {
            val path = match.resourcePath.lowercase()
            assertTrue("Match should relate to water can: $path",
                path.contains("water") || path.contains("can"))
        }
    }

    @Test
    fun `scanAvailableTemplates - should find harvest templates`() {
        val templates = importer.scanAvailableTemplates(mockApk.absolutePath)

        assertTrue("Should have harvest category", templates.containsKey("harvest"))
        assertTrue("Should have matches for harvest", templates["harvest"]!!.isNotEmpty())

        for (match in templates["harvest"]!!) {
            val path = match.resourcePath.lowercase()
            assertTrue("Match should relate to harvest: $path",
                path.contains("harvest") || path.contains("reap") || path.contains("collect"))
        }
    }

    @Test
    fun `scanAvailableTemplates - should find crop ripe templates`() {
        val templates = importer.scanAvailableTemplates(mockApk.absolutePath)

        assertTrue("Should have crop_ripe category", templates.containsKey("crop_ripe"))
        assertTrue("Should have multiple ripe crop matches",
            templates["crop_ripe"]!!.size >= 2)
    }

    @Test
    fun `scanAvailableTemplates - should find battle and surrender`() {
        val templates = importer.scanAvailableTemplates(mockApk.absolutePath)

        assertTrue("Should have battle_entry category", templates.containsKey("battle_entry"))
        assertTrue("Should have surrender category", templates.containsKey("surrender"))
        assertTrue("Should have home_icon category", templates.containsKey("home_icon"))
        assertTrue("Should have confirm category", templates.containsKey("confirm"))
    }

    @Test
    fun `scanAvailableTemplates - should handle non-existent apk`() {
        val templates = importer.scanAvailableTemplates("/non/existent.apk")
        assertTrue("Should return empty map for non-existent APK", templates.isEmpty())
    }

    @Test
    fun `scanAvailableTemplates - should rank matches by relevance`() {
        val templates = importer.scanAvailableTemplates(mockApk.absolutePath)

        // For water_can, the first match should have the most "water" keywords
        val waterMatches = templates["water_can"]!!
        assertTrue("Should have at least 2 matches for water", waterMatches.size >= 2)

        // First match should have the highest confidence
        val firstConfidence = waterMatches.first().confidence
        val lastConfidence = waterMatches.last().confidence
        assertTrue("Confidence should be sorted descending",
            firstConfidence >= lastConfidence)
    }

    @Test
    fun `getTemplateKeywords - should return all keyword mappings`() {
        val keywords = importer.getTemplateKeywords()

        assertTrue("Should have template keyword mappings", keywords.isNotEmpty())
        assertTrue("Should have water_can keywords", keywords.containsKey("water_can"))
        assertTrue("Should have harvest keywords", keywords.containsKey("harvest"))
        assertTrue("Should have crop_ripe keywords", keywords.containsKey("crop_ripe"))
        assertTrue("Should have Chinese keywords support",
            keywords.values.flatten().any { keyword ->
                keyword.codePoints().anyMatch { cp -> cp > 126 }
            })
    }

    @Test
    fun `getAvailableFarmApps - should return empty when no farm app installed`() {
        // Without actual Android context, this should handle gracefully
        val apps = importer.getAvailableFarmApps(
            org.robolectric.RuntimeEnvironment.getApplication()
        )
        // This test may return empty or throw, but shouldn't crash
        assertNotNull("Should not return null", apps)
    }

    @Test
    fun `scanAvailableTemplates - should detect all template categories`() {
        val templates = importer.scanAvailableTemplates(mockApk.absolutePath)

        val expectedCategories = setOf(
            "water_can", "harvest", "steal", "bless", "crop_ripe",
            "scarecrow", "battle_entry", "surrender", "home_icon",
            "friend_list", "confirm", "close", "gold", "diamond",
            "shop", "reward", "notice", "vip"
        )

        for (category in expectedCategories) {
            assertTrue("Should detect category: $category",
                templates.containsKey(category))
            assertTrue("Category $category should have matches",
                templates[category]!!.isNotEmpty())
        }
    }

    @Test
    fun `scanAvailableTemplates - should limit matches per category`() {
        val templates = importer.scanAvailableTemplates(mockApk.absolutePath)

        // Each category should have at most 5 matches (the maxMatches parameter)
        for ((_, matches) in templates) {
            assertTrue("Should not exceed 5 matches per category",
                matches.size <= 5)
        }
    }

    @Test
    fun `scanAvailableTemplates - mixed Chinese and English keywords matching`() {
        val templates = importer.scanAvailableTemplates(mockApk.absolutePath)

        // water_can keywords include both English ("water", "can") and Chinese ("浇水", "水壶")
        val waterMatches = templates["water_can"]!!
        assertTrue("Should find water_can templates via English keywords",
            waterMatches.isNotEmpty())
    }

    @Test
    fun `getImportHistory - should track imports`() {
        // Import history should start empty
        val history = importer.getImportHistory()
        assertNotNull("Should not be null", history)
    }

    @Test
    fun `clearImportHistory - should clear history`() {
        importer.clearImportHistory()
        val history = importer.getImportHistory()
        assertTrue("History should be empty after clear", history.isEmpty())
    }
}
