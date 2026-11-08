package com.netops.handbook.lens;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.Xfermode;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.PopupWindow;

import com.netops.handbook.lens.glass.BloomStrokeFx;
import com.netops.handbook.lens.glass.LiquidGlassShaders;

import org.json.JSONObject;

/**
 * R2 原生顶栏（L2 壳层接管第二步，2026-10-27）。
 *
 * 与 R1（LensBarView）同构：本层活在**顶部锚定的独立 PopupWindow**里，画顶栏
 * 全部静止态外观（玻璃背景 + 海拔阴影 + 发丝线 + 导航键 + 双行标题 + 搜索/主题键），
 * HTML 顶栏由 body.native-appbar-on 置 visibility:hidden（保留布局供量几何）。
 *
 * 与底栏的两点关键差异（设计约束，勿"简化"掉）：
 * 1. 顶栏玻璃的背景是**静态的**：.app 是 flex 列布局，内容在 #scroll 里滚动，
 *    永远不会滚到顶栏底下 —— 顶栏背后只有页面壁纸层。只在几何同步 / 主题 /
 *    壁纸版本（bgrev）变化时抓一次，没有底栏那样的交互期重抓。
 * 2. 搜索展开、抽屉打开时**原生整窗退位**，HTML 顶栏显形（输入框 / 键盘链路
 *    一概不动）。退位由 32-boot 的状态推送驱动，CSS 组合类兜底显形，
 *    见 06-anim.css 的 native-appbar-on 块。
 *
 * 几何真源在本类（2026-10-31 P0-2 起）：栏矩形 / 标题块 / 三个按钮命中区按 CSS
 * 布局的实测常量自测（{@link #applySelfGeometry(int)}），不再向 WebView 索取 ——
 * HTML 顶栏因此可整块移除，也不会再有「量到旧布局」的旋转时序问题。
 * 按钮动作 = 程序化 click 对应 HTML 按钮（visibility:hidden 收不到真触摸），
 * 全部既有逻辑留在页面侧。
 * 回退开关：设置页「我的→原生顶栏」（localStorage netops_native_appbar，默认开）。
 */
public class AppBarView extends View {

    private static final String TAG = "AppBar";

    private final WebView webView;
    private final float density;
    private PopupWindow popup;
    private ViewGroup anchor;
    private volatile boolean detached = false;

    /** .appbar 里除内容行之外的两条「仪表」：topbar-progress 2.5dp + border-bottom 1dp。
     *  玻璃必须把它们一起盖住 —— 只画 54dp 内容行时栏底会露 3.5dp 未处理壁纸
     *  （改动前 JS 量的是 .appbar 整体矩形，天然含这两条）。 */
    private static final float CHROME_EXTRA_DP = 3.5f;

    /* ---- 几何（本 View 坐标物理 px；本类自测，见 applySelfGeometry）---- */
    private final RectF barRect = new RectF();      // 栏矩形（含状态栏 padding 区）
    private final RectF titleRect = new RectF();    // 标题块（name+sub 两行）
    private final RectF[] btnRects = new RectF[3];  // nav / search / theme 命中区
    private boolean hasGeometry = false;

    /* ---- 状态（32-boot 推送）---- */
    private String name = "NetOps 2.0";
    private String sub = "网络学习辅助手册";
    private boolean navBack = false;      // false=菜单（一级），true=返回箭头（二级）
    private boolean themeMoon = false;    // false=太阳（当前亮），true=月亮（当前暗）
    private boolean scrolled = false;     // is-scrolled：栏底发丝线（apple 风格）
    private boolean searchOpen = false;   // 搜索展开期整窗退位
    private boolean overlaid = false;     // 抽屉在场期整窗退位
    private boolean dark = false;
    private boolean glassOn = true;
    private int bgRev = 0;                // 壁纸版本号：变化才重抓背景
    private int nativeBar = 1;            // 1=接管（画），0=退位（全不画）

    /* ---- 背景：PixelCopy（frostWorker）→ 霜链（sat1.5 + blur4dp）→ blurred ----
       原帧只作链输入即时回收，不再常驻（省一张全分辨率位图）。 */
    private Bitmap blurred;               // 霜链产物（半尺寸记录档）
    /* ---- 栏体液态玻璃（2026-11-04 起与底栏同一套 Kyant0 材质）----
       旧「QmDeve 库 AGSL(GlassAgsl) + saturate 1.65 + 渐变 tint + radial specular」
       整体退役：霜链（÷2 记录档 → sat1.5 → blur4dp 两级）+ 噪声抖动 + 栏缘
       REFRACTION(24dp/24dp) + BloomStroke（重力 −45°）+ surface tint α0.4，
       与 LensBarView 底栏完全同源——上下两栏一种材质。 */
    private RuntimeShader glassShader;      // 栏缘折射（LiquidGlassShaders.REFRACTION）
    /* 噪声抖动随霜链完成；栏缘折射只吃单层位图（零嵌套 input shader）。 */
    private RuntimeShader bloomShader;      // BloomStroke 双光源
    private final Paint glassP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean glassShaderFailed = false;
    private int backdropRev = -1;         // blurred 对应的 bgRev（-1=失效重抓）
    /* 滚动期：和底栏同一套——每 40ms 重抓一次背景（anchor.draw 同步，~17ms），
       让顶栏玻璃实时跟随栏下内容；停手 200ms 后自然停。 */
    private long scrollIdleUntil, lastScrollCapMs;
    private boolean scrollingNow;
    private final Paint blurP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bloomP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float capOffX, capOffY;       // 抓取矩形原点（本视图坐标；霜映射基准）
    /** 霜链重活（buildFrostBitmap）后台线程，主线程只赋值 + invalidate（同底栏）。 */
    private final Handler frostWorker;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /* BloomStroke 光源随重力（同 LensBarView 的 DeviceTilt） */
    private SensorManager sensorManager;
    private float gravityX, gravityY;

