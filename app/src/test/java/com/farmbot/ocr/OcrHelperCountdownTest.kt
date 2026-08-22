package com.farmbot.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrHelperCountdownTest {

    @Test fun `parse MM SS 格式`() {
        assertEquals(150, OcrHelper.parseCountdown("2:30"))
        assertEquals(119, OcrHelper.parseCountdown("1:59"))
        assertEquals(0, OcrHelper.parseCountdown("0:00"))
        assertEquals(3599, OcrHelper.parseCountdown("59:59"))
    }

    @Test fun `parse HH MM SS 格式`() {
        assertEquals(3725, OcrHelper.parseCountdown("1:02:05"))
        assertEquals(7320, OcrHelper.parseCountdown("2:02:00"))
    }

    @Test fun `parse 中文混合`() {
        assertEquals(90, OcrHelper.parseCountdown("1分30秒"))
        assertEquals(3661, OcrHelper.parseCountdown("1小时1分1秒"))
        assertEquals(3600, OcrHelper.parseCountdown("1小时"))
        assertEquals(180, OcrHelper.parseCountdown("3分"))
        assertEquals(15, OcrHelper.parseCountdown("15秒"))
    }

    @Test fun `parse 纯数字 fallback`() {
        assertEquals(120, OcrHelper.parseCountdown("120"))
        assertEquals(0, OcrHelper.parseCountdown("0"))
    }

    @Test fun `parse 带单位后缀 s`() {
        assertEquals(120, OcrHelper.parseCountdown("120s"))
        assertEquals(99, OcrHelper.parseCountdown("99S"))
    }

    @Test fun `parse 垃圾字符串`() {
        assertEquals(-1, OcrHelper.parseCountdown(""))
        assertEquals(-1, OcrHelper.parseCountdown("成熟中..."))
        assertEquals(-1, OcrHelper.parseCountdown("!!!"))
    }
}
