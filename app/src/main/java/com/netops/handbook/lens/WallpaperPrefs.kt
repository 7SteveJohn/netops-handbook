package com.netops.handbook.lens

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * 壁纸偏好真源（2026-11-14 M2-4，全 Compose 重构地基④）。
 *
 * 此前壁纸选择状态的真源在 WebView localStorage（netops_wallpaper /
 * netops_wp_custom / netops_glass.tint），原生 NativeBackdrop 只吃 JS 的
 * syncNativePageMode 镜像。本对象把真源迁到 SharedPreferences：
 *  - 迁移器：JS boot 一次性推送存量（migrateWallpaperState 桥，带
 *    netops_wp_mig_done 双侧标记防重复），dataURL 相册图解码落盘；
 *  - 设置面板（M4 起 Compose）直写直读；
 *  - NativeBackdrop / 页面 directMode 由 apply() 驱动（与原 syncNativePageMode
 *    下发链同语义）。
 * 过渡期 JS paintWallpaper 继续跑（CLI 页 .wall 层显示），M6 拆 WebView 后退役。
 *
 * blur 公式与 JS syncNativePageMode 同源：`2 + (tint-10) * 0.15`，tint ∈ [10,95]
 * 缺省 55（netops_glass JSON 的 glass.tint）。
 */
object WallpaperPrefs {

    private const val K_SELECT = "netops_wp_select"       // 'custom' / 'wp-x-y' / 'none'
    private const val K_CUSTOM = "netops_wp_custom_file"  // 自定义图落盘绝对路径
    private const val K_TINT = "netops_wp_tint"           // 玻璃 tint（blur 公式输入）
    private const val K_MIGRATED = "netops_wp_migrated"

    private lateinit var sp: android.content.SharedPreferences

    var select by mutableStateOf("none")            // 当前选择 id
        private set
    var customFile by mutableStateOf("")            // 相册图落盘绝对路径
        private set
    var tint by mutableIntStateOf(55)               // 通透度（blur 公式输入）
        private set

    /**
     * 壁纸位图 Compose State（2026-11-14 View 层退役：GlassDockHost 的壁纸层
     * 改 Compose Image 直绘，不再经 AndroidView(anchor)；解码异步、inSampleSize 2
     * 与 NativeBackdrop.decode 同口径）。null = 无壁纸（页底纯色）。
     */
    var wallBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
        private set

    /** 壁纸清单（native-data.json wallpapers 字段；nativeSettingsPanel 已直读）。 */
    var wallpapers: JSONArray = JSONArray()
        private set

    @JvmStatic fun init(activity: Activity, wallpapersArray: JSONArray) {
        sp = activity.getSharedPreferences("netops_shell", Activity.MODE_PRIVATE)
        appContext = activity.applicationContext
        wallpapers = wallpapersArray
        select = sp.getString(K_SELECT, "none") ?: "none"
        customFile = sp.getString(K_CUSTOM, "") ?: ""
        tint = sp.getInt(K_TINT, 55)
        apply(activity)   // 启动重放:进程被杀后 SP 状态仍生效(原仅面板触达)
    }

    /** JS boot 一次性迁移：存量选择 + 相册 dataURL（可能数 MB，仅首次）+ tint。 */
    @JvmStatic fun migrate(activity: Activity, selectId: String, customDataUrl: String, glassTint: Int) {
        if (sp.getBoolean(K_MIGRATED, false)) return
        sp.edit().putBoolean(K_MIGRATED, true).apply()
        tint = glassTint.coerceIn(10, 95)
        sp.edit().putInt(K_TINT, tint).apply()
        if (selectId == "custom" && customDataUrl.startsWith("data:")) {
            val path = saveDataUrl(activity, customDataUrl)
            if (path != null) {
                customFile = path
                sp.edit().putString(K_CUSTOM, path).apply()
            }
        }
        setSelectInternal(activity, selectId)
    }

    /** 设置面板改壁纸（value = 壁纸 id / 'custom' / 'none'）。 */
    @JvmStatic fun setSelect(activity: Activity, id: String) = setSelectInternal(activity, id)

