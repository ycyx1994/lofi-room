package com.lofiroom.tv.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ImportGuard 纯逻辑单测：导入路径穿越、扩展名白名单、落盘名整形。
 *
 * 断言的基准是「抽取前的现有实现」（CustomLibrary.importZip + 私有 sanitize），
 * 所有看起来别扭的期望值都在注释里标了 [怪]，那是现有语义的既成事实，
 * 供 review 判断要不要改，不是本测试的理想值。
 */
class ImportGuardTest {

    // ── isSafeRel：路径穿越 / 绝对路径 ──────────────────────

    @Test
    fun isSafeRel_rejects_parent_traversal() {
        assertFalse(ImportGuard.isSafeRel(".."))
        assertFalse(ImportGuard.isSafeRel("a/../../b"))
        assertFalse(ImportGuard.isSafeRel("wallpapers/../../../etc/passwd"))
    }

    @Test
    fun isSafeRel_rejects_absolute_paths() {
        assertFalse(ImportGuard.isSafeRel("/etc/passwd"))
        assertFalse(ImportGuard.isSafeRel("/"))
    }

    @Test
    fun isSafeRel_rejects_blank() {
        assertFalse(ImportGuard.isSafeRel(""))
        assertFalse(ImportGuard.isSafeRel("   "))
    }

    @Test
    fun isSafeRel_accepts_normal_relative_path() {
        assertTrue(ImportGuard.isSafeRel("wallpapers/ok.png"))
        assertTrue(ImportGuard.isSafeRel("ok.png"))
        assertTrue(ImportGuard.isSafeRel("a/b/c/d.mp3"))
    }

    @Test
    fun isSafeRel_accepts_dot_slash_prefix_because_normalize_strips_it() {
        // [怪] isSafeRel 只看 ".." / 前导 "/"；"./x" 由 normalizeEntry 先剥掉，
        // 所以单看 isSafeRel("./wallpapers/ok.png") 是 true。两步是配套使用的。
        assertTrue(ImportGuard.isSafeRel("./wallpapers/ok.png"))
        assertEquals("wallpapers/ok.png", ImportGuard.normalizeEntry("./wallpapers/ok.png"))
    }

    @Test
    fun isSafeRel_is_judged_after_normalization_for_backslash_paths() {
        // [怪] 原始串 "\windows\x"（一个反斜杠）不含 ".."、不以 "/" 开头 → 单看 isSafeRel 是 true，
        // 但真实导入流程先 normalizeEntry 把反斜杠换成 "/"，得到 "/windows/x"（绝对路径）→ 被拦下。
        // 顺序不能反：先 isSafeRel 再 normalize 会漏掉这种 UNC 风格路径。
        assertTrue(ImportGuard.isSafeRel("\\windows\\x"))
        assertEquals("/windows/x", ImportGuard.normalizeEntry("\\windows\\x"))
        assertFalse(ImportGuard.isSafeRel(ImportGuard.normalizeEntry("\\windows\\x")))
        // 反斜杠 + 穿越：归一化后仍被 ".." 拦住
        assertFalse(ImportGuard.isSafeRel(ImportGuard.normalizeEntry("..\\..\\etc\\passwd")))
    }

    // ── normalizeEntry：反斜杠 / ./ / custom/ 前缀 ──────────

    @Test
    fun normalizeEntry_strips_only_one_custom_prefix() {
        assertEquals("wallpapers/a.png", ImportGuard.normalizeEntry("custom/wallpapers/a.png"))
        assertEquals("custom/a.png", ImportGuard.normalizeEntry("custom/custom/a.png"))
        assertEquals("a/b.png", ImportGuard.normalizeEntry("a\\b.png"))
    }

    // ── extOf / isAllowedExt：大小写与多后缀 ────────────────

    @Test
    fun extOf_lowercases_and_handles_missing_ext() {
        assertEquals("png", ImportGuard.extOf("A.PNG"))
        assertEquals("png", ImportGuard.extOf("dir/a.Png"))
        assertEquals("", ImportGuard.extOf("noext"))
        assertEquals("", ImportGuard.extOf(""))
        assertEquals("gz", ImportGuard.extOf("a.tar.gz"))
        // [怪] ".hidden" 被当成「扩展名为 hidden」，点开头文件没有特殊处理
        assertEquals("hidden", ImportGuard.extOf(".hidden"))
    }

    @Test
    fun isAllowedExt_is_case_insensitive() {
        assertTrue(ImportGuard.isAllowedExt("a.PNG", ImportGuard.WALL_EXTS))
        assertTrue(ImportGuard.isAllowedExt("b.Jpg", ImportGuard.WALL_EXTS))
        assertTrue(ImportGuard.isAllowedExt("deep/dir/c.WebP", ImportGuard.WALL_EXTS))
        assertTrue(ImportGuard.isAllowedExt("d.FLAC", ImportGuard.TRACK_EXTS))
    }

