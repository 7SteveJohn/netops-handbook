package com.netops.handbook.lens;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.PixelCopy;
import android.widget.PopupWindow;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;

/**
 * 原生液体玻璃透镜层（B 路线）。
 *
 * 为什么必须是原生：WebView 的 backdrop-filter 只能拿到"合成器给的那块背景"，
 * 拿不到任意内容像素；这里直接把 WebView 画进离屏 Bitmap，再用 AGSL(RuntimeShader)
 * 逐像素算折射 —— 这是 FlClash 在 Flutter/Skia 里做的事，搬到了 Android 原生层。
 *
 * 运动模型照搬 FlClash navigation_dock.dart：位置/抬起/果冻三根物理弹簧，
 * 形变由"速度 + 刹车"耦合驱动（wobble），抬起必须满足两个到位条件才回落（dropLift）。
 */
public class LensBarView extends View implements Choreographer.FrameCallback {

    private static final String TAG = "LensBar";

    /* ---- 光学：FlClash LiquidGlassStyle.lens（dp，源码实测值）---- */
    private static final float REFRACTION  = -4.3f;  // 负 = 凸起透镜
    private static final float BEVEL       = 5.5f;
    private static final float DISPERSION  = 6.5f;
    private static final float LIFT_GROWTH = 16f;   // 抬起时两向各增长 16dp

    /* ---- 行为常量：全部来自 navigation_dock.dart ---- */
    private static final float LIFT_SNAP     = 0.975f;  // 升到位才允许回落
    private static final float LIFT_RELEASE  = 0.04f;   // 且已移动到格
    private static final float OVERDRAG      = 0.35f;   // 越界上限（格）
    private static final float TOUCH_SLOP_DP = 8f;
    private static final float FLING_MS      = 100f;    // 速度投影时长

    /* ---- 弹簧：与 FlClash 同名同参 ---- */
    private static final SpringSpec FOLLOW  = SpringSpec.ratio(1024, 0.9);
    private static final SpringSpec TRAVEL  = SpringSpec.durationBounce(400, 0.15);
    private static final SpringSpec LIFT_S  = SpringSpec.durationBounce(250, 0);
    private static final SpringSpec JELLY_S = SpringSpec.ratio(100, 0.4);

