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
}
