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
import java.io.InputStream

/**
 * #23 自定义内容库：内置资产 + 用户上传（H5 管理页）的合并视图。
 * - 壁纸命名约定：内置 = "wall_01.jpg"（assets/wallpapers/），自定义 = "custom/<文件名>"（custom/wallpapers/）
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