    /* ---- AGSL：圆角矩形 SDF → 数值梯度法线 → 位移采样 ----
       光学公式逐项对齐 FlClash shaders/liquid_glass.frag（chen08209/FlClash,
       GPL-3.0；此处只取测量数值与公式结构，代码为本项目自写）：
       - 折射剖面：位移在 rim 处为 0、bevel 深度处全深、2×bevel 内收束归零，
         之外全平 —— 取它们「outer band draws out what lies right under the
         rim」的带形，但把向中心缓减的尾巴裁掉：它们的图标走独立重绘层，
         折射只碰栏面底衬；我们的图标文字在背景图里，内部必须真平才锐利。
       - 边缘霜化：rimBlur 1.5dp×rise²（rise=1−inset/bevel），rim 带内所有
         采样过模糊盘 —— 它把色散糊成柔和色晕的关键。用 5 采样近似其
         16 采样 Vogel 盘（reach = (σ−0.5dp)×2.078，同式）。
       - 色散：uEdge.y 公式——沿法线镜像方向 (n.y,n.x)，侧面阻尼 0.6，
         顶/底自 rim 向 7dp 缓收，7→10dp 平滑全收，带外为零（kPartHold/Reach）。
       - 暗线：kLine 0.67dp、cap 0.298、强度 hairline 0.42，贴边一圈勾轮廓
         并压住贴边色散。
       - 端部压暗：endShade 0.045 / kEndShade 3dp；暗色模式亮度 uOptics.z=1.33。 */
    private static final String AGSL =
            "uniform shader backdrop;\n" +
            "uniform float2 uCenter;\n" +
            "uniform float2 uHalf;\n" +
            "uniform float uRadius;\n" +
            "uniform float uRefract;\n" +     // 折射深度 px = 4.3dp×dpr×lift
            "uniform float uBevel;\n" +       // bevel 宽 px
            "uniform float uDisperse;\n" +    // 色散分离量 dp = 6.5×lift
            "uniform float uDp;\n" +
            "uniform float2 uBgOffset;\n" +
            "uniform float uMagnify;\n" +
            "uniform float uBrightness;\n" +  // 暗色 1.33 / 浅色 1
            "\n" +
            "float sdRound(float2 p, float2 b, float r) {\n" +
            "    float2 q = abs(p) - b + r;\n" +
            "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
            "}\n" +
            "\n" +
            "half3 eval3(float2 uv) {\n" +
            "    half4 s = backdrop.eval(uv);\n" +
            "    return half3(s.r, s.g, s.b);\n" +
            "}\n" +
            "\n" +
            /* 边缘霜化盘：中心 + 4 对角，reach 同 FlClash kFrostReach 口径 */
            "half3 frosted(float2 uv, float reach) {\n" +
            "    if (reach < 1.0) return eval3(uv);\n" +
            "    half3 s = eval3(uv) * 0.36;\n" +
            "    s += (eval3(uv + float2(reach, reach))\n" +
            "        + eval3(uv - float2(reach, reach))\n" +
            "        + eval3(uv + float2(reach, -reach))\n" +
            "        + eval3(uv - float2(reach, -reach))) * 0.16;\n" +
            "    return s;\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 p) {\n" +
            "    float2 local = p - uCenter;\n" +
            "    float2 b = uHalf;\n" +
            "    float r = min(uRadius, min(b.x, b.y));\n" +
            "    float d = sdRound(local, b, r);\n" +
            "    float inset = -d;\n" +
            "    float insetDp = inset / uDp;\n" +
            "\n" +
            "    float e = 0.75;\n" +
            "    float2 g = float2(\n" +
            "        sdRound(local + float2(e, 0.0), b, r) - sdRound(local - float2(e, 0.0), b, r),\n" +
            "        sdRound(local + float2(0.0, e), b, r) - sdRound(local - float2(0.0, e), b, r)\n" +
            "    );\n" +
            "    float2 n = (length(g) > 1e-6) ? normalize(g) : float2(0.0, 0.0);\n" +
            "\n" +
            "    float bevel = max(min(uBevel, min(b.x, b.y) * 0.5), 1e-3);\n" +
            "    float rise = 1.0 - clamp(inset / bevel, 0.0, 1.0);\n" +
            "    float k = smoothstep(0.0, bevel, inset)\n" +
            "        * (1.0 - smoothstep(bevel, 2.0 * bevel, inset));\n" +
            "    float2 shift = n * uRefract * k;\n" +
            "    float sig = 1.5 * rise * rise * uDp;\n" +
            "    float reach = max(sig - 0.5 * uDp, 0.0) * 2.07846096908;\n" +
            "\n" +
            "    float2 uv = p - uBgOffset;\n" +
            "    float2 cuv = uCenter - uBgOffset;\n" +
            "    /* 镜内放大从镜缘向内渐入（rim 1.0 → 深处 uMagnify）：放大若为阶跃，\n" +
            "       拖动时被镜缘骑跨的图标一半 1.16 倍一半原大 = 重影（2026-10-26\n" +
            "       真机用户反馈）。渐入对应真实透镜的曲率，交界连续无撕裂。 */\n" +
            "    float m = smoothstep(0.0, bevel * 3.0, inset);\n" +
            "    float mag = mix(1.0, uMagnify, m);\n" +
            "    uv = cuv + (uv - cuv) / mag;\n" +
            "\n" +
            "    float3 col;\n" +
            "    if (insetDp < 10.0) {\n" +
            "        float2 part = float2(n.y, n.x) * 0.5 * uDisperse\n" +
            "            * (0.6 + 0.4 * abs(n.y) * max(1.0 - insetDp / 7.0, 0.0))\n" +
            "            * (1.0 - smoothstep(7.0, 10.0, insetDp)) * uDp;\n" +
            "        col.r = frosted(uv + shift + part, reach).r;\n" +
            "        col.g = frosted(uv + shift, reach).g;\n" +
            "        col.b = frosted(uv + shift - part, reach).b;\n" +
            "    } else {\n" +
            "        col.r = frosted(uv + shift, reach).r;\n" +
            "        col.g = frosted(uv + shift, reach).g;\n" +
            "        col.b = frosted(uv + shift, reach).b;\n" +
            "    }\n" +
            "\n" +
            "    col *= uBrightness;\n" +
            "    col *= 1.0 - 0.045 * abs(n.x) * exp(-insetDp / 3.0);\n" +
            "\n" +
            "    float line = clamp((0.67 - insetDp) * uDp + 0.5, 0.0, 1.0);\n" +
            "    if (line > 0.0) {\n" +
            "        half4 under = backdrop.eval(p - uBgOffset);\n" +
            "        float3 underC;\n" +
            "        underC.r = under.r; underC.g = under.g; underC.b = under.b;\n" +
            "        float3 dark = max(underC - 0.298, 0.22 * underC);\n" +
            "        col = mix(col, mix(underC, dark, 0.42), line);\n" +
            "    }\n" +
            "    return half4(col.r, col.g, col.b, 1.0);\n" +
            "}\n";

