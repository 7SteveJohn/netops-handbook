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
import android.graphics.RenderNode;
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

    /* ---- 栏体玻璃（AndroidLiquidGlassView 库 AGSL + FlClash 底栏配方，2026-10-28）----
       R1a 曾把容器画成近实色：当时 WebView 内容会滚进 dock 区，半透=正文透出。
       内容区收短（margin-bottom）后栏后只剩干净壁纸，具备了上真玻璃的前提。
       管线：整栏矩形（±pad 余量，折射向外采样）PixelCopy 每代一次（壁纸/主题/
       几何变化；floorBands 壁纸解码信号触发重抓——抓早了是 backdrop 底色且
       stale 永假）→ 库 AGSL 直接画在 onDraw 画布上。
       **不走 RenderNode 缓存**：录制一次长期重放的用法在真机 popup 窗里
       inputShader 采样失效（输出 alpha=0 全透明=壁纸透出，用户报「滑动轨迹
       透明」；页面头卡能用是因为库把 shader 挂在 RenderEffect 上且每帧重录）。
       透镜的 RuntimeShader 直画在此 view 久经验证，栏体同款直画，代价=每帧
       一次小面积 shader。抓图未就绪/低版本/失败回退实色（chromeB1）。
       交互透镜（drawGlass）不受影响，玻璃栏上再叠放大透镜=iOS 26 分层玻璃。 */
    private Bitmap barBackdrop;                     // 整栏背景抓图（栏矩形+pad，折射向外采样用）
    private BitmapShader barBackdropShader;
    private int barCapOffX, barCapOffY;             // 栏左上在背景位图内的偏移（pad 对齐）
    private RuntimeShader barGlassShader;
    private final Paint barGlassP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcBarRect = new Rect();
    private final RectF lastCapturedBar = new RectF();
    private int lastCapturedDark = -1;
    private boolean barCopyBusy;
    private static String barAgsl;                  // AGSL 源码（进程内缓存一份）
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

    public LensBarView(Context context, WebView webView) {
        super(context);
        this.webView = webView;
        this.density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(1f * density);
        rimPaint.setColor(Color.argb(90, 255, 255, 255));
        if (Build.VERSION.SDK_INT >= 33) {
            shader = new RuntimeShader(AGSL);
            glassPaint.setShader(shader);
        }
        initIcons();
        statStartNs = System.nanoTime();
        ensureTicking();
    }

    /** 帧回调自眠/唤醒：有弹簧或按压才逐帧，静止完全停摆（省电）。
     *  唤醒点只有两处——构造与任何触摸事件；其余启动动画的路径必须先过触摸。 */
    private void ensureTicking() {
        if (detached || ticking) return;
        ticking = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static double nowSec() {
        return System.nanoTime() / 1e9;
    }

    /**
     * JS 侧量好几何后上报（CSS px → 内部转物理 px）。
     * 格宽、药丸尺寸、首格中心全部由 CSS 给出，原生层不做推导 —— 推导必错
     * （栏有左右边距、药丸比一格宽 8px、还有 safe-area）。
     */
    @SuppressWarnings("unused")
    public void setLensGeometry(float slotPx, float pillWPx, float pillHPx,
                                float firstCxPx, float pillCyScreen, int tabCount,
                                float viewportHPx) {
        this.count = Math.max(1, tabCount);
        /* 上报的已经是物理像素（页面按 devicePixelRatio 换算过），这里不要再乘 density */
        this.slot = slotPx;
        this.pillW = pillWPx;
        this.pillH = pillHPx;
        this.firstCx = firstCxPx;
        /* y 的夹取基准 = 页面视口物理高（WebView 边到边，视口顶 = 屏幕顶）。
           不能用 heightPixels：它返回挖掉手势条后的"安全高"（本机 2587），
           比真实窗口（2800）矮 213px，拿它夹 y 会把药丸整体推高约 100px
           —— 2026-10-26 真机药丸悬空错位的根因之一。 */
        if (viewportHPx > pillHPx) {
            this.pillCyPx = Math.max(pillHPx / 2f + 2f,
                    Math.min(pillCyScreen, viewportHPx - pillHPx / 2f - 2f));
        } else {
            this.pillCyPx = pillCyScreen;
        }
        this.viewportH = viewportHPx;
        Log.i(TAG, String.format(
                "几何已同步 一格%.1f 药丸%.1f×%.1f 首格中心(%.1f,%.1f→%.1f) 共%d格 视口高%.0f",
                slotPx, pillWPx, pillHPx, firstCxPx, pillCyScreen, this.pillCyPx,
                tabCount, viewportHPx));
    }

    private final Runnable sliderCatchUp = new Runnable() {
        @Override public void run() {
            if (detached || pressed) return;
            pos.springTo(selectedIndex, TRAVEL, nowSec());
            ensureTicking();
        }
    };

    /** JS 侧切页后同步选中项，避免两边选中态不一致。 */
    @SuppressWarnings("unused")
    public void setSelectedIndex(int index) {
        int i = Math.max(0, Math.min(count - 1, index));
        selectedIndex = i;
        if (!pressed) {
            /* 滑块弹簧错峰 200ms：切页瞬间 WebView 正做重渲染（冷启动首开
               JIT 冷下更重），滑块动画每帧 invalidate 与之争抢 —— 实测
               阶段页入场动画 legacy janky 8.65%（关原生底栏 1.01%）。
               滑块停原格 200ms，页面转场首帧落地后再弹向新格（iOS 指示器惯例）。 */
            removeCallbacks(sliderCatchUp);
            postDelayed(sliderCatchUp, 250);
        }
    }

    /** 深浅色主题（透镜亮度 1.33 + 底栏配色整套），由 Activity 主题变化时推送。 */
    public void setDark(boolean d) {
        if (dark == d) return;
        dark = d;
        applyDockColors();
        chromeDark = -1;   // 强制重建缓存（含 per-tab sprites）
        java.util.Arrays.fill(tabSprites, null);
        lastCapturedDark = -1;   // 玻璃 tint 随主题（0.55 浅 / 0.62 暗），换代重抓重录
        invalidate();
    }

    /**
     * 主动向页面索取底栏几何 —— **原生驱动，可重复调用，每次全量重测**。
     *
     * 为什么索取制：上报一旦在 JS 里抛异常就完全静默，成功/失败在 Logcat 里一目了然。
     * 为什么每次重测、不再缓存 window.__lg：ready() 那一刻 safe-area 往往还没生效
     * （Bridge.ready 自己才触发 insets 应用），量到的是瞬态布局 —— 2026-10-26 实测
     * 首帧量到 药丸中心 804 CSS / 首格 195.9，而稳定值是 737 / 219，错值一旦缓存
     * 就被永久复用。真源改为**永远存在、永不 display:none 的栏矩形**：
     * 药丸在栏内上下对称各 6px 挂靠，栏中心 == 药丸中心；药丸宽高在它可见时量自身
     * （首索取必然发生在隐藏之前），隐藏后回退到"一格+8 / tab 行高"的同源推导。
     * 本方法在 ready、insets 应用后、attach 后 1.5s/3s 各调一次，以最后一次为准。
     */
    public void requestGeometry() {
        if (webView == null || detached) return;
        /* 用页面自己的 devicePixelRatio 换成**物理像素**再上报，别让原生拿 density 换算：
           wm density 是 560(3.5)，但 WebView 的 CSS 缩放未必等于它。 */
        String js = "(function(){try{"
                + "var dpr=window.devicePixelRatio||1;"
                + "var t=document.querySelectorAll('.tab');"
                + "if(!t.length)return;"
                + "var bar=document.querySelector('.tabbar');"
                + "if(!bar)return;"
                + "var r0=t[0].getBoundingClientRect();"
                + "var rb=bar.getBoundingClientRect();"
                /* 格宽 = 前两个 tab 中心的间距（而非 tab 自身宽）：≥600px 媒体查询给
                   .tab 加了 max-width+justify-self:center，宽屏下 tab 收缩成内容宽
                   居中在格子里（横屏实测 tab 52px、格距 140.8px）——拿 tab 宽当格宽
                   会让拖动映射整体错位（2026-10-26 横屏"划不动"根因）。中心间距
                   对限宽/居中/聚拢布局全部免疫。 */
                + "var c0=r0.left+r0.width/2;"
                + "var pitch=r0.width;"
                + "if(t.length>1){var r1=t[1].getBoundingClientRect();"
                + "pitch=Math.abs(r1.left+r1.width/2-c0);}"
                + "var p=document.querySelector('.tabbar > .tab-pill');"
                + "var rp=p?p.getBoundingClientRect():null;"
                + "var ok=rp&&rp.width>1&&rp.height>1;"       /* display:none 后全 0，作废 */
                + "if(window.NetBridge&&NetBridge.lensDiag){try{NetBridge.lensDiag("
                + "'tabs='+t.length+' pitch='+pitch.toFixed(1)+' rpw='+(rp?rp.width.toFixed(1):'x')"
                + "+' barW='+rb.width.toFixed(1)+' ih='+window.innerHeight);}catch(e){}}"
                + "if(ok&&Math.abs(rp.width-(pitch+8))>20)return;"
                /* ↑ 一致性护栏：药丸宽恒=格距+8px；偏差大说明布局还在过渡中
                   （旋转/收起动画半途），矩形不可信 —— 拒收本次，原生保留上一份
                   好几何，等过渡结束后的重测覆盖。 */
                + "var pillW=ok?rp.width:(pitch+8);"
                + "var pillH=ok?rp.height:r0.height;"
                + "var cy=rb.top+rb.height/2;"                 /* 栏中心=药丸中心（上下对称 6px） */
                + "document.body.classList.add('native-lens-on');"
                + "if(window.NetBridge&&NetBridge.setLensBarRect){"
                + "NetBridge.setLensBarRect(rb.left*dpr,rb.top*dpr,rb.width*dpr,rb.height*dpr);}"
                + "if(window.NetBridge&&NetBridge.setLensGeometry){"
                + "NetBridge.setLensGeometry(pitch*dpr,pillW*dpr,pillH*dpr,"
                + "c0*dpr,cy*dpr,t.length,window.innerHeight*dpr);}"
                + "}catch(e){}})()";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "索取几何的 JS 注入失败", t);
        }
    }

    /** 预热：先抓一张背景，把首次光栅化的开销摊在用户碰底栏之前。 */
    public void warmUp() {
        post(LensBarView.this::captureBackdrop);
    }


    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bandW = w;
        bandH = h;
        /* 只补 JS 还没上报的项：CSS 才是几何真源，这里只是兜底 */
        if (slot <= 0) {
            slot = w / (float) Math.max(1, count);
            firstCx = slot * 0.5f;
        }
        if (pillH <= 0) pillH = 56f * density;
        if (pillW <= 0) pillW = slot + 8f * density;   // 药丸比一格宽 8dp，与 CSS 同
    }

    /** 药丸中心 y（本 View 坐标）。JS 上报的是屏幕物理 px，减去本 View 的屏幕顶边即得；
     *  再向带内夹一次 —— 上报值无论多离谱都不允许画出 PopupWindow 边界。 */
    /** 复用的屏幕坐标缓冲：pillCy() 每帧被多次调用（绘制+重建门），
     *  原先每调一次 new int[2]，120Hz 下是纯 GC 垃圾。 */
    private final int[] locTmp = new int[2];
    private float pillCy() {
        if (pillCyPx > 0) {
            getLocationOnScreen(locTmp);
            float local = pillCyPx - locTmp[1];
            float hh = pillH / 2f;
            float lo = hh + 1f, hi = bandH - hh - 1f;
            if (hi > lo) local = Math.max(lo, Math.min(hi, local));
            return local;
        }
        return bandH - pillH / 2f - 22f * density;
    }

    /* ================= 运动：照搬 FlClash ================= */

    /**
     * 果冻形变：速度快则拉伸，刹车则压缩，两项相减后交给欠阻尼弹簧跟随。
     * target = 0.20·tanh(|v|/3) − 0.15·tanh(braking/25)，v 单位"格/秒"。
     */
    private void wobble(double now) {
        double v = pos.velocity();
        double acc = pos.acceleration();
        double braking = Math.max(0.0, -acc * Math.signum(v));
        double target = 0.20 * Math.tanh(Math.abs(v) / 3.0)
                - 0.15 * Math.tanh(braking / 25.0);
        if (Math.abs(target - jelly.target()) > 1e-4) {
            jelly.springTo(target, JELLY_S, now);
        }
    }

    /** UIKit 行为：升到位 + 移动到格，两个条件都满足才允许回落。 */
    private void dropLift(double now) {
        if (!dropping || pressed) return;
        if (lift.value() < LIFT_SNAP) return;
        if (Math.abs(pos.target() - pos.value()) > LIFT_RELEASE) return;
        dropping = false;
        lift.springTo(0, LIFT_S, now);
    }

    private void tick() {
        double now = nowSec();
        pos.advance(now);
        wobble(now);
        jelly.advance(now);
        lift.advance(now);
        dropLift(now);
    }

    private boolean animating() {
        return pos.active() || jelly.active() || lift.active();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (detached) {
            ticking = false;
            return;
        }
        tick();
        boolean lensing = pressed || lift.value() > 0.005f || lift.active();
        boolean act = lensing || animating();
        /* 滑块-only 动画隔帧上屏：弹簧照常每帧推进，上屏 60fps —— 切页转场期
           WebView 每帧都在主线程排版，滑块 invalidate 的合成成本减半
           （冷启动首开阶段页实测 legacy janky 8.65% vs 关原生 1.01%）。 */
        if (act && !lensing) frameSkip++;
        if (act && !lensing && (frameSkip & 1) == 1) {
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        if (act) {
            /* 透镜行为（广播 --lens-pos 调色 + 交互期重抓）只在「透镜在场」时做。
               nativeDock 后切页滑块也走弹簧动画（0.55s×120fps），若照旧每帧
               evaluateJavascript，就是打开卡片掉帧的元凶（2026-10-27 实测
               90th 16→24ms）—— HTML 底栏已隐藏，页面调色路径整体失效，
               nativeDock 时广播/调色/藏药丸全部跳过，滑块动画只剩 invalidate。 */
            float p = (float) pos.value();
            if (lensing && !nativeDock && Math.abs(p - lastSentPos) > 0.05f) {
                lastSentPos = p;
                callLensPos(p);
            }
            if (lensing && System.currentTimeMillis() - lastCaptureMs >= ACTIVE_CAPTURE_MS) {
                captureBackdrop();
            }
            /* 脏区重合成：滑块+蓝格移动区域（玻璃期仍整层——折射面大）。
               全窗 invalidate 的合成面积 3040×440；收窄后入场动画期的
               每帧 GPU 合成大幅下降（滑块动画 legacy janky 的最后一块）。 */
            if (nativeDock && !lensing) {
                float cx2 = firstCx + slot * (float) pos.value();
                float cy2 = pillCy();
                float pad2 = 40f * density;
                invalidate((int) Math.max(0, cx2 - pillW / 2f - pad2),
                        (int) Math.max(0, cy2 - pillH / 2f - pad2),
                        (int) Math.min(bandW, cx2 + pillW / 2f + pad2),
                        (int) Math.min(bandH, cy2 + pillH / 2f + pad2));
            } else {
                invalidate();
            }
            wasActive = true;
        } else if (wasActive) {
            /* 弹簧刚落定：补一帧空画把玻璃擦掉。同帧结束连续调色（摘 lens-live、
               把 is-active 归位到提交格 —— 取消路径不提交，这里兜底归位），
               并把按压期藏掉的 HTML 药丸还回来，随后帧回调自眠。 */
            invalidate();
            wasActive = false;
            if (!nativeDock) {
                callLensEnd(selectedIndex);
                setPressClass(false);
            }
            lastSentPos = Float.MIN_VALUE;   // 下次按压必发初始位置（否则原格重按不触发广播）
        }
        if (act || wasActive) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            ticking = false;
            frameSkip = 0;
        }
    }
    private int frameSkip = 0;

    /** 透镜浮点位置 → 页面 --lens-pos（旧页面没有此函数则静默跳过）。 */
    private void callLensPos(float p) {
        if (webView == null) return;
        String js = "(function(){try{if(typeof NetOpsLensPos==='function'){"
                + "NetOpsLensPos(" + String.format(java.util.Locale.US, "%.3f", p) + ");}}catch(e){}})()";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "透镜位置广播失败", t);
        }
    }

    /** 交互结束：摘 lens-live、--lens-pos，is-active 归位到提交格。 */
    private void callLensEnd(int index) {
        if (webView == null) return;
        String js = "(function(){try{if(typeof NetOpsLensEnd==='function'){"
                + "NetOpsLensEnd(" + index + ");}}catch(e){}})()";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "透镜调色收尾失败", t);
        }
    }

    /** 按压期间藏起 HTML 药丸（body.native-lens-press），松手落定后还回来。 */
    private void setPressClass(boolean on) {
        if (webView == null) return;
        String js = "(function(){try{document.body.classList."
                + (on ? "add" : "remove") + "('native-lens-press');}catch(e){}})()";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "同步按压态到页面失败", t);
        }
    }

    /**
     * 抓 WebView 在本 View 覆盖区域内的真实像素。
     *
     * 为什么不用 PixelCopy：它只有 SurfaceView / Surface / Window 三个重载
     * （API 37 也没有 View 版），而 Window 版抓的是整窗合成结果，会把本层自己
     * 画的玻璃一起抓进来 → 反馈循环。webView.draw(离屏 Canvas) 只画 WebView
     * 这棵子树，天然不含本层，也没有 API 门槛。代价是 software 光栅化，
     * 耗时正是本探针要测的量。
     */
    private void captureBackdrop() {
        if (detached || bandW <= 0 || bandH <= 0 || copyBusy) return;
        copyBusy = true;   /* bitmap 由各条路径按自己的尺寸建（整条带 / 药丸小块） */
        long t0 = System.nanoTime();
        if (window != null && Build.VERSION.SDK_INT >= 26) {
            captureByPixelCopy(t0);
        } else {
            captureByDraw(t0);
        }
    }

    /**
     * 首选：PixelCopy 直接回读合成结果（GPU 侧），不触发整页软件光栅化。
     * 抓的是**主窗口**，本层活在 PopupWindow 的独立 Window 里，所以不会抓到自己。
     */
    private void captureByPixelCopy(long t0) {
        int[] vl = new int[2];
        getLocationOnScreen(vl);
        /* 只抓药丸那一小块（+ 位移余量），不再抓整条带：
           整条带 1272×420 ≈ 2.1MB 回读实测 70ms，缩到 ~300×250 后应该一个量级往下掉。 */
        float cxScreen = firstCx + slot * (float) pos.value();
        float cyScreen = pillCyPx > 0 ? pillCyPx : (vl[1] + bandH - pillH / 2f - 22f * density);
        int pad = (int) (40 * density);
        int halfW = (int) (pillW / 2f + LIFT_GROWTH * density / 2f) + pad;
        int halfH = (int) (pillH / 2f + LIFT_GROWTH * density / 2f) + pad;
        DisplayMetrics m = getResources().getDisplayMetrics();
        /* 底边基准 = 页面视口物理高（2800）。heightPixels 只有 2587（挖掉手势条），
           拿它夹 srcRect 会把 2587 以下的标签整体裁出背景图，CLAMP 平铺成一片
           匀色 —— 2026-10-26 实测"镜内图标在、文字没了"就是这个。 */
        int maxH = viewportH > 0 ? (int) viewportH : m.heightPixels;
        int l = Math.max(0, (int) (cxScreen - halfW));
        int top = Math.max(0, (int) (cyScreen - halfH));
        int r = Math.min(m.widthPixels, (int) (cxScreen + halfW));
        int bot = Math.min(maxH, (int) (cyScreen + halfH));
        if (r - l < 4 || bot - top < 4) {
            copyBusy = false;
            return;
        }
        srcRect.set(l, top, r, bot);
        bgOffX = l - vl[0];
        bgOffY = top - vl[1];
        if (backdrop == null || backdrop.getWidth() != srcRect.width()
                || backdrop.getHeight() != srcRect.height()) {
            backdrop = Bitmap.createBitmap(srcRect.width(), srcRect.height(), Bitmap.Config.ARGB_8888);
            backdropShader = new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        try {
            PixelCopy.request(window, srcRect, backdrop, result -> {
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                copyBusy = false;
                if (result == PixelCopy.SUCCESS) {
                    copySumMs += ms;
                    captures++;
                    if (ms > copyMaxMs) copyMaxMs = ms;
                    lastCaptureMs = System.currentTimeMillis();
                    /* debug 取证：dump 最近一次抓图（R1 折射内容与页面不符排查） */
                    if ((getContext().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        try {
                            java.io.File f = new java.io.File(getContext().getExternalFilesDir(null), "backdrop-dump.png");
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                            backdrop.compress(Bitmap.CompressFormat.PNG, 90, fo);
                            fo.close();
                        } catch (Throwable ignored) { }
                    }
                    invalidate();
                } else {
                    Log.w(TAG, "PixelCopy 失败 result=" + result + "，退回软件绘制");
                    captureByDraw(System.nanoTime());
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            copyBusy = false;
            Log.w(TAG, "PixelCopy 抛异常，退回软件绘制", t);
            captureByDraw(System.nanoTime());
        }
    }

    /** 兜底：WebView 软件光栅化。实测 95~265ms，很贵，只在 PixelCopy 不可用时用。 */
    private void captureByDraw(long t0) {
        if (webView == null) {
            copyBusy = false;
            return;
        }
        int[] wl = new int[2];
        int[] vl = new int[2];
        webView.getLocationOnScreen(wl);
        this.getLocationOnScreen(vl);
        int left = vl[0] - wl[0];
        int top = vl[1] - wl[1];
        if (left < 0 || top < 0) {
            copyBusy = false;
            return;
        }
        if (backdrop == null || backdrop.getWidth() != bandW || backdrop.getHeight() != bandH) {
            backdrop = Bitmap.createBitmap(bandW, bandH, Bitmap.Config.ARGB_8888);
            backdropShader = new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        bgOffX = 0;
        bgOffY = 0;      /* 整条带模式：背景图左上角就是本 View 的左上角 */
        try {
            Canvas c = new Canvas(backdrop);
            c.translate(-left, -top);
            webView.draw(c);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            copySumMs += ms;
            captures++;
            if (ms > copyMaxMs) copyMaxMs = ms;
            lastCaptureMs = System.currentTimeMillis();
        } catch (Throwable t) {
            Log.w(TAG, "WebView 离屏绘制失败", t);
        } finally {
            copyBusy = false;
        }
    }

    /* ================= 触摸 ================= */

    /** 只接管药丸那一条带；其余放行，否则会吞掉底栏 tab 的点击。
     *  nativeDock 时接管整条栏（图标/文字也在原生，点击=切页），x 须落在栏矩形内。 */
    private boolean inPillBand(float y) {
        if (nativeDock && barRect.width() > 4) {
            RectF b = dockRect();
            return b.contains(lastHitX, y);
        }
        float cy = pillCy();
        float hh = pillH / 2f;
        return y >= cy - hh && y <= cy + hh;
    }
    private float lastHitX;

    /** 越界橡皮筋：1 − 1/(|o|·0.55/limit + 1)，渐近收敛而非线性跟随。 */
    private static float rubberBand(float overshoot, float limit) {
        float pull = (float) (1.0 - 1.0 / (Math.abs(overshoot) * 0.55 / limit + 1.0));
        return limit * pull * Math.signum(overshoot);
    }

    /** 手指 x → 位置（格），两端带橡皮筋。 */
    private float positionAt(float x) {
        float raw = (x - firstCx) / slot;
        if (raw < 0) return rubberBand(raw, OVERDRAG);
        if (raw > count - 1) return (count - 1) + rubberBand(raw - (count - 1), OVERDRAG);
        return raw;
    }

    /**
     * 药丸带之外的触摸**必须人工转发给 WebView**。
     * 本层活在独立 Window 里，事件不会像普通子 View 那样自动漏给下层 —— 不转发的话，
     * 底栏上下那一圈页面内容就点不动了（这是 PopupWindow 方案的必要补丁）。
     */
    private void forwardToWebView(MotionEvent e) {
        if (webView == null) return;
        int[] wl = new int[2];
        int[] vl = new int[2];
        webView.getLocationOnScreen(wl);
        this.getLocationOnScreen(vl);
        MotionEvent e2 = MotionEvent.obtain(e);
        e2.offsetLocation(vl[0] - wl[0], vl[1] - wl[1]);
        try {
            webView.dispatchTouchEvent(e2);
        } catch (Throwable t) {
            Log.w(TAG, "转发触摸到 WebView 失败", t);
        } finally {
            e2.recycle();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (slot <= 0) return false;
        ensureTicking();   // 帧回调自眠中：触摸唤醒
        /* 已接管（pressed）的手势一律自己处理，不转发，否则拖出药丸带就断线了 */
        lastHitX = e.getX();
        if (!pressed && !inPillBand(e.getY())) {
            forwardToWebView(e);
            return true;
        }
        double now = nowSec();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                Log.i(TAG, String.format("DOWN x=%.0f view w=%d -> cell %.2f (count=%d, popup=%dx%d)",
                        e.getX(), getWidth(), positionAt(e.getX()), count,
                        popup == null ? -1 : popup.getWidth(), popup == null ? -1 : popup.getHeight()));
                pressed = true;
                dragging = false;
                dropping = false;
                downX = lastX = e.getX();
                lastMoveMs = e.getEventTime();
                velIndexPerMs = 0;
                pressedIndex = Math.max(0, Math.min(count - 1, Math.round(positionAt(e.getX()))));
                pos.springTo(pressedIndex, TRAVEL, now);
                lift.springTo(1, LIFT_S, now);
                lastCaptureMs = 0;   // 按下立刻重抓背景，别让玻璃映出几分钟前的旧画面
                if (!nativeDock) setPressClass(true); // 藏起 HTML 药丸（nativeDock 时已整体隐藏）
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (!pressed) return false;
                float x = e.getX();
                long t = e.getEventTime();
                long dt = t - lastMoveMs;
                if (dt > 0) {
                    float v = (x - lastX) / slot / dt;          // 格/ms
                    velIndexPerMs = velIndexPerMs * 0.6f + v * 0.4f;
                    lastMoveMs = t;
                    lastX = x;
                }
                if (!dragging && Math.abs(x - downX) < TOUCH_SLOP_DP * density) return true;
                dragging = true;
                pos.springTo(positionAt(x), FOLLOW, now);
                int idx = Math.max(0, Math.min(count - 1, Math.round(positionAt(x))));
                if (idx != pressedIndex) {
                    pressedIndex = idx;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!pressed) return false;
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                pressed = false;
                if (up) {
                    /* 松手：按 100ms 速度投影落格，最多越过手指所在格的下一格 */
                    float lead = velIndexPerMs * FLING_MS;
                    int target = (int) Math.round(pos.target() + lead);
                    target = Math.max(pressedIndex - 1, Math.min(pressedIndex + 1, target));
                    target = Math.max(0, Math.min(count - 1, target));
                    pressedIndex = target;
                    pos.springTo(target, TRAVEL, now);
                    commit(target);
                } else {
                    pos.springTo(selectedIndex, TRAVEL, now);
                }
                dragging = false;
                dropping = true;
                invalidate();
                return true;
            }
        }
        return false;
    }

    /** 把选择提交给页面（页面切视图后回调用 setSelectedIndex 对齐）。 */
    private void commit(int index) {
        selectedIndex = index;
        if (webView == null) return;
        String js = "(function(){try{if(typeof NetOpsLensSelect==='function'){NetOpsLensSelect("
                + index + ");}}catch(e){}})()";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "提交选择失败", t);
        }
    }

    /* ================= 绘制 ================= */

    private void updateShaderUniforms(float cx, float hw, float hh) {
        if (shader == null) return;
        float liftV = (float) lift.value();
        shader.setFloatUniform("uCenter", cx, pillCy());
        shader.setFloatUniform("uHalf", hw, hh);
        shader.setFloatUniform("uRadius", Math.min(hw, hh));
        /* FlClash style.scale(lift)：折射与色散都随抬起量缩放 */
        shader.setFloatUniform("uRefract", Math.abs(REFRACTION) * density * liftV);
        shader.setFloatUniform("uBevel", BEVEL * density);
        shader.setFloatUniform("uDisperse", DISPERSION * liftV);
        shader.setFloatUniform("uDp", density);
        shader.setFloatUniform("uBgOffset", bgOffX, bgOffY);
        /* 镜内内容放大：FlClash _lensMagnify 16%，随抬起量进出。
           仅 nativeDock 开：HTML 底栏模式下图标文字就活在被折射的 WebView
           背景里，放大 = 镜内多出第二套错位内容（2026-10-27 真机「学习一格
           两份字」重影的根源），回退路径镜内保持 1:1。 */
        shader.setFloatUniform("uMagnify", nativeDock ? 1f + 0.16f * liftV : 1f);
        /* FlClash uOptics.z：暗色 1.33 / 浅色 1 */
        shader.setFloatUniform("uBrightness", dark ? 1.33f : 1.0f);
        if (backdropShader != null) {
            try {
                shader.setInputShader("backdrop", backdropShader);
            } catch (Throwable t) {
                Log.w(TAG, "setInputShader 失败", t);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long t0 = System.nanoTime();

        float liftV = (float) lift.value();
        if (nativeDock) {
            /* ---- 未拿到 JS 上报的栏几何前不画 ----
               冷启动时 WebView 还在加载，barRect 无效 → dockRect() 退回「全宽兜底」
               胶囊（又宽又高），数秒后真实几何到达再缩回，用户看到「由大变小」的
               生硬过渡（2026-10-27 用户报）。宁可先空着，几何到达后一次淡入。 */
            if (barRect.width() <= 4 || barRect.height() <= 4) {
                if (getAlpha() != 0f) setAlpha(0f);   /* 冷启动从不可见起步，等几何到达再淡入 */
                geomFadedIn = false;
                frames++;
                return;
            }
            if (!geomFadedIn) {
                geomFadedIn = true;
                animate().alpha(1f).setDuration(140).start();
            }
            /* ---- 原生底栏：容器→滑块→玻璃→图标文字。图标在玻璃之上，
               对齐 FlClash _LensContent 的「镜内内容独立重绘」——顺带解决
               旧 P2「镜内放大靠位图采样偏软」：现在是矢量重绘恒锐。 ---- */
            boolean lensing = pressed || liftV > 0.005f;
            drawDockChrome(canvas, lensing);
            if (lensing) {
                drawGlass(canvas, liftV);
            }
            drawLensTabs(canvas);
        } else {
            /* 旧路径：静止态本层不画（外观由 HTML 药丸承担），只画按压透镜 */
            if (liftV <= 0.005f && !pressed) {
                frames++;
                return;
            }
            drawGlass(canvas, liftV);
        }

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        drawSumMs += ms;
        if (ms > drawMaxMs) drawMaxMs = ms;
        frames++;
        if (System.nanoTime() - statStartNs > 2_000_000_000L) {
            Log.i(TAG, String.format(
                    "绘制%d帧 | 抓图%d次 单次均%.2fms 峰值%dms | draw均%.2fms 峰值%dms | pos=%.2f jelly=%+.3f lift=%.2f",
                    frames, captures,
                    captures > 0 ? copySumMs / (float) captures : 0f, copyMaxMs,
                    frames > 0 ? drawSumMs / (float) frames : 0f, drawMaxMs,
                    pos.value(), jelly.value(), lift.value()));
            frames = 0;
            captures = 0;
            copySumMs = 0;
            drawSumMs = 0;
            copyMaxMs = 0;
            drawMaxMs = 0;
            statStartNs = System.nanoTime();
        }
    }

    /** 整栏背景抓取（栏体玻璃采样源）：PixelCopy 主窗口、每代一次。
     *  与透镜的交互期局部抓分开（独立 busy，拖拽期的整栏回读不插队）。
     *  抓图矩形 = 栏 ± pad（折射向外弯，边缘带要采栏外壁纸——FlClash 透镜
     *  抓图带 40dp pad 同理）；位图原点=栏左上−pad，localMatrix 平移对齐
     *  content 坐标。代判定：矩形/主题变了才重抓，否则跳过。 */
    private void maybeCaptureBarBackdrop() {
        if (detached || !nativeDock || barCopyBusy || window == null) return;
        if (Build.VERSION.SDK_INT < 33) return;
        RectF bar = dockRect();
        int W = (int) bar.width(), H = (int) bar.height();
        if (W < 16 || H < 16) return;
        boolean stale = barBackdrop == null
                || Math.abs(lastCapturedBar.left - bar.left) > 0.5f
                || Math.abs(lastCapturedBar.top - bar.top) > 0.5f
                || Math.abs(lastCapturedBar.width() - bar.width()) > 0.5f
                || Math.abs(lastCapturedBar.height() - bar.height()) > 0.5f
                || lastCapturedDark != (dark ? 1 : 0);
        if (!stale) return;
        int[] vl = new int[2];
        getLocationOnScreen(vl);
        DisplayMetrics m = getResources().getDisplayMetrics();
        int maxH = viewportH > 0 ? (int) viewportH : m.heightPixels;
        int pad = (int) (32 * density);
        int cl = Math.max(0, (int) (vl[0] + bar.left) - pad);
        int ct = Math.max(0, (int) (vl[1] + bar.top) - pad);
        int cr = Math.min(m.widthPixels, (int) (vl[0] + bar.right) + pad);
        int cb = Math.min(maxH, (int) (vl[1] + bar.bottom) + pad);
        if (cr - cl < W || cb - ct < H) {
            Log.w(TAG, "栏玻璃抓取跳过：栏超界 rect=" + cl + "," + ct + "-" + cr + "," + cb);
            return;   // 栏连本体都被屏边裁掉：回退实色
        }
        if (barBackdrop == null || barBackdrop.getWidth() != cr - cl
                || barBackdrop.getHeight() != cb - ct) {
            barBackdrop = Bitmap.createBitmap(cr - cl, cb - ct, Bitmap.Config.ARGB_8888);
            barBackdropShader = new BitmapShader(barBackdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        srcBarRect.set(cl, ct, cr, cb);
        barCopyBusy = true;
        final RectF barSnap = new RectF(bar);
        /* 栏左上在位图内的偏移：content.eval(栏本地 p) 须读到位图 p+off */
        final int offX = vl[0] + (int) bar.left - cl;
        final int offY = vl[1] + (int) bar.top - ct;
        try {
            PixelCopy.request(window, srcBarRect, barBackdrop, result -> {
                barCopyBusy = false;
                if (result == PixelCopy.SUCCESS) {
                    lastCapturedBar.set(barSnap);
                    lastCapturedDark = dark ? 1 : 0;
                    barCapOffX = offX;
                    barCapOffY = offY;
                    if (barBackdropShader != null) {
                        /* setLocalMatrix 的 M 把画布坐标映到位图坐标（canvas p 采样
                           M·p）：栏本地 p 对应位图 p+off → 平移 +off。写成 −off 会
                           采样到负坐标被 CLAMP 成首行涂抹色（2026-10-28 真机实测
                           整条栏一色 227 的根因）。 */
                        android.graphics.Matrix mm = new android.graphics.Matrix();
                        mm.setTranslate(barCapOffX, barCapOffY);
                        barBackdropShader.setLocalMatrix(mm);
                    }
                    invalidate();   // 新底落位 → 之后的直画用新采样
                }
                /* 失败不重试：stale 判定仍是 false，等壁纸/主题/几何换代再来 */
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t2) {
            barCopyBusy = false;
            Log.w(TAG, "栏玻璃抓取抛异常，回退实色", t2);
        }
    }

    /** 栏体玻璃就绪判定 + uniforms 组装（每帧调用；未换代时仅重复设值，开销可忽略）。
     *  返回 false = 抓图未就绪/低版本/组装失败 → 回退实色。
     *  **直画不走 RenderNode**：录制一次长期重放的用法在真机 popup 窗里
     *  inputShader 采样失效（输出 alpha=0 全透明）；透镜的直画模式在此 view
     *  久经验证，栏体同款。 */
    private boolean ensureBarGlass(RectF bar, float r) {
        if (Build.VERSION.SDK_INT < 33 || !nativeDock) return false;
        if (barBackdrop == null || barBackdropShader == null) return false;
        int W = (int) bar.width(), H = (int) bar.height();
        if (barBackdrop.getWidth() < W + 2 || barBackdrop.getHeight() < H + 2) return false;
        try {
            if (barGlassShader == null) {
                barGlassShader = new RuntimeShader(loadBarAgsl(getResources()));
            }
            /* 配方=FlClash 底栏（pill）实测值（FlClash液态玻璃源码研读 §3.1）：
               tint #F4F4F4@58% 浅 / surfaceContainerHighest@48% 深（≈#363636，暗色
               下是「提亮中和」而不是白 tint 发奶）；饱和度 2.10/1.925——液态玻璃
               的通透感全靠它，1.0+白tint 会退化成磨砂白面板（2026-10-28 用户
               「跟 FlClash/酷安不一样」报因）。折射向外弯（正值，FlClash
               「凸起透镜向外弯」口径），带/位移按栏高夹紧。 */
            float band = Math.min(16f * density, H * 0.26f);
            float amount = Math.min(20f * density, H * 0.42f);
            barGlassShader.setFloatUniform("size", new float[]{W, H});
            barGlassShader.setFloatUniform("offset", new float[]{0f, 0f});
            barGlassShader.setFloatUniform("cornerRadii", new float[]{r, r, r, r});
            barGlassShader.setFloatUniform("refractionHeight", band);
            barGlassShader.setFloatUniform("refractionAmount", amount);
            barGlassShader.setFloatUniform("depthEffect", 0.3f);
            barGlassShader.setFloatUniform("chromaticAberration", 0.35f);
            barGlassShader.setFloatUniform("contrast", 0f);
            barGlassShader.setFloatUniform("whitePoint", 0f);
            barGlassShader.setFloatUniform("chromaMultiplier", dark ? 1.925f : 2.10f);
            if (dark) {
                barGlassShader.setFloatUniform("tintColor", new float[]{0.21f, 0.21f, 0.22f});
                barGlassShader.setFloatUniform("tintAlpha", 0.48f);
            } else {
                barGlassShader.setFloatUniform("tintColor", new float[]{0.957f, 0.957f, 0.957f});
                barGlassShader.setFloatUniform("tintAlpha", 0.55f);
            }
            barGlassShader.setInputShader("content", barBackdropShader);
            barGlassP.setShader(barGlassShader);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "栏体玻璃组装失败，回退实色", t);
            barGlassP.setShader(null);
            return false;
        }
    }

    private static String loadBarAgsl(android.content.res.Resources res) {
        if (barAgsl != null) return barAgsl;
        try (java.io.InputStream is = res.openRawResource(com.netops.handbook.R.raw.liquidglass_effect);
             java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            barAgsl = sb.toString();
        } catch (Throwable t) {
            throw new IllegalStateException("读栏体玻璃 AGSL 失败", t);
        }
        return barAgsl;
    }

    /** 壁纸/通透度变了（NativeBackdrop 重铺）：栏后像素换代，玻璃条需重抓。
     *  把代基准清空让 stale 判定成立；壁纸解码异步，三连发等它落定
     *  （沿用 remeasureLensSoon 的节奏）。 */
    public void onBackdropChanged() {
        lastCapturedBar.setEmpty();
        lastCapturedDark = -1;
        android.os.Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(this::maybeCaptureBarBackdrop, 400L);
        h.postDelayed(this::maybeCaptureBarBackdrop, 900L);
        h.postDelayed(this::maybeCaptureBarBackdrop, 1500L);
    }

    /** 分层缓存重建：B1=容器；B2=灰态图标+文字（透明底）。barRect/主题变化时调。 */
    private void rebuildChrome() {
        RectF bar = dockRect();
        int W = Math.max(1, (int) bar.width()), H = Math.max(1, (int) bar.height());
        if (chromeB1 == null || chromeB1.getWidth() != W || chromeB1.getHeight() != H
                || chromeB1.getConfig() == Bitmap.Config.HARDWARE
                || chromeDark != (dark ? 1 : 0)) {
            /* HARDWARE 位图不可写：末尾会把 chromeB1 转成 HARDWARE 常驻纹理，
               若本次重建尺寸不变而复用旧对象，new Canvas(硬件位图) 会直接抛异常
               （2026-10-27 位置/药丸中心重建门启用后真机崩溃：Canvas.<init>）。 */
            chromeB1 = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            chromeDark = dark ? 1 : 0;
        }
        chromeRect.set(bar);
        chromePillCy = pillCy();
        Canvas c1 = new Canvas(chromeB1);
        float r = bar.height() / 2f;
        c1.drawRoundRect(0, 0, W, H, r, r, barFillP);
        barStrokeP.setStrokeWidth(density);
        c1.drawRoundRect(0, 0, W, H, r, r, barStrokeP);
        /* per-tab 灰态小图：sprite 原点 = 格左缘/栏顶。宽度取格宽（文字仅 2 字不会溢出）。 */
        for (int i = 0; i < count && i < 5; i++) {
            float gx = firstCx + slot * i - slot / 2f;
            Bitmap sp = Bitmap.createBitmap(Math.max(4, (int) (slot + 20 * density)),
                    Math.max(4, (int) bar.height()), Bitmap.Config.ARGB_8888);
            drawTabsOn(new Canvas(sp), -gx, -bar.top, i, inactiveColor, true);
            tabSprites[i] = sp;
        }

        /* 滑块缓存：阴影 + 白药丸本体一次画好（透明底）。
           尺寸按最大态（含 LIFT 增量 + 阴影边距），blit 时按中心对齐。
           必须在 HARDWARE 转换之前建——2026-10-28：原先排在转换之后，首次重建
           sliderBmp 还是 null，copy() NPE 被吞 → chromeB1 永远停在软件位图，
           滑块动画期每帧整栏上传。 */
        int sw = (int) ((pillW + LIFT_GROWTH * density) / 2f * 2f + 24f * density);
        int sh = (int) ((pillH + LIFT_GROWTH * density) / 2f * 2f + 24f * density);
        sliderBmp = Bitmap.createBitmap(Math.max(4, sw), Math.max(4, sh), Bitmap.Config.ARGB_8888);
        Canvas c3 = new Canvas(sliderBmp);
        float bw = pillW / 2f, bh = pillH / 2f;
        RectF sr3 = new RectF(sw / 2f - bw, sh / 2f - bh, sw / 2f + bw, sh / 2f + bh);
        float rr = Math.min(bw, bh);
        Paint shadowP = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowP.setColor(Color.argb(38, 15, 23, 42));
        c3.drawRoundRect(sr3.left, sr3.top + density, sr3.right, sr3.bottom + 2f * density, rr, rr, shadowP);
        c3.drawRoundRect(sr3, rr, rr, sliderFillP);
        sliderStrokeP.setStrokeWidth(density);
        c3.drawRoundRect(sr3, rr, rr, sliderStrokeP);

        /* 软件 → HARDWARE 位图：blit 走 GPU 常驻纹理，免去每帧 ~7MB 的上传
           （120Hz 滑块动画期间这是与 WebView 争 GPU 的隐形大头）。失败回退软件位图。 */
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Bitmap h1 = chromeB1.copy(Bitmap.Config.HARDWARE, false);
                Bitmap h3 = sliderBmp.copy(Bitmap.Config.HARDWARE, false);
                if (h1 != null) chromeB1 = h1;
                if (h3 != null) sliderBmp = h3;
            }
        } catch (Throwable t) {
            Log.w(TAG, "HARDWARE 位图转换失败，保持软件位图", t);
        }
    }

    /** 原生底栏：容器/灰图标（缓存 blit）+滑块。
     *  lensing（透镜在场）走实时绘制（含果冻形变+saveLayer 阴影）；
     *  滑块-only 动画（切页弹簧）走 blit —— 每帧 saveLayer 的离屏分配是
     *  打开卡片期间与 WebView 渲染争抢的大头（2026-10-27 实测 90th 16→24ms）。 */
    private void drawDockChrome(Canvas canvas, boolean lensing) {
        RectF bar = dockRect();
        /* 重建门必须含**位置**：只比尺寸的话，栏整体上下移动（insets 变化/横竖屏/
           抽屉开关后的重报）不会触发重建，缓存里还是旧 bar.top 的偏移 ——
           图标被画到胶囊底缘、文字整行被裁掉（2026-10-27 真机「文字没了」）。 */
        if (chromeB1 == null || sliderBmp == null || chromeDark != (dark ? 1 : 0)
                || Math.abs(chromeRect.width() - bar.width()) > 1
                || Math.abs(chromeRect.height() - bar.height()) > 1
                || Math.abs(chromeRect.left - bar.left) > 0.5f
                || Math.abs(chromeRect.top - bar.top) > 0.5f
                || Math.abs(chromePillCy - pillCy()) > 0.5f) {
            rebuildChrome();
        }
        /* 栏体玻璃：每帧补抓+组装（代判定在内部，未变即零成本跳过），
           玻璃就绪则直画（库 AGSL，透镜同款模式），否则实色 blit */
        maybeCaptureBarBackdrop();
        if (ensureBarGlass(bar, bar.height() / 2f)) {
            int sv = canvas.save();
            canvas.translate(bar.left, bar.top);
            float rr2 = bar.height() / 2f;
            float bw2 = bar.width(), bh2 = bar.height();
            canvas.drawRoundRect(0, 0, bw2, bh2, rr2, rr2, barGlassP);
            barStrokeP.setStrokeWidth(density);
            canvas.drawRoundRect(0, 0, bw2, bh2, rr2, rr2, barStrokeP);
            canvas.restoreToCount(sv);
        } else {
            canvas.drawBitmap(chromeB1, bar.left, bar.top, null);
        }

        /* 滑块先画（在图标之下）：拖到两格中间时滑块从图标后面滑过，
           图标始终完整可见（HTML tab-pill 的 z 序，2026-10-27 用户反馈「遮挡其中一个」）。 */
        float cx = firstCx + slot * (float) pos.value();
        float cy = pillCy();
        float s = (float) jelly.value();
        float g = LIFT_GROWTH * density * (float) lift.value();
        float hw = (pillW + g) / 2f * (1f + s);
        float hh = (pillH + g) / 2f * (1f - s * 1.4f);
        if (!lensing && sliderBmp != null) {
            /* 滑块-only 动画：形变量（jelly/lift）微小，直接 blit 固定尺寸缓存 */
            canvas.drawBitmap(sliderBmp, cx - sliderBmp.getWidth() / 2f,
                    cy - sliderBmp.getHeight() / 2f, blitP);
        } else {
            tmpRect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            float sr = Math.min(hw, hh);
            int sv = canvas.save();
            canvas.saveLayer(tmpRect.left - 6f * density, tmpRect.top - 6f * density,
                    tmpRect.right + 6f * density, tmpRect.bottom + 6f * density, null);
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(Color.argb(38, 15, 23, 42));
            canvas.drawRoundRect(tmpRect.left, tmpRect.top + density, tmpRect.right,
                    tmpRect.bottom + 2f * density, sr, sr, sp);
            canvas.restoreToCount(sv);
            canvas.drawRoundRect(tmpRect, sr, sr, sliderFillP);
            sliderStrokeP.setStrokeWidth(density);
            canvas.drawRoundRect(tmpRect, sr, sr, sliderStrokeP);
        }

        /* 灰态图标+文字：per-tab blit（跳过选中格——选中格由 drawLensTabs 以蓝态画在玻璃上） */
        RectF bar2 = dockRect();
        float act = pressed ? (float) pos.value() : selectedIndex;
        int actIdx = Math.max(0, Math.min(count - 1, Math.round(act)));
        for (int i = 0; i < count && i < 5; i++) {
            if (tabSprites[i] == null || i == actIdx) continue;
            canvas.drawBitmap(tabSprites[i], firstCx + slot * i - slot / 2f, bar2.top, blitP);
        }
    }

    private final RectF barFallback = new RectF();

    /** 栏矩形：JS 上报优先；未上报时以药丸行推导（上下各外扩 6dp，左右满宽）。
     *  fallback 用独立 RectF —— 共享 tmpRect 会被滑块绘制覆盖，引用混乱。 */
    private RectF dockRect() {
        if (barRect.width() > 4 && barRect.height() > 4) return barRect;
        float cy = pillCy();
        float top = cy - pillH / 2f - 6f * density;
        barFallback.set(0, top, bandW, top + pillH + 12f * density);
        return barFallback;
    }

    /** 图标+文字绘制核心。dx/dy = 画布偏移；onlyIdx >= 0 时只画该格（蓝态），
     *  = -1 画全部（进缓存，须配 forceOff=true 强制灰态，否则 rebuild 时刻的
     *  activeIdx 蓝色会被烤进缓存 —— 滑块滑走后蓝字残留在错误格，
     *  2026-10-27 真机 cache-drag 实证）。 */
    private void drawTabsOn(Canvas canvas, float dx, float dy, int onlyIdx, int offColor,
                            boolean forceOff) {
        /* 与 drawLensTabs 同一套位置判定（玻璃在场跟 pos，离场回 selectedIndex）：
           两处不一致的话，滑行段蓝态会跟错格。 */
        boolean lensing = pressed || (float) lift.value() > 0.005f;
        float active = lensing ? (float) pos.value() : selectedIndex;
        int activeIdx = Math.max(0, Math.min(count - 1, Math.round(active)));
        float contentH = 22f * density + 2f * density + 12f * density;  // 图标22 + gap2 + 文字行高12
        float cy = pillCy();
        float iconTop = cy - contentH / 2f + dy;
        Paint.FontMetrics fm = textP.getFontMetrics();
        float textBaseline = iconTop + 22f * density + 2f * density - fm.ascent;

        for (int i = 0; i < count && i < 5; i++) {
            if (onlyIdx >= 0 && i != onlyIdx) continue;
            float cx = firstCx + slot * i + dx;
            boolean on = !forceOff && i == activeIdx;
            int color = on ? activeColor() : offColor;
            Path[] subs = iconPaths[i];
            if (subs != null) {
                canvas.save();
                canvas.translate(cx - 11f * density, iconTop);
                iconP.setColor(color);
                iconP.setAlpha(255);
                for (int k = 0; k < subs.length; k++) {
                    /* layers（第 0 图标）第三笔在 SVG 里 opacity=".5"，与 CSS 渲染对齐 */
                    boolean half = (i == 0 && k == subs.length - 1);
                    iconP.setAlpha(half ? 128 : 255);
                    canvas.drawPath(subs[k], iconP);
                }
                canvas.restore();
            }
            textP.setColor(color);
            textP.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(TAB_LABELS[i], cx, textBaseline, textP);
        }
    }

    /** 玻璃之上的图标重绘（FlClash _LensContent）：透镜跨两格时两格都要可见——
     *  round(pos) 一格蓝、另一格灰。屏幕画布 = 本层坐标，dx/dy 必须为 0
     *  （复用缓存变换会把图标画到栏外，2026-10-27 真机「孤立蓝字」实证）。
     *  位置判定含松手后的滑行段（玻璃未离场就跟着 pos 走）：滑行期玻璃
     *  不透明，只按 selectedIndex 重绘会让途经格整块消失在玻璃下
     *  （2026-10-27 真机「滑到中间遮挡文字」）。 */
    private void drawLensTabs(Canvas canvas) {
        boolean lensing = pressed || (float) lift.value() > 0.005f;
        float active = lensing ? (float) pos.value() : selectedIndex;
        int blue = Math.max(0, Math.min(count - 1, Math.round(active)));
        drawTabsOn(canvas, 0, 0, blue, 0, false);
        if (active != blue) {                      // 透镜跨两格：另一格灰态也重绘（否则被不透明玻璃盖住）
            int other = Math.max(0, Math.min(count - 1, (int) Math.floor(active)));
            if (other == blue) other = Math.max(0, Math.min(count - 1, (int) Math.ceil(active)));
            if (other != blue) drawTabsOn(canvas, 0, 0, other, iconP.getColor(), true);
        }
    }

    /** 按压透镜玻璃（原 onDraw 的 shader 段抽出，几何/降级逻辑原样）。 */
    private void drawGlass(Canvas canvas, float liftV) {
        float g = LIFT_GROWTH * density * liftV;
        float s = (float) jelly.value();
        float hw = (pillW + g) / 2f * (1f + s);
        float hh = (pillH + g) / 2f * (1f - s * 1.4f);
        float cx = firstCx + slot * (float) pos.value();
        float cy = pillCy();

        pillRect.set(cx - hw, cy - hh, cx + hw, cy + hh);
        float r = Math.min(hw, hh);

        if (shader != null && backdrop != null && Build.VERSION.SDK_INT >= 33) {
            updateShaderUniforms(cx, hw, hh);
            /* 玻璃随 lift 淡入（0.4 就到全显，压住淡出期的空档）。
               HTML 底栏模式透明度封顶 50%：本层活在独立 Window、永远盖在
               WebView 之上，全显的不透明玻璃会把栏内图标文字整块吃掉
               （2026-10-27 真机「滑到中间遮挡文字」）。镜内 1:1 之后，透过
               玻璃的实况内容与折射采样逐像素对齐，透镜只贡献霜边/折射/色散。 */
            float alphaK = Math.min(1f, liftV * 2.5f);
            if (!nativeDock) alphaK = Math.min(alphaK, 0.5f);
            glassPaint.setAlpha((int) (255f * alphaK));
            rimPaint.setAlpha((int) (90f * alphaK));
            int save = canvas.save();
            canvas.clipRect(0, 0, bandW, bandH);
            canvas.drawRoundRect(pillRect, r, r, glassPaint);
            canvas.drawRoundRect(pillRect, r, r, rimPaint);
            canvas.restoreToCount(save);
        } else {
            /* 背景未就绪时的按压降级：半透明药丸，保证按下有反馈 */
            fallbackPaint.setColor(Color.argb(60, 255, 255, 255));
            canvas.drawRoundRect(pillRect, r, r, fallbackPaint);
        }
    }

    /**
     * 挂到 Activity：本层活在 **PopupWindow 的独立 Window** 里。
     *
     * 为什么不再 addView 到主布局：PixelCopy 抓的是主窗口的合成结果，本层若在主窗口里，
     * 就会把自己画的玻璃一起抓进去 → 反馈循环。独立 Window 不参与主窗口合成，天然免疫。
     */
    public static LensBarView attach(Activity act, ViewGroup root, WebView webView) {
        LensBarView v = new LensBarView(act, webView);
        v.window = act.getWindow();
        v.anchor = root;
        int h = (int) (120 * act.getResources().getDisplayMetrics().density);
        v.popup = new PopupWindow(v, ViewGroup.LayoutParams.MATCH_PARENT, h, false);
        v.popup.setClippingEnabled(false);
        v.popup.setBackgroundDrawable(null);
        /* 保底预热：即使几何上报这条路失败，也要先有一张背景，
           否则药丸永远不会画出来（noBg 降级只是半透明，没有折射内容）。 */
        v.postDelayed(v::captureBackdrop, 1200);
        /* 滑块绘制路径预热（nativeDock）：冷启动后首次切页的滑块弹簧动画要付
           onDraw/blit 路径的 JIT 全额成本（实测首次切页 legacy janky 8.65% vs
           热态 3%）—— 几何同步后滑块无声地动 3% 再弹回，把路径跑热，
           用户第一次真实点击就是热路径。肉眼几乎不可见（一格的 3%）。 */
        v.postDelayed(() -> {
            if (v.detached || !v.nativeDock) return;
            v.pos.springTo(0.03, TRAVEL, nowSec());
            v.ensureTicking();
            v.postDelayed(() -> {
                if (v.detached || v.pressed) return;
                v.pos.springTo(0, TRAVEL, nowSec());
                v.ensureTicking();
            }, 150);
        }, 3000);
        /* 几何补测：ready 时刻 safe-area 尚未生效（Bridge.ready 才触发 insets 应用），
           首次量到的是瞬态布局。1.5s / 3s 各补量一次，稳定值覆盖瞬态值。 */
        v.postDelayed(v::requestGeometry, 1500);
        v.postDelayed(v::requestGeometry, 3000);
        v.showPopup();
        return v;
    }

    /** 显示透镜窗（幂等）。window token 未就绪时有界重试（200ms×25）——
     *  快速重启/键盘收起路径不保证 token 已在。 */
    /** 旋转后弹窗宽度不跟随：PopupWindow 的 MATCH_PARENT 在 showAtLocation 时定格，
     *  横屏 3040 宽的窗口转竖屏后仍按 3040 布局 —— 可见区域里只有前几格可点
     *  （2026-10-27 竖屏实测「我的」点不到）。宽度变了就 dismiss 重 show。 */
    private int lastPopupWidth;

    public void reflow() {
        if (popup == null || detached || anchor == null) return;
        int screenW = anchor.getResources().getDisplayMetrics().widthPixels;
        if (lastPopupWidth == screenW && popup.isShowing()) return;
        lastPopupWidth = screenW;
        if (popup.isShowing()) {
            try { popup.dismiss(); } catch (Throwable ignored) { }
        }
        showPopup();
    }

    private void showPopup() {
        if (popup == null || popup.isShowing() || detached || anchor == null) return;
        anchor.post(new Runnable() {
            int tries = 0;
            @Override public void run() {
                if (detached || popup.isShowing()) return;
                if (anchor.getWindowToken() != null) {
                    try {
                        popup.showAtLocation(anchor, Gravity.BOTTOM | Gravity.START, 0, 0);
                    } catch (Throwable t) {
                        Log.w(TAG, "PopupWindow 显示失败", t);
                    }
                    return;
                }
                if (++tries <= 25) anchor.postDelayed(this, 200);
                else Log.w(TAG, "PopupWindow 等待窗口 token 超时，未显示");
            }
        });
    }

    /** 键盘弹出时收起透镜窗：adjustResize 下它会悬停在键盘上沿，药丸拦截带
     *  正好压住页面底部内容；网页侧底栏此时已由 data-kb 隐藏，原生同步收起。
     *  收起时若正拖着，按取消处理弹回选中格。 */
    public void setImeVisible(boolean shown) {
        if (imeShown == shown || popup == null || detached) return;
        imeShown = shown;
        updatePopupVisibility();
    }

    /** 页面有底部弹层（sheet/抽屉）盖住底栏时收起透镜窗。弹窗是独立窗口永远
     *  置顶，药丸触摸带会吃掉 sheet 底部控件（全局质感的玻璃开关）的点击，甚至
     *  被当成切 tab 处理（切页还会顺带关 sheet）—— 2026-10-26 用户报障。
     *  页面经 NetBridge.setSheetOpen 推送开合状态。 */
    public void setOverlaid(boolean overlaid) {
        if (this.overlaid == overlaid || popup == null || detached) return;
        this.overlaid = overlaid;
        updatePopupVisibility();
    }

    /** 透镜窗显隐唯一入口：IME 或页面弹层任一在场就收窗。收窗时玻璃必须归零
     *  并让帧循环跑完落定分支——原实现漏了 lift 与页面类：若键盘/弹层在按压或
     *  抬起落定窗口内出现，lift 冻在半空、dropping 已取消、wasActive 被清 →
     *  帧循环休眠，无人再把玻璃送回 0；重显弹窗后 onDraw 看到 liftV>0 照画
     *  —— 玻璃药丸永久悬停在最上层，页面侧 lens-live/native-lens-press 残留
     *  让 HTML 药丸消失、tab 全灰（2026-10-26 用户报「药丸悬停所有页面关不掉」）。 */
    private void updatePopupVisibility() {
        boolean hide = imeShown || overlaid;
        if (hide) {
            boolean wasPressed = pressed;
            if (pressed) {
                pressed = false;
                dragging = false;
                dropping = false;
                pos.springTo(selectedIndex, TRAVEL, nowSec());
            }
            if (wasPressed || lift.value() > 0.005f || lift.active()) {
                dropping = false;
                lift.springTo(0, LIFT_S, nowSec());
                ensureTicking();
                wasActive = true;   // 交给 doFrame 的落定分支：callLensEnd + setPressClass(false)
            } else {
                wasActive = false;
            }
            if (popup.isShowing()) {
                try {
                    popup.dismiss();
                } catch (Throwable ignored) {
                    // 收起失败无后果
                }
            }
        } else {
            showPopup();
        }
    }

    /** Activity 销毁时收掉：帧回调、延迟任务、弹窗一次清干净，否则会 window leak
     *  且旧实例在 Choreographer 上永久空转（旋转重建场景会逐次累积）。 */
    public void detach() {
        detached = true;
        ticking = false;
        removeCallbacks(null);   // 预热/几何补测的 postDelayed 一并撤掉（detached 后本就是空操作）
        Choreographer.getInstance().removeFrameCallback(this);
        pressed = false;
        dragging = false;
        dropping = false;
        setPressClass(false);
        if (popup != null && popup.isShowing()) {
            try {
                popup.dismiss();
            } catch (Throwable ignored) {
                // 销毁期异常无意义
            }
        }
    }
}
