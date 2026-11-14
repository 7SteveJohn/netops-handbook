package com.netops.handbook.lens

import android.app.Activity
import org.json.JSONObject

/**
 * 数据备份（2026-11-14 M1d-1）：JS buildBackup/exportData/importData 的原生版，
 * 键名与格式双向兼容（旧版 App / 新版 互通）。
 *
 * 键面（导出+导入）：
 *   done / fav            = JSON 对象 {id:1}（JS store 格式；原生 SP netops_native
 *                           的逗号列表双向换算）
 *   quizWrong             = JSON 对象 {指纹:{m:模块锚点,n:错次}}（M1d-2 起原生写）
 *   netops_lglass         = none/standard/ultra（玻璃三档，M1c 起原生真源）
 *   netops_tabbar         = float/regular
 *   netops_theme          = auto/light/dark（JS 旧版不识别此键→忽略；新版恢复）
 *   netops_wallpaper      = 选择 id（'custom' 只记标记，图片本体不进文本备份，
 *                           恢复后由用户重选——与 JS 侧口径一致）
 *   netops_cli_hist       = JSON 数组字符串（CLI 历史裸键，上限 50）
 *
 * 真源映射：netops_native（done/fav/quizWrong）、netops.shell（theme/glass/
 * dockstyle/cli_hist/wallpaper select）——即 M2-4/M1c 收口后的原生真源。
 */
object BackupManager {

    data class Summary(val ok: Boolean, val restored: Int, val ignored: Int, val message: String)

    /* ---- 导出 ---- */

    @JvmStatic fun fileName(): String = "NetOps-数据备份-" +
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date()) + ".json"

    @JvmStatic fun buildJson(activity: Activity): String = collectData(activity).toString(2)

    private fun collectData(activity: Activity): JSONObject {
        val data = JSONObject()
        var n = 0
        val native = activity.getSharedPreferences("netops_native", Activity.MODE_PRIVATE)
        data.put("done", commaToJson(native.getString("done", "") ?: "")); n++
        data.put("fav", commaToJson(native.getString("fav", "") ?: "")); n++
        data.put("quizWrong", JSONObject(native.getString("quizWrong", "{}"))); n++
        val shell = activity.getSharedPreferences("netops.shell", Activity.MODE_PRIVATE)
        data.put("netops_theme", shell.getString("theme", null) ?: "auto"); n++
        data.put("netops_lglass", shell.getString("glass_level", null) ?: "ultra"); n++
        data.put("netops_tabbar",
            if (shell.getBoolean("dockstyle_docked", false)) "regular" else "float"); n++
        data.put("netops_wallpaper", WallpaperPrefs.select); n++
        val hist = shell.getString("cli_hist", null)
        if (hist != null) { data.put("netops_cli_hist", hist); n++ }
        android.util.Log.i("Backup", "导出 $n 项")
        return data
    }

    fun count(activity: Activity): Int = collectData(activity).length()

    /* ---- 导入 ---- */

    @JvmStatic fun importData(activity: Activity, text: String): Summary {
        val data = try { JSONObject(text) } catch (e: Throwable) {
            return Summary(false, 0, 0, "导入失败：不是有效的 JSON 备份")
        }
        val native = activity.getSharedPreferences("netops_native", Activity.MODE_PRIVATE)
        val shell = activity.getSharedPreferences("netops.shell", Activity.MODE_PRIVATE)
        var restored = 0
        var ignored = 0
        var doneJson: JSONObject? = null
        var favJson: JSONObject? = null
        for (k in data.keys()) {
            val v = data.opt(k) ?: continue
            when (k) {
                "done" -> if (isObj(v)) { doneJson = v as JSONObject; restored++ } else ignored++
                "fav" -> if (isObj(v)) { favJson = v as JSONObject; restored++ } else ignored++
                "quizWrong" -> if (isObj(v)) {
                    native.edit().putString("quizWrong", v.toString()).apply(); restored++
                } else ignored++
                "netops_theme" -> {
                    val m = v as? String ?: ""; if (m == "auto" || m == "light" || m == "dark") {
                        shell.edit().putString("theme", m).apply()
                        if (activity is com.netops.handbook.MainActivity) activity.applyThemeMode(m)
                        restored++
                    } else ignored++
                }
                "netops_lglass" -> {
                    val m = v as? String ?: ""
                    if (m == "none" || m == "standard" || m == "ultra") {
                        if (activity is com.netops.handbook.MainActivity) activity.applyGlassLevel(m)
                        restored++
                    } else ignored++
                }
                "netops_tabbar" -> {
                    val m = v as? String ?: ""
                    if (m == "float" || m == "regular") {
                        if (activity is com.netops.handbook.MainActivity)
                            activity.applyDockStyle(m == "regular")
                        restored++
                    } else ignored++
                }
                "netops_wallpaper" -> {
                    val m = v as? String ?: ""
                    if (m.isNotEmpty() && m.length < 400) {
                        WallpaperPrefs.setSelect(activity, m); restored++
                    } else ignored++
                }
                "netops_cli_hist" -> {
                    val s = v as? String ?: ""
                    if (s.length <= 200000) { shell.edit().putString("cli_hist", s).apply(); restored++ }
                    else ignored++
                }
                else -> ignored++
            }
        }
        if (restored == 0) return Summary(false, 0, ignored, "导入失败：备份里没有本应用可恢复的数据")
        /* done/fav 并入原生页（mergeLegacyState 即 SP 合并语义） */
        if (doneJson != null || favJson != null) {
            if (activity is com.netops.handbook.MainActivity)
                activity.refreshNativeState(doneJson, favJson)
        }
        return Summary(true, restored, ignored, "已恢复 $restored 项数据")
    }

    private fun isObj(v: Any): Boolean = v is JSONObject

    private fun commaToJson(comma: String?): JSONObject {
        val o = JSONObject()
        for (s in (comma ?: "").split(",")) if (s.isNotEmpty()) o.put(s, 1)
        return o
    }
}