    /* ---- 绘制 ---- */
    private final Paint tintP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairlineP = new Paint(Paint.ANTI_ALIAS_FLAG);  // is-scrolled 发丝线
    private final Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);    // 非玻璃模式 1px 底边
    private final Paint iconP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nameP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient gradShadow;

    /* 图标 path（web-src/html/sprite.svg 原文，24×24 viewBox，22dp 呈现）。
       顺序：0=菜单 1=返回 2=搜索 3=太阳 4=月亮。i-search/i-sun 的圆用 C 前缀
       （cx,cy,r），与 LensBarView 的 mini 语法同源。 */
    private static final String[][] ICON_PATHS = {
        { "M3 6h18M3 12h18M3 18h13" },
        { "m15 6-6 6 6 6" },
        { "C11,11,7", "m20 20-3.5-3.5" },
        { "C12,12,4.2",
          "M12 2v2.4M12 19.6V22M4.2 4.2l1.7 1.7M18.1 18.1l1.7 1.7M2 12h2.4M19.6 12H22M4.2 19.8l1.7-1.7M18.1 5.9l1.7-1.7" },
        { "M20.5 14.2A8.5 8.5 0 1 1 9.8 3.5a6.8 6.8 0 0 0 10.7 10.7Z" },
    };
    private Path[][] iconPaths = new Path[5][];

    /* 配色（对齐 01-tokens.css，dark 由 setDark 整套切换） */
    private int cSurface, cTint, cText, cText2, cText3, cHairline, cBorder, cShadowBase;

    private int pressedBtn = -1;          // 按压中的按钮 idx（按压反馈：底色 + 缩放）

    /* setPorterDuffXfermode 在 API 34 的 android.jar 已移除 → setXfermode + 缓存常量 */
    private static final Xfermode XF_ADD = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    public AppBarView(Context context, WebView webView) {
        super(context);
        this.webView = webView;
        this.density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        android.os.HandlerThread worker = new android.os.HandlerThread("appbar-frost");
        worker.start();
        frostWorker = new Handler(worker.getLooper());
        if (Build.VERSION.SDK_INT >= 33) {
            glassShader = tryShader(LiquidGlassShaders.REFRACTION, "顶栏折射");
            bloomShader = tryShader(LiquidGlassShaders.BLOOM_STROKE_DUAL, "顶栏 BloomStroke");
            bloomP.setXfermode(XF_ADD);
        }
        initIcons();
        applyColors();
    }

    /** RuntimeShader 软着陆构造：失败 → null（对应路径退位图绘制）。 */
    private static RuntimeShader tryShader(String src, String tag) {
        try {
            return new RuntimeShader(src);
        } catch (Throwable t) {
            Log.w(TAG, tag + " AGSL 编译失败，回退模糊位图", t);
            return null;
        }
    }

    /* ================= 挂载 ================= */

    public static AppBarView attach(Activity act, ViewGroup root, WebView webView) {
        AppBarView v = new AppBarView(act, webView);
        v.anchor = root;
        int h = (int) (170 * act.getResources().getDisplayMetrics().density);
        PopupWindow p = new PopupWindow(v, ViewGroup.LayoutParams.MATCH_PARENT, h, false);
        p.setClippingEnabled(false);
        p.setBackgroundDrawable(null);
        p.setElevation(0);
        v.popup = p;
        v.showPopup();
        /* 提前抓一次背景：否则首帧 onDraw 时 blurred 还是 null，会先铺兜底色再等
           异步补抓 —— 那几十毫秒正是「顶栏突然变白」的窗口（2026-11-07）。 */
        v.postDelayed(v::captureBackdrop, 120L);
        return v;
    }

    private void showPopup() {
        if (popup == null || popup.isShowing() || detached || anchor == null) return;
        anchor.post(new Runnable() {
            int tries = 0;
            @Override public void run() {
                if (detached || popup.isShowing()) return;
                if (anchor.getWindowToken() != null) {
                    try {
                        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, 0, 0);
                    } catch (Throwable t) {
                        Log.w(TAG, "PopupWindow 显示失败", t);
                    }
                    return;
                }
                if (++tries <= 25) anchor.postDelayed(this, 200);
                else Log.w(TAG, "等待窗口 token 超时，未显示");
            }
        });
    }

    /** 显隐唯一入口：搜索展开 / 抽屉在场 / 开关关闭 → 整窗退位（HTML 顶栏显形）。 */
    private void updatePopupVisibility() {
        boolean hide = searchOpen || overlaid || nativeBar == 0;
        if (hide) {
            if (popup != null && popup.isShowing()) {
                try { popup.dismiss(); } catch (Throwable ignored) { }
            }
        } else {
            showPopup();
        }
    }

    /** Activity 销毁时收掉：窗、延迟任务一次清干净（window leak 防护，R1 同款）。 */
    public void detach() {
        detached = true;
        if (frostWorker != null) frostWorker.getLooper().quit();   // 后台霜链线程随 View 退出
        removeCallbacks(null);
        if (popup != null && popup.isShowing()) {
            try { popup.dismiss(); } catch (Throwable ignored) { }
        }
    }

    /* ================= 桥接入口（MainActivity 转发） ================= */

    /** 壁纸层（NativeBackdrop，root 直挂、在 WebView 之下）。
     *  顶栏霜图只录它 —— WebView 的 body 是白底，录整个 View 树会把白底也录进来，
     *  顶栏就成了发白横带（2026-11-07 用户截图）。 */
    private android.view.ViewGroup wallpaperSource;
    public void setWallpaperSource(android.view.ViewGroup v) { wallpaperSource = v; }

    /** 滚动中由 MainActivity 的滚动监听驱动：顶栏玻璃实时跟随栏下内容。 */
    public void setScrolling() {
        scrollIdleUntil = SystemClock.uptimeMillis() + 200L;
        invalidate();
    }

    /** R2 开关（设置页「原生顶栏」）。关 = 全不画，HTML 顶栏原样。 */
    public void setNativeBar(boolean on) {
        int n = on ? 1 : 0;
        if (nativeBar == n) return;
        nativeBar = n;
        updatePopupVisibility();
        invalidate();
        Log.i(TAG, "原生顶栏 " + (on ? "接管" : "退位（回退 HTML 顶栏）"));
    }

    /** 顶栏底边（窗口本地 px）：横屏竖排 dock 要据此让开顶部，否则顶栏玻璃会从
     *  侧栏身上横切过去（2026-10-28 用户报「侧栏和顶栏重叠」）。 */
    public float barBottomPx() { return barRect.bottom; }

    public void setDark(boolean d) {
        boolean changed = (dark != d);
        if (dark == d) return;
        dark = d;
        applyColors();
        backdropRev = -1;   // 配色整套变化 → 重抓背景 + 重建霜链
        gradShadow = null;
        invalidate();
    }

    /**
     * 状态推送（32-boot 的观察器合并 30ms 后调用；JSON 在桥线程解析后直接改字段，
     * UI 无关 —— 绘制走 invalidate，字段的可见性由 Android 单线程模型保证）。
     * 字段缺省 = 维持原值，页面旧包/部分缺失不致崩。
     */
    public void setState(String json) {
        try {
            JSONObject o = new JSONObject(json);
            boolean dirty = false;
            String n = o.optString("name", null);
            String s = o.optString("sub", null);
            if (n != null && !n.equals(name)) { name = n; dirty = true; }
            if (s != null && !s.equals(sub)) { sub = s; dirty = true; }
            boolean b = o.optBoolean("navBack", navBack);
            if (b != navBack) { navBack = b; dirty = true; }
            b = o.optBoolean("themeMoon", themeMoon);
            if (b != themeMoon) { themeMoon = b; dirty = true; }
            b = o.optBoolean("scrolled", scrolled);
            if (b != scrolled) { scrolled = b; dirty = true; }
            b = o.optBoolean("glass", glassOn);
            if (b != glassOn) { glassOn = b; dirty = true; backdropRev = -1; }
            b = o.optBoolean("search", searchOpen);
            if (b != searchOpen) { searchOpen = b; dirty = true; }
            b = o.optBoolean("overlaid", overlaid);
            if (b != overlaid) { overlaid = b; dirty = true; }
            int rev = o.optInt("bgrev", bgRev);
            if (rev != bgRev) {
                bgRev = rev; backdropRev = -1;
                /* ❗必须显式发起：captureBackdrop 的调用点只有 boot/watchdog/抓后补链/
                   blurred==null 兜底——bgrev 变化后无人调用=霜图冻结在旧壁纸
                   （2026-11-09 用户报「切壁纸顶栏延迟、跨代不更新」的最终根因）。 */
                post(this::captureBackdrop);
            }
            if (dirty) {
                updatePopupVisibility();
                invalidate();
            }
        } catch (Throwable t) {
            Log.w(TAG, "状态解析失败", t);
        }
    }

    /**
     * 几何自测（P0-2，2026-10-31）：顶栏矩形不再向 WebView 索取，原生按 density
     * 常量自己算 —— HTML 顶栏因此可整块移除，旋转/冷启动也不再有「量到旧布局」
     * 的时序问题（2026-10-30 横屏玻璃带只有 1904px 的根因）。
     *
     * 横向常量按 CSS 的宽度断点折算（.appbar__row 的行盒 + padding + 44dp 按钮 + 4dp gap）：
     *   行内边距：<900dp → 0 6px（03-layout.css:51）；>=900dp → 0 18px（05-views.css:454）；
     *             >=1200dp → 0 22px（05-views.css:478）；
     *   行盒：>=600dp 起 max-width 720dp 居中（05-views.css:432），>=900dp 取消封顶。
     *   实测设备（~950dp 宽）即 pad=18 那一档，代入得 nav 中心距左 40dp /
     *   theme 中心距右 40dp / search 中心距右 88dp / 标题块左 66dp ——
     *   与改动前 JS 量 DOM 得到的值一致。
     *   命中区仍放宽到 48dp 见方（CSS .ibtn 是 44dp，图标中心不变）。
     *
     * 纵向：barRect 覆盖 .appbar 整体（含 2.5dp 进度条 + 1dp 底边框），但标题与三键
     * 只居中在 54dp 内容行里（否则会整体下移 1.75dp）。
     *
     * @param statusTopPx 状态栏（含刘海）高度，物理 px；来自 Insets systemBars|cutout
     */
    public void applySelfGeometry(int statusTopPx) {
        if (detached) return;
        float d = density;
        float barH = barHeightPx();
        float rowH = barH - CHROME_EXTRA_DP * d;   // .appbar__row 内容行高（54dp / 46dp）
        float top = Math.max(0f, statusTopPx);
        int sw = getResources().getDisplayMetrics().widthPixels;

        barRect.set(0f, 0f, sw, top + barH);
        float cy = top + rowH / 2f;
        float btn = 48f * d;            // 命中区边宽（48dp 见方）
        float half = btn / 2f;

        /* 横向：.appbar__row 的行盒在四个断点下不同 —— 先求行盒（rowLeft / rowW），
           再在行盒坐标系里套「pad + 半个 44dp 按钮」得键中心。padDp 是 dp，展开成
           px 必须乘 density；行盒位置也用 px。 */
        float wDp = sw / d;
        float padDp = wDp >= 1200f ? 22f : (wDp >= 900f ? 18f : 6f);   // 行内边距
        float pad = padDp * d;
        /* >=600dp 起行盒封顶 720dp 并居中（05-views.css:432），>=900dp 起取消封顶
           （05-views.css:454/478）—— 只有 720~899dp 档 rowLeft 非零（8" 平板竖屏 /
           分屏会落在这里），此时三键与标题都得整体右移 (sw-720dp)/2。 */
        float rowW = (wDp >= 600f && wDp < 900f) ? Math.min(sw, 720f * d) : sw;
        float rowLeft = (sw - rowW) / 2f;
        float navCx = rowLeft + pad + 22f * d;                  // 行左 + pad + 半格
        float themeCx = rowLeft + rowW - (pad + 22f * d);       // 行右 - pad - 半格
        float searchCx = themeCx - 48f * d;                     // 相邻一格：44dp 按钮 + 4dp gap
        btnRects[0] = new RectF(navCx - half, cy - half, navCx + half, cy + half);
        btnRects[1] = new RectF(searchCx - half, cy - half, searchCx + half, cy + half);
        btnRects[2] = new RectF(themeCx - half, cy - half, themeCx + half, cy + half);
        /* 标题块左 = 行左 + pad + 44dp 按钮 + 4dp gap（紧接 nav 按钮之后，不盖按钮）。
           右边界 = search 命中区左侧再让 8dp（CSS gap 4dp + 4dp 呼吸）。注意：
           绘制目前只消费 left/centerY（drawTitle 不裁剪、不省略），现有文案实测
           均在界内；若将来出现超长标题压到搜索键，再补 measureText 省略号。 */
        titleRect.set(rowLeft + pad + 48f * d, top, btnRects[1].left - 8f * d, top + rowH);

        hasGeometry = true;
        /* 窗口高度收紧到「栏 + 阴影」：attach 时给的 170dp 只是几何未知前的余量，
           但 PopupWindow 内的触摸全归本窗（非按钮区转发给 WebView），多出来的空白
           会吃掉下方内容的点击 —— 2026-10-27 真机：原生阶段页顶部按钮点不动。
           宽度必须给绝对 px：PopupWindow.update 对 MATCH_PARENT(-1) 的处理不可靠，
           旋转后窗口会停在旧宽度把画布裁掉（2026-10-30 真机横屏右侧裸壁纸硬缝）。 */
        try {
            if (popup != null) {
                popup.update(0, 0, sw, (int) (barRect.height() + 30f * d));
            }
        } catch (Throwable ignored) { }
        backdropRev = -1;   // 几何变了 → 背景区域变了 → 重抓
        gradShadow = null;
        invalidate();
        Log.i(TAG, String.format(
                "几何自测 栏(%.0f,%.0f %.0f×%.0f) 标题x=%.0f 三键x=%.0f/%.0f/%.0f",
                barRect.left, barRect.top, barRect.width(), barRect.height(),
                titleRect.left,
                btnRects[0].centerX(), btnRects[1].centerX(), btnRects[2].centerX()));
    }

    /**
     * 栏总高（物理 px）= .appbar 整体矩形高度，与改动前 JS 量 DOM 的口径一致：
     *   57.5dp = 54dp 内容行（--appbar-h）+ 2.5dp 进度条 + 1dp 底边框；
     * 矮屏横屏 CSS 把 --appbar-h 压到 46px（05-views.css
     * 「@media (max-height:460px) and (orientation:landscape)」），对应 49.5dp。
     * 3.5dp 那截不能省：只画内容行会让栏底露一条未处理的壁纸缝。
     */
    private float barHeightPx() {
        float dp = 57.5f;                                   // 54dp 内容行 + 2.5 + 1
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            boolean landscape = dm.widthPixels > dm.heightPixels;
            float hDp = dm.heightPixels / density;
            if (landscape && hDp <= 460f) dp = 49.5f;       // 46dp 内容行 + 2.5 + 1
        } catch (Throwable ignored) { }
        return dp * density;
    }

    /* ================= 背景（PixelCopy 采样） =================
     * 采样时机决定「通透是不是真的」：只在路由切换/滚动越阈值时抓一张，同页滚动
     * 时玻璃纹丝不动（2026-10-30 用户：「背景一直固定在那儿，通透在哪儿」）。
     * 因此加了滚动驱动重抓（JS 侧 120ms 节流 bump bgrev），滚动时玻璃跟着栏下内容走。
     * 系统级 FLAG_BLUR_BEHIND 试过：PopupWindow 拿不到窗口对象，走不通。 */

    /** 预热：几何同步后先抓一张，把首次成本摊在用户看见之前。 */
    public void warmUp() {
        post(this::captureBackdrop);
    }

    /** 非按钮区手势透传中（DOWN 起，UP/CANCEL 止）：整段转发给内容层，
     *  否则只发 DOWN 不发 UP，内容层永远收不到 click（2026-10-31 实测）。 */
    private boolean passthrough = false;

    private volatile boolean copyBusy = false;     /* PixelCopy 在途标志：onDraw 兜底路径没有它
                                                      会每帧发一次抓图请求（抓图风暴） */
    /** P2-1：霜图裁剪源矩形复用（onDraw 内 new Rect 的逐帧分配换成了 set）。 */
    private final Rect frostSrcRect = new Rect();

    /** 外部强制重抓：设置面板关闭/换壁纸时调用——面板开着的那次抓样把半透明遮罩
     *  和上一页头部一起采了进去（2026-10-30 用户报「玻璃里隐隐有网络学习手册 2.0」）。
     *  ❗2026-11-09：原来只置 backdropRev=-1 不发起——blurred!=null 后 onDraw 兜底
     *  不再走抓帧分支，失效位永远无人消费（壁纸换代顶栏冻结的根因之一）。 */
    public void invalidateBackdrop() {
        backdropRev = -1;
        post(this::captureBackdrop);
        invalidate();
    }

    /** 快照转场保持（2026-11-09）：true 期间暂停抓帧，收尾由 notifyBarsSwitchSettled 放行。 */
    public void setTransitionHold(boolean hold) { transitionHold = hold; }
    private volatile boolean transitionHold = false;

    private void captureBackdrop() {
        /* transitionHold：快照转场覆盖层存活期间不抓帧——覆盖层在主窗内，
           抓到的是旧页/混合像素（2026-11-09 用户报「顶栏有延迟」的缝隙之一），
           转场收尾由 MainActivity.notifyBarsSwitchSettled 统一放行+重抓。 */
        if (detached || copyBusy || transitionHold || !glassOn || !hasGeometry || backdropRev == bgRev) return;
        if (Build.VERSION.SDK_INT < 26) return;   // PixelCopy.Window 需 26+；低版本走实色降级
        Activity act = (getContext() instanceof Activity) ? (Activity) getContext() : null;
        if (act == null || act.getWindow() == null) return;
        int[] vl = new int[2];
        getLocationOnScreen(vl);
        /* 外扩 24dp（= 栏缘折射带）：折射要采栏外内容，CLAMP 边缘会成拉丝。
           源=主 Activity 窗口，顶栏视图虽只到栏底，窗口坐标能取到栏外。 */
        int pad = (int) (24f * density);
        android.view.View decor = act.getWindow().getDecorView();
        int l = Math.max(0, (int) barRect.left + vl[0] - pad);
        int top = Math.max(0, (int) barRect.top + vl[1] - pad);
        int r = Math.min(decor.getWidth(), (int) barRect.left + vl[0] + (int) barRect.width() + 1 + pad);
        int bot = Math.min(decor.getHeight(), (int) barRect.top + vl[1] + (int) barRect.height() + 1 + pad);
        int w = Math.max(4, r - l), h = Math.max(4, bot - top);
        /* 抓取原点换到本视图坐标（霜 localMatrix 的映射基准；外扩被边界裁掉时
           原点可为负，BitmapShader 映射天然支持） */
        final int capOffL = l - vl[0], capOffT = top - vl[1];
        final int revAtReq = bgRev;   /* 在途期间 bgrev 再变 → 完成后补抓（丢更新竞态） */
        final Handler copyHandler = frostWorker;   // 回调落后台线程：霜链重活不占主帧
        final Runnable watchdog = () -> {
            /* PixelCopy 回调可能被丢（切后台/旋转时 surface 撕掉）——copyBusy 一旦
               卡死，玻璃就冻结在最后一次成功采样直到重启（2026-10-30 真机实测）。
               700ms 没回来视为丢失：解除闩锁并重试一次。 */
            if (copyBusy && !detached) {
                copyBusy = false;
                post(this::captureBackdrop);
            }
        };
        final long capT0 = System.nanoTime();
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            copyBusy = true;
            copyHandler.postDelayed(watchdog, 700);
            /* ❗改成和底栏同源：直接用 anchor.draw 同步录 View 树，不再走 PixelCopy。
               PixelCopy 依赖窗口 surface，实测在真机上经常拿不到（result 非 SUCCESS
               或回调丢失），霜图永远为 null → 顶栏只能铺兜底色，就是用户看到的那块白
               （2026-11-07 用户点破：「底栏都已经实时渲染，顶栏为什么不能」）。
               anchor.draw 不依赖 surface，底栏已长期验证可用；录制只在几何/主题/壁纸
               变化时发生（顶栏背景是静态的），一次约十几毫秒，可接受。 */
            try {
                Canvas capCanvas = new Canvas(out);
                capCanvas.translate(-l, -top);
                /* 自定义壁纸走 WebView .wall（原生壁纸层对自定义为 GONE）——
                   采样源不可见时回退整窗录制，否则霜图采到空层=顶栏丢壁纸
                   （2026-11-09 相册壁纸回归时补）。 */
                if (wallpaperSource != null && wallpaperSource.getVisibility() == android.view.View.VISIBLE)
                    wallpaperSource.draw(capCanvas);
                else anchor.draw(capCanvas);
            } catch (Throwable t) {
                Log.w(TAG, "anchor.draw 录顶栏背景失败", t);
                copyBusy = false;
                out.recycle();
                return;
            }
            copyHandler.post(() -> {
                        copyHandler.removeCallbacks(watchdog);
                        if (detached) {
                            copyBusy = false;
                            out.recycle();
                            return;
                        }
                        final long buildT0 = System.nanoTime();
                        final Bitmap frost = buildFrostBitmap(out);
                        out.recycle();   // 原帧只作霜链输入，不留驻（省一张全分辨率常驻）
                        final long buildMs = (System.nanoTime() - buildT0) / 1_000_000L;
                        copyBusy = false;   // 解闩先于主线程赋值：间隙期的新请求不被吞
                        mainHandler.post(() -> {   // 主线程：只赋值 + 重建 shader
                            if (detached) {
                                if (frost != null) frost.recycle();
                                return;
                            }
                            capOffX = capOffL;
                            capOffY = capOffT;
                            if (frost != null) {
                                if (blurred != null) blurred.recycle();
                                blurred = frost;
                            }   // frost==null：霜链失败，保旧霜（rev 仍推进防重抓风暴）
                            buildGlassShader();
                            if (buildMs + (System.nanoTime() - capT0) / 1_000_000L > 6) {
                                Log.i(TAG, "抓取+霜链 " + w + "x" + h + " 霜链" + buildMs + "ms");
                            }
                            backdropRev = revAtReq;
                            invalidate();
                            if (bgRev != revAtReq) post(this::captureBackdrop);
                        });
            });
        } catch (Throwable t) {
            copyBusy = false;
            copyHandler.removeCallbacks(watchdog);
            Log.w(TAG, "抓背景异常", t);
        }
    }

    /** 霜链（与底栏 buildFrost 同构的 CPU 等价实现）：÷2 记录档 →
     *  vibrancy(sat 1.5) → 两级降采样 blur 4dp。顶栏背景以壁纸为主
     *  （设计约束：内容不滚进顶栏底下），不做亮度归一化 shim。
     *  ❗在 frostWorker 线程跑：局部 Paint（blurP 主线程回退绘制在用）；
     *  纯函数无字段突变，调用方负责位图生命周期。 */
    /** 采样 64 点判断是否近乎纯白（加载页/空白页）。 */
    private static boolean isNearlyBlank(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w < 8 || h < 8) return true;
        long sum = 0;
        int n = 0;
        for (int i = 1; i <= 8; i++) {
            for (int j = 1; j <= 8; j++) {
                int c = b.getPixel(w * i / 9, h * j / 9);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
                sum += (r * 299 + g * 587 + bl * 114) / 1000L;
                n++;
            }
        }
        return n > 0 && (sum / n) >= 247;
    }

    private Bitmap buildFrostBitmap(Bitmap raw) {
        try {
            if (raw == null || raw.isRecycled()) return blurred;
            /* ❗抓到「几乎全白」的图不要采用 —— 那是 WebView 的加载页（OFFLINE READY）。
               attach 后 120ms 就抓，此时页面还没渲染完，霜图会是一张白图，
               顶栏就永远铺着白雾；而 bgRev 不变又不会重抓（2026-11-07 用户截图证实）。
               返回 null 让主线程「保旧霜」，同时兜底分支会把 backdropRev 打回 -1，
               于是下一帧继续尝试，等页面加载完成自然抓到真实内容。 */
            if (isNearlyBlank(raw)) return null;
            /* 顶栏霜度 4dp：与底栏 buildFrost 同档（2026-11-09 用户报「越修越糊」
               ——16dp 矫枉过正）。「配不配」的问题由饱和自适应（亮壁纸 1.05）
               承担，模糊轴回到与 dock 一致；字影由 sticky 根治，与霜度无关。 */
            float sigma = 4f * density * LiquidGlassShaders.BLUR_RADIUS_TO_SIGMA;
            int exp = LiquidGlassShaders.downScaleExpFor(sigma * sigma);
            int sf = 1 << exp;
            int fw = Math.max(2, raw.getWidth() / sf), fh = Math.max(2, raw.getHeight() / sf);
            Bitmap half = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
            Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            /* 顶栏配壁纸（2026-11-09 用户报「顶栏跟壁纸不配」）：饱和提升按底图
               亮度自适应——1.5× 是深色壁纸时代调的味，亮蓝天壁纸直接被拉成艳蓝。
               均值取自抓帧原图顶条（栏下 sticky 无内容，即壁纸天区）；
               ≥192（亮壁纸）饱和降到 1.05；160~192 线性过渡；暗端原味 1.5。 */
            float sat = 1.5f;
            {
                int sw = raw.getWidth(), sh = Math.min(raw.getHeight(), 80);
                int[] spix = new int[sw * sh];
                raw.getPixels(spix, 0, sw, 0, 0, sw, sh);
                long lumSum = 0; int lumN = 0;
                for (int v : spix) {
                    int r = (v >> 16) & 0xFF, g = (v >> 8) & 0xFF, b = v & 0xFF;
                    lumSum += (r * 299 + g * 587 + b * 114) / 1000; lumN++;
                }
                if (lumN > 0) {
                    float mean = lumSum / (float) lumN;
                    if (mean >= 192f) sat = 1.05f;
                    else if (mean >= 160f) sat = 1.5f - (mean - 160f) / 32f * 0.45f;
                }
            }
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(sat);
            bp.setColorFilter(new ColorMatrixColorFilter(cm));
            new Canvas(half).drawBitmap(raw, null, new RectF(0, 0, fw, fh), bp);
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
        } catch (Throwable t) {
            /* ❗返回 null（不能返回 blurred——主线程会先 recycle 旧图再赋值，
               返回旧图 = 赋值一个已回收位图）。主线程见 null 保旧霜。 */
            Log.w(TAG, "霜链构建失败，沿用旧霜", t);
            return null;
        }
    }

    /** 组装栏体玻璃：noise(frost) → 栏缘 REFRACTION（24dp/24dp，同底栏 drawBarGlass）。
     *  方角栏 cornerRadii=0；霜半尺寸，localMatrix 把视图坐标映到霜像素。 */
    private void buildGlassShader() {
        /* 「无」档：不组装折射 shader——onDraw 走 blurred 霜图+染色的平绘回退 */
        if (NativeBlocks.glassNone()) { glassShader = null; return; }
        if (glassShaderFailed || blurred == null || barRect.width() <= 4) return;
        if (Build.VERSION.SDK_INT < 33 || glassShader == null) return;
        try {
            BitmapShader frostShader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix mm = new Matrix();
            mm.setScale(2f, 2f);
            mm.postTranslate(capOffX, capOffY);
            frostShader.setLocalMatrix(mm);
            /* 霜链已在 CPU 侧完成；只挂单层位图（零嵌套 input shader）。 */
            glassShader.setInputShader("content", frostShader);
            float W = barRect.width(), H = barRect.height();
            glassShader.setFloatUniform("size", W, H);
            /* centeredCoord=(coord+offset)−halfSize 要等于 coord−栏中心
               ⇒ offset = −栏左上（❗负号：视图坐标直画，SDF 零点对准栏中心） */
            glassShader.setFloatUniform("offset", -barRect.left, -barRect.top);
            glassShader.setFloatUniform("cornerRadii", 0f, 0f, 0f, 0f);
            glassShader.setFloatUniform("refractionHeight", 24f * density);
            glassShader.setFloatUniform("refractionAmount", -24f * density);
            glassShader.setFloatUniform("depthEffect", 0f);
            glassP.setShader(glassShader);
            invalidate();
        } catch (Throwable t) {
            glassShaderFailed = true;      /* 编译/组装失败：永久回退到位图绘制 */
            glassP.setShader(null);
            Log.w(TAG, "顶栏玻璃 shader 组装失败，回退模糊位图", t);
        }
    }

    /* ================= 配色与图标 ================= */

    /** 亮/暗两套，逐值对齐 01-tokens.css（--glass-tint-* / --hairline / --border）。 */
    private void applyColors() {
        if (dark) {
            cSurface = Color.argb(255, 28, 28, 30);          // --surface #1c1c1e
            cTint = Color.argb(66, 28, 28, 30);              // 深色 α0.26：同比例减淡
            cText = Color.argb(255, 245, 245, 247);          // --text #f5f5f7
            cText2 = Color.argb(255, 199, 199, 204);         // --text-2 #c7c7cc
            cText3 = Color.argb(255, 142, 142, 147);         // --text-3 #8e8e93
            cHairline = Color.argb(26, 255, 255, 255);       // --hairline rgba(255,255,255,.10)
            cBorder = Color.argb(255, 56, 56, 58);           // --border #38383a
            cShadowBase = Color.argb(97, 0, 0, 0);           // --glass-shadow rgba(0,0,0,.38) 首 stop
        } else {
            cSurface = Color.WHITE;                          // --surface #ffffff
            cTint = Color.argb(38, 244, 244, 244);           // α0.15：40% 白叠明亮蓝天=白横带（用户主诉），减到 0.15 保通透
            cText = Color.argb(255, 29, 29, 31);             // --text #1d1d1f
            cText2 = Color.argb(255, 81, 81, 84);            // --text-2 #515154
            cText3 = Color.argb(255, 134, 134, 139);         // --text-3 #86868b
            cHairline = Color.argb(18, 0, 0, 0);             // --hairline rgba(0,0,0,.07)
            cBorder = Color.argb(255, 227, 227, 232);        // --border #e3e3e8
            cShadowBase = Color.argb(26, 15, 23, 42);        // rgba(15,23,42,.10)
        }
        iconP.setColor(cText2);
        nameP.setColor(cText);
        subP.setColor(cText3);
        hairlineP.setColor(cHairline);
        borderP.setColor(cBorder);
        pressP.setColor(dark ? Color.argb(23, 255, 255, 255) : Color.argb(13, 0, 0, 0));
        fallbackP.setColor(cSurface);
        /* ❗兜底不画死白：blurred 还没抓到（首帧/重抓失败）时若铺 100% 白，
           顶栏会突然变成一块白板（2026-11-07 用户报「小概率变白底」）。
           留 12% 透光，视觉上是浅色玻璃而不是换了块板。 */
        fallbackP.setAlpha(224);
        tintP.setColor(cTint);   // surface tint α0.4（画在折射之后，与底栏同序）
        nameP.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));   // CSS 800 → Roboto 700 就近
        nameP.setTextSize(15.5f * density);
        subP.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        subP.setTextSize(10.5f * density);
    }

    /** 图标 Path 初始化：androidx PathParser 吃 sprite.svg 的 d 原文（R1 同法）。 */
    private void initIcons() {
        float s = 22f * density / 24f;   // viewBox 24 → 22dp（.icon 22×22）
        for (int i = 0; i < ICON_PATHS.length; i++) {
            Path[] subs = new Path[ICON_PATHS[i].length];
            for (int k = 0; k < ICON_PATHS[i].length; k++) {
                String seg = ICON_PATHS[i][k];
                Path p = new Path();
                if (seg.startsWith("C")) {               // C cx,cy,r → 圆
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
        iconP.setStrokeWidth(1.7f * s);                  // SVG stroke-width 1.7（24 系）
    }

    /* ================= 触摸 ================= */

    private static final float HIT_SLOP_DP = 4f;

    /** 命中测试：三个按钮（外扩 4dp 容差）。返回 -1 = 非按钮区。 */
    private int hitButton(float x, float y) {
        if (!hasGeometry) return -1;
        for (int i = 0; i < 3; i++) {
            RectF r = btnRects[i];
            if (r == null) continue;
            if (x >= r.left - HIT_SLOP_DP * density && x <= r.right + HIT_SLOP_DP * density
                    && y >= r.top - HIT_SLOP_DP * density && y <= r.bottom + HIT_SLOP_DP * density) {
                return i;
            }
        }
        return -1;
    }

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

    /** 非按钮区触摸 → 转发给活动根布局（谁在最上层就点谁）。
     *  原实现固定转发 WebView：原生页时代，栏下 30dp 阴影带与标题区的点击
     *  全被吞（WebView 在原生页下面是空的）——2026-10-31 实测「速查表」行
     *  在 y=274 点不动即此。 */
    private void forwardToContent(MotionEvent e) {
        if (anchor == null) { forwardToWebView(e); return; }
        int[] tl = new int[2];
        int[] vl = new int[2];
        anchor.getLocationOnScreen(tl);
        this.getLocationOnScreen(vl);
        MotionEvent e2 = MotionEvent.obtain(e);
        e2.offsetLocation(vl[0] - tl[0], vl[1] - tl[1]);
        try {
            anchor.dispatchTouchEvent(e2);
        } catch (Throwable t) {
            Log.w(TAG, "转发触摸到内容层失败", t);
        } finally {
            e2.recycle();
        }
    }

    /** 按钮动作 = 触发对应 HTML 按钮的 click()：抽屉/返回/搜索/主题的全部既有
     *  逻辑留在页面侧，原生只接管观感。visibility:hidden 的元素收不到真触摸，
     *  必须程序化 click（事件处理器照常执行）。 */
    private void fire(int idx) {
        if (webView == null) return;
        /* 主题键走原生翻转：不依赖 HTML btnTheme 闭包（点击链路曾静默失效）。
           顺序必须先 JS store.set 再 pushTheme——pushTheme 的 apply() 读 store，
           反了会被旧值翻回（2026-11-08）。 */
        if (idx == 2) {
            themeFlip.run();
            return;
        }
        String id = idx == 0 ? "btnNav" : "btnSearch";
        String js = "(function(){try{var b=document.getElementById('" + id + "');"
                + "if(b)b.click();}catch(e){}})()";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "触发按钮失败", t);
        }
    }

    /** 主题二态翻转（由 MainActivity 注入：fire(2) 时执行）。 */
    public Runnable themeFlip = () -> { };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (searchOpen || overlaid || nativeBar == 0) {
            /* 理论上退位期整窗已 dismiss；万一事件仍进来（时序窗），按透传处理 */
            forwardToContent(e);
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int idx = hitButton(e.getX(), e.getY());
                if (idx < 0) {
                    /* 非按钮区（标题/留白/栏下阴影带）：整段手势透传给内容层，
                       语义与「顶栏不存在」一致（DOWN 必须配 UP，见 passthrough 注释） */
                    passthrough = true;
                    forwardToContent(e);
                    return true;
                }
                pressedBtn = idx;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (passthrough) { forwardToContent(e); return true; }
                if (pressedBtn >= 0) {
                    if (hitButton(e.getX(), e.getY()) != pressedBtn) {
                        pressedBtn = -1;   // 滑出取消
                        invalidate();
                    }
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (passthrough) {
                    passthrough = false;
                    forwardToContent(e);
                    return true;
                }
                int idx = pressedBtn;
                pressedBtn = -1;
                invalidate();
                if (idx >= 0 && e.getActionMasked() == MotionEvent.ACTION_UP
                        && hitButton(e.getX(), e.getY()) == idx) {
                    fire(idx);
                }
                return idx >= 0;
            }
        }
        return false;
    }

    /* ================= 绘制 ================= */

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (nativeBar == 0 || searchOpen || overlaid) return;   // 退位期整层不画
        if (!hasGeometry) return;                                // 几何未到：全透明等待
        long t0 = System.nanoTime();

        /* 1. 背景：Kyant0 材质链（霜→噪声→栏缘折射 + tint α0.4 + BloomStroke）；
              实色 surface（玻璃关）。背景静态（内容不滚进顶栏底下），
              只按 bgRev/主题/几何重抓。z 序照搬上游：霜链 → surface → highlight。 */
        if (glassOn) {
            /* ❗不做滚动实时重抓：.appbar 改 sticky 后内容不会滚进顶栏下方，
               栏后是静态壁纸 —— 没有可实时渲染的变化，重抓只白花 17ms/次的帧预算
               （实测 99th 从 65ms 涨回 200ms）。背景更新仍走 bgRev 触发
               （切页 / 主题 / 壁纸变化），这才是「实时」真正有意义的地方。 */
            if (glassP.getShader() != null) {
                canvas.drawRect(barRect, glassP);
                canvas.drawRect(barRect, tintP);
                if (bloomShader != null && !NativeBlocks.glassNone()) {
                    BloomStrokeFx.draw(bloomShader, bloomP, canvas,
                            barRect.left, barRect.top, barRect.right, barRect.bottom,
                            0f, 0.75f, -45f, gravityX, gravityY, density);
                }
            } else if (blurred != null) {
                /* 霜含 24dp 外扩（视图原点在霜的 (−capOffX,−capOffY)/2 处）：
                   回退绘制必须裁出栏对应子区，整张拉伸会错位。
                   P2-1（2026-11-09）：Rect 提字段复用——minSdk 24 旧机此分支每帧
                   4 次 Math.round + new Rect，绘制线程常态命中。 */
                frostSrcRect.set(
                        Math.round((barRect.left - capOffX) / 2f),
                        Math.round((barRect.top - capOffY) / 2f),
                        Math.round((barRect.right - capOffX) / 2f),
                        Math.round((barRect.bottom - capOffY) / 2f));
                canvas.drawBitmap(blurred, frostSrcRect, barRect, blurP);
                canvas.drawRect(barRect, tintP);
            } else {
                /* ❗blurred 为空 = 霜图没抓到。此时必须把 backdropRev 打回 -1 才可能重抓：
                   captureBackdrop 的入口条件是 `backdropRev == bgRev → return`，而失败路径
                   也会把 backdropRev 推进到 revAtReq（见 :492「rev 仍推进防重抓风暴」），
                   于是 bgRev 不变 → 永远跳过 → 白底常驻，重启才恢复（2026-11-07 用户报
                   「还是会复现」）。copyBusy 会挡住并发请求，不会洪泛。 */
                backdropRev = -1;
                captureBackdrop();
                canvas.drawRect(barRect, fallbackP);
            }
            /* 玻璃海拔阴影：栏底向下的长影（glass-shadow 0 8px 28px 的顶栏份） */
            float shH = 28f * density;
            ensureShadow(shH);
            canvas.drawRect(barRect.left, barRect.bottom, barRect.right,
                    barRect.bottom + shH, shadowP);
        } else {
            canvas.drawRect(barRect, fallbackP);
            canvas.drawRect(barRect.left, barRect.bottom - density,
                    barRect.right, barRect.bottom, borderP);
        }
        if (scrolled) {
            /* is-scrolled：栏底发丝线（apple 风格：滚动后才出现） */
            canvas.drawRect(barRect.left, barRect.bottom - density,
                    barRect.right, barRect.bottom, hairlineP);
        }

        /* 2. 内容：导航键 / 标题两行 / 搜索键 / 主题键 */
        drawButton(canvas, 0, navBack ? 1 : 0);
        drawButton(canvas, 1, 2);
        drawButton(canvas, 2, themeMoon ? 4 : 3);
        drawTitle(canvas);

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms > 8) Log.i(TAG, "onDraw " + ms + "ms");   // 正常 <2ms；异常留痕
    }

    /* BloomStroke 光源随重力：传感器监听（同 LensBarView 的 DeviceTilt） */

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

    private void ensureShadow(float h) {
        if (gradShadow == null) {
            gradShadow = new LinearGradient(0, barRect.bottom, 0, barRect.bottom + h,
                    cShadowBase, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            shadowP.setShader(gradShadow);
        }
    }

    /** 画一个按钮：图标居中于命中区；按压态 = 圆角底 + 图标缩放 .86
     *  （对齐 .ibtn:active 的 scale(.86)+surf-2）。 */
    private void drawButton(Canvas canvas, int idx, int iconIdx) {
        RectF r = btnRects[idx];
        if (r == null) return;
        if (pressedBtn == idx) {
            float pad = 2f * density;
            canvas.drawRoundRect(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad,
                    14f * density, 14f * density, pressP);
        }
        Path[] subs = iconPaths[iconIdx];
        if (subs == null) return;
        canvas.save();
        canvas.translate(r.centerX(), r.centerY());
        float k = pressedBtn == idx ? 0.86f : 1f;
        canvas.scale(k, k);
        canvas.translate(-11f * density, -11f * density);   // 22dp 盒左上角
        for (Path p : subs) canvas.drawPath(p, iconP);
        canvas.restore();
    }

    /** 标题两行：name 15.5/800 + sub 10.5/400，块在 titleRect 内垂直居中
     *  （CSS flex column + justify-content:center 同构）。 */
    private void drawTitle(Canvas canvas) {
        if (titleRect.isEmpty()) return;
        Paint.FontMetrics fn = nameP.getFontMetrics();
        Paint.FontMetrics fs = subP.getFontMetrics();
        float nameH = fn.descent - fn.ascent;
        float subH = fs.descent - fs.ascent;
        float top = titleRect.centerY() - (nameH + subH) / 2f;
        float x = titleRect.left;
        canvas.drawText(name, x, top - fn.ascent, nameP);
        canvas.drawText(sub, x, top + nameH - fs.ascent, subP);
    }
}
