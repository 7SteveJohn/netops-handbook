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

    /** 壁纸清单（native-data.json wallpapers 字段；nativeSettingsPanel 已直读）。 */
    var wallpapers: JSONArray = JSONArray()
        private set

    fun init(activity: Activity, wallpapersArray: JSONArray) {
        sp = activity.getSharedPreferences("netops_shell", Activity.MODE_PRIVATE)
        wallpapers = wallpapersArray
        select = sp.getString(K_SELECT, "none") ?: "none"
        customFile = sp.getString(K_CUSTOM, "") ?: ""
        tint = sp.getInt(K_TINT, 55)
    }

    /** JS boot 一次性迁移：存量选择 + 相册 dataURL（可能数 MB，仅首次）+ tint。 */
    fun migrate(activity: Activity, selectId: String, customDataUrl: String, glassTint: Int) {
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
    fun setSelect(activity: Activity, id: String) = setSelectInternal(activity, id)

    /** 相册选图落盘（dataURL → files/wallpaper_custom.jpg）并切换。 */
    fun setCustomFromDataUrl(activity: Activity, dataUrl: String): Boolean {
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

    /** 驱动 NativeBackdrop + 全页 directMode（原 applyNativePageMode 等价逻辑）。 */
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
    }

    /** 旋转重解析（MainActivity.onConfigurationChanged 调）。 */
    fun applyForOrientationChange(activity: Activity) = apply(activity)

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
