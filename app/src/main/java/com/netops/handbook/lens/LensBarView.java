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
import android.view.ViewTreeObserver;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    /* 拖拽跟手弹簧：质量 1 时临界阻尼的稳态滞后 = v·2ζ/ω。首版 1024/0.9 →
       ω=32，快拖（1500px/s）药丸落后手指 ~84px（半格）—— 用户报「从第一个点
       到第二个点的中间非常粘手」（2026-10-29）。10000/1.0 → ω=100，滞后 ~30ms
       ≈ 1:1 跟手，保留一点缓冲的液感；松手落格仍是 TRAVEL 的弹性。 */
    private static final SpringSpec FOLLOW  = SpringSpec.ratio(10000, 1.0);
    private static final SpringSpec TRAVEL  = SpringSpec.durationBounce(400, 0.15);
    private static final SpringSpec FAST_TRAVEL = SpringSpec.durationBounce(140, 0.02);
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
    /** CSS 上报的底栏药丸尺寸（竖排期被 rail 常量覆盖，回横排要还原）。 */
    private float jsPillW = 0, jsPillH = 0;
    private float pillW = 0, pillH = 0;
    private float firstCx = 0;       // 第 0 格药丸中心 x（屏幕 px）
    /* ---- 横屏侧栏（竖排 dock，2026-10-28）----
       横屏下底栏横铺 5 格 = 尺寸失衡 + 遮挡不均（用户报），参照 iOS 侧栏改竖排：
       栏贴左缘、5 格纵列、药丸沿 y 滑动。**几何完全由原生自算**（轨道常量），
       不吃 JS 的底栏矩形——横屏时 JS 仍在量隐藏的横排 HTML 栏，口径不符。
       竖屏一切照旧（JS 几何驱动）。轴向抽象：axisFirst/axisPitch/crossC，
       横屏=x 起点/x 格距/y 中心；竖排=y 起点/y 格距/x 中心。 */
    private boolean dockVertical = false;
    private float axisFirst = 0f;    // 第 0 格轴向中心（视图本地）
    private float axisPitch = 0f;    // 格距（沿轴）
    private float crossCView = 0f;   // 固定方向中心（横=y / 竖=x，视图本地）
    private final RectF railRect = new RectF();   // 竖排侧栏矩形（视图本地）
    /** 竖排栏宽 / 左距（dp）。窗口宽度必须跟着这两个值走：窗口比栏宽多出去的每 1px
     *  都会**吞掉内容区的点击**（2026-10-28 复查：栏收窄到 48dp 后窗口还是 280px，
     *  内容左缘 90px 的点击全被转发进 WebView）。 */
    private static final float RAIL_W_DP = 48f;
    private static final float RAIL_LEFT_DP = 14f;
    /** 竖排侧栏顶让位（顶栏底边，视图本地 px）：顶栏是独立窗、永远压在上面，
     *  侧栏通高会被它横切一刀。 */
    private float railTop = 0f;
    private float pillCxNow, pillCyNow;           // 药丸中心（视图本地，按方向合成）
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
    private int downId = -1;   /* 多指：只跟起手那根手指（move 按它取坐标） */
    private float downX = 0;
    private float lastX = 0;
    private long lastMoveMs = 0;
    private float velIndexPerMs = 0;

    /* 抓取节流：静止时不必每帧抓 */
    private boolean copyBusy = false;
    private long lastCaptureMs = 0;
    private static final long ACTIVE_CAPTURE_MS = 50;    // 交互期背景重抓 ~20fps：镜内剖切边与页面同步
    /* 仅横排底栏透镜用（竖排侧栏无透镜 → 零抓图，见 drawDockChrome）。 */
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
    private final Paint railSelP = new Paint(Paint.ANTI_ALIAS_FLAG);   // 竖排选中项底
    private final Paint railBarP = new Paint(Paint.ANTI_ALIAS_FLAG);   // 竖排选中竖条
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
    private NativeBackdrop backdropSource;          // 静态壁纸采样源（对齐 AndroidLiquidGlassView 官方标准源）

    public void setBackdropSource(NativeBackdrop src) {
        this.backdropSource = src;
        onBackdropChanged();
    }

    private Bitmap barBackdrop;                     // 整栏背景抓图（栏矩形+pad，折射向外采样用）
    private BitmapShader barBackdropShader;
    private int barCapOffX, barCapOffY;             // 栏左上在背景位图内的偏移（pad 对齐）
    private RuntimeShader barGlassShader;
    private final Paint barGlassP = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 竖排侧栏面板（FlClash 口径纯色半透，见 drawDockChrome）：浅色 ~70% 白、
       暗色 ~80% 深面——暗壁纸上暗面板要更高不透明度才托得住灰图标。 */
    private final Paint railFillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcBarRect = new Rect();
    /** PixelCopy 落点（清晰原图）；barBackdrop 是它雾化后的合成结果。 */
    private Bitmap barCapTmp;
    private final RectF lastCapturedBar = new RectF();
    private int lastCapturedDark = -1;
    /** 栏下内容变了（滚动/切页/拖拽）→ 需要重抓。没有它时栏体玻璃采样源是静态壁纸，
        弯折平滑壁纸肉眼看不出，等于"栏体没有液态玻璃"（2026-10-31 定位）。 */
    private boolean barContentDirty = false;
    private long lastBarCapMs = 0;
    /** true = 上一次内容抓取失败过，本次改用静态壁纸兜底（下次成功再复位）。 */
    private boolean preferWallpaper = false;
    private boolean barCopyBusy;
    private static String barAgsl;                  // AGSL 源码（进程内缓存一份）
    /** 首次拿到真实栏几何后是否已淡入（冷启动防「由大变小」见 onDraw）。 */
    private boolean geomFadedIn = false;
    private final RectF tmpRect = new RectF();

    /** 亮/暗两套配色，逐值对齐 01-tokens.css（浅 #2563eb/#8E8E93；暗 #0a84ff/rgba(235,235,245,.60)）。 */
    private void applyDockColors() {
        if (dark) {
            railFillP.setColor(Color.argb(204, 24, 24, 26));   // 竖排面板：暗面 ~80%
            barFillP.setColor(Color.argb(255, 28, 28, 30));   // 实色回退
            barStrokeP.setColor(Color.argb(70, 255, 255, 255));   // 半透亮边高光（AndroidLiquidGlass 质感）
            sliderFillP.setColor(Color.argb(255, 44, 44, 46));    // --surface-2 #2c2c2e
            sliderStrokeP.setColor(Color.argb(26, 255, 255, 255));
            iconP.setColor(Color.argb(153, 235, 235, 245));       // --tab-inactive rgba(235,235,245,.60)
            textP.setColor(Color.argb(153, 235, 235, 245));
            inactiveColor = Color.argb(153, 235, 235, 245);
        } else {
            railFillP.setColor(Color.argb(178, 255, 255, 255));   // 竖排面板：白 ~70%（cf1fc0d 前的已验收值）
            barFillP.setColor(Color.WHITE);                    // 实色回退
            barStrokeP.setColor(Color.argb(120, 255, 255, 255));  // 半透白色高光边缘
            /* 对照 FlClash 手机端（液态玻璃=极致）实测：选中药丸 #DEE5FC（主色 12% 叠白，不是纯白）、
               未选中图标 #424754（深灰蓝，我们原来的 #8E8E93 偏浅发灰）。 */
            sliderFillP.setColor(Color.argb(31, 37, 99, 235));    // accent #2563EB @ 12%
            sliderStrokeP.setColor(Color.argb(18, 37, 99, 235));
            iconP.setColor(Color.rgb(66, 71, 84));                // #424754
            textP.setColor(Color.rgb(66, 71, 84));
            inactiveColor = Color.rgb(66, 71, 84);
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

    /** 竖排侧栏顶部让位（顶栏底边 px）。顶栏是独立 PopupWindow、永远在最上层，
     *  侧栏若通高就会被顶栏那条玻璃横切（2026-10-28 用户报「打开直接重叠」）。 */
    public void setRailTopInset(float px) {
        if (Math.abs(px - railTop) < 0.5f) return;
        railTop = Math.max(0f, px);
        reflow();        // 窗口 y 偏移/高度都要跟着变（顺带 computeRailGeometry）
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

    /** 桥：栏矩形（CSS×dpr 物理像素，**主窗口本地坐标**——JS getBoundingClientRect
     *  本就是窗口坐标，分屏/小窗下窗口 ≠ 屏幕，此口径保证换算基准不漂）。
     *  弹窗本地坐标由 dockRect()/pillCy() 按「弹窗在主窗口内的位置」实时换算
     *  （2026-10-28：原先在此处按 getLocationOnScreen 一次性换算，分屏/小窗切换
     *  时窗口在屏幕上移动，换算基准过期 → dock 错位/失效/重影）。 */
    public void setLensBarRect(float l, float t, float w, float h) {
        barRect.set(l, t, l + w, t + h);
        invalidate();
    }

    public LensBarView(Context context, WebView webView) {
        super(context);
        this.webView = webView;
        this.density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(1f * density);
        /* ❗描边画笔必须显式 STROKE：Paint 默认 FILL，漏设的话「画一圈边」实际是
           「整块填满」——栏体先画玻璃再被这条 #E3E3E8 实色盖回去，玻璃等于白做
           （2026-10-28 用户报「液态玻璃被改了」的真因）。 */
        barStrokeP.setStyle(Paint.Style.STROKE);
        sliderStrokeP.setStyle(Paint.Style.STROKE);
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
        this.jsPillW = pillWPx;
        this.jsPillH = pillHPx;
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
        if (dockVertical) {
            computeRailGeometry();                 // 轨高按视口高夹齐，视口到达后重排
        } else {
            axisFirst = firstCx;                   // 横排：轴向=x
            axisPitch = slot;
        }
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
            if (dockVertical || nativeDock) {
                /* 原生Dock/侧栏模式：0延迟直接跟进，彻底拔除250ms人为延迟导致的粘滞感 */
                removeCallbacks(sliderCatchUp);
                if (Math.round(pos.target()) != selectedIndex) {
                    pos.springTo(selectedIndex, dockVertical ? FAST_TRAVEL : TRAVEL, nowSec());
                    ensureTicking();
                }
            } else {
                removeCallbacks(sliderCatchUp);
                postDelayed(sliderCatchUp, 250);
            }
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
        if (webView == null || detached || dockVertical) return;
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
        updateOrientation();
        /* 只补 JS 还没上报的项：CSS 才是几何真源，这里只是兜底 */
        if (dockVertical) {
            computeRailGeometry();
            if (slot <= 0) slot = axisPitch;
        } else {
            if (slot <= 0) {
                slot = w / (float) Math.max(1, count);
                firstCx = slot * 0.5f;
            }
            if (pillH <= 0) pillH = 56f * density;
            if (pillW <= 0) pillW = slot + 8f * density;   // 药丸比一格宽 8dp，与 CSS 同
        }
        scheduleBarRecapture();      // 尺寸/方向变了：旧采样作废，重抓三代
    }

    /* ================= 横屏侧栏（竖排 dock） ================= */

    /** 横屏判定：**必须用主窗口尺寸**，不能用本 View（弹窗）的宽高比 ——
     *  弹窗横屏是 280×1904、竖屏是「屏宽×120dp」，用它的比例会把竖屏判成
     *  竖排栏（只剩居中一列图标）、横屏判成横排栏（画在 280px 宽的窗外 =
     *  整条 dock 消失）。2026-10-28 真机：两个方向同时报废，用户报
     *  「交互全没了」。 */
    private boolean isLandscapeWindow() {
        if (anchor != null && anchor.getWidth() > 0 && anchor.getHeight() > 0) {
            return anchor.getWidth() > anchor.getHeight();
        }
        android.content.res.Configuration c = getResources().getConfiguration();
        return c.screenWidthDp > c.screenHeightDp;
    }

    /** 药丸圆角：横排 = 半高（两端全圆胶囊）；竖排 = 18dp 圆角方块。
     *  竖排药丸 68×62dp，按 min(w,h)/2 取会变成**正圆**（2026-10-28 用户报
     *  「药丸状态有点奇怪」：选中格像挂了个球，文字贴着圆边）。 */
    private float pillCorner(float hw, float hh) {
        float r = Math.min(hw, hh);
        return dockVertical ? Math.min(18f * density, r) : r;
    }

    /** 栏体圆角：横排=栏高/2（胶囊），竖排=轨宽/2（竖向胶囊）。
     *  竖排栏高 = 整窗高，拿高度算半径会退化成满圆角。 */
    private float dockRadius(RectF bar) {
        /* 横排底栏：半高 = 胶囊（FlClash pill 就是这个语义，横向短条两端自然是半圆）。
           竖排侧栏**不能**照搬这条：栏高 ~1630px，半高半径 121px 会让两端变成半圆，
           整条看着像一根竖着的椭圆（2026-10-28 用户报）。侧栏是面板，用固定 16dp 圆角（与原生卡片同族）。 */
        float half = Math.min(bar.width(), bar.height()) / 2f;
        return dockVertical ? Math.min(10f * density, half) : half;
    }

    /** 方向推断 + 轨道几何。竖排几何完全由原生自算，不吃 JS 的横排 HTML 栏矩形。 */
    private void updateOrientation() {
        boolean vert = isLandscapeWindow();
        if (vert != dockVertical) {
            dockVertical = vert;
            chromeDark = -1;                       // 缓存（含 sprite 形状）按方向重建
            java.util.Arrays.fill(tabSprites, null);
            if (dockVertical) {
                computeRailGeometry();
            } else if (jsPillW > 0) {
                /* 竖排期 pillW/pillH 被 rail 常量（68×62dp）覆盖，回横排必须还原成
                   CSS 上报的底栏药丸 —— 否则底栏上挂着一颗小圆点（2026-10-28 用户报
                   「底栏药丸状态奇怪」）。 */
                pillW = jsPillW;
                pillH = jsPillH;
                if (slot > 0) { axisPitch = slot; axisFirst = firstCx; }
            }
            invalidate();
        } else if (dockVertical) {
            computeRailGeometry();                 // 尺寸变了（旋转/分屏拖拽）重排
        }
    }

    /** 竖排轨道几何（视图本地，density 缩放）：栏贴左缘、5 格纵列居中、
     *  药丸=轨道宽−20dp × 格距−16dp。数字对标 FlClash 单元格 76dp 口径。 */
    private void computeRailGeometry() {
        float dp = density;
        /* FlClash NavigationSidebar 实测（lib/ui/sidebar.dart:12-26）：
           栏宽 48dp、项 40dp 方块、项距 4dp、项内圆角 10dp、选中 = 8% 填充 + 左缘
           3dp×16dp 竖条。compact 态**只有图标没有文字**。 */
        float railW = RAIL_W_DP * dp;
        float pitch = 44f * dp;                    // 40 项 + 4 间距
        float pillHv = 40f * dp;
        float pillWv = 40f * dp;
        /* 轨高夹到页面视口高：窗口比视口多出的 1~2px 会让栏体抓图被判「超界」
           而整条回退实色；夹齐后抓图区恰好覆盖栏体。 */
        /* 本窗已整体下移到顶栏下沿，所以轨矩形从本地 0 起；高度 = 窗口高 - 14dp，
           两端圆角都能露出来（通高到底的话下端被屏幕边切掉，像「插进地里」）。 */
        float h = viewportH > 0 ? Math.min(bandH, viewportH) : bandH;
        /* 面板只包住 5 个图标（FlClash 的栏是贴着屏幕边的，没有面板；我们浮在壁纸上，
           面板必须收成内容高，否则 48dp 宽 × 全高就是一条空槽）。 */
        float contentH = 4f * pitch + pillHv;
        float panelH = contentH + 8f * dp;
        float cy0 = (h - 14f * dp) / 2f;
        railRect.set(0, cy0 - panelH / 2f, railW, cy0 + panelH / 2f);
        axisPitch = pitch;
        if (slot <= 0) slot = pitch;
        axisFirst = railRect.top + 4f * dp + pillHv / 2f;
        crossCView = railRect.left + railW / 2f;   // = 栏心（窗口本地）
        pillW = pillWv;                            // x 维（交叉向）
        pillH = pillHv;                            // y 维（轴向）
    }

    /** 药丸中心（视图本地，按方向合成）：横=(轴位置, 交叉中心)；竖=(交叉中心, 轴位置)。 */
    private void composePillCenter() {
        float axisPos = axisFirst + axisPitch * (float) pos.value();
        if (dockVertical) {
            pillCxNow = crossCView;
            pillCyNow = axisPos;
        } else {
            pillCxNow = axisFirst + axisPitch * (float) pos.value();
            pillCyNow = pillCy();
        }
    }

    /** 第 i 格轴向中心（视图本地）。 */
    private float cellAxis(int i) {
        return axisFirst + axisPitch * i;
    }

    /** 药丸中心 y（本 View 坐标）。JS 上报的是屏幕物理 px，减去本 View 的屏幕顶边即得；
     *  再向带内夹一次 —— 上报值无论多离谱都不允许画出 PopupWindow 边界。 */
    /** 复用的屏幕坐标缓冲：pillCy() 每帧被多次调用（绘制+重建门），
     *  原先每调一次 new int[2]，120Hz 下是纯 GC 垃圾。 */
    private final int[] locTmp = new int[2];
    /** 分屏/小窗坐标链缓冲：主窗口本地 ↔ 弹窗本地的换算基准与中间量。 */
    private final int[] winOff = new int[2];
    private final int[] pvOff = new int[2];
    private final int[] scrTmp = new int[2];
    private final int[] anchorScr = new int[2];
    private final int[] anchorWin = new int[2];
    private final RectF barLocal = new RectF();

    /** 弹窗内容视图在**主窗口**内的位置（窗口本地 ← 弹窗本地 的换算基准）。
     *  分屏/小窗下主窗口在屏幕上有偏移，getLocationOnScreen 的口径会把窗口
     *  偏移重复计入 —— 必须用「弹窗屏幕位置 − 主窗口屏幕原点」。 */
    private void popupOffsetInMainWindow(int[] out) {
        getLocationOnScreen(scrTmp);
        anchor.getLocationOnScreen(anchorScr);
        anchor.getLocationInWindow(anchorWin);
        out[0] = scrTmp[0] - (anchorScr[0] - anchorWin[0]);
        out[1] = scrTmp[1] - (anchorScr[1] - anchorWin[1]);
    }
    private float pillCy() {
        if (pillCyPx > 0) {
            popupOffsetInMainWindow(locTmp);
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
            if (lensing && !dockVertical
                    && System.currentTimeMillis() - lastCaptureMs >= ACTIVE_CAPTURE_MS) {
                captureBackdrop();
            }
            /* 脏区重合成：滑块+蓝格移动区域（玻璃期仍整层——折射面大）。
               全窗 invalidate 的合成面积 3040×440；收窄后入场动画期的
               每帧 GPU 合成大幅下降（滑块动画 legacy janky 的最后一块）。 */
            if (nativeDock && !lensing && !dockVertical) {
                float cx2 = firstCx + slot * (float) pos.value();
                float cy2 = pillCy();
                float pad2 = 40f * density;
                invalidate((int) Math.max(0, cx2 - pillW / 2f - pad2),
                        (int) Math.max(0, cy2 - pillH / 2f - pad2),
                        (int) Math.min(bandW, cx2 + pillW / 2f + pad2),
                        (int) Math.min(bandH, cy2 + pillH / 2f + pad2));
            } else {
                invalidate();   /* 透镜期/竖排侧栏全量重绘（竖排栏面积小，且半透明玻璃必须全量） */
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
        if (detached || bandW <= 0 || bandH <= 0 || copyBusy || dockVertical) return;
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
     * 坐标口径 = **主窗口本地**（PixelCopy 的 rect 就是窗口坐标；firstCx/pillCyPx
     * 均为窗口本地，分屏/小窗下不再叠加窗口的屏幕偏移）。
     */
    private void captureByPixelCopy(long t0) {
        /* 只抓药丸那一小块（+ 位移余量），不再抓整条带：
           整条带 1272×420 ≈ 2.1MB 回读实测 70ms，缩到 ~300×250 后应该一个量级往下掉。 */
        float cxScreen;
        float cyScreen;
        if (dockVertical) {
            popupOffsetInMainWindow(pvOff);
            cxScreen = crossCView + pvOff[0];
            cyScreen = cellAxis((int) Math.round(pos.value()))
                    + axisPitch * ((float) pos.value() - Math.round(pos.value())) + pvOff[1];
        } else {
            cxScreen = firstCx + slot * (float) pos.value();
            cyScreen = pillCyPx > 0 ? pillCyPx : (viewportH - pillH / 2f - 22f * density);
        }
        int pad = (int) (40 * density);
        int halfW = (int) (pillW / 2f + LIFT_GROWTH * density / 2f) + pad;
        int halfH = (int) (pillH / 2f + LIFT_GROWTH * density / 2f) + pad;
        DisplayMetrics m = getResources().getDisplayMetrics();
        /* 底边基准 = 页面视口物理高（2800）。heightPixels 只有 2587（挖掉手势条），
           拿它夹 srcRect 会把 2587 以下的标签整体裁出背景图，CLAMP 平铺成一片
           匀色 —— 2026-10-26 实测"镜内图标在、文字没了"就是这个。 */
        int maxH = viewportH > 0 ? (int) viewportH : m.heightPixels;
        int winW = anchor != null ? anchor.getWidth() : m.widthPixels;
        int l = Math.max(0, (int) (cxScreen - halfW));
        int top = Math.max(0, (int) (cyScreen - halfH));
        int r = Math.min(winW, (int) (cxScreen + halfW));
        int bot = Math.min(maxH, (int) (cyScreen + halfH));
        if (r - l < 4 || bot - top < 4) {
            copyBusy = false;
            return;
        }
        srcRect.set(l, top, r, bot);
        /* uBgOffset：弹窗本地 p → 位图坐标 = p + (弹窗窗口位置 − 抓图左上) */
        popupOffsetInMainWindow(pvOff);
        bgOffX = l - pvOff[0];
        bgOffY = top - pvOff[1];
        if (backdrop == null || backdrop.getWidth() != srcRect.width()
                || backdrop.getHeight() != srcRect.height()) {
            backdrop = Bitmap.createBitmap(srcRect.width(), srcRect.height(), Bitmap.Config.ARGB_8888);
            backdropShader = new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        issuePixelCopy(t0);
    }

    /** 发出一次 PixelCopy（srcRect/backdrop/bgOff 已就位）。 */
    private void issuePixelCopy(long t0) {
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
        if (dockVertical) {
            /* 竖排：接管带仅限轨道面板及其容错扩展（上下各8dp），其余放行给页面 */
            return y >= railRect.top - 8f * density && y <= railRect.bottom + 8f * density;
        }
        if (nativeDock && barRect.width() > 4) {
            RectF b = dockRect();
            return b.contains(lastHitX, y);
        }
        float cy = pillCy();
        float hh = pillH / 2f;
        return y >= cy - hh && y <= cy + hh;
    }

    /** 触摸的轴向坐标：横=x，竖=y（调用点已写 lastHitY）。 */
    private float lastHitY;
    private float hitAxis() { return dockVertical ? lastHitY : lastHitX; }
    private float hitAxis(float fallback) { return dockVertical ? lastHitY : fallback; }
    private float lastHitX;

    /** 越界橡皮筋：1 − 1/(|o|·0.55/limit + 1)，渐近收敛而非线性跟随。 */
    private static float rubberBand(float overshoot, float limit) {
        float pull = (float) (1.0 - 1.0 / (Math.abs(overshoot) * 0.55 / limit + 1.0));
        return limit * pull * Math.signum(overshoot);
    }

    /** 手指 x → 位置（格），两端带橡皮筋。 */
    private float positionAt(float x) {
        return positionAtAxis(dockVertical ? lastHitY : x);
    }

    private float positionAtAxis(float axisCoord) {
        float raw = (axisCoord - axisFirst) / axisPitch;
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
        float pitch = dockVertical ? axisPitch : slot;
        if (pitch <= 0) {
            if (dockVertical) computeRailGeometry();
            pitch = dockVertical ? axisPitch : slot;
            if (pitch <= 0) return false;
        }
        ensureTicking();   // 帧回调自眠中：触摸唤醒
        /* 已接管（pressed）的手势一律自己处理，不转发，否则拖出药丸带就断线了 */
        lastHitX = e.getX();
        lastHitY = e.getY();
        if (!pressed && !inPillBand(e.getY())) {
            forwardToWebView(e);
            return true;
        }
        double now = nowSec();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                Log.i(TAG, String.format("DOWN x=%.0f view w=%d -> cell %.2f (count=%d, popup=%dx%d)",
                        e.getX(), getWidth(), positionAtAxis(hitAxis()), count,
                        popup == null ? -1 : popup.getWidth(), popup == null ? -1 : popup.getHeight()));
                pressed = true;
                dragging = false;
                dropping = false;
                downId = e.getPointerId(0);
                downX = lastX = hitAxis(e.getX());
                lastMoveMs = e.getEventTime();
                velIndexPerMs = 0;
                pressedIndex = Math.max(0, Math.min(count - 1, Math.round(positionAtAxis(hitAxis()))));
                pos.springTo(pressedIndex, dockVertical ? FAST_TRAVEL : TRAVEL, now);
                if (!dockVertical) lift.springTo(1, LIFT_S, now);
                if (!dockVertical) lastCaptureMs = 0;   // 侧栏零截屏，底栏按下立刻重抓背景
                if (!nativeDock) setPressClass(true); // 藏起 HTML 药丸（nativeDock 时已整体隐藏）
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (!pressed) return false;
                /* 多指：只跟随起手那根手指；它抬起（次要指残留）按取消收场，
                   防止坐标跳到另一根手指上把药丸甩飞。 */
                int pi = e.findPointerIndex(downId);
                if (pi < 0) {
                    pressed = false;
                    pos.springTo(selectedIndex, dockVertical ? FAST_TRAVEL : TRAVEL, now);
                    dragging = false;
                    invalidate();
                    return true;
                }
                float x = hitAxis(e.getX(pi));
                long t = e.getEventTime();
                long dt = t - lastMoveMs;
                if (dt > 0) {
                    float v = (x - lastX) / axisPitch / dt;     // 格/ms（沿轴）
                    velIndexPerMs = velIndexPerMs * 0.6f + v * 0.4f;
                    lastMoveMs = t;
                    lastX = x;
                }
                if (!dragging && Math.abs(x - downX) < TOUCH_SLOP_DP * density) return true;
                dragging = true;
                pos.springTo(positionAtAxis(hitAxis(x)), FOLLOW, now);
                int idx = Math.max(0, Math.min(count - 1, Math.round(positionAtAxis(hitAxis(x)))));
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
                    pos.springTo(target, dockVertical ? FAST_TRAVEL : TRAVEL, now);
                    commit(target);
                } else {
                    pos.springTo(selectedIndex, dockVertical ? FAST_TRAVEL : TRAVEL, now);
                }
                dragging = false;
                dropping = !dockVertical;
                if (dockVertical) {
                    lift.springTo(0, FAST_TRAVEL, now);
                }
                invalidate();
                return true;
            }
        }
        return false;
    }

    /** 把选择提交给页面（页面切视图后回调用 setSelectedIndex 对齐）。 */
    /** 松手立刻通知宿主做原生切页（不等 JS 路由）：JS 那一跳实测 16~204ms，
     *  侧栏因此「不跟手」（2026-10-28）。原生先切，JS 事后确认是同一页即无操作。 */
    public interface OnCommit { void onCommit(int index); }

    private OnCommit onCommit;

    public void setOnCommit(OnCommit l) { onCommit = l; }

    private void commit(int index) {
        selectedIndex = index;
        if (onCommit != null) {
            try { onCommit.onCommit(index); } catch (Throwable ignored) { }
        }
        if (webView == null) return;
        String js;
        if (onCommit != null) {
            js = "(function(){try{"
                    + "if(typeof NetOpsLensSyncTab==='function'){NetOpsLensSyncTab(" + index + ");}"
                    + "else if(typeof NetOpsLensSelect==='function'){NetOpsLensSelect(" + index + ");}"
                    + "}catch(e){}})()";
        } else {
            js = "(function(){try{if(typeof NetOpsLensSelect==='function'){NetOpsLensSelect("
                    + index + ");}}catch(e){}})()";
        }
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "提交选择失败", t);
        }
    }

    /* ================= 绘制 ================= */

    private void updateShaderUniforms(float cx, float cy, float hw, float hh) {
        if (shader == null) return;
        float liftV = (float) lift.value();
        shader.setFloatUniform("uCenter", cx, cy);
        shader.setFloatUniform("uHalf", hw, hh);
        shader.setFloatUniform("uRadius", pillCorner(hw, hh));
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

    /* 临时探针：谁在空闲时每帧 invalidate（粘滞感排查） */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long t0 = System.nanoTime();

        float liftV = (float) lift.value();
        if (nativeDock) {
            /* ---- 未拿到几何前不画 ----
               横排：冷启动时 WebView 还在加载，barRect 无效 → dockRect() 退回「全宽
               兜底」胶囊（又宽又高），数秒后真实几何到达再缩回，用户看到「由大变小」
               的生硬过渡（2026-10-27 用户报）。宁可先空着，几何到达后一次淡入。
               竖排：轨矩形由 onSizeChanged 自算，等它算出来即可。 */
            boolean noGeom = dockVertical
                    ? (railRect.width() <= 4 || railRect.height() <= 4)
                    : (barRect.width() <= 4 || barRect.height() <= 4);
            if (noGeom) {
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
            /* 透镜只在横排底栏画：竖排侧栏走 FlClash 口径的平面面板（用户实证
               竖排强加玻璃「真的卡」，2026-10-29——AGSL 整轨每帧重绘 + 抓图链
               全部只服务于观感，平面绘制零成本）。 */
            if (lensing && !dockVertical) {
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
        /* 节流：PixelCopy 单次 1~20ms，滚动时每帧抓会把主线程按住（2026-10-28 实测
           空闲 60fps 重绘、全局发粘）。70ms ≈ 14fps，配合 composeHaze 的模糊已呈
           连续；静止时无脏标记 → 一次都不抓。 */
        if (barContentDirty && SystemClock.uptimeMillis() - lastBarCapMs < 70) return;
        RectF barWin = dockRect();
        if (barWin.width() <= 4 || barWin.height() <= 4) return;   // 无几何不抓
        /* dockRect = 弹窗本地（方向正确）；PixelCopy rect = 主窗口本地 → 加弹窗窗口偏移 */
        popupOffsetInMainWindow(pvOff);
        float winL = barWin.left + pvOff[0];
        float winT = barWin.top + pvOff[1];
        int W = (int) barWin.width(), H = (int) barWin.height();
        /* ❗比较必须同坐标系：lastCapturedBar 存的是**弹窗本地**（barSnap），这里原先
           拿它跟主窗口坐标的 winL/winT 比 —— 两者恒差一个弹窗偏移 → 永远「过期」→
           每帧重抓一次 PixelCopy（单次 ~20ms）→ 空闲也 60fps 重绘，全局手感发粘
           （2026-10-28 复查：绘制 140 帧/2s、inv 140/2s）。改比 barWin。 */
        boolean stale = barContentDirty || barBackdrop == null
                || Math.abs(lastCapturedBar.left - barWin.left) > 0.5f
                || Math.abs(lastCapturedBar.top - barWin.top) > 0.5f
                || Math.abs(lastCapturedBar.width() - barWin.width()) > 0.5f
                || Math.abs(lastCapturedBar.height() - barWin.height()) > 0.5f
                || lastCapturedDark != (dark ? 1 : 0);
        if (!stale) return;
        DisplayMetrics m = getResources().getDisplayMetrics();
        int maxH = viewportH > 0 ? (int) viewportH : m.heightPixels;
        int winW = anchor != null ? anchor.getWidth() : m.widthPixels;
        int pad = (int) (32 * density);
        int cl = Math.max(0, (int) winL - pad);
        int ct = Math.max(0, (int) winT - pad);
        int cr = Math.min(winW, (int) (winL + barWin.width()) + pad);
        int cb = Math.min(maxH, (int) (winT + barWin.height()) + pad);
        if (cr - cl < W || cb - ct < H) {
            Log.w(TAG, "栏玻璃抓取跳过：栏超界 rect=" + cl + "," + ct + "-" + cr + "," + cb);
            return;   // 栏连本体都被窗口边裁掉：回退实色
        }
        if (barBackdrop == null || barBackdrop.getWidth() != cr - cl
                || barBackdrop.getHeight() != cb - ct) {
            barBackdrop = Bitmap.createBitmap(cr - cl, cb - ct, Bitmap.Config.ARGB_8888);
            barBackdropShader = new BitmapShader(barBackdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        if (barCapTmp == null || barCapTmp.getWidth() != cr - cl
                || barCapTmp.getHeight() != cb - ct) {
            barCapTmp = Bitmap.createBitmap(cr - cl, cb - ct, Bitmap.Config.ARGB_8888);
        }
        srcBarRect.set(cl, ct, cr, cb);
        final RectF barSnap = new RectF(barWin);
        final int offX = pvOff[0] - cl;
        final int offY = pvOff[1] - ct;

        /* ❗2026-10-31 反转：原先「优先取静态壁纸（0ms 无截屏开销）」——但壁纸是平滑
           渐变，折射弯它等于什么都没弯，栏体玻璃在日常静止/滚动时都看不出液态感。
           改为优先抓**栏下真实内容**（经 composeHaze 糊成色块后再折射 = FlClash 观感），
           壁纸降级为「抓取失败时兜底」。开销由上面的 70ms 节流控制。 */
        if (preferWallpaper && backdropSource != null) {
            Bitmap wallBmp = backdropSource.getCurrentBitmap();
            if (wallBmp != null && !wallBmp.isRecycled()) {
                int bw = wallBmp.getWidth();
                int bh = wallBmp.getHeight();
                float scale = Math.max((float) winW / bw, (float) maxH / bh);
                float dx = (winW - bw * scale) * 0.5f;
                float dy = (maxH - bh * scale) * 0.5f;

                Canvas cv = new Canvas(barCapTmp);
                cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                android.graphics.Matrix mat = new android.graphics.Matrix();
                mat.setScale(scale, scale);
                mat.postTranslate(dx - cl, dy - ct);
                Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
                cv.drawBitmap(wallBmp, mat, bp);

                composeHaze(barCapTmp, barBackdrop);
                lastCapturedBar.set(barSnap);
                lastCapturedDark = dark ? 1 : 0;
                barCapOffX = offX;
                barCapOffY = offY;
                if (barBackdropShader != null) {
                    android.graphics.Matrix mm = new android.graphics.Matrix();
                    mm.setTranslate(barCapOffX, barCapOffY);
                    barBackdropShader.setLocalMatrix(mm);
                }
                return;
            }
            /* 壁纸兜底但位图还没解码完：本轮什么都不做，等下一帧/下一次通知，
               别掉到下面的 PixelCopy（那条路此刻必抛 no backing surface） */
            return;
        }

        barCopyBusy = true;
        barContentDirty = false;
        lastBarCapMs = SystemClock.uptimeMillis();
        /* window 字段是 attach 时缓存的；Activity 重建/系统换 surface 后它会失效，
           PixelCopy 直接抛 "Window doesn't have a backing surface!"（2026-10-31 实测
           每秒刷屏）。就近取当前 Activity 窗口，并要求 decor 已挂到窗口。 */
        Window wNow = window;
        if (getContext() instanceof Activity) {
            Activity a = (Activity) getContext();
            if (a.getWindow() != null) wNow = a.getWindow();
        }
        /* isAttachedToWindow 不足以判定：启动瞬间 decor 已挂上但 isShown() 仍 false，
           此时 PixelCopy 抛 "no backing surface"（2026-10-31 实测 rect=(0,536-220,1328)
           decorShown=false）。两个条件都要满足才抓。 */
        if (wNow == null || wNow.getDecorView() == null
                || !wNow.getDecorView().isAttachedToWindow()
                || !wNow.getDecorView().isShown()) {
            barCopyBusy = false;
            barContentDirty = true;      // 等窗口就绪再抓
            return;
        }
        try {
            PixelCopy.request(wNow, srcBarRect, barCapTmp, result -> {
                barCopyBusy = false;
                if (result == PixelCopy.SUCCESS) {
                    preferWallpaper = false;
                    composeHaze(barCapTmp, barBackdrop);
                    lastCapturedBar.set(barSnap);
                    lastCapturedDark = dark ? 1 : 0;
                    barCapOffX = offX;
                    barCapOffY = offY;
                    if (barBackdropShader != null) {
                        /* setLocalMatrix 的 M 把画布坐标映到位图坐标（canvas p 采样
                           M·p）：弹窗本地 p 对应位图 p+off → 平移 +off。写成 −off 会
                           采样到负坐标被 CLAMP 成首行涂抹色（2026-10-28 真机实测
                           整条栏一色 227 的根因）。 */
                        android.graphics.Matrix mm = new android.graphics.Matrix();
                        mm.setTranslate(barCapOffX, barCapOffY);
                        barBackdropShader.setLocalMatrix(mm);
                    }
                    invalidate();   // 新底落位 → 之后的直画用新采样
                } else {
                    /* 抓不到真实内容（锁屏/无 backing surface）→ 改走静态壁纸兜底：
                       壁纸弯折虽不明显，但至少是玻璃而不是实色板。 */
                    if (backdropSource != null && !preferWallpaper) {
                        preferWallpaper = true;
                        maybeCaptureBarBackdrop();
                    } else {
                        /* 连壁纸都没有：弃位图。透明位图上屏 = 玻璃凭空消失（alpha0 全透），
                           回退实色等下次几何/可见性变化再重抓 */
                        barBackdrop = null;
                        barBackdropShader = null;
                    }
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t2) {
            barCopyBusy = false;
            barBackdrop = null;
            barBackdropShader = null;
            Log.w(TAG, "栏玻璃抓取抛异常，回退实色", t2);
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        /* 解锁/回前台：主窗口表面刚恢复（锁屏期 PixelCopy 报 no backing surface，
           抓图全部失败），强制重抓一次——否则玻璃停留在透明/实色回退态 */
        if (isVisible && nativeDock && !barCopyBusy) {
            scheduleBarRecapture();   // 解锁/回前台：壁纸刚恢复，三连发重抓
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
        /* 竖排栏是整窗高，抓图矩形的上下 pad 被窗口边裁掉 → 位图恰好 W×H，
           不能要求 +2 余量（否则竖排永远走实色回退） */
        if (barBackdrop.getWidth() < W || barBackdrop.getHeight() < H) return false;
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
            float minDim = Math.min(W, H);
            float band = Math.min(18f * density, minDim * 0.28f);
            float amount = Math.min(22f * density, minDim * 0.45f);
            barGlassShader.setFloatUniform("size", new float[]{W, H});
            barGlassShader.setFloatUniform("offset", new float[]{0f, 0f});
            barGlassShader.setFloatUniform("cornerRadii", new float[]{r, r, r, r});
            barGlassShader.setFloatUniform("refractionHeight", band);
            barGlassShader.setFloatUniform("refractionAmount", -amount); // 负值：AndroidLiquidGlassView 官方标准向内凸透镜折射
            barGlassShader.setFloatUniform("depthEffect", 0.35f);
            barGlassShader.setFloatUniform("chromaticAberration", 0.40f);
            barGlassShader.setFloatUniform("contrast", 0.05f);
            barGlassShader.setFloatUniform("whitePoint", 0.02f);
            barGlassShader.setFloatUniform("chromaMultiplier", dark ? 1.85f : 2.05f);
            if (dark) {
                barGlassShader.setFloatUniform("tintColor", new float[]{0.18f, 0.18f, 0.20f});
                barGlassShader.setFloatUniform("tintAlpha", 0.42f);
            } else {
                barGlassShader.setFloatUniform("tintColor", new float[]{0.96f, 0.96f, 0.98f});
                barGlassShader.setFloatUniform("tintAlpha", 0.36f); // 36% 纯净乳白染色，透出 64% 饱和壁纸原色
            }
            barGlassShader.setInputShader("content", barBackdropShader);
            barGlassP.setShader(barGlassShader);
            barGlassP.setAlpha(255); // 全强度渲染：由 AGSL 内部 mix 决定透光率，不垫实白底板
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "栏体玻璃组装失败，回退实色", t);
            barGlassP.setShader(null);
            return false;
        }
    }

    /** 栏体 AGSL 交给共享加载器（AppBarView 用同一份，见 GlassAgsl）。 */
    private static String loadBarAgsl(android.content.res.Resources res) {
        if (barAgsl != null) return barAgsl;
        barAgsl = GlassAgsl.load(res);
        if (barAgsl == null) throw new IllegalStateException("读栏体玻璃 AGSL 失败");
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

    /** FlClash 底栏 haze（研读 §2-④ / §3.1）：**28% 清晰原图 + 72% 过 15.6dp 模糊**，
     *  不是单档模糊。这里用「降采样再双线性放大」近似高斯（一次性、每代一次），
     *  合成结果直接喂给着色器当 content —— 文字被糊掉、只剩色块，才是 iOS 底栏的手感。
     *  之前只有 tint 没有 haze，采样的是**清晰**背景，正文原样透出来（用户报「像半透明白板」）。 */
    private void composeHaze(Bitmap src, Bitmap dst) {
        if (src == null || dst == null) return;
        try {
            int w = src.getWidth(), h = src.getHeight();
            int div = Math.max(4, Math.round(15.6f * density / 3f));   // ≈ 15.6dp 的模糊半径
            int sw = Math.max(1, w / div), sh = Math.max(1, h / div);
            Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            new Canvas(small).drawBitmap(src, null, new Rect(0, 0, sw, sh),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            Canvas c = new Canvas(dst);
            c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(small, null, new Rect(0, 0, w, h), p);
            small.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "haze 合成失败，退回清晰底图", t);
            try {
                Canvas c = new Canvas(dst);
                c.drawBitmap(src, 0f, 0f, null);
            } catch (Throwable ignored) { }
        }
    }

    /** 栏体采样源补抓（三连发）：冷启动那次抓到的是白屏/半成品，几何不变化就一直用着。 */
    /** 栏下内容变了（滚动/切页/拖拽）：标脏并立刻尝试重抓（内部 70ms 节流）。
     *  2026-10-31：栏体玻璃采样源由静态壁纸改为真实内容后，靠这个入口跟随滚动。 */
    public void refreshBarBackdrop() {
        if (detached || !nativeDock) return;
        barContentDirty = true;
        if (!barCopyBusy && SystemClock.uptimeMillis() - lastBarCapMs >= 70) {
            maybeCaptureBarBackdrop();
        }
        invalidate();
    }

    private void scheduleBarRecapture() {
        lastCapturedBar.setEmpty();
        lastCapturedDark = -1;
        android.os.Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(this::maybeCaptureBarBackdrop, 500L);
        h.postDelayed(this::maybeCaptureBarBackdrop, 1500L);
        h.postDelayed(this::maybeCaptureBarBackdrop, 3000L);
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
        float r = dockRadius(bar);
        c1.drawRoundRect(0, 0, W, H, r, r, barFillP);
        barStrokeP.setStrokeWidth(density);
        c1.drawRoundRect(0, 0, W, H, r, r, barStrokeP);
        /* per-tab 灰态小图：sprite 原点 = 格左缘/栏顶（横）或栏左缘/格顶（竖）。
           宽度取格宽（文字仅 2 字不会溢出）。 */
        for (int i = 0; i < count && i < 5; i++) {
            if (dockVertical) {
                Bitmap sp = Bitmap.createBitmap(Math.max(4, (int) bar.width()),
                        Math.max(4, (int) (axisPitch + 20 * density)), Bitmap.Config.ARGB_8888);
                /* dx 把格心（crossCView 含栏左距）平到 sprite 宽度中点 */
                drawTabsOn(new Canvas(sp), bar.width() / 2f - crossCView,
                        -(cellAxis(i) - axisPitch / 2f), i, inactiveColor, true);
                tabSprites[i] = sp;
            } else {
                float gx = firstCx + slot * i - slot / 2f;
                Bitmap sp = Bitmap.createBitmap(Math.max(4, (int) (slot + 20 * density)),
                        Math.max(4, (int) bar.height()), Bitmap.Config.ARGB_8888);
                drawTabsOn(new Canvas(sp), -gx, -bar.top, i, inactiveColor, true);
                tabSprites[i] = sp;
            }
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
        float rr = pillCorner(bw, bh);
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

    /** 竖排选中态，照搬 FlClash：项底 = 40dp 方块 10dp 圆角、`onSurface @ 8%` 填充
     *  （切换即跳，不做滑动）；左缘一条 3dp×16dp 全圆竖条（首个主色）跟着弹簧滑。 */
    private void drawRailSelection(Canvas canvas) {
        float dp = density;
        float item = 40f * dp;
        RectF bar = dockRect();
        float p = (float) pos.value();
        int idx = Math.max(0, Math.min(count - 1, Math.round(p)));
        float selCy = cellAxis(idx);
        float r = 10f * dp;
        railSelP.setColor(dark ? 0x1FFFFFFF : 0x14000000);       // onSurface @ 8%
        canvas.drawRoundRect(crossCView - item / 2f, selCy - item / 2f,
                crossCView + item / 2f, selCy + item / 2f, r, r, railSelP);
        float iy = axisFirst + axisPitch * p;                    // 竖条跟随弹簧
        float bw = 3f * dp, bh = 16f * dp, x = bar.left + 4f * dp;
        railBarP.setColor(activeColor());
        canvas.drawRoundRect(x, iy - bh / 2f, x + bw, iy + bh / 2f, bw / 2f, bw / 2f, railBarP);
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
        /* 栏体：竖排 = FlClash 口径的**纯色半透面板**（FlClash NavigationSidebar
           本无玻璃面板，垫一层半透只为壁纸上的图标可读）—— 无 AGSL、无抓图、
           无透镜，每帧只剩几个 drawRoundRect + 位图 blit。用户实证竖排强加
           玻璃「真的卡」（2026-10-29），玻璃观感让位流畅。横排 = 玻璃路径不动。 */
        maybeCaptureBarBackdrop();
        boolean hasGlass = ensureBarGlass(bar, dockRadius(bar));
        if (hasGlass) {
            int sv = canvas.save();
            canvas.translate(bar.left, bar.top);
            float rr2 = dockRadius(bar);
            float bw2 = bar.width(), bh2 = bar.height();
            canvas.drawRoundRect(0, 0, bw2, bh2, rr2, rr2, barGlassP);
            barStrokeP.setStrokeWidth(density);
            canvas.drawRoundRect(0, 0, bw2, bh2, rr2, rr2, barStrokeP);
            canvas.restoreToCount(sv);
        } else {
            int bs = canvas.save();
            canvas.translate(bar.left, bar.top);
            float br2 = dockRadius(bar);
            Paint fillP = dockVertical ? railFillP : barFillP;
            canvas.drawRoundRect(0, 0, bar.width(), bar.height(), br2, br2, fillP);
            barStrokeP.setStrokeWidth(density);
            canvas.drawRoundRect(0, 0, bar.width(), bar.height(), br2, br2, barStrokeP);
            canvas.restoreToCount(bs);
        }

        composePillCenter();
        /* 滑块先画（在图标之下）：拖到两格中间时滑块从图标后面滑过，
           图标始终完整可见（HTML tab-pill 的 z 序，2026-10-27 用户反馈「遮挡其中一个」）。 */
        float cx = pillCxNow;
        float cy = pillCyNow;
        float s = (float) jelly.value();
        float g = LIFT_GROWTH * density * (float) lift.value();
        float hw;
        float hh;
        if (dockVertical) {
            hw = (pillW + g) / 2f * (1f - s * 1.4f);
            hh = (pillH + g) / 2f * (1f + s);
        } else {
            hw = (pillW + g) / 2f * (1f + s);
            hh = (pillH + g) / 2f * (1f - s * 1.4f);
        }
        if (dockVertical) {
            drawRailSelection(canvas);
        } else if (!lensing && sliderBmp != null) {
            /* 滑块-only 动画：形变量（jelly/lift）微小，直接 blit 固定尺寸缓存 */
            canvas.drawBitmap(sliderBmp, cx - sliderBmp.getWidth() / 2f,
                    cy - sliderBmp.getHeight() / 2f, blitP);
        } else {
            tmpRect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            float sr = pillCorner(hw, hh);
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

        composePillCenter();
        /* 灰态图标+文字：per-tab blit（跳过选中格——选中格由 drawLensTabs 以蓝态画在玻璃上） */
        RectF bar2 = dockRect();
        float act = pressed ? (float) pos.value() : selectedIndex;
        int actIdx = Math.max(0, Math.min(count - 1, Math.round(act)));
        for (int i = 0; i < count && i < 5; i++) {
            if (tabSprites[i] == null || i == actIdx) continue;
            if (dockVertical) {
                canvas.drawBitmap(tabSprites[i], bar2.left, cellAxis(i) - axisPitch / 2f, blitP);
            } else {
                canvas.drawBitmap(tabSprites[i], firstCx + slot * i - slot / 2f, bar2.top, blitP);
            }
        }
    }

    private final RectF barFallback = new RectF();

    /** 栏矩形：JS 上报优先；未上报时以药丸行推导（上下各外扩 6dp，左右满宽）。
     *  fallback 用独立 RectF —— 共享 tmpRect 会被滑块绘制覆盖，引用混乱。 */
    private RectF dockRect() {
        /* 竖排：栏体 = 原生自算的轨矩形。横屏时 JS 仍在量隐藏的横排 HTML 栏
           （y≈1750、宽 2300+），拿它当栏体等于把整条栏画到 280px 宽的弹窗外。 */
        if (dockVertical) return railRect;
        if (barRect.width() <= 4 || barRect.height() <= 4) {
            float cy = pillCy();
            float top = cy - pillH / 2f - 6f * density;
            barFallback.set(0, top, bandW, top + pillH + 12f * density);
            return barFallback;
        }
        /* JS 推的是主窗口本地坐标；弹窗画布是弹窗本地 → 按「弹窗在主窗口内的
           位置」实时换算（分屏/小窗下窗口在屏幕上移动，固定换算会过期错位） */
        popupOffsetInMainWindow(winOff);
        barLocal.set(barRect.left - winOff[0], barRect.top - winOff[1],
                barRect.right - winOff[0], barRect.bottom - winOff[1]);
        return barLocal;
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
        Paint.FontMetrics fm = textP.getFontMetrics();
        for (int i = 0; i < count && i < 5; i++) {
            if (onlyIdx >= 0 && i != onlyIdx) continue;
            float cx, iconTop;
            if (dockVertical) {
                /* FlClash compact：项内只有图标，垂直居中，无文字 */
                cx = crossCView + dx;
                iconTop = cellAxis(i) - 11f * density + dy;
            } else {
                cx = firstCx + slot * i + dx;
                iconTop = pillCy() - contentH / 2f + dy;
            }
            float textBaseline = iconTop + 22f * density + 2f * density - fm.ascent;
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
            if (dockVertical) continue;            // 竖排无文字
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
        composePillCenter();
        float cx = pillCxNow;
        float cy = pillCyNow;
        float hw;
        float hh;
        if (dockVertical) {
            /* 竖排：运动轴 = y —— 果冻的拉伸/压缩随之对调 */
            hw = (pillW + g) / 2f * (1f - s * 1.4f);
            hh = (pillH + g) / 2f * (1f + s);
        } else {
            hw = (pillW + g) / 2f * (1f + s);
            hh = (pillH + g) / 2f * (1f - s * 1.4f);
        }

        pillRect.set(cx - hw, cy - hh, cx + hw, cy + hh);
        float r = pillCorner(hw, hh);

        if (shader != null && backdrop != null && Build.VERSION.SDK_INT >= 33) {
            updateShaderUniforms(cx, cy, hw, hh);
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
        float dp0 = act.getResources().getDisplayMetrics().density;
        boolean vert0 = act.getResources().getConfiguration()
                .screenWidthDp > act.getResources().getConfiguration().screenHeightDp;
        int w0 = vert0 ? (int) (RAIL_W_DP * dp0) : ViewGroup.LayoutParams.MATCH_PARENT;
        int h0 = vert0 ? ViewGroup.LayoutParams.MATCH_PARENT : (int) (120 * dp0);
        v.popup = new PopupWindow(v, w0, h0, false);
        v.popupVertical = vert0;
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
        /* 窗口几何联动：分屏/小窗/自由窗口拖拽都会改 anchor 尺寸而不一定触发
           onConfigurationChanged（rotation 才必触发）——布局一变就对齐弹窗宽度
           并重测几何，否则 dock 留在旧窗口的尺寸/位置上（2026-10-28 用户报）。 */
        v.lastAnchorW = -1;
        v.lastAnchorH = -1;
        v.anchor.getViewTreeObserver().addOnGlobalLayoutListener(v.anchorLayoutListener);
        v.showPopup();
        return v;
    }

    /** 显示透镜窗（幂等）。window token 未就绪时有界重试（200ms×25）——
     *  快速重启/键盘收起路径不保证 token 已在。 */
    /** 旋转后弹窗宽度不跟随：PopupWindow 的 MATCH_PARENT 在 showAtLocation 时定格，
     *  横屏 3040 宽的窗口转竖屏后仍按 3040 布局 —— 可见区域里只有前几格可点
     *  （2026-10-27 竖屏实测「我的」点不到）。宽度变了就 dismiss 重 show。 */
    private int lastPopupWidth;
    private int lastPopupHeight = -1;
    private float lastPopupTop = -1f;
    private boolean popupVertical;

    public void reflow() {
        if (popup == null || detached || anchor == null) return;
        /* 基准 = **anchor 窗口宽**（分屏=半屏宽、小窗=窗口宽、全屏=屏宽）——
           用屏宽做基准的话，分屏/小窗切换不触发重算，弹窗保持旧宽
           （2026-10-28 用户报「分屏/小窗 dock 失效、尺寸不对、回全屏重影」）。 */
        int winW = anchor.getWidth();
        int winH = anchor.getHeight();
        boolean vert = isLandscapeWindow();   // 横屏 = 左侧竖排栏（不能拿弹窗自身宽高比判，见 isLandscapeWindow）
        /* 形态基准变化才重算：宽（分屏/小窗）与横竖形态任一变化都 dismiss 重 show */
        if (lastPopupWidth == winW && lastPopupHeight == winH
                && Math.abs(lastPopupTop - railTop) < 0.5f && popup.isShowing()) return;
        lastPopupWidth = winW;
        lastPopupHeight = winH;
        lastPopupTop = railTop;
        /* MATCH_PARENT 在 showAtLocation 时按屏幕定格；分屏/小窗必须显式给
           窗口宽，否则弹窗比窗口宽、dock 溢出到别人的半屏里。 */
        float dp = anchor.getResources().getDisplayMetrics().density;
        if (vert) {
            popupVertical = true;
            popup.setWidth((int) (RAIL_W_DP * dp));   // 只含栏宽；左距交给窗口 x 偏移
            /* 窗口从顶栏下沿起（不是 0）：**触摸窗不能盖住顶栏**，否则左侧菜单键
               落在窗里被 dock 吃掉（2026-10-28 顶栏铺满后 ☰ 移到最左，点不动）。 */
            popup.setHeight(Math.max(1, winH - (int) railTop));
        } else {
            popupVertical = false;
            popup.setWidth(Math.max(1, winW));
            popup.setHeight((int) (120f * dp));
        }
        if (popup.isShowing()) {
            try { popup.dismiss(); } catch (Throwable ignored) { }
        }
        showPopup();
        scheduleBarRecapture();      // 弹窗尺寸/位置换代：栏体采样源跟着换
    }

    private void showPopup() {
        if (popup == null || popup.isShowing() || detached || anchor == null) return;
        anchor.post(new Runnable() {
            int tries = 0;
            @Override public void run() {
                if (detached || popup.isShowing()) return;
                if (anchor.getWindowToken() != null) {
                    try {
                        /* 竖排窗口不能贴屏幕左缘：0..38px 是系统返回手势区，弹窗压住它
                           就再也滑不出返回（2026-10-28 复查 MIUI 日志 BackGestureEvent Fault）。
                           窗口左移出 RAIL_LEFT_DP，栏体视觉位置不变（railRect.left 为 0）。 */
                        popup.showAtLocation(anchor, popupVertical
                                ? (Gravity.START | Gravity.TOP)
                                : (Gravity.BOTTOM | Gravity.START),
                                popupVertical ? (int) (RAIL_LEFT_DP * density) : 0,
                                popupVertical ? (int) railTop : 0);
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

    /* ---- 窗口几何联动（分屏/小窗/自由窗口）----
       进入/退出分屏、拖拽小窗、调整分割线都改 anchor 尺寸而不一定触发
       onConfigurationChanged（rotation 才必触发）。布局一变就对齐弹窗宽度
       并重测几何，否则 dock 留在旧窗口的尺寸/位置上（2026-10-28 用户报）。 */
    private int lastAnchorW = -1;
    private int lastAnchorH = -1;
    private final ViewTreeObserver.OnGlobalLayoutListener anchorLayoutListener =
            this::onAnchorLayoutMaybe;
    private final Runnable anchorLayoutWork = this::onAnchorLayoutChanged;

    private void onAnchorLayoutMaybe() {
        if (detached || anchor == null) return;
        if (anchor.getWidth() == lastAnchorW && anchor.getHeight() == lastAnchorH) return;
        lastAnchorW = anchor.getWidth();
        lastAnchorH = anchor.getHeight();
        /* 布局风暴节流：分割线拖拽会连续 layout，稳定 120ms 后一次到位 */
        anchor.removeCallbacks(anchorLayoutWork);
        anchor.postDelayed(anchorLayoutWork, 120);
    }

    private void onAnchorLayoutChanged() {
        if (detached) return;
        reflow();
        requestGeometry();   // barRect 的窗口本地基准变了，几何整体重测
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
    public boolean isDockVertical() { return dockVertical; }

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
        try {
            anchor.getViewTreeObserver().removeOnGlobalLayoutListener(anchorLayoutListener);
        } catch (Throwable ignored) { }
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
