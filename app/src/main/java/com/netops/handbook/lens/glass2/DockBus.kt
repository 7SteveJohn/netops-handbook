package com.netops.handbook.lens.glass2

import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose dock 的桥总线(2026-11-12,P2 迁移)。
 *
 * 职责:把 MainActivity 桥(JS 几何/主题/档位/滚动信号)喂给 Compose 侧的
 * GlassDock,并把 dock 的切页决定回传。字段全部是 Compose State——dock 的
 * 绘制 lambda 读它们,任何写入自动触发重绘;Java 桥经 @JvmStatic 方法写入。
 *
 * 坐标口径:**主窗口本地物理像素**(与旧 LensBarView 的 barRect 同源——
 * JS getBoundingClientRect × dpr)。新 dock 与内容同窗同树,不再需要
 * 旧方案的「弹窗偏移换算」:窗口本地 == Compose Box 本地。
 *
 * 旧 LensBarView(独立 PopupWindow + PixelCopy 链)保留在库里但不再挂载
 * (MainActivity.USE_GLASS2 门控),真机浸泡一个版本后再删(回退保险)。
 */
object DockBus {

    /* ---- 档位开关(编译期定,回退改 false 即回旧链) ----
       2026-11-14 恢复 glass2 默认+按设备分流内容源:
       - 非 HONOR(MagicOS):页面 record 同帧折射(KernelSU 结构原样,平板已验证);
       - HONOR(MagicOS)(MagicOS):WebView 被 GraphicsLayer.record 离屏重绘时
         Chromium tile 内存超限拒绘(探针 D 验证点实锤)——页面直绘,玻璃内容源
         走 PageSnap(PixelCopy 直读已合成 Surface,同旧路线已验证通道),
         玻璃链(prism shader/RenderEffect)原样保留。 */
    const val USE_GLASS2 = true

    /** 页面内容能否 record 离屏(玻璃同帧折射):HONOR(MagicOS) 的 WebView 判死 →
     *  false = 页面直绘 + PageSnap 快照喂玻璃。 */
    val CAPTURE_PAGES: Boolean by lazy {
        val m = android.os.Build.MANUFACTURER.uppercase()
        !(m.contains("HUAWEI") || m.contains("HONOR"))
    }

    /* ---- 几何(JS 上报,窗口本地 px) ---- */
    val barRect = RectF()                       // 栏体矩形(窗口本地)
    var pillCyPx by mutableFloatStateOf(0f)     // 药丸中心 y(窗口本地)
    var viewportH by mutableFloatStateOf(0f)    // 页面视口物理高(=窗口高)
    var pitchPx by mutableFloatStateOf(0f)      // 格距
    var pillWPx by mutableFloatStateOf(0f)      // 药丸 CSS 尺寸(物理 px)
    var pillHPx by mutableFloatStateOf(0f)
    var firstCxPx by mutableFloatStateOf(0f)    // 首格中心 x

    /* ---- 模式 ----
       @JvmName:属性 setter 与下方 @JvmStatic 桥函数 JVM 签名冲突,错开命名 */
    var nativeDock by mutableStateOf(false)   // 原生底栏接管(netops_native_dock)
    var docked by mutableStateOf(false)           // 底栏样式=原生贴底(netops_tabbar=regular)
    var glassLevel by mutableStateOf("ultra") // none / standard / ultra
    /* M2-2(2026-11-14):dark 单源化——委托 AppShellState.isDark（ThemeBus 只写
       单源；GlassDock 组合期读本属性=订阅 AppShellState,重组语义不变）。 */
    var dark: Boolean
        get() = com.netops.handbook.ui.AppShellState.isDark
        set(v) { com.netops.handbook.ui.AppShellState.isDark = v }
    var railTopPx by mutableFloatStateOf(0f)    // 竖排侧栏顶部让位(顶栏底边,窗口本地)
    var overlaid by mutableStateOf(false)       // 抽屉在场 → dock 让位隐藏

    /* ---- 内容/选择 ---- */
    var selectedIndex by mutableIntStateOf(0)
    /** 内容重录信号:滚动/切页/主题/几何任何变化都 bump,dock 读它触发重绘
     *  (捕获层由内容节点在自身重绘时自动 re-record,dock 只需跟着重放)。 */
    var refreshTick by mutableIntStateOf(0)

    /* ---- 回传 ---- */
    /** dock 松手 → 原生切页(MainActivity 注入,等价旧 setOnCommit)。 */
    @Volatile var onCommit: ((Int) -> Unit)? = null
        private set

    @JvmStatic
    fun bump() { refreshTick++ }

    /** 主题(ThemeBus 经 MainActivity 注入)。 */
    @JvmStatic
    fun pushDark(d: Boolean) { if (dark != d) { dark = d; bump() } }

    @JvmStatic
    fun setOnCommit(l: ((Int) -> Unit)?) { onCommit = l }

    /** 滚动信号:ViewTreeObserver OnScrollChanged,40ms 节流(=旧 25fps invalidate)。 */
    private var lastScrollBump = 0L

    @JvmStatic
    fun onScrollThrottled() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScrollBump >= 40) { lastScrollBump = now; bump() }
    }

    /* ================= Java 桥入口(MainActivity Bridge 调用) ================= */

    /** 栏矩形(窗口本地)。底栏恒底锚:落进上半窗 = CSS 过渡期的废矩形,拒收
     *  (照搬旧 setLensBarRect 的护栏,2026-11-09「dock 消失」根因)。 */
    @JvmStatic
    fun setBarRect(l: Float, t: Float, w: Float, h: Float) {
        if (viewportH > 0 && t + h < viewportH * 0.6f) return
        if (Math.abs(barRect.left - l) > 1 || Math.abs(barRect.top - t) > 1
            || Math.abs(barRect.width() - w) > 1 || Math.abs(barRect.height() - h) > 1
        ) {
            barRect.set(l, t, l + w, t + h)
            bump()
        }
    }

    /** setLensGeometry 桥:一格距/药丸尺寸/首格中心/药丸中心 y/视口高。 */
    @JvmStatic
    fun setGeometry(pitch: Float, pillW: Float, pillH: Float, firstCx: Float,
                    pillCyScreen: Float, viewportHPx: Float) {
        pitchPx = pitch
        pillWPx = pillW
        pillHPx = pillH
        firstCxPx = firstCx
        var cy = pillCyScreen
        if (viewportHPx > pillH) {
            cy = maxOf(pillH / 2f + 2f, Math.min(pillCyScreen, viewportHPx - pillH / 2f - 2f))
        }
        pillCyPx = cy
        viewportH = viewportHPx
        bump()
    }

    @JvmStatic
    fun pushSelectedIndex(i: Int) {
        val v = i.coerceIn(0, 3)
        if (selectedIndex != v) { selectedIndex = v; bump() }
    }

    @JvmStatic
    fun pushNativeDock(on: Boolean) { if (nativeDock != on) { nativeDock = on; bump() } }

    @JvmStatic
    fun setDockedStyle(d: Boolean) { if (docked != d) { docked = d; bump() } }

    @JvmStatic
    fun pushGlassLevel(level: String) {
        if (glassLevel != level) { glassLevel = level; bump() }
    }

    @JvmStatic
    fun setRailTop(px: Float) { if (Math.abs(railTopPx - px) > 0.5f) { railTopPx = px; bump() } }

    @JvmStatic
    fun pushOverlaid(o: Boolean) { if (overlaid != o) { overlaid = o; bump() } }

    /** 滚动信号(不节流,内部用)。 */
    @JvmStatic
    fun onScroll() { bump() }
}
