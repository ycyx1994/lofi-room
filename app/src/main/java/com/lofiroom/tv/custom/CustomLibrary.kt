package com.lofiroom.tv.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import com.lofiroom.tv.data.Playlist
import com.lofiroom.tv.data.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * #23 自定义内容库：内置资产 + 用户上传（H5 管理页）的合并视图。
 * - 壁纸命名约定：内置 = "wall_01.webp"（assets/wallpapers/），自定义 = "custom/<文件名>"（custom/wallpapers/）
 * - 曲目：内置 Track(isCustom=false)，自定义 Track(isCustom=true, asset=绝对路径)
 * - 元数据 custom/meta.json（org.json 读写）持久化自定义项的展示名与顺序
 */
object CustomLibrary {

    const val CUSTOM_PREFIX = "custom/"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private lateinit var appContext: Context
    private lateinit var rootDir: File // custom/
    private val wallDir get() = File(rootDir, "wallpapers")
    private val musicDir get() = File(rootDir, "music")
    private val metaFile get() = File(rootDir, "meta.json")

    /** 自定义壁纸文件名（不含前缀），顺序 = meta 顺序 */
    @Volatile
    private var customWalls: List<String> = emptyList()

    /** #24 自定义壁纸展示名（文件名 → 上传原名去扩展名）：落盘名带时间戳前缀，不适合直接展示 */
    @Volatile
    private var customWallNames: Map<String, String> = emptyMap()

    /** 自定义曲目（asset = 绝对路径） */
    @Volatile
    private var customTracks: List<Track> = emptyList()

    /** 内置壁纸名（assets 列举排序后） */
    private val builtinWalls: List<String> by lazy {
        (appContext.assets.list("wallpapers") ?: emptyArray()).sorted().toList()
    }

    /** 合并壁纸名（内置在前）——WallpaperView / 缩略图统一按此取名 */
    val wallpaperNames: List<String>
        get() = builtinWalls + customWalls.map { CUSTOM_PREFIX + it }

    /** 合并曲目（内置在前） */
    val tracks: List<Track>
        get() = Playlist.tracks + customTracks