    private final WebView webView;
    /* 主窗口：PixelCopy 要抓它。本层必须活在**独立 Window**（PopupWindow）里，
       否则抓主窗口时会连自己画的玻璃一起抓进来 = 反馈循环。 */
    private Window window;
    private PopupWindow popup;
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 背景未就绪的按压降级
    private final RectF pillRect = new RectF();
    private final Rect srcRect = new Rect();
    private final float density;

    private Bitmap backdrop;
    private BitmapShader backdropShader;
    private RuntimeShader shader;

    /* 几何（px）。全部由 JS 直接上报，原生层不做任何推导 —— CSS 才是几何真源 */
    private int bandW = 0, bandH = 0;
    private int count = 5;
    private float slot = 0;          // 一格宽
    private float pillW = 0, pillH = 0;
    private float firstCx = 0;       // 第 0 格药丸中心 x（屏幕 px）
    private float pillCyPx = 0;      // 药丸中心 y（由 JS 上报，避免和 safe-area 打架）
    private float viewportH = 0;     // 页面视口物理高（=真实窗口高）；heightPixels 是挖掉手势条的安全高，两者差 213px
    private float bgOffX = 0, bgOffY = 0;   // 背景图左上角在本 View 坐标里的位置

    /* 弹簧状态 */
    private Spring pos = new Spring(0);
    private final Spring lift = new Spring(0);
    private final Spring jelly = new Spring(0);
    private boolean pressed = false;
    private boolean dragging = false;
    private boolean dropping = false;
    private int pressedIndex = 0;
    private int selectedIndex = 0;
    private float downX = 0;
    private float lastX = 0;
    private long lastMoveMs = 0;
    private float velIndexPerMs = 0;

    /* 抓取节流：静止时不必每帧抓 */
    private boolean copyBusy = false;
    private long lastCaptureMs = 0;
    private static final long ACTIVE_CAPTURE_MS = 50;    // 交互期背景重抓 ~20fps：镜内剖切边与页面同步
    private boolean wasActive = false;                   // 弹簧落定后补一帧空画用
    private boolean dark = false;                        // 暗色模式（uBrightness 1.33 的开关）
    private float lastSentPos = Float.MIN_VALUE;         // 上次广播的透镜位置（连续调色节流）
    private volatile boolean detached = false;           // Activity 销毁后一切回调自默
    private boolean imeShown = false;                    // 键盘弹出期透镜窗整体收起
    private boolean overlaid = false;                    // 页面底部弹层（sheet）在场期同样收起
    private boolean ticking = false;                     // 帧回调自眠：静止时不再 120Hz 空转
    private ViewGroup anchor;                            // PopupWindow 显示锚点

    /* 统计 */
    private int frames = 0;
    private int captures = 0;
    private long copySumMs = 0, drawSumMs = 0, copyMaxMs = 0, drawMaxMs = 0;
    private long statStartNs = 0;

    /* ================= R1 原生底栏（L2 壳层接管） =================
     * nativeDock=true 时本层画底栏全部静止态外观（容器/滑块/图标/文字），
     * HTML 底栏由 body.native-dock-on 置 visibility:hidden（保留布局，
     * requestGeometry 依旧能量到几何 —— 可回退：开关关掉即恢复 HTML 底栏）。
     * 视觉数值全部对齐 CSS：容器 64dp 高胶囊(pad 6/8)、滑块=一格+8dp、
     * 图标 22dp(stroke 1.7/24 单位)、文字 10dp/500、gap 2dp。 */

