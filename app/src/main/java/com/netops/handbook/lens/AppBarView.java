package com.netops.handbook.lens;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.PopupWindow;

import org.json.JSONArray;
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
 * 几何真源在 CSS（栏矩形 / 标题块 / 三个按钮矩形全量由 JS 上报，原生层零推导
 * —— R1 的血泪教训：推导必错）。按钮动作 = 程序化 click 对应 HTML 按钮
 * （visibility:hidden 收不到真触摸），全部既有逻辑留在页面侧。
 * 回退开关：设置页「我的→原生顶栏」（localStorage netops_native_appbar，默认开）。
 */
public class AppBarView extends View {

    private static final String TAG = "AppBar";

    private final WebView webView;
    private final float density;
    private PopupWindow popup;
    private ViewGroup anchor;
    private volatile boolean detached = false;

    /* ---- 几何（本 View 坐标物理 px；JS 上报，原生层不做推导）---- */
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

    /* ---- 背景：PixelCopy → ÷2×4 下采样 → 上采样 blit 即模糊 ---- */
    private Bitmap backdrop;              // 抓到的原始背景（栏矩形区域）
    private Bitmap blurred;               // 下采样链产物
    private int backdropRev = -1;         // blurred 对应的 bgRev（-1=失效重抓）
    private final Paint blurP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ColorMatrixColorFilter saturateFilter;   // --glass-saturate 165%，常量

