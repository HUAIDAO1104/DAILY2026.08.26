package com.pengxh.daily.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun `download sources prefer domestic mirrors and keep github fallback`() {
        val official =
            "https://github.com/HUAIDAO1104/DAILY2026.08.26/releases/download/v2.5.4/DailyTask-v2.5.4.apk"

        val sources = AppUpdateManager.buildDownloadSources(official)

        assertEquals(3, sources.size)
        assertTrue(sources[0].url.startsWith("https://ghfast.top/https://github.com/"))
        assertTrue(sources[1].url.startsWith("https://gh-proxy.com/https://github.com/"))
        assertEquals(official, sources[2].url)
        assertEquals("国内加速线路 1", sources[0].name)
        assertEquals("GitHub 备用线路", sources[2].name)
    }
}