    /** 图标 SVG path（web-src/html/sprite.svg 原文，24×24 viewBox），顺序=tab 顺序。
     *  layers 第三笔在 SVG 里 opacity .5 —— 用 pathAlpha 单独记。 */
    private static final String[][] ICON_PATHS = {
        { "m12 3 9 5-9 5-9-5 9-5Z", "m3 13 9 5 9-5", "m3 17.5 9 5 9-5" },              // 学习 i-layers
        { "M14.6 6.4a4.5 4.5 0 0 0 5.9 5.9l-8.3 8.3a2.4 2.4 0 0 1-3.4 0l-2.5-2.5a2.4 2.4 0 0 1 0-3.4Z",
          "M14.6 6.4 18 3l3 3-3.4 3.4" },                                              // 排障 i-wrench
        { "R2.5,4,19,16,2.2", "m6.5 9.5 3 2.7-3 2.7", "M12.5 15.4h5" },                 // 字典 i-terminal
        { "R2.5,7,19,13,2.2", "M9 7V5.2A1.7 1.7 0 0 1 10.7 3.5h2.6A1.7 1.7 0 0 1 15 5.2V7",
          "M2.5 12.5h19" },                                                            // 面试 i-briefcase
        { "C12,8,4", "M4.5 20.5a7.5 7.5 0 0 1 15 0" },                                  // 我的 i-user
    };
    private static final String[] TAB_LABELS = { "学习", "排障", "字典", "面试", "我的" };

    private boolean nativeDock = false;
    private final RectF barRect = new RectF();      // 栏矩形（本 View 坐标 px；JS 上报）
    private Path[][] iconPaths = new Path[5][];     // 每图标 = 多条子路径（layers 第三笔 alpha .5）
    /* 分层缓存（滑块动画期间每帧 onDraw 的成本大头）：B1=容器，B2=灰态图标+文字。
       每帧 = blit B1 + 画滑块（盖住所格灰图）+ blit B2 + 玻璃 + 选中格蓝图标。
       barRect/主题变化时重建；滑块动画每帧从「4 roundrect+12 path+5 text」
       降到「2 blit + 1 roundrect + 1 path + 1 text」。 */
    private Bitmap chromeB1;
    private Bitmap[] tabSprites = new Bitmap[5];   // 每格灰态（图标+文字）小图——滑块必须画在图标之下，
                                                   // 拖到两格中间时两格图标都要可见（HTML/FlClash 行为）
    private int chromeDark = -1;
    private final RectF chromeRect = new RectF();
    private Bitmap sliderBmp;   // 含阴影的滑块（透明底），滑块-only 动画期 blit 免 saveLayer
    private final Paint blitP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint barFillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barStrokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sliderFillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sliderStrokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 灰态图标/文字色（缓存小图专用）。不能借用 iconP.getColor()——绘制过程中它会被
     *  逐格改成选中蓝，重建缓存时正好取到蓝色，灰态小图被烤成蓝态（2026-10-27 真机实测）。 */
    private int inactiveColor = Color.rgb(142, 142, 147);
    /** 小图内容的垂直基准（药丸中心）。它随 pillCyPx/insets 独立漂移，
     *  必须纳入重建判定，否则栏没动、药丸动了 → 小图内容仍是旧偏移（文字被裁）。 */
    private float chromePillCy = -1f;
    /** 首次拿到真实栏几何后是否已淡入（冷启动防「由大变小」见 onDraw）。 */
    private boolean geomFadedIn = false;
    private final RectF tmpRect = new RectF();

    /** 亮/暗两套配色，逐值对齐 01-tokens.css（浅 #2563eb/#8E8E93；暗 #0a84ff/rgba(235,235,245,.60)）。 */
    private void applyDockColors() {
        if (dark) {
            barFillP.setColor(Color.argb(255, 28, 28, 30));   // 实色：用户实证半透透出正文「吓人」       // 近实色 modal 级（.95），对齐「滚动路径零模糊」决策
            barStrokeP.setColor(Color.argb(255, 56, 56, 58));     // --border #38383a
            sliderFillP.setColor(Color.argb(255, 44, 44, 46));    // --surface-2 #2c2c2e
            sliderStrokeP.setColor(Color.argb(26, 255, 255, 255));
            iconP.setColor(Color.argb(153, 235, 235, 245));       // --tab-inactive rgba(235,235,245,.60)
            textP.setColor(Color.argb(153, 235, 235, 245));
            inactiveColor = Color.argb(153, 235, 235, 245);
        } else {
            barFillP.setColor(Color.WHITE);                    // 实色：同上
            barStrokeP.setColor(Color.argb(255, 227, 227, 232));  // --border #e3e3e8
            sliderFillP.setColor(Color.WHITE);
            sliderStrokeP.setColor(Color.argb(20, 0, 0, 0));
            iconP.setColor(Color.rgb(142, 142, 147));             // --tab-inactive #8E8E93
            textP.setColor(Color.rgb(142, 142, 147));
            inactiveColor = Color.rgb(142, 142, 147);
        }
    }