    /* ---- 绘制 ---- */
    private final Paint tintP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specularP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairlineP = new Paint(Paint.ANTI_ALIAS_FLAG);  // is-scrolled 发丝线
    private final Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);    // 非玻璃模式 1px 底边
    private final Paint iconP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nameP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient gradTint;      // 缓存：applyColors()/setGeometry() 时置空重建
    private RadialGradient gradSpec;
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
    private int cSurface, cTintTop, cTintBot, cText, cText2, cText3, cHairline, cBorder, cShadowBase;

    private int pressedBtn = -1;          // 按压中的按钮 idx（按压反馈：底色 + 缩放）

    public AppBarView(Context context, WebView webView) {
        super(context);
        this.webView = webView;
        this.density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.65f);
        saturateFilter = new ColorMatrixColorFilter(cm);
        blurP.setColorFilter(saturateFilter);
        initIcons();
        applyColors();
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
        removeCallbacks(null);
        if (popup != null && popup.isShowing()) {
            try { popup.dismiss(); } catch (Throwable ignored) { }
        }
    }

    /* ================= 桥接入口（MainActivity 转发） ================= */

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
        if (dark == d) return;
        dark = d;
        applyColors();
        backdropRev = -1;   // 配色整套变化 → 重抓背景 + 重建渐变缓存
        gradTint = null;
        gradSpec = null;
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
            if (rev != bgRev) { bgRev = rev; backdropRev = -1; }
            if (dirty) {
                updatePopupVisibility();
                invalidate();
            }
        } catch (Throwable t) {
            Log.w(TAG, "状态解析失败", t);
        }
    }

    /**
     * 几何上报（原生索取制的应答）。数组均为屏幕物理 px（CSS×devicePixelRatio）：
     * bar=整条（含状态栏 padding 区），title=标题块，nav/search/theme=按钮命中区。
     */
    public void setGeometry(String json) {
        try {
            JSONObject o = new JSONObject(json);
            RectF bar = readRect(o, "bar");
            RectF title = readRect(o, "title");
            RectF nav = readRect(o, "nav");
            RectF se = readRect(o, "search");
            RectF th = readRect(o, "theme");
            if (bar == null || bar.width() < 4 || bar.height() < 4) return;
            int[] vl = new int[2];
            getLocationOnScreen(vl);
            toLocal(bar, vl);
            toLocal(title, vl);
            toLocal(nav, vl);
            toLocal(se, vl);
            toLocal(th, vl);
            barRect.set(bar);
            titleRect.set(title == null ? new RectF() : title);
            btnRects[0] = nav;
            btnRects[1] = se;
            btnRects[2] = th;
            hasGeometry = true;
            /* 窗口高度收紧到「栏 + 阴影」：attach 时给的 170dp 只是几何未知前的余量，
               但 PopupWindow 内的触摸全归本窗（非按键区转发给 WebView），多出来的
               空白会吃掉下方内容的点击 —— 2026-10-27 真机：原生阶段页顶部按钮点不动。
               收到几何后按真实栏高重设窗口。 */
            try {
                if (popup != null) {
                    int hWin = (int) (barRect.height() + 30f * density);
                    popup.update(0, 0, ViewGroup.LayoutParams.MATCH_PARENT, hWin);
                }
            } catch (Throwable ignored) { }
            backdropRev = -1;   // 几何变了 → 背景区域变了 → 重抓
            gradTint = null;
            gradSpec = null;
            gradShadow = null;
            invalidate();
            Log.i(TAG, String.format(
                    "几何已同步 栏(%.0f,%.0f %.0f×%.0f) 标题x=%.0f 三键x=%.0f/%.0f/%.0f",
                    barRect.left, barRect.top, barRect.width(), barRect.height(),
                    titleRect.left,
                    btnRects[0] == null ? -1 : btnRects[0].centerX(),
                    btnRects[1] == null ? -1 : btnRects[1].centerX(),
                    btnRects[2] == null ? -1 : btnRects[2].centerX()));
        } catch (Throwable t) {
            Log.w(TAG, "几何解析失败", t);
        }
    }

    private static RectF readRect(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        if (a == null || a.length() < 4) return null;
        RectF r = new RectF();
        r.set((float) a.optDouble(0), (float) a.optDouble(1),
                (float) a.optDouble(0) + (float) a.optDouble(2),
                (float) a.optDouble(1) + (float) a.optDouble(3));
        return r;
    }

    private static void toLocal(RectF r, int[] viewOrigin) {
        r.offsetTo(r.left - viewOrigin[0], r.top - viewOrigin[1]);
    }

    /**
     * 主动向页面索取几何（R1 同款索取制：失败在 logcat 一目了然）。
     * Bridge.ready、insets 应用后、旋转补测时各调一次（MainActivity 挂钩）。
     */
    public void requestGeometry() {
        if (webView == null || detached) return;
        String js = "(function(){try{"
                + "var dpr=window.devicePixelRatio||1;"
                + "var bar=document.querySelector('.appbar');"
                + "var title=document.querySelector('.appbar__title');"
                + "var nav=document.getElementById('btnNav');"
                + "var se=document.getElementById('btnSearch');"
                + "var th=document.getElementById('btnTheme');"
                + "if(!bar||!title||!nav||!se||!th)return;"
                + "function r(el){var b=el.getBoundingClientRect();"
                + "return [b.left*dpr,b.top*dpr,b.width*dpr,b.height*dpr];}"
                + "if(window.NetBridge&&NetBridge.setAppBarGeometry){"
                + "NetBridge.setAppBarGeometry(JSON.stringify({bar:r(bar),title:r(title),"
                + "nav:r(nav),search:r(se),theme:r(th)}));}"
                + "}catch(e){}})()";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "索取几何的 JS 注入失败", t);
        }
    }

    /* ================= 背景（PixelCopy + 下采样模糊） ================= */

    /** 预热：几何同步后先抓一张，把首次成本摊在用户看见之前。 */
    public void warmUp() {
        post(this::captureBackdrop);
    }

    private boolean copyBusy = false;     /* PixelCopy 在途标志：onDraw 兜底路径没有它
                                              会每帧发一次抓图请求（抓图风暴） */

    /** 外部强制重抓：设置面板关闭时调用——面板开着的那次抓样把半透明遮罩
     *  和上一页头部一起采了进去（2026-10-30 用户报「玻璃里隐隐有网络学习手册 2.0」）。 */
    public void invalidateBackdrop() {
        backdropRev = -1;
        invalidate();
    }

    private void captureBackdrop() {
        if (detached || copyBusy || !glassOn || !hasGeometry || backdropRev == bgRev) return;
        if (Build.VERSION.SDK_INT < 26) return;   // PixelCopy.Window 需 26+；低版本走实色降级
        Activity act = (getContext() instanceof Activity) ? (Activity) getContext() : null;
        if (act == null || act.getWindow() == null) return;
        int[] vl = new int[2];
        getLocationOnScreen(vl);
        int l = Math.max(0, (int) barRect.left + vl[0]);
        int top = Math.max(0, (int) barRect.top + vl[1]);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int r = Math.min(sw, l + (int) barRect.width() + 1);
        int bot = top + (int) barRect.height() + 1;
        int w = Math.max(4, r - l), h = Math.max(4, bot - top);
        final int revAtReq = bgRev;   /* 在途期间 bgrev 再变 → 完成后补抓（丢更新竞态） */
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            copyBusy = true;
            PixelCopy.request(act.getWindow(), new Rect(l, top, l + w, top + h), out,
                    result -> {
                        copyBusy = false;
                        if (detached) return;
                        if (result == PixelCopy.SUCCESS) {
                            backdrop = out;
                            buildBlurChain();
                            backdropRev = revAtReq;
                            invalidate();
                            if (bgRev != revAtReq) post(this::captureBackdrop);
                        } else {
                            Log.w(TAG, "PixelCopy 失败 result=" + result + "，顶栏退回实色");
                        }
                    }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            copyBusy = false;
            Log.w(TAG, "抓背景异常", t);
        }
    }

    /** ÷2 四连下采样（每次双线性 ≈ 2×2 均值），blit 上采样时再补一层双线性
     *  —— 合成低通对齐 --glass-blur 20px 的观感（AGSL 高斯留作打磨项）。 */
    private void buildBlurChain() {
        try {
            Bitmap cur = backdrop;
            for (int i = 0; i < 4 && cur.getWidth() > 8 && cur.getHeight() > 4; i++) {
                Bitmap half = Bitmap.createBitmap(Math.max(1, cur.getWidth() / 2),
                        Math.max(1, cur.getHeight() / 2), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(half);
                Matrix m = new Matrix();
                m.setScale(0.5f, 0.5f);
                c.drawBitmap(cur, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
                if (cur != backdrop) cur.recycle();
                cur = half;
            }
            if (blurred != null && blurred != cur) blurred.recycle();
            blurred = cur;
        } catch (Throwable t) {
            Log.w(TAG, "模糊链构建失败", t);
            blurred = null;
        }
    }

    /* ================= 配色与图标 ================= */

    /** 亮/暗两套，逐值对齐 01-tokens.css（--glass-tint-* / --hairline / --border）。 */
    private void applyColors() {
        if (dark) {
            cSurface = Color.argb(255, 28, 28, 30);          // --surface #1c1c1e
            cTintTop = Color.argb(77, 58, 58, 64);           // --glass-tint-top rgba(58,58,64,.30)
            cTintBot = Color.argb(56, 32, 32, 36);           // --glass-tint-bot rgba(32,32,36,.22)
            cText = Color.argb(255, 245, 245, 247);          // --text #f5f5f7
            cText2 = Color.argb(255, 199, 199, 204);         // --text-2 #c7c7cc
            cText3 = Color.argb(255, 142, 142, 147);         // --text-3 #8e8e93
            cHairline = Color.argb(26, 255, 255, 255);       // --hairline rgba(255,255,255,.10)
            cBorder = Color.argb(255, 56, 56, 58);           // --border #38383a
            cShadowBase = Color.argb(97, 0, 0, 0);           // --glass-shadow rgba(0,0,0,.38) 首 stop
        } else {
            cSurface = Color.WHITE;                          // --surface #ffffff
            cTintTop = Color.argb(51, 255, 255, 255);        // --glass-tint-top rgba(255,255,255,.20)
            cTintBot = Color.argb(36, 250, 250, 252);        // --glass-tint-bot rgba(250,250,252,.14)
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

    /** 按钮动作 = 触发对应 HTML 按钮的 click()：抽屉/返回/搜索/主题的全部既有
     *  逻辑留在页面侧，原生只接管观感。visibility:hidden 的元素收不到真触摸，
     *  必须程序化 click（事件处理器照常执行）。 */
    private void fire(int idx) {
        if (webView == null) return;
        String id = idx == 0 ? "btnNav" : (idx == 1 ? "btnSearch" : "btnTheme");
        String js = "(function(){try{var b=document.getElementById('" + id + "');"
                + "if(b)b.click();}catch(e){}})()";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "触发按钮失败", t);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (searchOpen || overlaid || nativeBar == 0) {
            /* 理论上退位期整窗已 dismiss；万一事件仍进来（时序窗），按透传处理 */
            forwardToWebView(e);
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int idx = hitButton(e.getX(), e.getY());
                if (idx < 0) {
                    /* 非按钮区（标题/留白）：HTML 顶栏无任何可点目标，原样转发，
                       语义与「顶栏不存在」一致 */
                    forwardToWebView(e);
                    return true;
                }
                pressedBtn = idx;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
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

        /* 1. 背景：模糊壁纸 + tint 渐变（玻璃开）；实色 surface（玻璃关）。
              背景静态（内容不滚进顶栏底下），只按 bgRev/主题/几何重抓。 */
        if (glassOn) {
            if (blurred != null) {
                canvas.drawBitmap(blurred, null, barRect, blurP);
            } else {
                captureBackdrop();   // 首帧未就绪：实色垫底 + 异步补抓
                canvas.drawRect(barRect, fallbackP);
            }
            ensureGradient();
            canvas.drawRect(barRect, tintP);
            ensureSpecular();
            canvas.drawRect(barRect, specularP);
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

    private void ensureGradient() {
        if (gradTint == null) {
            gradTint = new LinearGradient(0, barRect.top, 0, barRect.bottom,
                    cTintTop, cTintBot, Shader.TileMode.CLAMP);
            tintP.setShader(gradTint);
        }
    }

    /** 镜面高光（静态版）：radial 高光压在 tint 上，对齐 ::after 的存在感。
     *  动态跟随 --mx/--my 是打磨项，v1 不追。 */
    private void ensureSpecular() {
        if (gradSpec == null) {
            gradSpec = new RadialGradient(barRect.width() * 0.30f, barRect.top,
                    barRect.height() * 1.6f,
                    Color.argb(26, 255, 255, 255), Color.argb(0, 255, 255, 255),
                    Shader.TileMode.CLAMP);
            specularP.setShader(gradSpec);
        }
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
