package com.netops.handbook.lens;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Xfermode;
import android.graphics.RuntimeShader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.ViewTreeObserver;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.app.Activity;
import android.os.Build;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;

import com.netops.handbook.lens.glass.BloomStrokeFx;
import com.netops.handbook.lens.glass.LiquidGlassShaders;

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

    /* 光学常量真源 = lens/glass/LiquidGlassShaders.java（Kyant0/AndroidLiquidGlass
       Apache-2.0 verbatim 照搬，2026-11-04 起；此前 FlClash 系自研参数全部退役）。 */
    /* 药丸尺寸：静止就比一格宽、且上下凸出栏体（参考 iOS 26 / 酷安那种形态）。
       此前只有 liftV>0 才加增长，静止严格一格 → 视觉上「收着」（2026-11-07 用户指出）。 */
    private static final float PILL_REST_GROWTH = 16f;  // 静止两向各 +16dp
    private static final float LIFT_GROWTH = 24f;       // 按压抬到 +24dp

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
    /* 切页位移改**临界阻尼**：bounce 0.15 会过冲——药丸冲过目标格再弹回，
       切「学习/排障」时表现为左右抽搐（2026-11-06 用户报）。KernelSU 同文件
       用的是 Compose `spring(1f, 300f, 0.5f)`，dampingRatio=1.0 即无过冲。 */
    private static final SpringSpec TRAVEL  = SpringSpec.durationBounce(320, 0);
    private static final SpringSpec FAST_TRAVEL = SpringSpec.durationBounce(140, 0.02);
    private static final SpringSpec LIFT_S  = SpringSpec.durationBounce(250, 0);
    /* FlClash _pressSpring: mass 1 / stiffness 311 / ratio 0.593（抬起用 LIFT_S 250） */
    /* 按压抬起用**临界阻尼**（bounce 0）：上游 pressProgress 是 tween，单调逼近；
       0.593 阻尼比会过冲（放大过头→缩回），在通透药丸上读作「按住时抽搐」
       （2026-11-06 用户报）。 */
    private static final SpringSpec PRESS_S = SpringSpec.durationBounce(180, 0);
    private static final SpringSpec JELLY_S = SpringSpec.ratio(100, 0.4);
    /* FlClash _glowIn 100ms / _glowOut 500ms */
    private static final SpringSpec GLOW_IN  = SpringSpec.durationBounce(100, 0);
    private static final SpringSpec GLOW_OUT = SpringSpec.durationBounce(500, 0);

    /* ---- 玻璃栈（2026-11-04 起整体照搬 Kyant0/AndroidLiquidGlass，Apache-2.0）----
       shader 原文在 lens/glass/LiquidGlassShaders.java；结构对齐 KernelSU
       FloatingBottomBar：栏体 = 霜链(vibrancy→blur4dp) + 栏缘 lens(24/24) +
       tint α0.4 + BloomStroke 高光；药丸 = 清晰内容(tab 镜像+页面)过
       折射+七彩色散 lens(10/14dp×lift, CA 0.5) + rest 面 0.1 + BloomStroke +
       innerShadow。运动模型（本文件弹簧）仍为我方骨架，不动。 */
    /* ---- 液态玻璃内容容器（2026-11-03 v5，pane 分层+无 shader 传参）----
    /** 容器折射（已退役，2026-11-03 品红实验）：View.setRenderEffect
     *  (RuntimeShader) 在本机 HyperOS/Adreno **静默不渲染**（构造成功、
     *  绘制跳过）——保留空壳维持 MainActivity 桥契约。 */
    public boolean attachContentContainer(ViewGroup container) {
        return false;
    }


    /** 容器折射按状态挂/摘：只服务横排原生 dock。竖排 rail（平面面板）、
     *  HTML dock、键盘/弹层在场（shader 区域会折射弹层内容）、弹窗收起
     *  一律摘除——摘除后容器恢复直绘，零 shader 成本。 */
    /** 容器折射按状态挂/摘（已退役：RenderEffect 本机不渲染，空壳）。 */
    public void updateContainerEffect() {
    }    /* ---- 栏体底衬（2026-11-03 用户定版）：中性黑底 + sfTintP 白/暗薄染 =
       磨砂栏面最终色；**壁纸色不准上栏**（用户明确：避免背景图颜色混进 dock）。
       壁纸霜位图链保留作扩展点（wallFrostShader 仅作就绪门控），当前不参与着色。 ---- */
    private Bitmap wallFrost;
    private BitmapShader wallFrostShader;
    private boolean wallFrostPending;
    /* ---- 栏后真实内容快照（2026-11-04 照搬链）----
       PixelCopy 抓主窗口「栏矩形±40dp」→ contentSharpFull（全分辨率清晰层，
       药丸折射采样源，KernelSU combinedBackdrop 的页面层等价物）→ ÷2 记录档
       （downScaleExpFor blur4dp 档）→ vibrancy(sat1.5) → 两级降采样 blur4dp
       → contentFrost（栏面霜）。抓取时机不变：滚动停手 250ms / 切页 / 几何 /
       冷启动三连发。40dp 外扩 = miuix blur padding（kernel reach）+ 栏缘
       lens 24dp 折射带的外采余量。 */
    private Bitmap contentFrost;      // 模糊霜（半尺寸记录档）
    private Bitmap contentSharpFull;  // 清晰层（全分辨率，药丸折射源）
    private float contentOffX, contentOffY;   // 抓取矩形原点（本视图坐标）
    private static final float BACKDROP_PAD_DP = 40f;  // miuix blur()/lens() padding 原值
    private final Paint contentFrostP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private boolean contentFrostBusy;
    private final Handler frostHandler = new Handler(Looper.getMainLooper());
    /** 霜链重活（buildFrost/pane 合成）在后台线程跑，主线程只赋值+invalidate——
     *  旋转/滚动停手后的抓图回调不再挤主线程（用户报「旋转切换卡一下」）。 */
    private final Handler frostWorker;
    private final Runnable frostDebounce = this::rebuildContentFrost;
    /** 把药丸几何喂给容器 shader（容器本地坐标 = 本视图本地 + 弹窗在主窗口的偏移）。
     *  几何变了才 invalidate 容器——拖动期容器逐帧重合成走 GPU（FBO replay +
     *  单 pass shader，实测量级远低于旧 PixelCopy 主线程回读），静止零重绘。 */

    /* ---- RuntimeShader 组（shader 原文 = LiquidGlassShaders verbatim）----
       全部 SDK≥33 构造、try/catch 软着陆（任一失败→null→对应路退平绘）。
       ⚠ 单层 BitmapShader 接线为 2026-11-04 诊断中改法，详见工作日志：
       嵌套 RuntimeShader / localMatrix / 纹理刷新 / GPU 编译失败均已排除，
       不是栏体灰条成因，勿据此继续改 shader。 */
    private RuntimeShader shader;          // 药丸：折射+七彩色散
    private RuntimeShader barLensShader;   // 栏缘折射（无色散）
    private RuntimeShader bloomShader;     // BloomStroke 双光源高光
    /* 上游 CombinedBackdrop = 页面层 + tab 镜像层录进同一纹理；这里是 CPU
       预烘焙等价物，药丸 shader 只吃这一张位图。 */
    private Bitmap combinedBmp;
    private BitmapShader combinedShader;
    /* 同帧实时 backdrop：弹窗同幅的主窗口内容 + 降采样模糊缓冲 */
    private Bitmap liveBackdrop, liveToned;
    private Bitmap liveSharp;      // 1/4 录制源（药丸直接用，栏体走两级降采样）
    private Bitmap liveStep;       // 1/8 中间级（模拟 miuix blur 的两级串联）
    private BitmapShader liveBackdropShader;
    private long lastCaptureMs;            // 实时录制节流（30fps 上限）
    private float lastRawLuma = -1f;       // 整图帧间低通的收敛判定
    private int liveStillFrames = 999;
    private final int[] winOffTmp = new int[2];

    private final WebView webView;
    /* 主窗口：PixelCopy 要抓它。本层必须活在**独立 Window**（PopupWindow）里，
       否则抓主窗口时会连自己画的玻璃一起抓进来 = 反馈循环。 */
    private Window window;
    private PopupWindow popup;
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 背景未就绪的按压降级
    private final RectF pillRect = new RectF();
    private final Path pillClipPath = new Path();   // 药丸圆角裁剪（位图直贴路径复用）
    private final float density;

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

    /* 弹簧状态 */
    private Spring pos = new Spring(0);
    private final Spring lift = new Spring(0);
    private final Spring jelly = new Spring(0);
    /* 触摸光晕（FlClash _glow 移植）：进 100ms/出 500ms，松手 reach ×4 扩散 */
    private final Spring glow = new Spring(0);
    private final Spring glowSpread = new Spring(0);
    private float glowX, glowY;
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

    /* 仅横排底栏透镜用（竖排侧栏无透镜 → 零抓图，见 drawDockChrome）。 */
    private boolean wasActive = false;                   // 弹簧落定后补一帧空画用
    private volatile boolean dark = false;               // 暗色模式（tint 色/配色整套开关；frostWorker 线程也读）
    private float lastSentPos = Float.MIN_VALUE;         // 上次广播的透镜位置（连续调色节流）
    private volatile boolean detached = false;           // Activity 销毁后一切回调自默
    private boolean imeShown = false;                    // 键盘弹出期透镜窗整体收起
    private boolean overlaid = false;                    // 页面底部弹层（sheet）在场期同样收起
    private boolean ticking = false;                     // 帧回调自眠：静止时不再 120Hz 空转
    private ViewGroup anchor;                            // PopupWindow 显示锚点

    /* ---- BloomStroke 光源随重力旋转（miuix DeviceTilt 原语义）----
       |g|>0.1（≈6° 倾斜）时主光方位 = atan2(gy,gx) 按 3° 步进量化；
       否则回正上（−90°）。栏额外 −45°、药丸额外 +90°（FloatingBottomBar 原值）。 */
    private SensorManager sensorManager;
    private float gravityX, gravityY;
    /* 切页后暂停实时录制：页面正在换内容，1/2 录制（10~14ms）叠加页面重绘
       会把 draw 推到 37ms、掉到 19fps（2026-11-07 实测）。玻璃滞后半秒不可见。 */
    private long liveCooldownUntil;
    /* 滚动中：录制降到 1/6 换帧率（背景逐帧跟随、不跳变），停手 200ms 后
       自动补一帧 1/2 高清。此前全程 1/2 + 30fps 节流 = 滑动时每 33ms 才跳一次，
       表现「卡 + 变化不均匀」（2026-11-07 用户报）。 */
    private long scrollIdleUntil;
    /* 滚动期异步抓图：PixelCopy 由 GPU 拷贝 + 回调，不占绘制线程。
       同步 anchor.draw 单次 17ms 必顶穿 16.7ms 预算（2026-11-07）。 */
    private Bitmap liveRaw;
    private boolean liveCopyInFlight;
    /** 上一次同步录制耗时（ns）。原生页（字典/面试）实测 55ms，而 WebView 页 17ms。 */
    private long lastSyncCapCost;
    private boolean scrollingNow;   // 本帧是否处于滚动：绘制降质用
    private static final float GRAVITY_DIR_THRESHOLD_SQ = 0.01f;
    private static final float GRAVITY_ANGLE_STEP_RAD = (float) (3.0 * Math.PI / 180.0);
    private static final float LIGHT_REF_X = 0.5f, LIGHT_REF_Y = 0.7f;
    /* setPorterDuffXfermode 在 API 34 的 android.jar 已移除 → setXfermode + 缓存常量 */
    private static final Xfermode XF_ADD = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private static final Xfermode XF_CLEAR = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

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
    private final Paint railFillP = new Paint(Paint.ANTI_ALIAS_FLAG);   // 竖排面板底
    private final Paint sfTintP = new Paint(Paint.ANTI_ALIAS_FLAG);   // 栏体白/暗薄染（药丸区挖孔）
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
    /* _LensContent 移植：抬手 warp 面板（药丸盖住格的离屏位图 → shader 深弯） */
    private Bitmap lensTabBmp;
    private BitmapShader lensTabShader;
    private float tabOffX, tabOffY;
    private int tabW, tabH;    /* 栏体投影（KernelSU dropShadow：radius 10dp，黑 α0.1 浅 / 0.2 暗，无偏移） */
    private final Paint barShadowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 栏面 pane：栏后内容霜（含 40dp 外扩边），供栏缘 lens 折射采样。
     *  KernelSU 链里 tint α0.4 在 lens **之后**作 surface 画上，不烘进 pane。 */
    private Bitmap barPane;
    private BitmapShader barPaneShader;   // 栏 lens 的 content 输入（localMatrix=contentOff）
    private Bitmap barPanePrev;      // 旧栏面（换霜后淡出用）
    private BitmapShader barPanePrevShader;
    private long paneFadeAt;         // 淡入起点（uptimeMillis；0=无过渡）
    private static final long PANE_FADE_MS = 320;   // 滚动停手换霜的过渡时长
    /* 药丸 rest 面（KernelSU onDrawSurface 原值）：浅 黑0.1 / 暗 白0.1，
       随 lift 淡出至 0；按压期另叠 black 0.03×lift。 */
    private final Paint pillRestP = new Paint(Paint.ANTI_ALIAS_FLAG);
    /* 药丸挖孔路径：栏体白 tint 在药丸区让位（清玻璃比磨砂栏更透，2026-11-03） */
    private final Path pillHolePath = new Path();
    private final Paint railSelP = new Paint(Paint.ANTI_ALIAS_FLAG);   // 竖排选中项底
    private final Paint railBarP = new Paint(Paint.ANTI_ALIAS_FLAG);   // 竖排选中竖条
    private final Paint barStrokeP = new Paint(Paint.ANTI_ALIAS_FLAG);   // 仅无玻璃回退分支的边缘
    private final Paint bloomP = new Paint(Paint.ANTI_ALIAS_FLAG);   // BloomStroke 载体（BlendMode ADD）
    private final Paint barLensP = new Paint(Paint.ANTI_ALIAS_FLAG); // 栏缘 lens 载体
    private final Paint pillPressP = new Paint(Paint.ANTI_ALIAS_FLAG); // 药丸按压叠层（黑 0.03×lift）
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
        rebuildWallFrost();
    }

    /** 壁纸/通透度变了（NativeBackdrop 重铺）。
     *  2026-11-04 起**空实现**：wallFrost 位图不参与任何着色（栏面由
     *  barPane 霜 + sfTintP 薄染），重建 ~10ms CPU + 屏幕四分之一大位图纯属旋转/换壁纸热路径
     *  上的浪费（用户报「旋转切换卡一下」）。链保留（setBackdropSource 冷启动
     *  仍建一次），恢复壁纸霜方案时在这里重新挂三连发。 */
    public void onBackdropChanged() {
    }

    /** 重建壁纸霜：NativeBackdrop 当前壁纸 → cover 铺满屏幕/2 尺寸 →
     *  降采样两步近似高斯（sigma 24dp 档：downscale 比率 = 24*dp/3）。
     *  CPU 一次 ~10ms； wallpaper 未设时回退中性灰。 */
    private void rebuildWallFrost() {
        if (detached || anchor == null) return;
        int w = anchor.getWidth();
        int h = viewportH > 0 ? (int) viewportH : anchor.getHeight();
        if (w <= 0 || h <= 0) return;
        int fw = w / 2, fh = h / 2;
        Bitmap src0 = backdropSource != null ? backdropSource.getCurrentBitmap() : null;
        Bitmap out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (src0 != null && !src0.isRecycled()) {
            int bw = src0.getWidth(), bh = src0.getHeight();
            float scale = Math.max((float) w / bw, (float) h / bh);
            float dx = (w - bw * scale) * 0.5f;
            float dy = (h - bh * scale) * 0.5f;
            /* cover 铺满窗口坐标（再除 2 = 霜位图坐标） */
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(scale / 2f, scale / 2f);
            m.postTranslate(dx / 2f, dy / 2f);
            c.drawBitmap(src0, m, bp);
        } else {
            c.drawColor(dark ? 0xFF202226 : 0xFFEDEDF0);
        }
        /* 两步降采样近似高斯：先缩到目标/8，再放大回目标/2（FILTER 双线性） */
        int dw = Math.max(2, fw / 8), dh = Math.max(2, fh / 8);
        Bitmap small = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(out, null,
                new RectF(0, 0, dw, dh), bp);
        c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        c.drawBitmap(small, null, new RectF(0, 0, fw, fh), bp);
        small.recycle();
        wallFrost = out;
        wallFrostShader = new BitmapShader(out,
                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        invalidate();
    }

    /** 抓栏后**真实内容**（主窗口「栏矩形±40dp」，不含本弹窗——透镜在独立
     *  Window，PixelCopy 抓主窗口天然无反馈环）。外扩 = miuix blur padding +
     *  栏缘 lens 24dp 折射带的外采余量。回调跑在 frostWorker：buildFrost 等
     *  位图重活离主线程，主线程只做字段赋值 + invalidate。
     *  watchdog：PixelCopy 回调可能被丢（旋转撕 surface）——contentFrostBusy
     *  一旦卡死，霜就冻结到重启（2026-10-30 顶栏同款事故）；700ms 无回响应
     *  解闩重抓。 */
    private void rebuildContentFrost() {
        /* transitionHold：快照转场覆盖层存活期间不抓帧（覆盖层在主窗内，抓到
           旧页像素）——转场收尾由 MainActivity.notifyBarsSwitchSettled 放行。 */
        if (detached || dockVertical || transitionHold || !nativeDock || contentFrostBusy || window == null) return;
        if (barRect.width() <= 4 || barRect.height() <= 4) return;
        View decor = window.getDecorView();
        if (decor == null || decor.getWidth() <= 0) return;
        int pad = (int) (BACKDROP_PAD_DP * density);
        int l = Math.max(0, (int) barRect.left - pad);
        int t = Math.max(0, (int) barRect.top - pad);
        int r = Math.min(decor.getWidth(), (int) Math.ceil(barRect.right) + pad);
        int b = Math.min(decor.getHeight(), (int) Math.ceil(barRect.bottom) + pad);
        int w = r - l, h = b - t;
        if (w < 8 || h < 8) return;
        final Bitmap raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        /* 采样基准在主线程算好带进回调（popupOffset 读视图位置，不能去 worker） */
        popupOffsetInMainWindow(winOff);
        final int offX = l - winOff[0], offY = t - winOff[1];
        contentFrostBusy = true;
        final Handler copyHandler = frostWorker;
        final Runnable watchdog = () -> {
            if (contentFrostBusy && !detached) {
                contentFrostBusy = false;
                Log.w(TAG, "抓图回调丢失（疑似旋转撕 surface），解闩重抓");
                scheduleFrostCapture();
            }
        };
        try {
            copyHandler.postDelayed(watchdog, 700);
            PixelCopy.request(window, new Rect(l, t, r, b), raw, result -> {
                copyHandler.removeCallbacks(watchdog);
                if (detached) {
                    contentFrostBusy = false;
                    raw.recycle();
                    return;
                }
                if (result != PixelCopy.SUCCESS) {
                    contentFrostBusy = false;
                    raw.recycle();
                    return;
                }
                final long capT0 = System.nanoTime();
                Bitmap frost = null;
                try {
                    frost = buildFrost(raw);   // CPU 重活：worker 线程（异常不裸抛，见下）
                } catch (Throwable e) {
                    Log.w(TAG, "霜链构建异常，沿用旧霜", e);
                }
                final Bitmap frostOut = frost;
                final long capMs = (System.nanoTime() - capT0) / 1_000_000L;
                frostHandler.post(() -> {               // 主线程：只赋值 + 重建 shader
                    contentFrostBusy = false;
                    if (detached) {
                        raw.recycle();
                        if (frostOut != null) frostOut.recycle();
                        return;
                    }
                    contentOffX = offX;
                    contentOffY = offY;
                    if (contentSharpFull != null) contentSharpFull.recycle();
                    contentSharpFull = raw;
                    /* 清晰层只作 CPU 预烘焙采样源，不再挂成 shader input。 */
                    if (frostOut != null) {
                        if (contentFrost != null) contentFrost.recycle();
                        contentFrost = frostOut;
                    }   // frostOut==null：霜链异常，保旧霜（raw 照常上清晰层）
                    captures++;
                    copySumMs += capMs;
                    if (capMs > copyMaxMs) copyMaxMs = capMs;
                    buildBarPane();
                    invalidate();
                });
            }, copyHandler);
        } catch (Throwable e) {
            contentFrostBusy = false;
            copyHandler.removeCallbacks(watchdog);
            raw.recycle();
            Log.w(TAG, "栏体内容快照失败", e);
        }
    }

    /** 快照转场保持（2026-11-09）：true 期间暂停栏后内容抓帧，收尾统一放行。 */
    public void setTransitionHold(boolean hold) { transitionHold = hold; }
    private volatile boolean transitionHold = false;

    /** miuix blur 链的 CPU 等价实现：÷2 记录档（downScaleExpFor 对 blur4dp 的
     *  自适应档）→ vibrancy（saturation 1.5，sRGB 空间同式；brightness=0 时
     *  COLOR_CONTROLS 的 pow(2.2) 分支不生效，ColorMatrix 等价）→ 两级降采样
     *  blur 4dp（KernelSU bar 原值；盒宽 = σ_screen = 4dp×0.45）。
     *  ❗在 frostWorker 线程跑：全部用局部 Paint（contentFrostP 主线程绘制
     *  在用，跨线程共享 Paint 会竞态）。 */
    private Bitmap buildFrost(Bitmap raw) {
        /* 记录档：σ_screen = 4dp·density·0.45 → σ² 套 downScaleExpFor */
        float sigma = 4f * density * LiquidGlassShaders.BLUR_RADIUS_TO_SIGMA;
        int exp = LiquidGlassShaders.downScaleExpFor(sigma * sigma);
        int sf = 1 << exp;
        int fw = Math.max(2, raw.getWidth() / sf), fh = Math.max(2, raw.getHeight() / sf);
        Bitmap half = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        /* vibrancy = colorControls(0, 1, 1.5)：sRGB 饱和度（线性亮度分支只在
           brightness≠0 时生效，本链 brightness=0）+ 亮度归一化 shim（见下） */
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.5f);
        /* 亮度归一化 shim（唯一保留的自研项）：霜面均值锚 236/40，防白卡页面
           整栏跳色（2026-11-03 用户报障回归防线）；KernelSU 链无此项。
           P2-2 诊断（2026-11-09 真机实测）：本系数只进 barPane（药丸透镜内容
           输入），竖屏栏面本体走 SF blurBehind 不经此链——0.5→0.85 实测栏面
           亮度零变化（我的页前后逐像素全同）。栏面亮暗页差 ~60 级的真杠杆 =
           sfTintP 染色，改动需看图拍板，维持 0.5 不动。 */
        float adj = ((dark ? 40f : 236f) - frostMeanLumaOf(raw)) * 0.5f;
        cm.postConcat(new ColorMatrix(new float[]{
                1, 0, 0, 0, adj,
                0, 1, 0, 0, adj,
                0, 0, 1, 0, adj,
                0, 0, 0, 1, 0}));
        bp.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(half).drawBitmap(raw, null, new RectF(0, 0, fw, fh), bp);
        /* blur 4dp：半尺寸盒宽 K ↔ 全尺寸 σ ≈ K px（两级 bilinear 往返实证） */
        int k = Math.max(2, Math.round(sigma));
        int dw = Math.max(2, fw / k), dh = Math.max(2, fh / k);
        Bitmap small = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        Paint bp2 = new Paint(Paint.FILTER_BITMAP_FLAG);
        new Canvas(small).drawBitmap(half, null, new RectF(0, 0, dw, dh), bp2);
        Bitmap out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(small, null, new RectF(0, 0, fw, fh), bp2);
        small.recycle();
        half.recycle();
        return out;
    }

    /** 抓取原帧的平均亮度（shim 锚点输入；直接读 raw，稀疏 ~96 点）。 */
    private float frostMeanLumaOf(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int sx = Math.max(1, w / 12), sy = Math.max(1, h / 8);
        float sum = 0f;
        int n = 0;
        for (int y = 0; y < h; y += sy) {
            for (int x = 0; x < w; x += sx) {
                int c = bmp.getPixel(x, y);
                sum += 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c);
                n++;
            }
        }
        return n > 0 ? sum / n : (dark ? 40f : 236f);
    }

    /** 栏面 pane = 抓取矩形全幅的霜（含 40dp 外扩边，栏缘折射要采栏外内容）。
     *  上游链里 tint α0.4 在 lens 之后作 surface 画上（drawDockChrome），不烘这里。 */
    private float paneKeyOffX = -1f, paneKeyOffY = -1f;

    private void buildBarPane() {
        if (contentFrost == null || contentFrost.getWidth() < 8) return;
        Bitmap fresh = Bitmap.createBitmap(contentFrost.getWidth() * 2,
                contentFrost.getHeight() * 2, Bitmap.Config.ARGB_8888);
        new Canvas(fresh).drawBitmap(contentFrost, null,
                new RectF(0, 0, fresh.getWidth(), fresh.getHeight()), contentFrostP);
        /* 抓取原点变了（几何/转屏）→ 旧 pane 与新 pane 内容错位，交叉淡化会
           「双重影」——跳过淡化直接切。原点不变的换霜（滚动停手）才淡化。 */
        boolean originMoved = Math.abs(contentOffX - paneKeyOffX) > 0.5f
                || Math.abs(contentOffY - paneKeyOffY) > 0.5f;
        paneKeyOffX = contentOffX;
        paneKeyOffY = contentOffY;
        if (barPanePrev != null) barPanePrev.recycle();
        if (originMoved) {
            barPanePrev = null;
            barPanePrevShader = null;
        } else {
            barPanePrev = barPane;
            barPanePrevShader = barPaneShader;
        }
        barPane = fresh;
        barPaneShader = new BitmapShader(barPane, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setTranslate(contentOffX, contentOffY);
        barPaneShader.setLocalMatrix(m);
        paneFadeAt = SystemClock.uptimeMillis();
        invalidate();
    }
    /** 首次拿到真实栏几何后是否已淡入（冷启动防「由大变小」见 onDraw）。 */
    private boolean geomFadedIn = false;
    private final RectF tmpRect = new RectF();

    /** 亮/暗两套配色，逐值对齐 01-tokens.css（浅 #2563eb/#8E8E93；暗 #0a84ff/rgba(235,235,245,.60)）。 */
    private void applyDockColors() {
        if (dark) {
            railFillP.setColor(Color.argb(204, 24, 24, 26));   // 竖排面板：暗面 ~80%
            barFillP.setColor(Color.argb(255, 28, 28, 30));   // 实色回退
            sfTintP.setColor(Color.argb(160, 33, 31, 38));    // KernelSU surfaceContainer(dark)，alpha 40%→55%：用户 2026-11-07「不用非常通透」
            barStrokeP.setColor(Color.argb(70, 255, 255, 255));   // 仅无玻璃回退分支
            sliderFillP.setColor(Color.argb(255, 44, 44, 46));    // --surface-2 #2c2c2e
            sliderStrokeP.setColor(Color.argb(26, 255, 255, 255));
            iconP.setColor(Color.argb(153, 235, 235, 245));       // --tab-inactive rgba(235,235,245,.60)
            textP.setColor(Color.argb(153, 235, 235, 245));
            inactiveColor = Color.argb(153, 235, 235, 245);
        } else {
            railFillP.setColor(Color.argb(178, 255, 255, 255));   // 竖排面板：白 ~70%（cf1fc0d 前的已验收值）
            barFillP.setColor(Color.WHITE);                    // 实色回退
            /* KernelSU FloatingBottomBar.kt:220 `surfaceContainer.copy(0.4f)`；
               浅色 M3 surfaceContainer = #F3EDF7 → 40% alpha。 */
            sfTintP.setColor(Color.argb(160, 243, 237, 247));
            barStrokeP.setColor(Color.argb(120, 255, 255, 255));  // 仅无玻璃回退分支
            /* 对照 FlClash 手机端（液态玻璃=极致）实测：选中药丸 #DEE5FC（主色 12% 叠白，不是纯白）、
               未选中图标 #424754（深灰蓝，我们原来的 #8E8E93 偏浅发灰）。 */
            sliderFillP.setColor(Color.argb(31, 37, 99, 235));    // accent #2563EB @ 12%
            sliderStrokeP.setColor(Color.argb(18, 37, 99, 235));
            iconP.setColor(Color.rgb(66, 71, 84));                // #424754
            textP.setColor(Color.rgb(66, 71, 84));
            inactiveColor = Color.rgb(66, 71, 84);
        }
        /* 药丸 rest 面（KernelSU：浅 黑0.1 / 暗 白0.1，随 lift 淡出） */
        pillRestP.setColor(dark ? Color.WHITE : Color.BLACK);
        pillPressP.setColor(Color.BLACK);   // 按压叠层 black 0.03×lift（原值）
        /* 栏体投影（dropShadow radius 10dp：浅 0.1 / 暗 0.2） */
        barShadowP.setColor(dark ? Color.argb(51, 0, 0, 0) : Color.argb(26, 0, 0, 0));
        barShadowP.setMaskFilter(new BlurMaskFilter(10f * density, BlurMaskFilter.Blur.NORMAL));
        buildBarPane();
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
        /* ❗无 early-return：弹窗显隐必须无条件对齐——boot 时序下 attach 先
           无条件 showPopup、JS 的 false 上报早于 attach 被丢弃，若此处
           「值相等即返回」，补施永远走不到 dismiss，弹窗永久盖死栏区
           （2026-11-02 真机实锤：HTML dock 被盖、红条实验不上屏）。 */
        nativeDock = on;
        updateContainerEffect();   // HTML dock / 原生 dock 切换：容器折射跟着挂/摘
        if (on) scheduleFrostCapture();   // 接管即抓霜（几何可能早于接管上报）
        if (popup != null) {
            if (on && !popup.isShowing()) showPopup();
            else if (!on && popup.isShowing()) popup.dismiss();
        }
        if (webView != null) {
            webView.invalidate();
            webView.postInvalidateDelayed(64);
        }
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
        /* 底栏恒底锚（悬浮/贴底两态都是）：矩形落进上半窗 = CSS 过渡中途量到的
           废矩形（2026-11-09 实证：切贴底时探针在过渡期采到 top≈0，弹窗整体
           搬到顶栏后面=「dock 消失」）——拒收，保留上一份好几何等重测。 */
        if (viewportH > 0 && t + h < viewportH * 0.6f) return;
        boolean moved = Math.abs(barRect.left - l) > 1 || Math.abs(barRect.top - t) > 1
                || Math.abs(barRect.width() - w) > 1 || Math.abs(barRect.height() - h) > 1;
        barRect.set(l, t, l + w, t + h);
        if (moved) scheduleFrostCapture();
        invalidate();
    }

    /** 冷启动霜位图三连发（壁纸解码/首帧/web 内容落定三个时间点各抓一次）。 */
    private void scheduleFrostCapture() {
        frostHandler.removeCallbacks(frostDebounce);
        frostHandler.postDelayed(frostDebounce, 200L);
        frostHandler.postDelayed(frostDebounce, 600L);
        frostHandler.postDelayed(frostDebounce, 1200L);
    }

    /** 路由变化（WebView 导航/历史变更）后重抓栏后霜——SPA 切页无滚动信号，
        不抓的话栏体停留在上一页的雾。 */
    public void requestFrostRefresh() {
        scheduleFrostCapture();
    }

    /** 快照转场收口（MainActivity.notifyBarsSwitchSettled 调用）：解除活抓冷却
     *  并补抓霜。冷却的转场保护职责已由 transitionHold 承担——收口即解除，
     *  用户紧接着的按住拖拽立即拿到新页采样（2026-11-09「按住药丸来回拖动
     *  dock 滞后 1-2s」的组成部分）。 */
    public void onSwitchSettled() {
        liveCooldownUntil = 0L;
        requestFrostRefresh();
    }

    public LensBarView(Context context, WebView webView) {
        super(context);
        this.webView = webView;
        this.density = context.getResources().getDisplayMetrics().density;
        android.os.HandlerThread worker = new android.os.HandlerThread("lens-frost");
        worker.start();
        frostWorker = new Handler(worker.getLooper());        /* ❗描边画笔必须显式 STROKE：Paint 默认 FILL，漏设的话「画一圈边」实际是
           「整块填满」——栏体先画玻璃再被这条 #E3E3E8 实色盖回去，玻璃等于白做
           （2026-10-28 用户报「液态玻璃被改了」的真因）。 */
        barStrokeP.setStyle(Paint.Style.STROKE);
        sliderStrokeP.setStyle(Paint.Style.STROKE);
        if (Build.VERSION.SDK_INT >= 33) {
            /* 软着陆：RuntimeShader 构造即编译——shader 编译失败若让构造器抛出，
               attach 的 catch 会把整个 lensBar 置 null = 底栏彻底消失；逐个退回
               null，对应路径走平绘（回退贴片），dock 仍然可用。 */
            try {
                shader = new RuntimeShader(LiquidGlassShaders.REFRACTION_DISPERSION);
                glassPaint.setShader(shader);
            } catch (Throwable t) {
                Log.w(TAG, "药丸 AGSL 编译失败，退回平绘路径", t);
                shader = null;
            }
            barLensShader = tryShader(LiquidGlassShaders.REFRACTION, "栏缘折射");
            bloomShader = tryShader(LiquidGlassShaders.BLOOM_STROKE_DUAL, "BloomStroke");
            /* 镜内内容二次折射（KernelSU tabs 层单独过 lens(24dp,24dp)）。
               ❗只能在离屏 Pass 里跑：在 onDraw 调用链内 new Canvas + RuntimeShader
               会污染主 Canvas 状态（栏面整条变黑、药丸过曝，2026-11-06 实测）。 */
            bloomP.setXfermode(XF_ADD);   // Compose BlendMode.Plus 同义
        }
        initIcons();
        statStartNs = System.nanoTime();
        ensureTicking();
    }

    /** RuntimeShader 软着陆构造：失败 → null（对应路径退平绘），不炸 attach。 */
    private static RuntimeShader tryShader(String src, String tag) {
        try {
            return new RuntimeShader(src);
        } catch (Throwable t) {
            Log.w(TAG, tag + " AGSL 编译失败，对应路径退平绘", t);
            return null;
        }
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

    /* ---- DeviceTilt：重力传感器（miuix rememberDeviceTilt 原语义）---- */
    private final SensorEventListener tiltListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            gravityX = event.values[0];
            gravityY = event.values[1];
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (sensorManager == null) {
            sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        }
        Sensor g = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) : null;
        if (sensorManager != null && g != null) {
            sensorManager.registerListener(tiltListener, g, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(tiltListener);
        }
        super.onDetachedFromWindow();
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
        /* rebuildWallFrost 已撤出几何热路径（wallFrost 不参与着色，重建 ~10ms
           + 大位图纯浪费；壁纸霜方案重启时在 setBackdropSource 恢复）。 */
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
        scheduleFrostCapture();   // 主题切换：页面重绘后重抓（旧霜是旧配色）
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
        /* 2026-11-02 快照链退役：玻璃底=SF blurBehind 实时模糊，无需预热。 */
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
        }    }

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
     *  竖排栏高 = 整窗高，拿高度算半径会退化成满圆角。
     *  2026-11-09 贴底样式（netops_tabbar=regular）：满宽方角栏——统一 14dp
     *  圆角（底角贴屏幕圆角曲线，顶角与卡片同族），替代半高胶囊。 */
    private float dockRadius(RectF bar) {
        /* 横排底栏：半高 = 胶囊（FlClash pill 就是这个语义，横向短条两端自然是半圆）。
           竖排侧栏**不能**照搬这条：栏高 ~1630px，半高半径 121px 会让两端变成半圆，
           整条看着像一根竖着的椭圆（2026-10-28 用户报）。侧栏是面板，用固定 16dp 圆角（与原生卡片同族）。 */
        float half = Math.min(bar.width(), bar.height()) / 2f;
        if (dockVertical) return Math.min(10f * density, half);
        return docked ? 14f * density : half;
    }

    /** 贴底样式旗标（JS netops_tabbar 经桥同步）：改圆角语义 + 失效栏体缓存。 */
    public void setDocked(boolean d) {
        if (docked == d) return;
        docked = d;
        rebuildChrome();   /* B1 容器位图的圆角/实色烤在缓存里，必须重建 */
        invalidate();
    }
    private boolean docked = false;
    private boolean chromeDocked = false;   /* B1 重建门的贴底维度（与 chromeDark 同构） */

    /** 液态玻璃三档变更（桥 setGlassLevel）：药丸 uniform 每帧直读无需重建，
     *  B1 容器位图与栏体缓存重建一次保一致。 */
    public void onGlassLevelChanged() {
        rebuildChrome();
        invalidate();
    }
    /** 「无」档平绘载体（drawBarGlass 的 pane 直贴，提字段免每帧分配）。 */
    private final Paint barPlainP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** 贴底 M3 指示器载体（图标背后 64×32dp 胶囊）。 */
    private final Paint indicatorP = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 方向推断 + 轨道几何。竖排几何完全由原生自算，不吃 JS 的横排 HTML 栏矩形。 */
    private void updateOrientation() {
        boolean vert = isLandscapeWindow();
        if (vert != dockVertical) {
            dockVertical = vert;
            updateContainerEffect();   // 竖排 rail 无玻璃：容器折射摘除（零 shader 成本）
            chromeDark = -1;                       // 缓存（含 sprite 形状）按方向重建
            for (int i = 0; i < tabSprites.length; i++) {
                if (tabSprites[i] != null) tabSprites[i].recycle();   // 翻转旧图即时回收
                tabSprites[i] = null;
            }
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
        /* ❗只在**拖动**时做速度形变：切页位移的速度同样很大，会让 jelly 目标
           跳到 0.20，药丸横向拉长 20% 再按 JELLY_S(0.4 欠阻尼) 回弹数次 →
           切「学习/排障」时左右抽搐（2026-11-06 用户报）。KernelSU 的速度形变
           也只在按住拖动（layerBlock 内 dampedDragAnimation）生效。 */
        double target = 0;
        if (dragging) {
            double v = pos.velocity();
            double acc = pos.acceleration();
            double braking = Math.max(0.0, -acc * Math.signum(v));
            target = 0.20 * Math.tanh(Math.abs(v) / 3.0)
                    - 0.15 * Math.tanh(braking / 25.0);
        }
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
        glow.advance(now);
        glowSpread.advance(now);
        dropLift(now);
    }

    private boolean animating() {
        return pos.active() || jelly.active() || lift.active()
                || glow.active() || glowSpread.active();
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
            /* 2026-11-01 拖动期重抓已删：快照=整条带壁纸、与药丸位置无关
               （见 captureByPixelCopy），拖动不改变条带内容——省掉拖动期
               每帧 70ms 级回读。壁纸换代由 onBackdropChanged 三连发补抓。 */
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
                glowX = e.getX();
                glowY = e.getY();
                glowSpread.springTo(0, FAST_TRAVEL, now);
                glow.springTo(1, GLOW_IN, now);
                downX = lastX = hitAxis(e.getX());
                lastMoveMs = e.getEventTime();
                velIndexPerMs = 0;
                pressedIndex = Math.max(0, Math.min(count - 1, Math.round(positionAtAxis(hitAxis()))));
                pos.springTo(pressedIndex, dockVertical ? FAST_TRAVEL : TRAVEL, now);
                if (!dockVertical) lift.springTo(1, PRESS_S, now);
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
                glowX = e.getX(pi);
                glowY = e.getY(pi);
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
                /* 触摸光晕熄灭：reach ×4 扩散淡出（_glowOut 500ms） */
                glow.springTo(0, GLOW_OUT, now);
                glowSpread.springTo(1, GLOW_OUT, now);
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
        /* ❗冷却 650ms（切页弹簧 550ms 落定 + 100ms 渲染余量）：
           原 1200ms 是保守值，用户实测感知「还慢了半秒」（2026-11-07）。
           冷却一结束立即补一次重绘 —— 切页后若用户不碰屏幕，没有任何
           滚动/点击去触发 invalidate，onDraw 不会跑，captureLiveBackdrop
           恢复了也没机会录 → dock 停留上一页渲染。 */
        liveCooldownUntil = SystemClock.uptimeMillis() + 650;
        frostHandler.postDelayed(() -> {
            /* 补绘强制走同步录制：若沿用自适应（原生页 lastSyncCapCost>30 → 异步），
               PixelCopy 29~62ms + 回调再画一帧，会让切页后的首次更新再多拖 ~100ms。 */
            lastSyncCapCost = 0L;
            invalidate();
        }, 700L);
        /* 切页后新内容落定再抓霜（内容霜跟随页面，不跟随点击瞬间的旧页面） */
        frostHandler.removeCallbacks(frostDebounce);
        frostHandler.postDelayed(frostDebounce, 400L);
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
            captureLiveBackdrop();   // 同帧录主窗口内容，供栏体/药丸共用（真·实时）
            /* ---- 原生底栏：容器→滑块→玻璃→图标文字。图标在玻璃之上，
               对齐 FlClash _LensContent 的「镜内内容独立重绘」——顺带解决
               旧 P2「镜内放大靠位图采样偏软」：现在是矢量重绘恒锐。 ---- */
            boolean lensing = pressed || liftV > 0.005f;
            /* 栏体按压胀大：KernelSU FloatingBottomBar layerBlock 的
               `lerp(1f, 1f + 16.dp.toPx() / width, pressProgress)`。
               只缩放绘制，不改布局与命中区（命中仍走 View 原始坐标）。 */
            float dockS = 1f + (16f * density / Math.max(1f, bandW)) * liftV;
            boolean dockScaled = dockS > 1.0005f;
            if (dockScaled) {
                canvas.save();
                canvas.scale(dockS, dockS, bandW / 2f, bandH / 2f);
            }
            drawDockChrome(canvas, lensing);
            /* 透镜只在横排底栏画：竖排侧栏走 FlClash 口径的平面面板（用户实证
               竖排强加玻璃「真的卡」，2026-10-29——AGSL 整轨每帧重绘 + 抓图链
               全部只服务于观感，平面绘制零成本）。 */
            boolean glassLive = shader != null
                    && !NativeBlocks.glassNone()
                    && !dockVertical && Build.VERSION.SDK_INT >= 33;
            if (!dockVertical) {
                drawGlass(canvas, liftV);
            }
            /* 2026-11-01 选中格图标常驻透镜面板（composeLensTab，rest 也弯折
               ——「透过药丸看见 Dock 字体」由玻璃下的弯折字体承担）；平绘
               drawLensTabs 仅作无玻璃回退（旧机/快照未就绪）。 */
            if (!glassLive) {
                drawLensTabs(canvas);
            }
            if (dockScaled) canvas.restore();
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

    /** 栏体玻璃就绪判定 + uniforms 组装（每帧调用；未换代时仅重复设值，开销可忽略）。
     *  返回 false = 抓图未就绪/低版本/组装失败 → 回退实色。
     *  **直画不走 RenderNode**：录制一次长期重放的用法在真机 popup 窗里
     *  inputShader 采样失效（输出 alpha=0 全透明）；透镜的直画模式在此 view
     *  久经验证，栏体同款。 */

    /** 栏体 haze：全图「降采样再双线性放大」近似高斯（一次性、每代一次），合成结果
     *  直接喂给着色器当 content —— 文字被糊掉、只剩色块，才是 iOS 底栏的手感。
     *  2026-11-01 挡背景色两轮迭代：24dp 模糊下壁纸仍留**可辨认轮廓**（真机
     *  栏后透出人物双腿剪影，用户驳回「这叫磨砂玻璃？」）——磨砂的验收线是
     *  「认不出任何内容，只剩明暗洗」，半径拉到 60dp。栏体 tint 0.48 只做
     *  乳白薄染，玻璃感来自 haze 明暗洗 + 边缘折射，不靠透出内容。
     *  与 ensureBarGlass 的 tint/chroma 配套：0.70×chroma1.60≈1.12 ≈ 原饱和度，
     *  背景色不再被放艳；明度收口保证深色壁纸不把栏体拖出发黑区。
     *  之前只有 tint 没有 haze，采样的是**清晰**背景，
     *  正文原样透出来（用户报「像半透明白板」）。 */
    
    /** 栏体采样源补抓（三连发）：冷启动那次抓到的是白屏/半成品，几何不变化就一直用着。 */
    
    /** 分层缓存重建：B1=容器；B2=灰态图标+文字（透明底）。barRect/主题变化时调。 */
    /* ---- SF blurBehind 窗口：挂一次、每帧对齐、卸载即收 ---- */

    /** 挂载 blur 窗口（只试一次）。**两扇窗口**（2026-11-03 通透感重构）：
     *  单扇时 blurBehind 糊住整条栏含药丸区——挖掉白 tint 也只是「无 tint 的糊」
     *  =雾蒙蒙（用户实测）。两扇左右分布、中间让出药丸「清孔」：孔内=原始内容
     *  （+容器折射弯折）=FlClash 透镜「镜内锐利镜外霜」的真结构；孔外=糊+白 tint。
     *  窗口 rect 随药丸移动逐帧对齐（静止零开销）。失败/系统关闭退役为快照管线。 */
    
    /** 两扇 blur 窗口对齐：左扇=[栏左, 孔左)，右扇=[孔右, 栏右]（孔=药丸±1px，
     *  与 sfTintP 挖孔同缝，1px 缝被药丸玻璃+镜缘光学盖住）。rect 无变化零开销；
     *  拖动期每帧两次 updateViewLayout（真机 gfxinfo 实测无回归后再放行）。 */
    
    
    private void rebuildChrome() {
        RectF bar = dockRect();
        int W = Math.max(1, (int) bar.width()), H = Math.max(1, (int) bar.height());
        if (chromeB1 == null || chromeB1.getWidth() != W || chromeB1.getHeight() != H
                || chromeB1.getConfig() == Bitmap.Config.HARDWARE
                || chromeDark != (dark ? 1 : 0) || chromeDocked != docked) {
            /* HARDWARE 位图不可写：末尾会把 chromeB1 转成 HARDWARE 常驻纹理，
               若本次重建尺寸不变而复用旧对象，new Canvas(硬件位图) 会直接抛异常
               （2026-10-27 位置/药丸中心重建门启用后真机崩溃：Canvas.<init>）。 */
            if (chromeB1 != null) chromeB1.recycle();   // 旧图（含被 HARDWARE 替换前的软件图）及时回收
            chromeB1 = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            chromeDark = dark ? 1 : 0;
            chromeDocked = docked;
        }
        chromeRect.set(bar);
        chromePillCy = pillCy();
        Canvas c1 = new Canvas(chromeB1);
        float r = dockRadius(bar);
        if (docked) {
            /* 原生贴底（FlClash docked = M3 NavigationBar）：实色 surface
               （var(--surface) 同值）+ 顶缘发丝线——玻璃管线整体退场，档位不作用于栏体。 */
            Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
            solid.setColor(dark ? Color.argb(255, 28, 28, 30) : Color.WHITE);
            c1.drawRoundRect(0, 0, W, H, r, r, solid);
            Paint hl = new Paint();
            hl.setColor(dark ? Color.argb(26, 255, 255, 255) : Color.argb(18, 0, 0, 0));
            c1.drawRect(0, 0, W, density, hl);
        } else {
            c1.drawRoundRect(0, 0, W, H, r, r, barFillP);
            barStrokeP.setStrokeWidth(density);
            c1.drawRoundRect(0, 0, W, H, r, r, barStrokeP);
        }
        /* per-tab 灰态小图：sprite 原点 = 格左缘/栏顶（横）或栏左缘/格顶（竖）。
           宽度取格宽（文字仅 2 字不会溢出）。 */
        for (int i = 0; i < count && i < 5; i++) {
            if (dockVertical) {
                Bitmap sp = Bitmap.createBitmap(Math.max(4, (int) bar.width()),
                        Math.max(4, (int) (axisPitch + 20 * density)), Bitmap.Config.ARGB_8888);
                /* dx 把格心（crossCView 含栏左距）平到 sprite 宽度中点 */
                drawTabsOn(new Canvas(sp), bar.width() / 2f - crossCView,
                        -(cellAxis(i) - axisPitch / 2f), i, inactiveColor, true);
                if (tabSprites[i] != null) tabSprites[i].recycle();   // 旋转/换尺寸的旧图及时回收
                tabSprites[i] = sp;
            } else {
                float gx = firstCx + slot * i - slot / 2f;
                Bitmap sp = Bitmap.createBitmap(Math.max(4, (int) (slot + 20 * density)),
                        Math.max(4, (int) bar.height()), Bitmap.Config.ARGB_8888);
                drawTabsOn(new Canvas(sp), -gx, -bar.top, i, inactiveColor, true);
                if (tabSprites[i] != null) tabSprites[i].recycle();
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
        Bitmap newSlider = Bitmap.createBitmap(Math.max(4, sw), Math.max(4, sh), Bitmap.Config.ARGB_8888);
        Canvas c3 = new Canvas(newSlider);
        float bw = pillW / 2f, bh = pillH / 2f;
        RectF sr3 = new RectF(sw / 2f - bw, sh / 2f - bh, sw / 2f + bw, sh / 2f + bh);
        float rr = pillCorner(bw, bh);
        Paint shadowP = new Paint(Paint.ANTI_ALIAS_FLAG);
        /* FlClash _lensShadows：black@0.055 + blur 5（我们原 15% 硬边影过重） */
        shadowP.setColor(Color.argb(14, 15, 23, 42));
        shadowP.setMaskFilter(new BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL));
        /* 药丸投影随档位：standard/none 砍（与栏体投影同一语义） */
        if (!NativeBlocks.glassNone() && !NativeBlocks.glassStandard())
            c3.drawRoundRect(sr3.left, sr3.top + density, sr3.right, sr3.bottom + 2f * density, rr, rr, shadowP);
        c3.drawRoundRect(sr3, rr, rr, sliderFillP);
        sliderStrokeP.setStrokeWidth(density);
        c3.drawRoundRect(sr3, rr, rr, sliderStrokeP);

        /* 软件 → HARDWARE 位图：blit 走 GPU 常驻纹理，免去每帧 ~7MB 的上传
           （120Hz 滑块动画期间这是与 WebView 争 GPU 的隐形大头）。失败回退软件位图。 */
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Bitmap h1 = chromeB1.copy(Bitmap.Config.HARDWARE, false);
                Bitmap h3 = newSlider.copy(Bitmap.Config.HARDWARE, false);
                if (h1 != null) {
                    chromeB1.recycle();   // 被替换的软件图（含上一代 HARDWARE 纹理）及时回收
                    chromeB1 = h1;
                }
                if (h3 != null) {
                    if (sliderBmp != null) sliderBmp.recycle();
                    sliderBmp = h3;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "HARDWARE 位图转换失败，保持软件位图", t);
        }
        if (sliderBmp != newSlider) {
            if (sliderBmp != null) sliderBmp.recycle();
            sliderBmp = newSlider;
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
        /* 药丸几何先算（栏体挖孔与玻璃/图标同帧同源）；玻璃可用判定提前。 */
        composePillCenter();
        float cx = pillCxNow;
        float cy = pillCyNow;
        float s = (float) jelly.value();
        float g = (PILL_REST_GROWTH
                + (LIFT_GROWTH - PILL_REST_GROWTH) * (float) lift.value()) * density;
        float hw;
        float hh;
        if (dockVertical) {
            hw = (pillW + g) / 2f * (1f - s * 1.4f);
            hh = (pillH + g) / 2f * (1f + s);
        } else {
            hw = (pillW + g) / 2f * (1f + s);
            hh = (pillH + g) / 2f * (1f - s * 1.4f);
        }
            boolean glassLive = shader != null
                    && !docked
                    && !dockVertical && Build.VERSION.SDK_INT >= 33;
        /* 药丸挖孔（2026-11-03 三轮改判）：清玻璃的本质=比磨砂栏**更透**。栏体
           62% 白 tint 若在药丸下连续，透过药丸看到的是「白上叠白」=雾蒙蒙磨砂
           （用户实测「像磨砂玻璃，没有液态通透感」）。挖孔后药丸内=容器 shader
           的清晰弯折内容 + 药丸自身薄 tint（唯一霜层）——「见 Dock 字体不见
           背景图」由 lensTab 全强度文字 + 折射承担，不靠栏体白 tint。
           （前次回撤的前提——lensTab 不可靠/容器折射是死代码——均已不成立。） */
        /* 栏体：竖排 = FlClash 口径的**纯色半透面板**（用户实证竖排强加玻璃
           「真的卡」，玻璃观感让位流畅）。横排 = **壁纸霜直铺**（wallFrost 位图
           shader，窗口坐标 1:1——霜位图=屏幕/2，localMatrix 缩 0.5 平移 pvOff/2）
           + 白 tint 薄染。药丸不再挖孔：药丸 AGSL 采样同一霜源，天然无缝。 */
        if (glassLive) {
            /* 视图坐标系直画（shader 采样基准 = 视图坐标，与药丸链一致）。
               z 序照搬上游 drawBackdrop：投影 → 霜链(lens) → surface tint → highlight。 */
            float br2 = dockRadius(bar);
            /* 栏体投影：standard/none 砍（FlClash standard 语义「底栏阴影」） */
            if (!NativeBlocks.glassNone() && !NativeBlocks.glassStandard())
                canvas.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, br2, br2, barShadowP);
            if (barPane != null && barLensShader != null) {
                float t = paneFadeAt == 0 ? 1f
                        : Math.min(1f, (SystemClock.uptimeMillis() - paneFadeAt) / (float) PANE_FADE_MS);
                if (barPanePrev != null && barPanePrevShader != null && t < 1f) {
                    drawBarGlass(canvas, bar, br2, barPanePrevShader, 255);
                    drawBarGlass(canvas, bar, br2, barPaneShader, (int) (255f * t));
                    postInvalidateOnAnimation();
                } else {
                    if (barPanePrev != null) {   // 淡出完成：旧 pane 退役（不再等到下次重建）
                        barPanePrev.recycle();
                        barPanePrev = null;
                        barPanePrevShader = null;
                    }
                    drawBarGlass(canvas, bar, br2, barPaneShader, 255);
                }
            } else {
                /* 霜未就绪：不画黑底（叠 tint 后正是 98 灰故障）。
                   栏面交给下面 sfTintP 的半透明薄染。 */
            }
            /* surface tint：上游 onDrawSurface 的 containerColor α0.4，画在 lens 之后 */
            canvas.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, br2, br2, sfTintP);
            /* 栏缘高光：KernelSU `highlight = { baseHighlight.value.copy(alpha = 0.75f) }`
               （iosIndicatorSpecular：White α0.12 / innerBlur 2dp / dualPeak 双光源）。 */
            drawBloomStroke(canvas, bar.left, bar.top, bar.right, bar.bottom, br2, 0.75f, 0f);
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

        /* 滑块先画（在图标之下）：拖到两格中间时滑块从图标后面滑过，
           图标始终完整可见（HTML tab-pill 的 z 序，2026-10-27 用户反馈「遮挡其中一个」）。 */
        if (dockVertical) {
            drawRailSelection(canvas);
        } else if (docked) {
            /* 原生贴底（FlClash docked = M3 NavigationBar）：指示器 = 图标背后的
               64×32dp 胶囊（secondaryContainer ≈ 应用 accent-soft），选中图标着
               主色由下方 drawLensTabs 平绘承担。跟随弹簧与悬浮态一致。 */
            float indCx = firstCx + slot * (float) pos.value();
            float indCy = pillCy() - (22f * density + 2f * density + 12f * density) / 2f
                    + 11f * density;
            indicatorP.setColor(dark ? Color.argb(46, 10, 132, 255) : Color.argb(28, 37, 99, 235));
            canvas.drawRoundRect(indCx - 32f * density, indCy - 16f * density,
                    indCx + 32f * density, indCy + 16f * density,
                    16f * density, 16f * density, indicatorP);
        } else if (!glassLive && !lensing && sliderBmp != null) {
            /* 无玻璃能力（旧机/快照未就绪）的回退贴片；玻璃可用时静止/滑行/
               按压全走下方实时路径——材质同一，不再「静止平涂贴片+按压玻璃」两张皮 */
            canvas.drawBitmap(sliderBmp, Math.round(cx - sliderBmp.getWidth() / 2f),
                    Math.round(cy - sliderBmp.getHeight() / 2f), blitP);
        } else if (glassLive) {
            /* 药丸全部外观（glass 链 + rest 面 + 高光 + 内阴影）改在 drawGlass 里
               画——上游 z 序 = backdrop 链 → surface → highlight，surface 必须在
               折射输出**之上**，留在 chrome 段会被玻璃盖掉。 */
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

        /* 灰态图标+文字：per-tab blit（跳过选中格——选中格由 drawLensTabs 以蓝态画在玻璃上） */
        RectF bar2 = dockRect();
        float act = pressed ? (float) pos.value() : selectedIndex;
        int actIdx = Math.max(0, Math.min(count - 1, Math.round(act)));
        for (int i = 0; i < count && i < 5; i++) {
            if (tabSprites[i] == null) continue;
            if (dockVertical) {
                if (i == actIdx) continue;
                canvas.drawBitmap(tabSprites[i], bar2.left, cellAxis(i) - axisPitch / 2f, blitP);
            } else {
                /* 玻璃可用时：药丸盖住的格（含滑行跨越格）由透镜面板重绘（rest 态
                   也是 1:1 清晰内容），平绘 sprite 若再画一层=双影 */
                if (glassLive
                        && Math.abs(firstCx + slot * i - cx) < slot * 0.5f + 2f * density) {
                    continue;
                }
                canvas.drawBitmap(tabSprites[i], Math.round(firstCx + slot * i - slot / 2f), bar2.top, blitP);
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

    /** _LensContent 移植（FlClash navigation_dock）：抬手时把药丸盖住的格
     *  （选中蓝态 + 跨格灰态）烘进离屏面板，喂给 shader 做 lensContent 级
     *  深弯（refraction 14dp / bevel 11.2dp）——图标在清玻璃下被放大弯折。
     *  平绘的 drawLensTabs 同帧按 (1−lift) 淡出，两者交接。rest 态绑定 1×1
     *  透明兜底，保证 shader input 恒有合法绑定。 */
    private void composeLensTab(float cx, float cy, float hw, float hh, float liftV) {
        /* 放大在**烘焙端**完成（FlClash _LensContent 矢量放大重绘同语义）：
           内容按 mag 预缩放画进位图、shader 1:1 采样——此前在 shader 里对 1x
           位图做 1.16 倍双线性上采样=模糊，采样相位还随药丸移动逐帧变化=颗粒
           （2026-11-03 用户实测「滚过文字发虚带颗粒」）。矢量按放大率重绘恒锐。 */
        /* 静止也给 1.08 的放大：参考形态里药丸内的图标本来就比邻格大一档，
           只有按压才放大（KernelSU lerp(1,1.2)）会显得静止态「平」（2026-11-07）。 */
        float mag = 1f + 0.2f * liftV;   // KernelSU：静止 1.0，按压到 1.2
        int pad = (int) ((mag > 1.01f ? 16f : 12f) * density);
        int w = (int) (hw * 2f) + pad * 2 + 2;
        int h = (int) (hh * 2f) + pad * 2 + 2;
        if (w < 4 || h < 4) {
            lensTabShader = null;
            return;
        }
        boolean lensing = pressed || (float) lift.value() > 0.005f;
        float active = lensing ? (float) pos.value() : selectedIndex;
        int blue = Math.max(0, Math.min(count - 1, Math.round(active)));
        int other = -1;
        if (active != blue) {
            other = Math.max(0, Math.min(count - 1, (int) Math.floor(active)));
            if (other == blue) other = Math.max(0, Math.min(count - 1, (int) Math.ceil(active)));
            if (other == blue) other = -1;
        }
        /* 取整对齐（2026-11-03 真机「文字糊」第二根因）：uTabOff 带小数时，
           shader 的 tabUv = p - uTabOff 小数部分偏离 0.5 → 双线性采样永远落在
           两枚纹素之间 = 整层文字发虚。取整后 tabUv 小数恰为 0.5 = 纹素中心，
           1:1 像素映射。绘制偏移与 uTabOff 必须同源同值。 */
        float ox = (float) Math.floor(cx - hw - pad);
        float oy = (float) Math.floor(cy - hh - pad);
        /* 面板**随药丸移动逐帧重烘**（2026-11-03 真机「停两格中间彩虹糊」根因）：
           内容锚在药丸上，药丸一动 tabOff 必须换——脏检查只许跳过「格子/尺寸/
           主题/偏移」全都没变的情况。重烘成本 ~0.1ms（2 图标+文字），远比错位
           静帧+边缘越界拉丝便宜。位图/shader 对象仅在 metaDirty 时重建。 */
        boolean metaDirty = lensTabBmp == null || lensTabBmp.getWidth() != w
                || lensTabBmp.getHeight() != h
                || lensTabKeyBlue != blue || lensTabKeyOther != other
                || lensTabKeyDark != (dark ? 1 : 0);
        boolean moved = Math.abs(lensTabKeyOx - ox) > 0.5f
                || Math.abs(lensTabKeyOy - oy) > 0.5f;
        if (!metaDirty && !moved && lensTabShader != null) return;
        lensTabKeyBlue = blue;
        lensTabKeyOther = other;
        lensTabKeyDark = dark ? 1 : 0;
        lensTabKeyOx = ox;
        lensTabKeyOy = oy;
        if (lensTabBmp == null || lensTabBmp.getWidth() != w || lensTabBmp.getHeight() != h) {
            lensTabBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            lensTabShader = null;   // 新位图 → 旧 shader 引用失效，必须重建
        }
        lensTabBmp.eraseColor(0);
        Canvas c = new Canvas(lensTabBmp);
        tabOffX = ox;
        tabOffY = oy;
        tabW = w;
        tabH = h;
        float pcx = cx - ox;
        float pcy = cy - oy;
        c.save();
        c.translate(pcx, pcy);
        c.scale(mag, mag);
        c.translate(-pcx, -pcy);
        drawTabsOn(c, -ox, -oy, blue, 0, false);
        if (other >= 0 && other != blue) {
            drawTabsOn(c, -ox, -oy, other, iconP.getColor(), true);
        }
        c.restore();
        if (lensTabShader == null) {
            lensTabShader = new BitmapShader(lensTabBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        /* 镜内二次折射不再尝试：RuntimeShader 只能跑在**硬件** Canvas 上，
           而离屏位图必须是 `new Canvas(Bitmap)`（软件画布）→ 必然抛
           "Software rendering doesn't support RuntimeShader"（2026-11-06 实测崩溃）。
           在 View 架构下这条能力不存在，要做得换 HardwareRenderer/RenderNode 重写绘制管线。 */
    }

    private int lensTabKeyBlue = -1, lensTabKeyOther = -1, lensTabKeyDark = -1;
    private float lensTabKeyOx = -1f, lensTabKeyOy = -1f;

    /** 同帧实时 backdrop：把主窗口 View 树录进离屏位图，替代 PixelCopy 离散快照。
     *  dock 在独立 Window、不在 anchor 子 View 里 → 录制天然不含自己，无自反馈。
     *  1/8 降采样再放大 = 轻量模糊：保留颜色/明暗流动，去掉可读文字（页面文字
     *  直接进玻璃正是此前按压色块的来源）。静止时 onDraw 不跑，零开销。 */
    /** 滚动期异步抓背景。dock 在独立 PopupWindow，抓主窗口天然不含自己，无自反馈。 */
    private void requestLivePixelCopy() {
        if (liveCopyInFlight || window == null || anchor == null) return;
        int w = Math.round(bandW), h = Math.round(bandH);
        if (w < 4 || h < 4) return;
        if (liveRaw == null || liveRaw.getWidth() != w || liveRaw.getHeight() != h) {
            liveRaw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        popupOffsetInMainWindow(winOffTmp);
        Rect src = new Rect(winOffTmp[0], winOffTmp[1], winOffTmp[0] + w, winOffTmp[1] + h);
        /* ❗必须裁到窗口内：View 比可见 dock 大一圈（BACKDROP_PAD 40dp 外扩），
           底部有一部分在屏幕外。srcRect 越界时 PixelCopy 会抓到黑边甚至直接失败，
           表现就是「底栏乱渲染」（2026-11-07 用户报）。
           越界区域保留上一帧内容 —— 那部分本来就不显示。 */
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (!src.intersect(new Rect(0, 0, dm.widthPixels, dm.heightPixels))
                || src.width() < 4 || src.height() < 4) {
            liveCopyInFlight = false;
            return;
        }
        liveCopyInFlight = true;
        try {
            PixelCopy.request(window, src, liveRaw, result -> {
                liveCopyInFlight = false;
                if (result != PixelCopy.SUCCESS) return;
                if (liveSharp == null || liveSharp.isRecycled()) return;
                liveSharp.eraseColor(0);
                new Canvas(liveSharp).drawBitmap(liveRaw, null,
                        new RectF(0, 0, liveSharp.getWidth(), liveSharp.getHeight()),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
                invalidate();       // 下一帧合成链就会用上新数据
            }, frostHandler);
        } catch (Throwable t) {
            liveCopyInFlight = false;
            Log.w(TAG, "PixelCopy 请求失败", t);
        }
    }

    private void captureLiveBackdrop() {
        if (anchor == null || bandW < 4 || bandH < 4) return;
        /* 30fps 上限：anchor.draw() 要重绘整棵主窗口（含 WebView），切页转场时
           与页面绘制抢主线程会「非常卡」（2026-11-06 用户报）。 */
        long nowMs = SystemClock.uptimeMillis();
        /* 滚动中不限速（每帧跟手），静止时 30fps 足矣 */
        scrollingNow = nowMs < scrollIdleUntil;
        /* 录制成本是硬成本（anchor.draw 里 WebView 要渲染整页，不随目标尺寸降低，
           实测 1/2 与 1/6 都是 17ms）。所以滚动时只能降频率换帧率：
           15fps 更新背景 + 其余帧复用 → 帧率回到 50fps 以上（2026-11-07）。 */
        if (nowMs - lastCaptureMs < (scrollingNow ? 40 : 33)) return;
        /* 药丸位移动画期（切页/拖动）不录制，沿用上一帧：录制 12ms + 药丸重绘
           叠加就顶穿 16.7ms 预算，表现为「局部丢帧」（2026-11-06 用户报）。
           采样按新 tabOff 取旧图，内容只是滞后一帧，肉眼不可见。 */
        /* 录制精度提到 1/2 后单次更贵：按压/抬升动画期也一律沿用上一帧，
           否则按压时每帧录制会顶穿预算。静止态才重录。 */
        /* 位移动画 / 按压 / 拖动期的录制策略（2026-11-09 重写，用户报「按住药丸
           来回拖动，dock 显示上一页背景 1-2s 才切换」）：按住态显示层切到
           liveSharp（按住=通透），旧逻辑按住期一律不录 → liveSharp 冻在切页前
           的画面，松手后还要等冷却+弹簧耗尽才恢复。改为分场景——
             · 手指按住拖拽（页面静止，录制 ~17ms）：80ms 节拍低速跟拍并绕开
               冷却（与滚动 40ms 同一经济学：17ms / 4~5 帧摊一次）；
             · 切页动画/转场期（页面重绘中，录制实测 56~59ms/帧）：维持一律
               不录——点按切页时 WebView 会触发滚动信号（scrollingNow 变 true），
               若放开这路必然卡死（2026-11-07 用户报点按卡顿），故此判断仍须
               排在滚动判断之前。 */
        boolean fingerDown = pressed || dragging;
        boolean pageSwitching = transitionHold || (pos.active() && !fingerDown);
        if (pageSwitching && liveSharp != null) return;
        long minGap = fingerDown ? 80 : (scrollingNow ? 40 : 33);
        if (nowMs - lastCaptureMs < minGap) return;
        /* 冷却只拦「无交互」期：按住拖拽期页面静止、录制安全，绕开冷却
           （冷却的转场保护职责已由 transitionHold 承担）。 */
        if (!scrollingNow && !fingerDown && nowMs < liveCooldownUntil && liveSharp != null) return;
        lastCaptureMs = nowMs;
        int w = Math.round(bandW), h = Math.round(bandH);
        /* 精度分档：滚动中 1/6（≈4ms，可每帧跟上），静止 1/2（≈16ms，一次到位） */
        int div = 2;   // 固定 1/2：切档会让 liveSharp 尺寸突变、背景瞬间换图（2026-11-07 回滚）
        int sharpW = Math.max(2, w / div), sharpH = Math.max(2, h / div);
        boolean rebuild = liveBackdrop == null
                || liveBackdrop.getWidth() != w || liveBackdrop.getHeight() != h
                || liveSharp == null || liveSharp.isRecycled()
                || liveSharp.getWidth() != sharpW || liveSharp.getHeight() != sharpH;
        if (rebuild) {
            if (liveBackdrop != null) liveBackdrop.recycle();
            if (liveStep != null) liveStep.recycle();
            if (liveToned != null) liveToned.recycle();
            if (liveSharp != null) liveSharp.recycle();
            liveBackdrop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            /* 三级：1/4 录制 → 1/8 中间级 → 1/16 终点，串联两次降采样。
               ❗miuix blur(4dp) 是「全分辨率 → 1/2 → 1/10」**两级**串联
               （downScaleExpFor(σ²)=1 → sf=2，再 k=round(σ)=5）。此前我们一级
               1/11 双线性只取 2×2、丢掉 9/10 信息 → 混叠成块与横向条纹，
               这就是「玻璃模糊状态」复刻不出来的原因（2026-11-06 用户指出）。 */
            liveSharp = Bitmap.createBitmap(sharpW, sharpH,
                    Bitmap.Config.ARGB_8888);
            /* 中间级恒定 = 清晰源的一半：静止档 1/4、滚动档 1/12（= 栏体终点，
               两者同尺寸时降采样退化为复制，仍然正确） */
            liveStep = Bitmap.createBitmap(Math.max(2, sharpW / 2), Math.max(2, sharpH / 2),
                    Bitmap.Config.ARGB_8888);
            /* 终点 1/12：miuix blur(4dp) 应从**全分辨率**降 10 倍
               （σ=4.95px → sf=2、k=round(σ)=5）。我们录制只有 1/4（主线程成本所限），
               再降 3 倍凑到总 12 倍，等效模糊量最接近上游的 10 倍。 */
            int sw = Math.max(2, w / 18), sh = Math.max(2, h / 18);   // 磨砂：18 倍降采样，字形完全消失
            liveToned = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        }
        /* ❗滚动期改用 PixelCopy 异步抓图：同步 `anchor.draw` 单次 17ms
           （WebView 要渲染整页，与分辨率 / clip / 分帧都无关），必顶穿预算。
           ❗**不 return**：合成段必须每帧照跑，否则 combinedBmp/liveBackdrop
           状态与绘制时序错位，药丸会整个消失（2026-11-07 实测踩过）。 */
        /* 自适应：上一次同步录制超过 30ms（原生页场景）就改用异步，
           否则每帧 55ms 直接把切页后的几秒拖成卡顿（2026-11-07 用户报字典/面试）。 */
        boolean useAsync = scrollingNow || lastSyncCapCost > 30_000_000L;
        if (useAsync) {
            requestLivePixelCopy();
        } else {
            liveSharp.eraseColor(0);
            Canvas off = new Canvas(liveSharp);
            popupOffsetInMainWindow(winOffTmp);
            off.save();
            off.scale((float) liveSharp.getWidth() / w, (float) liveSharp.getHeight() / h);
            off.translate(-winOffTmp[0], -winOffTmp[1]);
            /* ❗必须按 dock 矩形裁剪：否则 anchor.draw 会遍历整棵 pagesLayer，
               原生页（字典 76 行 / 面试 53 题，大量子 View）实测 55ms，而
               WebView 页只有 17ms —— 这就是「切到字典/面试特别卡」的原因。
               裁剪后 ViewGroup 会跳过与 dock 不相交的 child（2026-11-07）。 */
            off.clipRect(winOffTmp[0], winOffTmp[1], winOffTmp[0] + w, winOffTmp[1] + h);
            long __c0 = System.nanoTime();
            try {
                anchor.draw(off);   // 同帧录制主窗口内容（含壁纸/原生页/WebView）
                lastSyncCapCost = System.nanoTime() - __c0;
            } catch (Throwable t) {
                Log.w(TAG, "实时 backdrop 录制失败，沿用上一帧", t);
                off.restore();
                return;
            }
            off.restore();
        }
        /* 降采样第一级：1/4 → 1/8（对应 miuix 的 sf=2 档） */
        liveStep.eraseColor(0);
        new Canvas(liveStep).drawBitmap(liveSharp, null,
                new RectF(0, 0, liveStep.getWidth(), liveStep.getHeight()),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        /* 亮度归一化：复用 buildFrost 的 shim（霜面均值锚 236/40）+ vibrancy
           saturation 1.5。漏接这层会让「我的」页整栏跳深灰（实测 167,168,174）。
           半量补偿只锚绝对亮度，页面色相/相对明暗仍实时跟随，不生硬割裂。 */
        /* 整图帧间低通：logcat 实证切页时 raw 一帧从 59 跳到 139 后 38 帧不变，
           只对亮度标量阻尼没用——底图仍每帧直录、玻璃瞬间变脸。这里让整张
           liveToned 以 α=0.12 叠新帧，在 ~600ms 内渐变；亮度锚也基于混合后
           的图，天然平滑，不再需要单独的标量阻尼。 */
        /* 照抄 KernelSU：Vibrancy.kt 的 colorControls(brightness=0, contrast=1,
           saturation=1.5) —— contrast 1.0 即**不调整**（我上一轮照 FlClash 用 2.1/1.4
           是混了两家参数）。 */
        float rawLuma = frostMeanLumaOf(liveStep);
        boolean first = lastRawLuma < 0f;
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.5f);
        /* ColorMatrix 只作用在 1/24 小图上：挂在全尺寸放大那步实测
           draw 均 38ms/帧，挪回小图后恢复 ~13ms（与无滤镜同量级）。 */
        Paint toneP = new Paint(Paint.FILTER_BITMAP_FLAG);
        toneP.setColorFilter(new ColorMatrixColorFilter(cm));
        /* 帧间低通（α=0.12 叠新帧、~40 帧收敛）只服务于「内容跳变」场景：
           切页后 1.2s 冷却期内用它做渐变，避免玻璃瞬间变脸。
           其余情况（含滚动停手后的补帧）必须直录 —— 否则每次更新都要 666ms
           才收敛，用户感知就是「dock 背景延迟半秒到一秒」（2026-11-07 报）。 */
        boolean jumpy = !first && nowMs < liveCooldownUntil;
        toneP.setAlpha(jumpy ? (int) (255f * 0.12f) : 255);
        if (first) liveToned.eraseColor(0);
        /* 降采样第二级 + tone：1/8 → 1/16（对应 miuix 的 k=5 档） */
        new Canvas(liveToned).drawBitmap(liveStep, null,
                new RectF(0, 0, liveToned.getWidth(), liveToned.getHeight()), toneP);
        /* 内容变化后至少自驱动 40 帧让混合收敛，收敛后自眠 */
        if (first || Math.abs(rawLuma - lastRawLuma) > 0.5f) liveStillFrames = 0;
        else liveStillFrames++;
        lastRawLuma = rawLuma;
        /* haze 是 FlClash 独有；KernelSU 没有 haze，只有一层 blur(4dp)。
           删掉 28/72 混合，直接放大 tone 后的霜图。 */
        Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
        liveBackdrop.eraseColor(0);
        new Canvas(liveBackdrop).drawBitmap(liveToned, null, new RectF(0, 0, w, h), bp);
        liveBackdropShader = new BitmapShader(liveBackdrop,
                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (liveStillFrames < 40) postInvalidateOnAnimation();   // 混合未收敛，继续下一帧
    }

    /** 药丸 lens 的 content = 「同帧实时模糊的主窗口内容 + 淡蓝紫 tint + 放大 tab 镜像」。
     *  ❗不用 contentSharpFull 离散快照（会串页面文字、按动成色块）；改用
     *  captureLiveBackdrop() 每帧同帧录制并 1/8 降采样，只留颜色/明暗流动。
     *  底色经实时位图 + tint 铺满，REFRACTION_DISPERSION 不会采到透明像素。
     *  药丸与栏体共用同一张 liveBackdrop，颜色连续、不生硬割裂。 */
    private boolean buildCombined(float r, float liftV) {
        /* 优先用折射后的图层；首帧还没有（滞后一帧）就退回原图 */
        Bitmap tabs = lensTabBmp;
        if (tabs == null || tabs.isRecycled()) return false;
        int w = tabW, h = tabH;
        if (w < 4 || h < 4) return false;
        /* 滚动中沿用上一帧：combinedBmp 是 464×214 的整块重绘 + 位图采样，
           每帧重算约 10ms，叠加录制与两处 shader 必超预算（2026-11-07 实测
           draw 均 28ms / 31fps）。滚动时药丸位置不变，滞后一帧无感。 */
        if (combinedBmp == null || combinedBmp.getWidth() != w || combinedBmp.getHeight() != h) {
            if (combinedBmp != null) combinedBmp.recycle();
            combinedBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        combinedBmp.eraseColor(0);
        Canvas c = new Canvas(combinedBmp);
        float pl = pillRect.left - tabOffX, pt = pillRect.top - tabOffY;
        float pr = pillRect.right - tabOffX, pb = pillRect.bottom - tabOffY;
        /* ❗不在整图上裁剪药丸形状：裁剪会让药丸外全是透明像素，AGSL 在
           pillRect 边缘采样到透明 → 只在内圈输出颜色，形成「内外两层胶囊」
           （2026-11-05 用户报）。底色铺满整图，tab 镜像才按形状裁剪。 */
        /* 药丸 = 玻璃：取 1/8 清晰版背景（栏体才是 1/24 磨砂），无白底、无色相。
           玻璃能看清背后内容、只在边缘弯折，糊成一片的是磨砂而非玻璃
           （2026-11-06 用户报「下面有字体时太模糊」）。 */
        /* ❗药丸不经过 blur：KernelSU 药丸的 effects 只有 lens()，折射的是
           `combinedBackdrop`（未模糊的 backdrop + tabs 层）。栏体才 blur(4dp)。
           此前用栏体的 liveBackdrop 铺药丸，等于把栏体的模糊套给药丸（用户指出
           「模糊值是底部 dock 栏的，不是药丸的」）。 */
        /* 药丸两态的内容源：
           静止 = 磨砂（liveBackdrop 1/18，与栏体同一份，读不出背后文字）
           按住 = 通透（liveSharp 1/2 清晰源，按 liftV 淡入覆盖磨砂层）
           ❗两层叠加而不是二选一：换源会整块突变，按 liftV 淡入才连续。
           此前只有一个磨砂源，导致「按住也糊」——通透感彻底消失（2026-11-07 用户报）。 */
        if (liveBackdrop != null && !liveBackdrop.isRecycled()) {
            c.drawBitmap(liveBackdrop, -tabOffX, -tabOffY,
                    new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        if (liftV > 0.01f && liveSharp != null && !liveSharp.isRecycled()) {
            float k = (float) liveSharp.getWidth() / bandW;
            Paint sharpP = new Paint(Paint.FILTER_BITMAP_FLAG);
            sharpP.setAlpha((int) (255f * Math.min(1f, liftV)));
            c.drawBitmap(liveSharp,
                    new Rect((int) (tabOffX * k), (int) (tabOffY * k),
                            (int) ((tabOffX + w) * k), (int) ((tabOffY + h) * k)),
                    new RectF(0, 0, w, h), sharpP);
        }
        if (liveBackdrop == null || liveBackdrop.isRecycled()) {
            c.drawColor(dark ? 0xFF2C2C2E : 0xFFDEE5FC);
        }
        /* 中性灰阶 tint（R=G=B），不许带色相：药丸只比栏面略亮一档来定形，
           选中态由蓝色图标承担。此前用 accent 蓝 9% 会整块偏蓝（用户报「偏色」）。 */
        Paint baseP = new Paint(Paint.ANTI_ALIAS_FLAG);
        /* 底色 α 26 → 100（用户 2026-11-07：只要不糊就行、不用非常通透）：
           背后内容被压淡到 6 成，模糊感不再显现；折射/色散仍可见。 */
        /* 底色随按压进度变化：静止 α150 磨砂（与栏体同材质）、按住降到 α50 通透，
           这正是 KernelSU 的两态 —— lens() 只在 pressProgress>0 时才折射。 */
        int baseA = (int) (150 - 100 * Math.min(1f, liftV));
        baseP.setColor(dark ? Color.argb(baseA, 74, 74, 80) : Color.argb(baseA, 252, 252, 252));
        c.drawRect(0f, 0f, w, h, baseP);
        pillClipPath.reset();
        pillClipPath.addRoundRect(pl, pt, pr, pb, r, r, Path.Direction.CW);
        int sv = c.save();
        c.clipPath(pillClipPath);
        c.drawBitmap(tabs, 0f, 0f, null);
        c.restoreToCount(sv);
        /* 位图像素坐标 = 视图坐标 - tabOff；与栏体 barPaneShader 同一接线口径 */
        combinedShader = new BitmapShader(combinedBmp,
                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setTranslate(tabOffX, tabOffY);
        combinedShader.setLocalMatrix(m);
        return true;
    }

    /** 按压透镜玻璃（照搬 KernelSU 药丸层）：lens(10/14dp×lift, depth, CA0.5) 折射
     *  「清晰页面 + tab 镜像」合成内容，随后 surface（rest 0.1 / press 0.03）→
     *  BloomStroke → innerShadow → 触摸光晕。rest 态 refractionHeight=0 时
     *  shader 自然退化为 1:1 透传（上游同语义），静止常驻。 */
    private void drawGlass(Canvas canvas, float liftV) {
        /* 静止 12dp 基础增长 + 按压再增量 12dp（到 24dp） */
        float g = (PILL_REST_GROWTH + (LIFT_GROWTH - PILL_REST_GROWTH) * liftV) * density;
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

        boolean glassReady = shader != null && !NativeBlocks.glassNone() && Build.VERSION.SDK_INT >= 33;
        if (glassReady) {
            composeLensTab(cx, cy, hw, hh, liftV);
            if (lensTabShader != null && buildCombined(r, liftV)) {
                /* Kyant0 药丸 lens 恢复接线：content = 自足不透明底 + tab 镜像，
                   不含页面快照。静止 refractionHeight=0 退化为 1:1 透传；
                   按压才出 10/14dp 折射 + 0.5 色散。 */
                shader.setInputShader("content", combinedShader);
                shader.setFloatUniform("size", hw * 2f, hh * 2f);
                shader.setFloatUniform("offset", hw - cx, hh - cy);
                shader.setFloatUniform("cornerRadii", r, r, r, r);
                /* 照抄 KernelSU FloatingBottomBar.kt:447-451：
                   lens(refractionHeight = 10dp×progress, refractionAmount = 14dp×progress,
                        depthEffect = true, chromaticAberration = 0.5f)。
                   上一轮改成 FlClash 的 −4.3/5.5 是混了两家，已回滚。 */
                /* 静止就有折射（6dp 带 / 9dp 位移），按压升到 12/18 —— 参考形态的
                   静止药丸边缘本来就有弯折与彩虹边；此前 `10*liftV` 让静止折射量
                   归零、退化成 1:1 透传，是「没有玻璃质感」的直接原因（2026-11-07）。 */
                shader.setFloatUniform("refractionHeight", 10f * density * liftV);
                shader.setFloatUniform("refractionAmount", -14f * density * liftV);
                shader.setFloatUniform("depthEffect", 0f);   // 关掉中心深度项：水滴中心是平的，只有边缘一圈弯折
                shader.setFloatUniform("chromaticAberration", NativeBlocks.glassStandard() ? 0f : 0.28f);   // 标准/无档砍色散（FlClash standard 语义）
                float alphaK = nativeDock ? 1f : Math.min(1f, liftV * 2.5f);
                if (!nativeDock) alphaK = Math.min(alphaK, 0.5f);
                /* ❗静止 refractionHeight=0 时 shader 里 `-sd / refractionHeight`
                   除零 → 药丸边缘一圈 NaN，表现为「内层小胶囊」的双层边界
                   （2026-11-05 用户报）。静止直接 1:1 直贴 combinedBmp，
                   只有按压 liftV>0（分母非零）才走折射/色散链。 */
                int save = canvas.save();
                canvas.clipRect(0, 0, bandW, bandH);
                /* 静止也走折射链：refractionHeight 现在恒 ≥14dp（非零），
                   当年 `-sd / refractionHeight` 除零产生 NaN、边缘一圈「内层小胶囊」
                   的前提已不成立。此前静止走平绘 = **完全不折射**，
                   这才是「药丸没有玻璃质感」的根因（2026-11-07）。 */
                if (liftV > 0.01f && r > 0f) {   // 静止走平绘：KernelSU 静止 lens() 直接 return，纯磨砂
                    glassPaint.setShader(shader);
                    glassPaint.setAlpha(255);
                    canvas.drawRoundRect(pillRect, r, r, glassPaint);
                } else {
                    /* 静止 1:1 直贴：shader 采样时 tabOff 取整、pillRect 是小数坐标，
                       半像素错位会在药丸边缘拉出一圈灰带（误读为「双层」）。
                       直贴按整数偏移放置 combinedBmp，边缘只有 1px 抗锯齿。 */
                    Path pView = new Path();
                    pView.addRoundRect(pillRect, r, r, Path.Direction.CW);
                    int sv3 = canvas.save();
                    canvas.clipPath(pView);
                    canvas.drawBitmap(combinedBmp, tabOffX, tabOffY, null);
                    canvas.restoreToCount(sv3);
                }
                /* surface：rest 黑(浅)/白(暗) 0.1×(1−lift) + 按压黑 0.03×lift */
                /* rest surface 不再压暗：半透明底已经比栏面亮 5 级，再叠 4% 黑
                   会把它拉回与栏面同色甚至更暗（又回到凹陷/双层观感）。 */
                int restA = 0;
                if (restA > 2) {
                    pillRestP.setAlpha(restA);
                    canvas.drawRoundRect(pillRect, r, r, pillRestP);
                }
                int pressA = (int) (255f * 0.03f * liftV);
                if (pressA > 2) {
                    pillPressP.setAlpha(pressA);
                    canvas.drawRoundRect(pillRect, r, r, pillPressP);
                }
                /* 药丸内阴影不画：KernelSU 源码 :471-477 虽有 innerShadow(8dp, Black α0.15,
                   alpha=pressProgress)，但实机在通透药丸上就是一条灰影，用户明确不要
                   （2026-11-06）。以观感为准，不以此条为「必须对齐」。 */
                /* 药丸边缘不再画独立白描边：1dp 白环与内部淡蓝紫 tint 色差明显，
                   像素上虽只有一次过渡，视觉却读成「白胶囊套蓝紫胶囊」两层
                   （2026-11-05 用户报）。边缘交给栏面自身的明暗过渡。 */
                /* 药丸内阴影已移除：上游 8dp/黑15% 的「上缘影」靠实心白底衬托，
                   通透药丸上无论 8dp 还是 3dp 都会留一条灰带（2026-11-05 用户反复
                   指出）。药丸边缘改为完全依靠折射/色散与栏面的清晰度差。 */
                /* 触摸光晕（我方保留子系统）：径向 ADD 叠在玻璃上 */
                float glowV = (float) glow.value();
                if (glowV > 0.01f) {
                    float spreadV = (float) glowSpread.value();
                    float gr = Math.max(1f, 28f * density * (1f + 3f * spreadV));
                    float gs = 0.06f * glowV * glowV * (nativeDock ? 1f : alphaK);
                    int ca = (int) (255f * gs * 0.8f);
                    int ea = (int) (255f * gs * 0.56f);
                    if (ca > 2) {
                        RadialGradient rg = new RadialGradient(glowX, glowY, gr,
                                new int[]{Color.argb(Math.min(255, ca), 255, 255, 255),
                                          Color.argb(Math.min(255, ea), 255, 255, 255)},
                                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
                        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
                        gp.setShader(rg);
                        gp.setXfermode(XF_ADD);
                        canvas.drawRoundRect(pillRect, r, r, gp);
                    }
                }
                /* 药丸边缘高光：KernelSU 的 `pillHighlight` alpha = pressProgress（静止为 0），
                   但它的静止定形由 `drawBackdrop` 的 hairline/rim 承担；我们没有那一层，
                   静止态药丸就只剩一块颜色、没有玻璃边缘。这里给一个 0.32 的常驻底光，
                   按压时再升到 1.0 —— 边缘光不影响"糊"的观感，是不牺牲清晰度的定形手段。 */
                drawBloomStroke(canvas, pillRect.left, pillRect.top, pillRect.right, pillRect.bottom,
                        r, Math.max(0.32f, liftV), 90f);
                canvas.restoreToCount(save);
            }
        } else {
            /* 背景未就绪时的按压降级：半透明药丸，保证按下有反馈。
               「无」档（netops_lglass=none）常驻此路径：alpha 提到 200——
               23% 白在浅霜上不可见 = 选中指示丢失（2026-11-09 真机截图实证）；
               实感白胶囊 = FlClash 贴底态的选中语义，图标保持灰态。 */
            fallbackPaint.setColor(Color.argb(NativeBlocks.glassNone() ? 200 : 60, 255, 255, 255));
            canvas.drawRoundRect(pillRect, r, r, fallbackPaint);
        }
    }

    /** 栏体玻璃一帧：noise(child=pane) → 栏缘 lens（24dp/24dp）两级链一次画成。
     *  cornerRadii 折射族约定 = [TL, TR, BR, BL]（四角同值无歧义）。
     *  视图坐标直画（无 translate）：centeredCoord=(coord+offset)−halfSize 要等于
     *  coord−栏中心 ⇒ offset = −栏左上（❗不是 0——SDF 零点必须对准栏中心）。 */
    private void drawBarGlass(Canvas canvas, RectF bar, float r, BitmapShader pane, int alpha) {
        /* 「无」档（netops_lglass=none）：整条平绘——pane 直贴（无栏缘折射），
           bloom/药丸折射由各自位点门控。平绘 Paint 提字段复用（每帧调用）。 */
        if (NativeBlocks.glassNone()) {
            barPlainP.setShader(pane);
            barPlainP.setAlpha(alpha);
            canvas.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, r, r, barPlainP);
            return;
        }
        /* 不再按滚动状态降质：分段计时证明栏体 shader 本身 <1ms，降质只带来
           「松手瞬间画面突变」（2026-11-07 用户报）。真正的瓶颈是录制，见
           captureLiveBackdrop 的频率控制。 */
        /* ❗必须把 shader 挂到 Paint：漏挂时 Paint 用默认 BLACK 画整块，
           叠 sfTintP 40% 白 = (98,98,98) 纯灰（2026-11-05 平板实测根因）。
           pane 是 barLensShader 的 content 输入，不是 Paint 的 shader。 */
        barLensP.setShader(barLensShader);
        barLensShader.setInputShader("content",
                liveBackdropShader != null ? liveBackdropShader : pane);
        barLensShader.setFloatUniform("size", bar.width(), bar.height());
        barLensShader.setFloatUniform("offset", -bar.left, -bar.top);
        barLensShader.setFloatUniform("cornerRadii", r, r, r, r);
        barLensShader.setFloatUniform("refractionHeight", 24f * density);
        barLensShader.setFloatUniform("refractionAmount", -24f * density);
        barLensShader.setFloatUniform("depthEffect", 0f);
        barLensP.setAlpha(alpha);
        canvas.drawRoundRect(bar.left, bar.top, bar.right, bar.bottom, r, r, barLensP);
    }

    /** BloomStroke 双光源高光：实现在 glass/BloomStrokeFx（顶栏共用）。
     *  ❗必须把画布原点平移到矩形左上再调：shader 里 `halfView` 同时被当作
     *  「中心点」（abs(fragCoord-halfView)）和「半尺寸」（xy < halfView-R），
     *  只有在「坐标从 0 开始」时两个语义才重合（miuix 画在独立 layer 正是如此）。
     *  全屏坐标下传中心值，会让整条栏被判成内部返回透明、只剩两端条带算高光，
     *  → 左右圆角内侧纯黑竖条（2026-11-06 定位）。 */
    private void drawBloomStroke(Canvas canvas, float l, float t, float rt, float b,
                                 float radius, float alpha, float extraDegrees) {
        if (bloomShader == null || NativeBlocks.glassNone() || alpha <= 0.01f) return;
        int sv = canvas.save();
        canvas.translate(l, t);
        BloomStrokeFx.draw(bloomShader, bloomP, canvas, 0f, 0f, rt - l, b - t,
                radius, alpha, extraDegrees, gravityX, gravityY, density);
        canvas.restoreToCount(sv);
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
        /* 2026-11-03 P0 栏后内容霜：窗口级滚动信号（OnScrollChangedListener 对整个
           ViewTree 生效，WebView 与原生页滚动都触发）→ 停手 250ms 抓一次。
           滚动期间不抓：PixelCopy 单次实测 29~62ms，滚动中抓=主线程搬像素。 */
        root.getViewTreeObserver().addOnScrollChangedListener(() -> {
            /* 滚动逐帧驱动 dock 重绘 → captureLiveBackdrop 同帧跟随页面。
               此前这里只排 250ms 的旧抓图，onDraw 不跑，玻璃在滚动中是冻结的。 */
            v.scrollIdleUntil = android.os.SystemClock.uptimeMillis() + 120L;
            v.invalidate();
            v.frostHandler.removeCallbacks(v.frostDebounce);
            v.frostHandler.postDelayed(v.frostDebounce, 250L);
        });
        float dp0 = act.getResources().getDisplayMetrics().density;
        boolean vert0 = act.getResources().getConfiguration()
                .screenWidthDp > act.getResources().getConfiguration().screenHeightDp;
        int w0 = vert0 ? (int) (RAIL_W_DP * dp0) : ViewGroup.LayoutParams.MATCH_PARENT;
        int h0 = vert0 ? ViewGroup.LayoutParams.MATCH_PARENT : (int) (120 * dp0);
        v.popup = new PopupWindow(v, w0, h0, false);
        v.popupVertical = vert0;
        /* 2026-11-01 SF 实时磨砂分层：blur 窗走 PANEL(子层1)，本弹窗升
           SUB_PANEL(子层2) —— 层序 app 内容(0) < blur(1) < chrome(2)，SF 只糊
           背后的 Activity 合成结果，chrome 不被波及（实测 1002=MEDIA_OVERLAY
           在本机映射 SUB_PANEL，反压 chrome=整条被糊成雾）。 */
        v.popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
        v.popup.setClippingEnabled(false);
        v.popup.setBackgroundDrawable(null);
        /* 2026-11-02 快照链退役：玻璃底=SF blurBehind 实时模糊，无需预抓。 */
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
        int newW = vert ? (int) (RAIL_W_DP * dp) : Math.max(1, winW);
        int newH = vert ? Math.max(1, winH - (int) railTop) : (int) (120f * dp);
        int newX = vert ? (int) (RAIL_LEFT_DP * density) : 0;
        int newY = vert ? (int) railTop : 0;
        if (popup.isShowing() && vert == popupVertical) {
            /* 同形态（gravity 不变）走 update() 平滑改尺寸/位置——dismiss+show
               会整窗拆建（surface 释放→重分配→输入空窗），旋转/分屏切换的
               「卡一下」主源。翻转（横排↔竖排）gravity 变了，update() 无法改
               gravity，仍走 dismiss+show。update 失败退回旧路径。 */
            try {
                popup.update(newX, newY, newW, newH);
                popupVertical = vert;
                lastPopupWidth = winW;
                lastPopupHeight = winH;
                lastPopupTop = railTop;
                return;
            } catch (Throwable t) {
                Log.w(TAG, "popup.update 失败，退回 dismiss+show", t);
            }
        }
        popupVertical = vert;
        popup.setWidth(newW);
        popup.setHeight(newH);
        if (popup.isShowing()) {
            try { popup.dismiss(); } catch (Throwable ignored) { }
        }
        showPopup();    }

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
        updateContainerEffect();   // 键盘/弹层在场：shader 区域会折射到弹层内容，摘除
    }

    /** Activity 销毁时收掉：帧回调、延迟任务、弹窗一次清干净，否则会 window leak
     *  且旧实例在 Choreographer 上永久空转（旋转重建场景会逐次累积）。 */
    public void detach() {
        detached = true;
        updateContainerEffect();   // 摘除容器折射（空壳 no-op）
        frostWorker.getLooper().quit();   // 后台霜链线程随 View 退出（防 Activity 重建累积）
        ticking = false;
        removeCallbacks(null);   // 预热/几何补测的 postDelayed 一并撤掉（detached 后本就是空操作）
        Choreographer.getInstance().removeFrameCallback(this);
        try {
            anchor.getViewTreeObserver().removeOnGlobalLayoutListener(anchorLayoutListener);
        } catch (Throwable ignored) { }
        if (combinedBmp != null) {
            combinedBmp.recycle();
            combinedBmp = null;
            combinedShader = null;
        }
        if (liveBackdrop != null) { liveBackdrop.recycle(); liveBackdrop = null; }
        if (liveStep != null) { liveStep.recycle(); liveStep = null; }
        if (liveToned != null) { liveToned.recycle(); liveToned = null; }
        if (liveSharp != null) { liveSharp.recycle(); liveSharp = null; }
        liveBackdropShader = null;
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