    private int activeColor() {
        return dark ? Color.rgb(10, 132, 255) : Color.rgb(37, 99, 235); // --accent 浅 #2563eb / 暗 #0a84ff
    }

    /** 图标 Path 初始化：androidx PathParser 吃 sprite.svg 的 d 原文（保持逐字节一致），
     *  R 开头的圆角矩形/C 开头的圆用 Android 原生 API 拼（SVG rect/circle 的等价表达）。
     *  坐标系：24 单位 → 22dp。layers 第三笔在 SVG 里 opacity=".5"，随子路径记录。 */
    private void initIcons() {
        float s = 22f * density / 24f;   // viewBox 24 → 22dp
        int[][] subAlpha = { { 255, 255, 128 }, { 255, 255 }, { 255, 255, 255 },
                             { 255, 255, 255 }, { 255, 255 } };
        for (int i = 0; i < ICON_PATHS.length; i++) {
            Path[] subs = new Path[ICON_PATHS[i].length];
            for (int k = 0; k < ICON_PATHS[i].length; k++) {
                String seg = ICON_PATHS[i][k];
                Path p = new Path();
                if (seg.startsWith("R")) {           // R x,y,w,h,r → 圆角矩形
                    String[] v = seg.substring(1).split(",");
                    float x = Float.parseFloat(v[0]), y = Float.parseFloat(v[1]);
                    tmpRect.set(x, y, x + Float.parseFloat(v[2]), y + Float.parseFloat(v[3]));
                    float r = Float.parseFloat(v[4]);
                    p.addRoundRect(tmpRect, r, r, Path.Direction.CW);
                } else if (seg.startsWith("C")) {    // C cx,cy,r → 圆
                    String[] v = seg.substring(1).split(",");
                    p.addCircle(Float.parseFloat(v[0]), Float.parseFloat(v[1]),
                            Float.parseFloat(v[2]), Path.Direction.CW);
                } else {
                    try {
                        Path parsed = androidx.core.graphics.PathParser.createPathFromPathData(seg);
                        if (parsed != null) p.set(parsed);
                    } catch (Throwable t) {
                        Log.w(TAG, "图标 path 解析失败 " + i, t);
                    }
                }
                Matrix m = new Matrix();
                m.setScale(s, s);
                p.transform(m);
                subs[k] = p;
            }
            iconPaths[i] = subs;
        }
        iconP.setStyle(Paint.Style.STROKE);
        iconP.setStrokeCap(Paint.Cap.ROUND);
        iconP.setStrokeJoin(Paint.Join.ROUND);
        iconP.setStrokeWidth(1.7f * s);          // SVG stroke-width 1.7（24 系）随 22/24 缩放
        textP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textP.setTextSize(10f * density);
        applyDockColors();
    }

    /** 桥：页面 boot 上报原生底栏开关（localStorage netops_native_dock，默认开）。 */
    public void setNativeDock(boolean on) {
        if (nativeDock == on) return;
        nativeDock = on;
        if (on && iconPaths[0] == null) initIcons();
        ensureTicking();
        invalidate();
        Log.i(TAG, "原生底栏 " + (on ? "接管" : "退位（回退 HTML 底栏）"));
    }

    /** 桥：栏矩形（CSS×dpr 物理像素，屏幕坐标）。容器/触摸判定/图标配色的几何真源。 */
    public void setLensBarRect(float l, float t, float w, float h) {
        int[] vl = new int[2];
        getLocationOnScreen(vl);
        barRect.set(l - vl[0], t - vl[1], l - vl[0] + w, t - vl[1] + h);
        invalidate();
    }

}
