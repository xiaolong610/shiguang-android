package app.shiguang.mobile

import org.junit.Assert.*
import org.junit.Test

class NarrationLanguageTest {
    @Test fun contentLanguageDoesNotDependOnInterface(){
        assertEquals("en",NarrationLanguage.code("Chapter 1. The lighthouse stood beside the sea."))
        assertEquals("zh",NarrationLanguage.code("第一章 灯塔。海边的邮局在下午四点关门。"))
        assertEquals("en",NarrationLanguage.code("A long English passage with a short Chinese title 中文."))
        assertEquals("zh",NarrationLanguage.code("中文正文中偶尔出现 English 缩写不应影响整章朗读。"))
    }
    @Test fun legacyRefreshErrorsAreRecognizedAfterLanguageSwitch(){
        assertTrue(AppLanguage.refreshFailed("刷新失败：请求超时"))
        assertTrue(AppLanguage.refreshFailed("Refresh failed: Request timed out"))
        assertFalse(AppLanguage.refreshFailed("No updates"))
    }
}