    /** 相册选图落盘（dataURL → files/wallpaper_custom.jpg）并切换。 */
    @JvmStatic fun setCustomFromDataUrl(activity: Activity, dataUrl: String): Boolean {
        val path = saveDataUrl(activity, dataUrl) ?: return false
        customFile = path
        sp.edit().putString(K_CUSTOM, path).apply()
        setSelectInternal(activity, "custom")
        return true
    }

    fun setTint(activity: Activity, v: Int) {
        tint = v.coerceIn(10, 95)
        sp.edit().putInt(K_TINT, tint).apply()
        apply(activity)
    }

    private fun setSelectInternal(activity: Activity, id: String) {
        select = if (id.startsWith("wp-") || id == "custom" || id == "none") id else "none"
        sp.edit().putString(K_SELECT, select).apply()
        apply(activity)
    }

    /** 驱动 NativeBackdrop + 全页 directMode + wallBitmap（原 applyNativePageMode 等价逻辑）。 */
    fun apply(activity: Activity) {
        val landscape = activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wp = resolveWp(activity, landscape)
        val direct = wp != null
        val file = wp?.optString("file") ?: if (select == "custom") customFile else ""
        val blur = blurDp()
        activity.runOnUiThread {
            if (activity is com.netops.handbook.MainActivity) activity.applyPageMode(direct, file, blur)
        }
        /* Compose 壁纸层真源（View 层退役）：横竖配对与 NativeBackdrop 同算法 */
        decodeWallAsync(com.netops.handbook.lens.NativeBackdrop.resolveOrientationFile(file, landscape) ?: file)
    }

