package com.lofiroom.tv.custom

/**
 * 导入 / 落盘安全校验（纯 Kotlin，**不含任何 android.\* 依赖**，可在 JVM 单测里直接跑）。
 *
 * 从 CustomLibrary.importZip + CustomLibrary.sanitize 原样抽出，语义一字不差：
 * 只做字符串判断与整形，不碰文件系统、不碰 Context。抽出来的目的是把「路径穿越 / 扩展名白名单 /
 * 落盘名整形」这三块风险最高又零覆盖的逻辑变成可单测的纯函数。
 */
internal object ImportGuard {

    /** 壁纸扩展名白名单（与 LanServer 上传白名单一致） */
    val WALL_EXTS = setOf("jpg", "jpeg", "png", "webp")

    /** 音乐扩展名白名单 */
    val TRACK_EXTS = setOf("mp3", "m4a", "wav", "aac", "flac", "ogg")

    /** 落盘名允许保留的字符：字母数字 + . _ - 与 CJK 基本区，其余一律转下划线 */
    private val UNSAFE = Regex("[^A-Za-z0-9._\\-\u4e00-\u9fa5]")

    /**
     * zip 条目名归一化：反斜杠当分隔符、剥掉 `./` 前缀与整目录打包带来的 `custom/` 前缀。
     * 必须在 [isSafeRel] 之前调用：`\\windows\\x`（反斜杠路径）归一化后是 `/windows/x`，
     * 带前导斜杠 = 绝对路径，由 [isSafeRel] 拒收；顺序反了就会漏掉这种 Windows 风格路径。
     */
    fun normalizeEntry(name: String): String =
        name.replace('\\', '/').removePrefix("./").removePrefix("custom/")

    /** 归一化后的相对路径是否安全：非空、不含 `..`、不以 `/` 开头（绝对路径） */
    fun isSafeRel(rel: String): Boolean =
        rel.isNotBlank() && !rel.contains("..") && !rel.startsWith("/")

    /** 小写扩展名：取最后一个 `.` 之后；无扩展名返回 `""`（`"x.php.png"` → `"png"`） */
    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** 目录深度不限，只看最后一段的文件名：非空且扩展名在白名单里 */
    fun isAllowedExt(rel: String, exts: Set<String>): Boolean {
        val base = rel.substringAfterLast('/')
        return base.isNotBlank() && extOf(base) in exts
    }

    /** 落盘名整形：先剥目录，再把白名单外字符逐字转下划线；结果为空则回退 `"file"` */
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return base.replace(UNSAFE, "_").ifBlank { "file" }
    }
}
