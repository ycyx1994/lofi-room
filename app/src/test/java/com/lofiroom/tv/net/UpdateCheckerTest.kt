package com.lofiroom.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本号白名单 + 数值版本比较（UpdateChecker 纯逻辑部分） */
class UpdateCheckerTest {

    // ── parseVersion：版本号白名单（防劫持页/脏数据冒充版本号） ──

    @Test
    fun parseVersion_accepts_plain_versions() {
        assertEquals("1.8", UpdateChecker.parseVersion("1.8"))
        assertEquals("2.0.1", UpdateChecker.parseVersion("2.0.1"))
        assertEquals("1.8.0.2", UpdateChecker.parseVersion("1.8.0.2"))
    }

    @Test
    fun parseVersion_accepts_v_prefix_and_trims_whitespace() {
        assertEquals("1.9", UpdateChecker.parseVersion("v1.9"))
        assertEquals("1.9", UpdateChecker.parseVersion("  v1.9 \n"))
    }

    @Test
    fun parseVersion_rejects_non_version_text() {
        assertNull(UpdateChecker.parseVersion(""))                    // 空响应
        assertNull(UpdateChecker.parseVersion("<html>302 Found</html>")) // 劫持页
        assertNull(UpdateChecker.parseVersion("latest"))              // 非版本文本
        assertNull(UpdateChecker.parseVersion("1.8\n<script>"))       // 尾随注入
        assertNull(UpdateChecker.parseVersion("1..8"))                // 脏数据
        assertNull(UpdateChecker.parseVersion("1.8-beta"))            // 预发布后缀不收
        assertNull(UpdateChecker.parseVersion("v"))                   // 只有前缀
        assertNull(UpdateChecker.parseVersion("1.8.0.0.1"))           // 超 4 段
    }

    // ── hasUpdate：数值分段比较 ──

    @Test
    fun hasUpdate_numeric_segment_compare() {
        assertTrue(UpdateChecker.hasUpdate("1.8", "1.9"))
        assertTrue(UpdateChecker.hasUpdate("1.9", "1.10"))  // 数值比较：1.10 > 1.9
        assertTrue(UpdateChecker.hasUpdate("1.8", "2.0"))
        assertTrue(UpdateChecker.hasUpdate("1.8", "1.8.1"))
        assertFalse(UpdateChecker.hasUpdate("1.8", "1.8"))   // 相等无更新
        assertFalse(UpdateChecker.hasUpdate("1.10", "1.9"))
        assertFalse(UpdateChecker.hasUpdate("2.0", "1.99"))
    }

    @Test
    fun hasUpdate_handles_v_prefix_and_missing_segments() {
        assertTrue(UpdateChecker.hasUpdate("1.8", "v1.9"))
        assertTrue(UpdateChecker.hasUpdate("v1.8", "1.9"))
        assertTrue(UpdateChecker.hasUpdate("1", "1.0.1"))
        assertFalse(UpdateChecker.hasUpdate("1.0.1", "1"))
        assertFalse(UpdateChecker.hasUpdate("1.0", "1"))     // 缺段补 0 比较
    }
}