    /**
     * 壁纸位图异步解码+预糊（View 层退役）：file 空=无壁纸；绝对路径=相册落盘图
     * （customFile），其余按 assets wallpapers/ 解析。inSampleSize 2 与
     * NativeBackdrop.decode 同口径；预糊在后台线程跑（3 遍盒式模糊几毫秒级），
     * 完成写 State，Compose 壁纸层自动重组。
     */
    private fun decodeWallAsync(file: String) {
        if (file.isEmpty()) { wallBitmap = null; return }
        android.util.Log.i("WallpaperPrefs", "decodeWallAsync file=$file blur=${blurDp()}")
        val blur = blurDp()
        decodeExec.execute {
            val ctx = appContext ?: return@execute
            val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            val sharp = try {
                if (file.startsWith("/")) {
                    java.io.FileInputStream(java.io.File(file)).use {
                        android.graphics.BitmapFactory.decodeStream(it, null, o)
                    }
                } else {
                    ctx.assets.open("wallpapers/$file").use {
                        android.graphics.BitmapFactory.decodeStream(it, null, o)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w("WallpaperPrefs", "wallBitmap 解码失败 $file", e)
                null
            } ?: return@execute
            /* ❗强制 software 位图(2026-11-14 平板实证)：API 26+ 默认 HARDWARE
               位图在 MagicOS 上层提升盖住 Compose 兄弟(平板 t1/t2:壁纸在/页面灭)；
               software 位图无独立 surface,Compose Image 直绘安全。 */
            val soft = if (sharp.config == android.graphics.Bitmap.Config.HARDWARE)
                sharp.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else sharp
            wallBitmap = preBlur(ctx, soft, blur)
            android.util.Log.i("WallpaperPrefs",
                "wallBitmap ready ${soft.width}x${soft.height} cfg=${soft.config}")
        }
    }

    /** 一次性预糊（NativeBackdrop.preBlur 同口径：缩到 320w→盒式模糊 3 遍→显示放大）。 */
    private fun preBlur(
        ctx: android.content.Context,
        src: android.graphics.Bitmap,
        blurDp: Float,
    ): android.graphics.Bitmap {
        val tw = minOf(320, src.width)
        val th = maxOf(1, Math.round(src.height.toFloat() * tw / src.width))
        val small = android.graphics.Bitmap.createScaledBitmap(src, tw, th, true)
        val dm = ctx.resources.displayMetrics
        val scale = maxOf(1f, dm.widthPixels.toFloat() / tw)
        val r = maxOf(1, Math.round(maxOf(0.01f, blurDp) * dm.density / scale))
        val w = small.width; val h = small.height
        val px = IntArray(w * h); val tmp = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) {
            blurPass(px, tmp, w, h, r, horizontal = true)
            blurPass(tmp, px, w, h, r, horizontal = false)
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    /** 盒式模糊单遍（NativeBackdrop.blurH/blurV 合并版）。 */
    private fun blurPass(
        src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean,
    ) {
        val n = if (horizontal) w else h
        val m = if (horizontal) h else w
        val div = r * 2 + 1
        for (b in 0 until m) {
            var a = 0L; var rr = 0L; var gg = 0L; var bb = 0L
            for (i in -r..r) {
                val c = src[if (horizontal) b * w + i.coerceIn(0, n - 1) else i.coerceIn(0, n - 1) * w + b]
                a += (c ushr 24); rr += ((c shr 16) and 0xff); gg += ((c shr 8) and 0xff); bb += (c and 0xff)
            }
            for (x in 0 until n) {
                val idx = if (horizontal) b * w + x else x * w + b
                val out = ((a / div).toInt() shl 24) or ((rr / div).toInt() shl 16) or
                        ((gg / div).toInt() shl 8) or (bb / div).toInt()
                dst[idx] = out
                val addI = (x + r + 1).coerceAtMost(n - 1)
                val remI = (x - r).coerceAtLeast(0)
                val add = src[if (horizontal) b * w + addI else addI * w + b]
                val rem = src[if (horizontal) b * w + remI else remI * w + b]
                a += (add ushr 24) - (rem ushr 24)
                rr += ((add shr 16) and 0xff) - ((rem shr 16) and 0xff)
                gg += ((add shr 8) and 0xff) - ((rem shr 8) and 0xff)
                bb += (add and 0xff) - (rem and 0xff)
            }
        }
    }

    /** 解码+预糊串行队列（换壁纸/拖 tint 时任务排队，最后一帧胜出）。 */
    private val decodeExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 解码线程用的 app context（init 时挂上；apply 不依赖 Activity 生命周期）。 */
    @Volatile private var appContext: android.content.Context? = null

    /** 旋转重解析（MainActivity.onConfigurationChanged 调）。 */
    @JvmStatic fun applyForOrientationChange(activity: Activity) = apply(activity)

    /** 横竖屏配对解析（resolveWpForOrientation 同语义），custom → 绝对路径。 */
    fun resolveWp(activity: Activity, landscape: Boolean): JSONObject? {
        if (select == "custom") {
            if (customFile.isEmpty()) return null
            val o = JSONObject()
            o.put("file", customFile)
            return o
        }
        if (select == "none") return null
        for (i in 0 until wallpapers.length()) {
            val w = wallpapers.optJSONObject(i) ?: continue
            if (w.optString("id") == select) {
                val isLand = w.optString("orientation") == "landscape"
                if (isLand != landscape) {
                    val pair = w.optString("pair")
                    for (j in 0 until wallpapers.length()) {
                        val p2 = wallpapers.optJSONObject(j) ?: continue
                        if (p2.optString("id") == pair) return p2
                    }
                }
                return w
            }
        }
        return null
    }

    /** blur 公式（与 JS syncNativePageMode 同源）。 */
    fun blurDp(): Float = 2f + (tint.coerceIn(10, 95) - 10) * 0.15f

    private fun saveDataUrl(activity: Activity, dataUrl: String): String? {
        return try {
            val b64 = dataUrl.substringAfter("base64,", "")
            if (b64.isEmpty()) return null
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val f = java.io.File(activity.filesDir, "wallpaper_custom.jpg")
            java.io.FileOutputStream(f).use { it.write(bytes) }
            f.absolutePath
        } catch (e: Throwable) {
            android.util.Log.w("WallpaperPrefs", "custom 落盘失败", e)
            null
        }
    }
}