    /** #23 库变更回调（主线程分发）：上传/删除/重命名/移动后触发 */
    var onLibraryChanged: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        rootDir = context.getExternalFilesDir(null)?.resolve("custom")
            ?: File(context.filesDir, "custom")
        wallDir.mkdirs()
        musicDir.mkdirs()
        loadMeta()
    }

    // ── 查询 ──────────────────────────────────────────────

    /** 按命名约定打开壁纸流：custom/ 前缀走文件，否则走 assets */
    fun openWallpaper(name: String): InputStream? = try {
        if (name.startsWith(CUSTOM_PREFIX)) {
            File(wallDir, name.substring(CUSTOM_PREFIX.length)).inputStream()
        } else {
            appContext.assets.open("wallpapers/$name")
        }
    } catch (e: Exception) {
        null
    }

    /** 壁纸展示名（H5 列表用）：内置取中文名，自定义取文件名 */
    fun wallpaperDisplayName(index: Int): String {
        val names = wallpaperNames
        val n = names.getOrNull(index) ?: return "#$index"
        if (!n.startsWith(CUSTOM_PREFIX)) {
            val bi = builtinWalls.indexOf(n)
            if (bi in Playlist.wallpapers.indices) return Playlist.wallpapers[bi].cn
        }
        val f = n.removePrefix(CUSTOM_PREFIX)
        // #24 优先展示原名；老 meta 无名字段回退：剥落盘时间戳前缀（毫秒_随机_）与扩展名
        return customWallNames[f]
            ?: f.replaceFirst(Regex("^\\d+_\\d+_"), "").substringBeforeLast('.')
    }

    /** #23 解码壁纸到目标宽（inSampleSize 抽样 + 自定义图 EXIF 方向校正），内置/自定义通用 */
    fun decodeWallpaper(name: String, targetWidth: Int): Bitmap? {
        val bmp = decodeSampled(name, targetWidth) ?: return null
        if (!name.startsWith(CUSTOM_PREFIX)) return bmp
        // 手机竖拍照片带 EXIF 旋转，BitmapFactory 不处理，需手动转正
        val degrees = exifDegrees(File(wallDir, name.substring(CUSTOM_PREFIX.length)))
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val fixed = try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Exception) {
            return bmp
        }
        if (fixed !== bmp) bmp.recycle()
        return fixed
    }

    private fun decodeSampled(name: String, targetWidth: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openWallpaper(name)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) null else {
            var sample = 1
            while (opts.outWidth / (sample * 2) >= targetWidth) sample *= 2
            opts.inSampleSize = sample
            opts.inJustDecodeBounds = false
            openWallpaper(name)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Exception) {
        null
    }

    private fun exifDegrees(f: File): Int = try {
        when (ExifInterface(f.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    // ── 导出备份 ─────────────────────────────────────────

    /** 导入结果（成功才返回；失败抛异常由 LanServer 转错误响应） */
    data class ImportResult(val walls: Int, val tracks: Int, val bytes: Long)

    /** 导出自定义内容为 zip 流：manifest.json + meta.json + wallpapers/ + music/（结构与落盘一致，可回推恢复）。
     *  part: "all" 全量 / "wall" 只照片 / "music" 只音乐（分包方便大音乐库分批下载） */
    fun exportZip(out: OutputStream, part: String = "all") {
        synchronized(lock) {
            ZipOutputStream(out).use { z ->
                val files = mutableListOf<Pair<String, File>>()
                if (part != EXPORT_MUSIC) {
                    customWalls.forEach { f ->
                        File(wallDir, f).takeIf { it.exists() }?.let { files += "wallpapers/$f" to it }
                    }
                }
                if (part != EXPORT_WALL) {
                    customTracks.forEach { t ->
                        File(t.asset).takeIf { it.exists() }?.let { files += "music/${it.name}" to it }
                    }
                }
                // manifest：清单供恢复端核对（zip 自带 CRC，清单用于人读与摘要展示）
                val arr = JSONArray()
                files.forEach { (rel, f) ->
                    arr.put(JSONObject().put("path", rel).put("size", f.length()))
                }
                val manifest = JSONObject()
                    .put("app", "lofiroom")
                    .put("part", part)
                    .put("files", arr)
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write(manifest.toString(2).toByteArray())
                z.closeEntry()
                if (metaFile.exists()) {
                    z.putNextEntry(ZipEntry("meta.json"))
                    metaFile.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
                files.forEach { (rel, f) ->
                    z.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
    }

    // ── 导入恢复 ─────────────────────────────────────────
    // 校验清单（对应评估 P1）：路径穿越拒绝、扩展名白名单、单文件/总量/条目数上限、
    // meta 解析失败即合成兜底；覆盖模式下旧数据先改名 custom.bak，失败回滚。

    private const val EXPORT_ALL = "all"
    private const val EXPORT_WALL = "wall"
    private const val EXPORT_MUSIC = "music"
    private const val MAX_IMPORT_ENTRIES = 2000
    private const val MAX_IMPORT_TOTAL = 1024L * 1024 * 1024   // 解压后总量 1GB
    private const val MAX_IMPORT_WALL = 20L * 1024 * 1024      // 与上传上限一致
    private const val MAX_IMPORT_TRACK = 100L * 1024 * 1024
    private const val MAX_IMPORT_META = 2L * 1024 * 1024
    private val WALL_IMPORT_EXTS = setOf("jpg", "jpeg", "png", "webp")
    private val TRACK_IMPORT_EXTS = setOf("mp3", "m4a", "wav", "aac", "flac", "ogg")

    /**
     * 导入备份 zip。
     * @param replace true = 覆盖恢复（整目录原子替换）；false = 合并导入（追加，保留现有内容）
     */
    @Throws(IOException::class)
    fun importZip(input: InputStream, replace: Boolean): ImportResult {
        val tmp = File(rootDir.parentFile, "custom.import.tmp")
        if (tmp.exists()) tmp.deleteRecursively()
        File(tmp, "wallpapers").mkdirs()
        File(tmp, "music").mkdirs()
        var entries = 0
        var total = 0L
        var wallCount = 0
        var trackCount = 0
        try {
            ZipInputStream(input.buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    entries++
                    if (entries > MAX_IMPORT_ENTRIES) throw IOException("zip 条目过多")
                    if (e.isDirectory) continue
                    // 归一化：反斜杠、./ 前缀、整目录打包时的 custom/ 前缀
                    val rel = e.name.replace('\\', '/')
                        .removePrefix("./")
                        .removePrefix("custom/")
                    if (rel.isBlank() || rel.contains("..") || rel.startsWith("/")) {
                        throw IOException("非法路径：${e.name}")
                    }
                    val base = rel.substringAfterLast('/')
                    val ext = base.substringAfterLast('.', "").lowercase()
                    // 按扩展名归类，任意目录深度都收（兼容 adb 整目录打包、散文件打包）
                    val dest: File = when {
                        rel == "manifest.json" || rel.endsWith("/manifest.json") -> {
                            total += copyLimited(zin, File(tmp, "manifest.json"), MAX_IMPORT_META)
                            continue
                        }

                        rel == "meta.json" || rel.endsWith("/meta.json") -> {
                            total += copyLimited(zin, File(tmp, "meta.json"), MAX_IMPORT_META)
                            continue
                        }

                        base.isNotBlank() && ext in WALL_IMPORT_EXTS ->
                            File(File(tmp, "wallpapers"), sanitize(base))

                        base.isNotBlank() && ext in TRACK_IMPORT_EXTS ->
                            File(File(tmp, "music"), sanitize(base))

                        else -> continue // 目录/白名单外文件跳过
                    }
                    val cap = if (dest.parentFile.name == "wallpapers") MAX_IMPORT_WALL else MAX_IMPORT_TRACK
                    total += copyLimited(zin, dest, cap)
                    if (total > MAX_IMPORT_TOTAL) throw IOException("备份包过大（解压后 >1GB）")
                    if (dest.parentFile.name == "wallpapers") wallCount++ else trackCount++
                }
            }
            // 以实际落盘为准（zip 内同名文件会覆盖，计数可能与条目数不一致）
            wallCount = File(tmp, "wallpapers").listFiles()?.count { it.isFile } ?: 0
            trackCount = File(tmp, "music").listFiles()?.count { it.isFile } ?: 0
            if (wallCount == 0 && trackCount == 0) throw IOException("zip 里没有照片或音乐")
            // meta 归一化：以 zip meta 的展示名为偏好，按实际落盘文件重建，消除缺失/改名/脏引用
            val metaTmp = File(tmp, "meta.json")
            val rawMeta = try {
                JSONObject(metaTmp.readText())
            } catch (e: Exception) {
                JSONObject()
            }
            val meta = normalizeMeta(tmp, rawMeta)
            metaTmp.writeText(meta.toString(2))
            if (replace) {
                synchronized(lock) {
                    val bak = File(rootDir.parentFile, "custom.bak")
                    if (bak.exists()) bak.deleteRecursively()
                    val hadOld = rootDir.exists()
                    if (hadOld && !rootDir.renameTo(bak)) throw IOException("旧数据备份失败（存储被占用？）")
                    if (!tmp.renameTo(rootDir)) {
                        if (hadOld) bak.renameTo(rootDir) // 回滚
                        throw IOException("写入失败")
                    }
                    if (bak.exists()) bak.deleteRecursively()
                    loadMeta()
                }
            } else {
                mergeImport(tmp, meta)
            }
            notifyChanged()
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw if (e is IOException) e else IOException("导入失败：$e")
        }
        return ImportResult(wallCount, trackCount, total)
    }

    /** 合并导入：tmp 里的文件并入现有库（落盘名重新生成防冲突），meta 名称映射保留展示名 */
    private fun mergeImport(tmp: File, meta: JSONObject) = synchronized(lock) {
        val wallNames = mutableMapOf<String, String>()
        val w = meta.optJSONArray("wallpapers")
        if (w != null) for (i in 0 until w.length()) {
            val o = w.getJSONObject(i)
            wallNames[o.optString("file")] = o.optString("name")
        }
        val trackMeta = mutableMapOf<String, Pair<String, String>>()
        val t = meta.optJSONArray("tracks")
        if (t != null) for (i in 0 until t.length()) {
            val o = t.getJSONObject(i)
            trackMeta[o.optString("file")] = o.optString("title") to o.optString("sub")
        }
        File(tmp, "wallpapers").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
            val dest = uniqueName(wallDir, f.name)
            f.copyTo(dest, overwrite = true)
            f.delete()
            customWalls = customWalls + dest.name
            val dn = wallNames[f.name]?.ifBlank { null }
                ?: f.name.replaceFirst(Regex("^\\d+_\\d+_"), "").substringBeforeLast('.')
            customWallNames = customWallNames + (dest.name to dn)
        }
        File(tmp, "music").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
            val dest = uniqueName(musicDir, f.name)
            f.copyTo(dest, overwrite = true)
            f.delete()
            val (title, sub) = trackMeta[f.name] ?: ("" to "")
            customTracks = customTracks + Track(
                title.ifBlank { f.name.replaceFirst(Regex("^\\d+_\\d+_"), "").substringBeforeLast('.') },
                sub.ifBlank { "MY MUSIC · 自定义" },
                dest.absolutePath,
                isCustom = true
            )
        }
        saveMetaLocked()
    }

    /** 流式拷贝并限长（防 zip 炸弹），返回实际字节数 */
    private fun copyLimited(input: InputStream, dest: File, cap: Long): Long {
        var n = 0L
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                n += r
                if (n > cap) {
                    dest.delete()
                    throw IOException("单文件过大：${dest.name}")
                }
                out.write(buf, 0, r)
            }
        }
        return n
    }

    /** meta 归一化：按实际落盘文件重建 meta（展示名优先取 zip meta，回退文件名去时间戳前缀） */
    private fun normalizeMeta(dir: File, src: JSONObject): JSONObject {
        val wallNames = mutableMapOf<String, String>()
        src.optJSONArray("wallpapers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                wallNames[o.optString("file")] = o.optString("name")
            }
        }
        val trackMeta = mutableMapOf<String, Pair<String, String>>()
        src.optJSONArray("tracks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                trackMeta[o.optString("file")] = o.optString("title") to o.optString("sub")
            }
        }
        val w = JSONArray()
        File(dir, "wallpapers").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach {
            val nm = wallNames[it.name]?.ifBlank { null }
                ?: it.name.replaceFirst(Regex("^\\d+_\\d+_"), "").substringBeforeLast('.')
            w.put(JSONObject().put("file", it.name).put("name", nm))
        }
        val t = JSONArray()
        File(dir, "music").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach {
            val (ti, su) = trackMeta[it.name] ?: ("" to "")
            t.put(JSONObject().put("file", it.name).put("title", ti).put("sub", su))
        }
        return JSONObject().put("wallpapers", w).put("tracks", t)
    }

    /** 新落盘名（时间戳前缀保证唯一），与 addWallpaper/addTrack 命名一致 */
    private fun uniqueName(dir: File, base: String): File {
        var i = 0
        val ts = System.currentTimeMillis()
        while (true) {
            val f = File(dir, "${ts + i}_${(0..9999).random()}_${sanitize(base)}")
            if (!f.exists()) return f
            i++
        }
    }

    // ── 变更（全部线程安全，meta 写回 + 主线程回调）──────────

    /** 落盘一张自定义壁纸（src 已在目标路径则不搬移），返回合并下标；失败返回 -1 */
    fun addWallpaper(src: File, displayName: String): Int = synchronized(lock) {
        try {
            val safe = sanitize(displayName)
            val dest = File(wallDir, "${System.currentTimeMillis()}_${(0..9999).random()}_$safe")
            if (src.absolutePath != dest.absolutePath) src.copyTo(dest, overwrite = true)
            customWalls = customWalls + dest.name
            // #24 记住上传原名（去扩展名）做展示名，meta 一起持久化
            customWallNames = customWallNames + (dest.name to displayName.substringBeforeLast('.').ifBlank { dest.nameWithoutExtension.replaceFirst(Regex("^\\d+_\\d+_"), "") })
            saveMetaLocked()
            notifyChanged()
            wallpaperNames.size - 1
        } catch (e: Exception) {
            -1
        }
    }

    /** 删除自定义壁纸（合并下标）；内置项与越界返回 false */
    fun removeWallpaper(index: Int): Boolean = synchronized(lock) {
        val ci = index - builtinWalls.size
        if (ci !in customWalls.indices) return false
        val name = customWalls[ci]
        File(wallDir, name).delete()
        customWalls = customWalls - name
        customWallNames = customWallNames - name
        saveMetaLocked()
        notifyChanged()
        true
    }

    /** 落盘一首自定义音频，返回合并下标；失败返回 -1 */
    fun addTrack(src: File, title: String, sub: String): Int = synchronized(lock) {
        try {
            val safe = sanitize(src.name)
            val dest = File(musicDir, "${System.currentTimeMillis()}_${(0..9999).random()}_$safe")
            if (src.absolutePath != dest.absolutePath) src.copyTo(dest, overwrite = true)
            val t = Track(
                title.ifBlank { dest.nameWithoutExtension.replaceFirst(Regex("^\\d+_\\d+_"), "") },
                sub.ifBlank { "MY MUSIC · 自定义" },
                dest.absolutePath,
                isCustom = true
            )
            customTracks = customTracks + t
            saveMetaLocked()
            notifyChanged()
            Playlist.tracks.size + customTracks.size - 1
        } catch (e: Exception) {
            -1
        }
    }

    /** 重命名自定义曲目（合并下标）；内置项与越界返回 false */
    fun renameTrack(index: Int, title: String, sub: String): Boolean = synchronized(lock) {
        val ci = index - Playlist.tracks.size
        if (ci !in customTracks.indices) return false
        customTracks = customTracks.toMutableList().also {
            it[ci] = it[ci].copy(
                title = title.ifBlank { it[ci].title },
                sub = sub.ifBlank { it[ci].sub }
            )
        }
        saveMetaLocked()
        notifyChanged()
        true
    }

    /** 自定义曲目在自身区间内上移/下移（dir = -1/1），返回是否移动成功 */
    fun moveTrack(index: Int, dir: Int): Boolean = synchronized(lock) {
        val ci = index - Playlist.tracks.size
        val cj = ci + dir
        if (ci !in customTracks.indices || cj !in customTracks.indices) return false
        customTracks = customTracks.toMutableList().also {
            val tmp = it[ci]; it[ci] = it[cj]; it[cj] = tmp
        }
        saveMetaLocked()
        notifyChanged()
        true
    }

    /** 删除自定义曲目（合并下标）；内置项与越界返回 false */
    fun removeTrack(index: Int): Boolean = synchronized(lock) {
        val ci = index - Playlist.tracks.size
        if (ci !in customTracks.indices) return false
        val t = customTracks[ci]
        File(t.asset).delete()
        customTracks = customTracks - t
        saveMetaLocked()
        notifyChanged()
        true
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return base.replace(Regex("[^A-Za-z0-9._\\-\u4e00-\u9fa5]"), "_").ifBlank { "file" }
    }

    private fun notifyChanged() {
        mainHandler.post { onLibraryChanged?.invoke() }
    }

    // ── meta.json 持久化（调用方持锁）────────────────────────

    private fun loadMeta() = synchronized(lock) {
        val walls = mutableListOf<String>()
        val wallNames = mutableMapOf<String, String>()
        val tracks = mutableListOf<Track>()
        try {
            if (metaFile.exists()) {
                val json = JSONObject(metaFile.readText())
                val w = json.optJSONArray("wallpapers") ?: JSONArray()
                for (i in 0 until w.length()) {
                    val o = w.getJSONObject(i)
                    val f = o.optString("file")
                    if (f.isNotBlank() && File(wallDir, f).exists()) {
                        walls.add(f)
                        // #24 展示名（老 meta 无此字段则回退文件名）
                        val dn = o.optString("name")
                        if (dn.isNotBlank()) wallNames[f] = dn
                    }
                }
                val t = json.optJSONArray("tracks") ?: JSONArray()
                for (i in 0 until t.length()) {
                    val o = t.getJSONObject(i)
                    val f = o.optString("file")
                    val file = File(musicDir, f)
                    if (f.isNotBlank() && file.exists()) {
                        tracks.add(
                            Track(
                                o.optString("title").ifBlank { file.nameWithoutExtension.replaceFirst(Regex("^\\d+_\\d+_"), "") },
                                o.optString("sub").ifBlank { "MY MUSIC · 自定义" },
                                file.absolutePath,
                                isCustom = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // meta 损坏则从空开始；已落盘文件不删，用户仍可从 H5 删除
        }
        customWalls = walls
        customWallNames = wallNames
        customTracks = tracks
    }

    private fun saveMetaLocked() {
        try {
            val w = JSONArray()
            customWalls.forEach {
                w.put(JSONObject().put("file", it).put("name", customWallNames[it] ?: ""))
            }
            val t = JSONArray()
            customTracks.forEach {
                t.put(
                    JSONObject()
                        .put("file", File(it.asset).name)
                        .put("title", it.title)
                        .put("sub", it.sub)
                )
            }
            metaFile.writeText(JSONObject().put("wallpapers", w).put("tracks", t).toString(2))
        } catch (e: Exception) {
            // 写失败不致命：本次会话内存态仍正确，下次启动按目录扫描兜底
        }
    }
}
