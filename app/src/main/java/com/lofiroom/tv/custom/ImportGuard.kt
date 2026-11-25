package com.lofiroom.tv.custom

/**
 * 导入 / 落盘安全校验（纯 Kotlin，**不含任何 android.\* 依赖**，可在 JVM 单测里直接跑）。
 *
 * 从 CustomLibrary.importZip + CustomLibrary.sanitize 原样抽出，语义一字不差：
 * 只做字符串判断与整形，不碰文件系统、不碰 Context。抽出来的目的是把「路径穿越 / 扩展名白名单 /
 * 落盘名整形」这三块风险最高又零覆盖的逻辑变成可单测的纯函数。
 *
 * 2026-11-20 review 后补齐（P3-7/P3-8）：落盘名长度上限（截断保扩展名）、同目录落盘名去重、
 * 展示名控制字符消毒——这些是原实现的缺口，不是既成语义。
 */
internal object ImportGuard {

    /** 壁纸扩展名白名单（与 LanServer 上传白名单一致） */
    val WALL_EXTS = setOf("jpg", "jpeg", "png", "webp")

    /** 音乐扩展名白名单 */
    val TRACK_EXTS = setOf("mp3", "m4a", "wav", "aac", "flac", "ogg")

    /** 落盘名允许保留的字符：字母数字 + . _ - 与 CJK 基本区，其余一律转下划线 */
    private val UNSAFE = Regex("[^A-Za-z0-9._\\-\u4e00-\u9fa5]")

    /** 展示名要剥的控制字符：ASCII 控制符 + C1 + 行/段分隔符 U+2028/2029（→空格，防截断注入与多行夹带） */
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u2028\\u2029]")

    /** 展示名要剥的隐形字符：零宽 + BOM + bidi 控制（直接删除，防显示欺骗） */
    private val INVISIBLE = Regex("[\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]")

    /** 落盘名 stem 长度上限（字符数）：uniqueName 前缀 ~20B + CJK 3B/char × 64 ≈ 212B < 255B 文件名上限 */
    private const val MAX_NAME_LEN = 64

    /** 落盘名扩展名段长度上限（含点）；白名单扩展名最长 4+1，防展示名尾巴灌长串 */
    private const val MAX_EXT_LEN = 8

    /** 展示名长度上限：与 LanServer 写口 MAX_TEXT_LEN 一致 */
    private const val MAX_DISPLAY_LEN = 200

    /**
     * zip 条目名归一化：反斜杠当分隔符、剥掉 `./` 前缀与整目录打包带来的 `custom/` 前缀。
     * 必须在 [isSafeRel] 之前调用：`\\windows\\x`（反斜杠路径）归一化后是 `/windows/x`，
     * 带前导斜杠 = 绝对路径，由 [isSafeRel] 拒收；顺序反了就会漏掉这种 Windows 风格路径。
     */
    fun normalizeEntry(name: String): String =
        name.replace('\\', '/').removePrefix("./").removePrefix("custom/")

    /**
     * 归一化后的相对路径是否安全：非空、不以 `/` 开头（绝对路径）、且**没有任何 `..` 路径段**。
     * 按段精确判而非 `contains("..")` 子串：`A..B.mp3`、`..png`、`a..b.png` 是合法文件名（无 `..` 段），
     * 旧子串判断会把它们当穿越 → importZip 抛异常整包导入失败（过度拒绝）。
     */
    fun isSafeRel(rel: String): Boolean =
        rel.isNotBlank() && !rel.startsWith("/") && rel.split('/').none { it == ".." }

    /** 小写扩展名：取最后一个 `.` 之后；无扩展名返回 `""`（`"x.php.png"` → `"png"`） */
    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** 目录深度不限，只看最后一段的文件名：非空且扩展名在白名单里 */
    fun isAllowedExt(rel: String, exts: Set<String>): Boolean {
        val base = rel.substringAfterLast('/')
        return base.isNotBlank() && extOf(base) in exts
    }

    /**
     * 落盘名整形：先剥目录，再把白名单外字符逐字转下划线；结果为空则回退 `"file"`；
     * 超长截断（P3-7）但**保住扩展名**：`"a"×200 + ".png"` → `"a"×64 + ".png"`。
     * 点开头（`.hidden`、`.`、`..`）不分段，整串截断——与原语义一致。
     */
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.replace(UNSAFE, "_").ifBlank { "file" }
        val dot = clean.lastIndexOf('.')
        if (dot <= 0) return clean.take(MAX_NAME_LEN)
        val stem = clean.substring(0, dot).take(MAX_NAME_LEN)
        val ext = clean.substring(dot).take(MAX_EXT_LEN)
        return stem + ext
    }

    /**
     * 落盘去重（P3-7）：不同原名 sanitize 后可能同名（`a-b.png` 与 `a_b.png` → `a_b.png`），
     * 同目录内后到者在扩展名前追加 `-2`、`-3`…，防互相覆盖丢内容。[taken] 为该目录已用名集合。
     */
    fun dedupeName(sanitized: String, taken: MutableSet<String>): String {
        if (taken.add(sanitized)) return sanitized
        val dot = sanitized.lastIndexOf('.')
        val stem = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val ext = if (dot > 0) sanitized.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "$stem-$i$ext"
            if (taken.add(candidate)) return candidate
            i++
        }
    }

    /**
     * 展示名消毒（P3-8）：隐形字符直接删、控制字符换空格、去首尾空白、限长 200。
     * 允许返回空串——调用方保留原有 ifBlank 回退（文件名派生展示名）。
     */
    fun sanitizeDisplay(value: String): String =
        value.replace(INVISIBLE, "").replace(CONTROL, " ").trim().take(MAX_DISPLAY_LEN)

    /**
     * zip 条目抽取决策（importZip 的纯逻辑，P3-6 集成测试缝）：
     * 归一化后的相对路径 → 落盘相对位置 `meta.json` / `manifest.json` /
     * `wallpapers/<去重落盘名>` / `music/<去重落盘名>`；白名单外返回 null=跳过。
     * 路径穿越拦截不在这里——调用方先过 normalizeEntry + isSafeRel（非法直接抛）。
     */
    fun planExtract(rel: String, usedWall: MutableSet<String>, usedTrack: MutableSet<String>): String? {
        val base = rel.substringAfterLast('/')
        return when {
            rel == "manifest.json" || rel.endsWith("/manifest.json") -> "manifest.json"
            rel == "meta.json" || rel.endsWith("/meta.json") -> "meta.json"
            isAllowedExt(rel, WALL_EXTS) -> "wallpapers/" + dedupeName(sanitize(base), usedWall)
            isAllowedExt(rel, TRACK_EXTS) -> "music/" + dedupeName(sanitize(base), usedTrack)
            else -> null
        }
    }
}
