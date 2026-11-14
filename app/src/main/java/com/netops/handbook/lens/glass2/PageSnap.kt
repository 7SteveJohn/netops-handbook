package com.netops.handbook.lens.glass2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 页面内容快照源(2026-11-15 终态,dock 独立弹窗的内容通道)。
 *
 * 演进:①PixelCopy 直读主窗 Surface(旧 LensBarView 路线)→ ②本机实测回调
 * 时序不可靠(首帧后静默冻结/失败),且 alpha 通道不可控——**照抄旧 dock 最终
 * 形态:View.draw() 同步录软件画布**(旧 captureBackdrop 同款,跑过数月):
 * 内容树 ComposeView.draw() 软渲染进 ARGB 位图,alpha 完整、同步无回调丢失、
 * 无自反馈(dock 在独立弹窗=另一棵 View 树,不在内容树内)。
 *
 * 结构 = 三环缓冲同构:3 张轮换位图,主线程 post 写"写槽"并推进 writeIdx;
 * Compose drawBehind(RenderThread)读 writeIdx−1 槽(上一份完整帧)——读写槽
 * 永不相交,流水线深度 ≤2,3 环覆盖。25fps 节流(旧 captureLiveBackdrop 同款)。
 *
 * 成本:内容树软渲染 ≈ 十几 ms/次(旧 captureBackdrop 实测同量级),40ms 节流
 * 下主线程占空比可接受(旧 dock 同款跑数月)。
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

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private const val TAG = "PageSnap"
    private var diag = 0

    /** 玻璃层每帧调用:节流 25fps,对 bar 外扩矩形发起同步软录(post 到本帧遍历后)。 */
    fun request(view: View, contentRect: RectF) {
        if (pending) return
        if (view.width <= 0 || view.height <= 0) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastMs < 40) return   // 25fps 节流(旧 captureLiveBackdrop 同款)
        lastMs = now
        val l = contentRect.left.toInt().coerceAtLeast(0)
        val t = contentRect.top.toInt().coerceAtLeast(0)
        val r = contentRect.right.toInt().coerceAtMost(view.width)
        val b = contentRect.bottom.toInt().coerceAtMost(view.height)
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
        if (diag == 0) android.util.Log.i(TAG, "first request rect=($l,$t,$r,$b) view=${view.width}x${view.height}")
        mainHandler.post {
            try {
                val cap = Canvas(target)
                cap.translate(-l.toFloat(), -t.toFloat())
                view.draw(cap)
                writeIdx = (writeIdx + 1) % 3
                version++
                if (diag == 0) {
                    val px = target.getPixel(target.width / 2, target.height / 2)
                    android.util.Log.i(TAG,
                        "snap ready ${target.width}x${target.height} " +
                        "px a=${px ushr 24} rgb=${px and 0xFFFFFF}")
                }
            } catch (t: Throwable) {
                if (diag < 10) android.util.Log.w(TAG, "draw capture fail", t)
            } finally {
                pending = false
                diag++
            }
        }
    }

    /** 最新完整帧(drawBehind/RenderThread 读安全:读槽≠写槽≠上一槽)。 */
    fun latest(): Bitmap? = ring[(writeIdx + 2) % 3]
}