    @Test
    fun isAllowedExt_rejects_others() {
        assertFalse(ImportGuard.isAllowedExt("a.exe", ImportGuard.WALL_EXTS))
        assertFalse(ImportGuard.isAllowedExt("a.apk", ImportGuard.WALL_EXTS))
        assertFalse(ImportGuard.isAllowedExt("a.apk", ImportGuard.TRACK_EXTS))
        // 白名单不跨类：图片扩展名不算音乐
        assertFalse(ImportGuard.isAllowedExt("a.png", ImportGuard.TRACK_EXTS))
        assertFalse(ImportGuard.isAllowedExt("noext", ImportGuard.WALL_EXTS))
        assertFalse(ImportGuard.isAllowedExt("", ImportGuard.WALL_EXTS))
        assertFalse(ImportGuard.isAllowedExt("wallpapers/", ImportGuard.WALL_EXTS))
    }

    @Test
    fun isAllowedExt_only_looks_at_last_segment() {
        // [怪] 多后缀只看最后一段：x.php.png 收下，落盘名仍带 .php.png（双扩展名）
        assertTrue(ImportGuard.isAllowedExt("x.php.png", ImportGuard.WALL_EXTS))
        assertEquals("x.php.png", ImportGuard.sanitize("x.php.png"))
        assertFalse(ImportGuard.isAllowedExt("x.png.php", ImportGuard.WALL_EXTS))
    }

    // ── sanitize：落盘名整形 ───────────────────────────────

    @Test
    fun sanitize_replaces_unsafe_chars() {
        assertEquals("a_b.png", ImportGuard.sanitize("a b.png"))
        assertEquals("a_b.png", ImportGuard.sanitize("a?b.png"))
        assertEquals("a_b_c.webp", ImportGuard.sanitize("a*b:c.webp"))
        assertEquals("a_b.png", ImportGuard.sanitize("a\u0000b.png")) // 控制字符
    }

    @Test
    fun sanitize_strips_directories() {
        assertEquals("y.png", ImportGuard.sanitize("dir/x/y.png"))
        assertEquals("y.png", ImportGuard.sanitize("dir\\x\\y.png"))
        assertEquals("y.png", ImportGuard.sanitize("../../y.png"))
    }

    @Test
    fun sanitize_keeps_cjk_and_allowed_symbols() {
        assertEquals("中文名.png", ImportGuard.sanitize("中文名.png"))
        assertEquals("夜-景_01.v2.png", ImportGuard.sanitize("夜-景_01.v2.png"))
        // 全角标点不在白名单内
        assertEquals("夜_景.png", ImportGuard.sanitize("夜？景.png"))
    }

    @Test
    fun sanitize_falls_back_to_file_only_when_result_is_blank() {
        assertEquals("file", ImportGuard.sanitize(""))
        // [怪] "   " / "??" 不会走到 ifBlank（先被替换成 "_"），只有空串才回退 "file"
        assertEquals("___", ImportGuard.sanitize("   "))
        assertEquals("__", ImportGuard.sanitize("??"))
    }

    @Test
    fun sanitize_does_not_touch_dots() {
        // [怪] "." 原样返回：File(wallpapers, ".") 就是 wallpapers 目录本身，
        // 后续 outputStream() 会抛 IOException（Is a directory）→ 整个导入失败。
        // ".." 同理原样返回，但 importZip 的 isSafeRel 会先把它拦掉，所以实际不可达。
        assertEquals(".", ImportGuard.sanitize("."))
        assertEquals("..", ImportGuard.sanitize(".."))
        assertEquals("...", ImportGuard.sanitize("..."))
    }

    @Test
    fun sanitize_truncates_long_names_but_keeps_ext() {
        // 2026-11-20 review P3-7：>255B 文件名落盘抛错 → stem 截 64，扩展名保留
        val long = "a".repeat(200) + ".png"
        assertEquals("a".repeat(64) + ".png", ImportGuard.sanitize(long))
        assertEquals(68, ImportGuard.sanitize(long).length)
        // 无扩展名整串截 64
        assertEquals(64, ImportGuard.sanitize("b".repeat(200)).length)
        // 扩展名段也限长 8（含点，防展示名尾巴灌长串）
        assertEquals("x." + "z".repeat(7), ImportGuard.sanitize("x." + "z".repeat(50)))
    }

    @Test
    fun sanitize_truncates_cjk_safely_under_255_bytes() {
        // 64 个 CJK（3B/char）+ uniqueName 前缀 ~20B ≈ 212B < 255B 文件名上限
        val s = ImportGuard.sanitize("中".repeat(300) + ".png")
        assertEquals(64, s.removeSuffix(".png").length)
    }

    // ── dedupeName：同目录落盘名去重 ─────────────────────

