package com.netops.handbook.lens.glass2

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.view.PixelCopy
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 页面内容快照源(2026-11-14 手机兼容,KernelSU 结构的该机内容通道)。
 *
 * 背景:MagicOS(HONOR(MagicOS))定制 WebView 被 GraphicsLayer.record 离屏重绘时,
 * Chromium 合成器 tile 内存超限拒绘(`tile memory limits exceeded, some content
 * may not draw` 实锤,探针 D 验证点)——record 路在该机不可用。PixelCopy 直读
 * **已合成的窗口 Surface**,不触发 WebView.draw() 离屏重绘(旧 LensBarView 路线
 * 跑过多轮已验证),是该机唯一健康的内容通道。
 *
 * 结构 = 旧路线三环缓冲同构:3 张轮换位图,主线程 PixelCopy 回调写"写槽"并
 * 推进 writeIdx;Compose drawBehind(RenderThread)读 writeIdx−1 槽(上一份
 * 完整帧)——读写槽永不相交,流水线深度 ≤2,3 环覆盖。25fps 节流(旧同款)。
 *
 * 平板/非 HONOR 设备不走此对象(DockBus.CAPTURE_PAGES=true → record 同帧)。
 */
object PageSnap {

    private val ring = arrayOfNulls<Bitmap>(3)
    private var writeIdx = 0
    private var lastMs = 0L
    private var pending = false

    /** 快照左上角的窗口坐标(drawBehind 用 Matrix 对齐玻璃节点)。 */
    @Volatile var snapL: Int = 0
    @Volatile var snapT: Int = 0

    /** 快照完成版本号(State):驱动玻璃层重绘取新帧。 */
    var version by mutableIntStateOf(0)
        private set

    /** 玻璃层每帧调用:节流 25fps,对 bar 外扩矩形发起 PixelCopy。 */
    fun request(window: Window, contentRect: RectF) {
        if (pending) return
        val now = SystemClock.uptimeMillis()
        if (now - lastMs < 40) return   // 25fps 节流(旧 captureLiveBackdrop 同款)
        lastMs = now
        val dl = window.decorView
        val l = contentRect.left.toInt().coerceAtLeast(0)
        val t = contentRect.top.toInt().coerceAtLeast(0)
        val r = contentRect.right.toInt().coerceAtMost(dl.width)
        val b = contentRect.bottom.toInt().coerceAtMost(dl.height)
        val w = r - l
        val h = b - t
        if (w < 4 || h < 4) return
        val bmp = ring[writeIdx]
        val target = if (bmp == null || bmp.width != w || bmp.height != h) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { ring[writeIdx] = it }
        } else bmp
        pending = true
        snapL = l
        snapT = t
        if (diag == 0) android.util.Log.i(TAG, "first request rect=($l,$t,$r,$b) win=${dl.width}x${dl.height}")
        PixelCopy.request(window, Rect(l, t, r, b), target, { res ->
            pending = false
            if (res == PixelCopy.SUCCESS) {
                writeIdx = (writeIdx + 1) % 3
                version++
                if (diag == 0) android.util.Log.i(TAG, "snap ready ${target.width}x${target.height}")
            } else if (diag < 5) {
                android.util.Log.w(TAG, "PixelCopy res=$res")
            }
            diag++
        }, Handler(Looper.getMainLooper()))
    }

    private const val TAG = "PageSnap"
    @Volatile private var diag = 0

    /** 最新完整帧(drawBehind/RenderThread 读安全:读槽≠写槽≠上一槽)。 */
    fun latest(): Bitmap? = ring[(writeIdx + 2) % 3]
}
