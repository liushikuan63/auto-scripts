package com.farmbot.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextRecognizerResultTest {

    @Test
    fun getTextsInRegionReturnsOnlyIntersectingLines() {
        val result = TextRecognizer.OcrResult(
            listOf(
                block(
                    line("inside", Rect(10, 10, 80, 40)),
                    line("outside", Rect(120, 10, 200, 40))
                )
            )
        )

        assertEquals(listOf("inside"), result.getTextsInRegion(Rect(0, 0, 100, 100)))
    }

    @Test
    fun getTextsInRegionFallsBackToElementBounds() {
        val element = TextRecognizer.TextElement("target", 0.9f, Rect(20, 20, 60, 50))
        val line = TextRecognizer.TextLine("target line", listOf(element), null)
        val result = TextRecognizer.OcrResult(listOf(block(line)))

        assertEquals(listOf("target line"), result.getTextsInRegion(Rect(0, 0, 80, 80)))
        assertEquals(emptyList<String>(), result.getTextsInRegion(Rect(100, 100, 200, 200)))
    }

    private fun block(vararg lines: TextRecognizer.TextLine) = TextRecognizer.TextBlock(
        text = lines.joinToString("\n") { it.text },
        lines = lines.toList(),
        boundingBox = null
    )

    private fun line(text: String, bounds: Rect) = TextRecognizer.TextLine(
        text = text,
        elements = listOf(TextRecognizer.TextElement(text, 0.9f, bounds)),
        boundingBox = bounds
    )
}