    @Test
    fun dedupeName_keeps_first_and_suffixes_collisions() {
        val taken = mutableSetOf<String>()
        assertEquals("a_b.png", ImportGuard.dedupeName("a_b.png", taken))
        // a-b.png sanitize 后同名：后到者 -2/-3…（扩展名之前）
        assertEquals("a_b-2.png", ImportGuard.dedupeName("a_b.png", taken))
        assertEquals("a_b-3.png", ImportGuard.dedupeName("a_b.png", taken))
    }

    @Test
    fun dedupeName_handles_no_ext_and_dot_names() {
        val taken = mutableSetOf<String>()
        assertEquals("x", ImportGuard.dedupeName("x", taken))
        assertEquals("x-2", ImportGuard.dedupeName("x", taken))
        // 点开头不拆段：冲突后缀追加整串尾
        assertEquals(".env", ImportGuard.dedupeName(".env", taken))
        assertEquals(".env-2", ImportGuard.dedupeName(".env", taken))
    }

    // ── sanitizeDisplay：展示名消毒 ───────────────────────

    @Test
    fun sanitizeDisplay_strips_control_and_invisible_chars() {
        assertEquals("a b", ImportGuard.sanitizeDisplay("a\u0000b"))
        assertEquals("ab", ImportGuard.sanitizeDisplay("a\u200bb"))
        assertEquals("ab", ImportGuard.sanitizeDisplay("a\ufeffb"))
        // bidi 控制字符剥掉（防显示欺骗）
        assertEquals("abc", ImportGuard.sanitizeDisplay("a\u202ebc"))
        // 换行/制表变空格、首尾空白剥
        assertEquals("a b", ImportGuard.sanitizeDisplay(" a\nb\t"))
    }

    @Test
    fun sanitizeDisplay_caps_length_and_may_return_blank() {
        assertEquals(200, ImportGuard.sanitizeDisplay("x".repeat(500)).length)
        // 纯控制字符 → 空串，调用方 ifBlank 回退
        assertEquals("", ImportGuard.sanitizeDisplay("\u0000\u200b"))
        assertEquals("", ImportGuard.sanitizeDisplay("   "))
    }

    // ── planExtract：importZip 抽取决策集成（P3-6）────────

    @Test
    fun planExtract_full_import_pipeline_over_realistic_entries() {
        // 模拟 importZip 决策链：normalizeEntry → isSafeRel → planExtract（含跨条目去重）
        val usedWall = mutableSetOf<String>()
        val usedTrack = mutableSetOf<String>()
        fun plan(raw: String): String? {
            val rel = ImportGuard.normalizeEntry(raw)
            if (!ImportGuard.isSafeRel(rel)) throw IllegalArgumentException(raw)
            return ImportGuard.planExtract(rel, usedWall, usedTrack)
        }
        // meta / manifest 任意目录深度都认
        assertEquals("meta.json", plan("custom/meta.json"))
        assertEquals("manifest.json", plan("backup/manifest.json"))
        // 壁纸/音乐按扩展名分拣，目录被剥
        assertEquals("wallpapers/a.png", plan("custom/wallpapers/a.png"))
        assertEquals("music/a.mp3", plan("music/deep/a.mp3"))
        // 跨条目 sanitize 碰撞去重（"a b.png" / "a?b.png" 都整形为 a_b.png）
        assertEquals("wallpapers/a_b.png", plan("wallpapers/a b.png"))
        assertEquals("wallpapers/a_b-2.png", plan("wallpapers/a?b.png"))
        // 超长截断在决策里生效（stem 64 + ext 保）
        assertEquals("wallpapers/" + "中".repeat(64) + ".webp", plan("wallpapers/" + "中".repeat(300) + ".webp"))
        // 白名单外跳过（exe / 无扩展名 / 目录名）
        assertEquals(null, plan("wallpapers/evil.exe"))
        assertEquals(null, plan("music/noext"))
        // 穿越在 isSafeRel 先被拦（决策不可达）
        try {
            plan("wallpapers/../../etc/passwd")
            throw AssertionError("穿越未拦截")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── 组合：一个真实 zip 条目名的完整判定链 ───────────────

    @Test
    fun full_chain_accepts_wallpaper_and_rejects_traversal() {
        val okZipEntry = "custom/wallpapers/1234_5678_夜景 01.JPG"
        val rel = ImportGuard.normalizeEntry(okZipEntry)
        assertTrue(ImportGuard.isSafeRel(rel))
        assertTrue(ImportGuard.isAllowedExt(rel, ImportGuard.WALL_EXTS))
        assertEquals("1234_5678_夜景_01.JPG", ImportGuard.sanitize(rel.substringAfterLast('/')))

        val evil = ImportGuard.normalizeEntry("wallpapers/../../databases/app.db")
        assertFalse(ImportGuard.isSafeRel(evil))
    }
}
