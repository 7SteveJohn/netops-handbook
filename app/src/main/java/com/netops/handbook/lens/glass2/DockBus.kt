package com.netops.handbook.lens.glass2

import android.graphics.RectF
import android.view.View
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
       2026-11-14 M1d-3:WebView 已摘除(唯一 record 死因),树里只剩 View/Compose——
       全设备统一 glass2 record 同帧折射(KernelSU 结构原样),分流/PageSnap 妥协层
       待 M3 清理退役。旧 LensBarView 保留为库内回退位。 */
    const val USE_GLASS2 = true

    /** 页面内容能否 record 离屏：M1d-3 WebView 摘除后树里只剩 View/Compose——
     *  探针实证 record 对 View/Compose 全绿（死的只有 WebView），恒 true。
     *  (2026-11-14 二次实证修订：record/AGSL/位图绘在 HONOR 上连纯 Compose
     *  兄弟内容一并毒杀，dock 已进独立弹窗，本旗退化为死值,M6 清。) */
    val CAPTURE_PAGES: Boolean = true

    /* ---- dock 独立弹窗(2026-11-14 终态) ----
       同窗(哪怕两棵 ComposeView 树)有 dock 子树时普通内容不被 MagicOS
       合成器绘制；dock 进专属 PopupWindow(自带 window/surface/composer)。 */
    var winWpx by mutableIntStateOf(0)   // 窗口物理宽(computeDockGeometry 落)
    var winHpx by mutableIntStateOf(0)   // 窗口物理高
    /** 内容树 View(PageSnap 软录源,dock 弹窗玻璃采样它)。 */
    @JvmField var contentView: View? = null
    var dockOrigin by mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero) // 弹窗原点(窗口本地)
    /** GlassDock 自算矩形+外扩 pad+窗口尺寸上报(弹窗几何真源)。 */
    @JvmField var onDockRect: ((android.graphics.RectF, Float) -> Unit)? = null
    /** 覆盖层开合(设置/抽屉)→弹窗 dismiss/reshow。 */
    @JvmField var onOverlaid: ((Boolean) -> Unit)? = null

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
    fun pushOverlaid(o: Boolean) {
        if (overlaid != o) { overlaid = o; bump() }
        onOverlaid?.invoke(o)
    }

    /** GlassDock 合成期上报弹窗矩形(带去重:rect/pad 全等才忽略)。 */
    fun pushDockRect(r: RectF, padPx: Float) {
        onDockRect?.invoke(RectF(r), padPx)
    }

    /** Java 几何入口(computeDockGeometry 尾调)：窗口尺寸+当前栏矩形。 */
    @JvmStatic
    fun pushPopupGeometry(winW: Int, winH: Int, r: RectF, padPx: Float) {
        winWpx = winW
        winHpx = winH
        onDockRect?.invoke(RectF(r), padPx)
    }

    /** 滚动信号(不节流,内部用)。 */
    @JvmStatic
    fun onScroll() { bump() }
}
