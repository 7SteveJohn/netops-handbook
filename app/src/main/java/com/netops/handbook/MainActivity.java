package com.netops.handbook;

import android.annotation.SuppressLint;
import android.content.ComponentCallbacks2;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NetOps 2.0 离线壳。
 *
 * 设计原则：
 * 1. 零网络 —— 未声明 INTERNET 权限，WebView 只加载 assets 内的单文件页面，
 *    任何非本地导航一律拦截。
 * 2. 边到边 —— 系统栏透明，真实安全区以 dp 注入网页 CSS 变量，由网页统一排版。
 * 3. 返回键交由网页 —— 网页自建路由栈（file:// 下 history.pushState 不可用），
 *    通过 window.NetOpsBack() 逐层回退，返回 false 时才退出应用。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NetOps";
    private static final String PAGE = "file:///android_asset/index.html";

    private static final String PREFS = "netops.shell";
    /** 网页最近一次生效的配色（"dark" / "light"），用于下次冷启动首帧配色。 */
    private static final String KEY_THEME = "theme";

    private static final int BG_DARK = 0xFF0B1220;
    private static final int BG_LIGHT = 0xFFF5F7FB;

    /** 与 css/03-layout.css 中 .edge-catcher 的宽度保持一致。 */
    private static final int EDGE_DP = 18;
    /** 系统对单侧手势排除区的高度上限（超出部分会被系统忽略）。 */
    private static final int EXCLUSION_MAX_DP = 200;

    /** 询问网页是否消费了本次返回；返回字符串 "true" / "false"。 */
    private static final String JS_BACK =
            "(function(){try{return (typeof NetOpsBack==='function')?!!NetOpsBack():false;}catch(e){return false;}})()";

    private WebView webView;
    private FrameLayout root;

    /** 主文档连续载入失败计数（成功载入或手动重试时清零）。 */
    private int reloadTries = 0;
    /** 渲染进程已被系统回收：此后严禁再触碰旧 WebView 实例。 */
    private boolean rendererGone = false;
    /** 内存吃紧时收掉过离屏预栅格化，回前台要还回去（否则玻璃退回平涂）。 */
    private boolean preRasterDropped = false;
    /** 错误页「重新载入」链接的目标（about: 不产生真实导航）。 */
    private static final String RETRY_PAGE = "about:netops-retry";

    /** 「再按一次退出」防误触：记录上次根页面按下返回键的时间戳（毫秒）。 */
    private long lastBackPressMs = 0;
    /** 两次返回键的最大间隔（毫秒），超出则重置计数。 */
    private static final int BACK_EXIT_INTERVAL_MS = 2000;
    /** 等网页应答返回键的时限：超时按「已在根页面」处理，防止 JS 卡死导致返回键失灵。 */
    private static final int BACK_ACK_TIMEOUT_MS = 500;
    /** 相册选图的请求码。 */
    private static final int REQ_PICK_IMAGE = 9001;
    /** 文件导入（<input type=file> 选择器）请求码。 */
    private static final int REQ_CHOOSER = 9002;
    /** 文件导入回调（onShowFileChooser 暂存，onActivityResult 回传）。 */
    private android.webkit.ValueCallback<Uri[]> uploadMessage = null;

    private boolean pageReady = false;
    private String pendingInsetsJs = null;
    private int lastInsetSig = Integer.MIN_VALUE;

    // ------------------------------------------------------------------ 生命周期

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 边到边：内容自己铺满，安全区由 WindowInsets 下发给网页
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // 冷启动首帧直接采用网页上次生效的配色：深色用户不会再看到一闪而过的白底
        boolean dark = resolvedDark();
        int bg = dark ? BG_DARK : BG_LIGHT;
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));

        root = new FrameLayout(this);
        root.setId(View.generateViewId());
        root.setFitsSystemWindows(false);
        root.setBackgroundColor(bg);
        setContentView(root);

        applyBarAppearance(dark);
        bindInsets();
        bindGestureExclusion();
        bindBackKey();

        createWebView(bg, savedInstanceState);
    }

    /**
     * 建（或重建）WebView。载入失败与渲染进程被杀都走这一条路径，
     * 避免初始化配置在两处副本之间漂移。
     */
    private void createWebView(int bg, Bundle state) {
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(bg);
        root.addView(webView);

        configureWebView();
        webView.addJavascriptInterface(new Bridge(), "NetBridge");
        pageReady = false;
        rendererGone = false;

        boolean restored = false;
        if (state != null) {
            try { restored = webView.restoreState(state) != null; } catch (Exception e) { restored = false; }
        }
        if (!restored) webView.loadUrl(PAGE);
    }

    /** 渲染进程没了 / 反复载入失败后的自我了断式重建。 */
    private void rebuildWebView() {
        runOnUiThread(() -> {
            try {
                if (webView != null) {
                    root.removeView(webView);
                    try { webView.removeJavascriptInterface("NetBridge"); } catch (Exception ignored) { }
                    webView.destroy();
                }
            } catch (Exception e) {
                Log.w(TAG, "销毁旧 WebView 失败（继续重建）", e);
            }
            webView = null;
            reloadTries = 0;
            createWebView(resolvedDark() ? BG_DARK : BG_LIGHT, null);
        });
    }

    /**
     * 主文档载入失败：先有限次自动重载（资源被安全软件瞬时拦截时可自愈），
     * 仍失败就显示错误页 —— 旧实现什么也不做，用户只会看到永久白屏。
     */
    private void handleLoadFailure(String why) {
        if (webView == null || rendererGone) return;
        if (reloadTries++ < 2) {
            Log.w(TAG, "首页载入失败，第 " + reloadTries + " 次自动重试：" + why);
            webView.postDelayed(() -> {
                if (webView != null && !rendererGone) webView.loadUrl(PAGE);
            }, 600L * reloadTries);
            return;
        }
        Log.e(TAG, "首页多次载入失败：" + why);
        pageReady = false;
        String skin = resolvedDark()
                ? "background:#0B1220;color:#f5f5f7" : "background:#f5f7fb;color:#1d1d1f";
        String mute = resolvedDark() ? "#8e8e93" : "#86868b";
        webView.loadDataWithBaseURL(null,
                "<!doctype html><meta charset=utf-8>"
              + "<meta name=viewport content='width=device-width,initial-scale=1'>"
              + "<body style='font:15px/1.7 system-ui;margin:0;padding:26vh 26px 0;" + skin + ";text-align:center'>"
              + "<b>页面没能载入</b><br>"
              + "<span style='font-size:13px;opacity:.85'>本应用完全离线、不申请网络权限；"
              + "连续三次载入失败通常是安装包资源损坏，或被安全软件拦截。</span><br><br>"
              + "<a href='" + RETRY_PAGE + "' style='color:#2563eb'>重新载入</a>"
              + "<p style='color:" + mute + ";font-size:12px'>NetOps 2.0 · 网络学习辅助手册</p></body>",
                "text/html", "utf-8", null);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 旋转已被 configChanges 接管，这里保的是「进程被杀后重建」
        if (webView != null && !rendererGone) {
            try { webView.saveState(outState); } catch (Exception e) { Log.w(TAG, "saveState 失败", e); }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 不暂停的话，网页里的定时器与预栅格化会在后台继续烧 CPU/电
        if (webView != null && !rendererGone) {
            // 注意：WebView 没有 pause()/resume()，按视图暂停用的是 onPause()/onResume()
            try { webView.onPause(); } catch (Exception e) { Log.w(TAG, "onPause 失败", e); }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null && !rendererGone) {
            try { webView.onResume(); } catch (Exception e) { Log.w(TAG, "onResume 失败", e); }
            if (preRasterDropped) {
                try { webView.getSettings().setOffscreenPreRaster(true); } catch (Exception e) { Log.w(TAG, "恢复预栅格化失败", e); }
                preRasterDropped = false;
            }
        }
        if (root != null) ViewCompat.requestApplyInsets(root);
    }

    /**
     * 系统内存压力下的降级。javap 查过：WebView 没有 trimMemory/onLowMemory 可供转发，
     * 只剩 freeMemory()；真正值钱的是收掉 offscreenPreRaster 那份视口大小的离屏缓冲
     * （1080p 一页量级约 10 MB）。回前台再恢复，否则 backdrop-filter 会退回平涂。
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (webView == null || rendererGone) return;
        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return;
        try {
            if (webView.getSettings().getOffscreenPreRaster()) {
                webView.getSettings().setOffscreenPreRaster(false);
                preRasterDropped = true;
            }
            webView.freeMemory();
        } catch (Exception e) {
            Log.w(TAG, "内存降级失败", e);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("NetBridge");
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ WebView

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);          // 全部逻辑在本地脚本内
        s.setDomStorageEnabled(true);          // 打卡 / 收藏 / 主题偏好持久化
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);            // 仅用于 android_asset
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);                    // 跟随系统字号会破坏栅格，这里锁定
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);   // 无网络，避免多余的初始化开销
        }

        /* 玻璃核心：让 backdrop-filter 模糊/色散在安卓 WebView 可靠渲染。
           offscreenPreRaster 预栅格化离屏缓冲；LAYER_TYPE_HARDWARE 强制 WebView
           走硬件合成层，否则中低端机 blur 会失效（纯透明/平涂）。 */
        s.setOffscreenPreRaster(true);
        preRasterDropped = false;
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        /* 文件导入：必须实现 onShowFileChooser，否则 <input type=file> 无法弹出系统选择器 */
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             android.webkit.ValueCallback<Uri[]> filePathCallback,
                                             android.webkit.WebChromeClient.FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, REQ_CHOOSER);
                    return true;
                } catch (Exception e) {
                    uploadMessage = null;
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return blockExternal(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockExternal(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                reloadTries = 0;
                flushInsets();
                pushTheme(isNightMode());
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.startsWith(PAGE)) handleLoadFailure(description);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;   // 子资源（壁纸）失败忽略
                String u = request.getUrl() == null ? "" : request.getUrl().toString();
                if (!u.startsWith(PAGE)) return;
                handleLoadFailure(error == null ? "unknown" : String.valueOf(error.getDescription()));
            }

            /**
             * 渲染进程被系统回收或崩溃后，旧 WebView 实例不可再用，任何后续调用都会闪退。
             * 必须返回 true（= 应用已接管）；返回 false 会让系统直接杀掉整个进程。
             * 低版本不会回调本方法（API 26+），因此无需版本判断。
             */
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                rendererGone = true;
                pageReady = false;
                Log.e(TAG, "WebView 渲染进程退出，重建界面。didCrash="
                        + (detail != null && detail.didCrash()));
                Toast.makeText(MainActivity.this, "界面进程被系统回收，正在恢复", Toast.LENGTH_SHORT).show();
                rebuildWebView();
                return true;
            }
        });
    }

    /** 只允许留在本地页面内；其余（含 http/https）全部丢弃。 */
    private boolean blockExternal(Uri uri) {
        if (uri == null) return true;
        if (RETRY_PAGE.equals(uri.toString())) {
            reloadTries = 0;
            if (webView != null && !rendererGone) webView.loadUrl(PAGE);
            return true;
        }
        String sc = uri.getScheme();
        boolean local = "file".equalsIgnoreCase(sc) || "about".equalsIgnoreCase(sc)
                || "data".equalsIgnoreCase(sc) || "blob".equalsIgnoreCase(sc);
        if (!local) {
            Log.i(TAG, "已拦截非本地导航：" + uri);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 安全区

    private void bindInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            float dp = getResources().getDisplayMetrics().density;
            if (dp <= 0) dp = 1f;

            int top = Math.round(bars.top / dp);
            int left = Math.round(bars.left / dp);
            int right = Math.round(bars.right / dp);
            // 键盘弹出时底部安全区让位给输入法，避免搜索框被遮挡
            int keyboard = Math.round(ime.bottom / dp);
            int bottom = Math.max(Math.round(bars.bottom / dp), keyboard);

            // 第五个参数是输入法高度：网页据此收起标签栏与悬浮按钮，
            // 不然它们会被顶到键盘正上方挡住内容
            int sig = ((((top * 31 + bottom) * 31 + left) * 31 + right) * 31) + keyboard;
            if (sig != lastInsetSig) {
                lastInsetSig = sig;
                pendingInsetsJs = "window.NetOpsSetInsets && NetOpsSetInsets("
                        + top + "," + bottom + "," + left + "," + right + "," + keyboard + ");";
                flushInsets();
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void flushInsets() {
        if (!pageReady || pendingInsetsJs == null || webView == null) return;
        final String js = pendingInsetsJs;
        webView.post(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    // ------------------------------------------------------------------ 手势冲突

    /**
     * Android 10+ 的系统返回手势会吃掉屏幕左缘的横滑，而网页左缘恰好是抽屉的拉出热区，
     * 两者叠在一起时抽屉几乎拉不出来。把这条竖条声明为手势排除区即可让网页优先响应。
     * 系统对每侧的排除高度上限为 200dp，因此只保留拇指最容易够到的中段。
     */
    private void bindGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        root.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, orr, ob) -> applyGestureExclusion());
        applyGestureExclusion();
    }

    private void applyGestureExclusion() {
        if (root == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        float dp = getResources().getDisplayMetrics().density;
        if (dp <= 0) dp = 1f;
        int w = Math.round(EDGE_DP * dp);
        int h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        int band = Math.min(h, Math.round(EXCLUSION_MAX_DP * dp));
        int top = Math.max(0, (h - band) / 2);
        List<Rect> rects = new ArrayList<>();
        rects.add(new Rect(0, top, w, top + band));
        ViewCompat.setSystemGestureExclusionRects(root, rects);
    }

    // ------------------------------------------------------------------ 主题

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 网页记过配色就以网页为准（含用户手动切换的深/浅色），否则跟随系统。 */
    private boolean resolvedDark() {
        String t = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_THEME, null);
        if ("dark".equals(t)) return true;
        if ("light".equals(t)) return false;
        return isNightMode();
    }

    private void rememberTheme(boolean dark) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_THEME, dark ? "dark" : "light")
                .apply();
    }

    /** 网页主题变化后同步系统栏图标明暗与窗口底色。 */
    private void applyBarAppearance(boolean dark) {
        Window w = getWindow();
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setAppearanceLightStatusBars(!dark);
        c.setAppearanceLightNavigationBars(!dark);
        int bg = dark ? BG_DARK : BG_LIGHT;
        if (root != null) root.setBackgroundColor(bg);
        if (webView != null) webView.setBackgroundColor(bg);
        w.getDecorView().setBackgroundColor(bg);
    }

    private void pushTheme(boolean dark) {
        runOnUiThread(() -> applyBarAppearance(dark));
    }

    // ------------------------------------------------------------------ 返回键

    private void bindBackKey() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView == null) { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); return; }
                final OnBackPressedCallback self = this;
                /* 网页侧只回答「这一页我消费了吗」。退出确认只由本类负责，
                   避免同一键叠两个 Toast，也避免网页逻辑卡住时返回键彻底失灵。 */
                final AtomicBoolean answered = new AtomicBoolean(false);
                webView.evaluateJavascript(JS_BACK, value -> {
                    if (answered.compareAndSet(false, true)) onBackConsumed("true".equals(value), self);
                });
                webView.postDelayed(() -> {
                    if (answered.compareAndSet(false, true)) {
                        Log.w(TAG, "网页未在时限内应答返回键，按根页面处理");
                        onBackConsumed(false, self);
                    }
                }, BACK_ACK_TIMEOUT_MS);
            }
        });
    }

    /** 返回键归属：网页消费则什么都不做；处于根页面走「再按一次退出」防误触。 */
    private void onBackConsumed(boolean consumed, OnBackPressedCallback self) {
        if (consumed) return;
        long now = System.currentTimeMillis();
        if (now - lastBackPressMs < BACK_EXIT_INTERVAL_MS) {
            lastBackPressMs = 0;
            self.setEnabled(false);
            getOnBackPressedDispatcher().onBackPressed();
        } else {
            lastBackPressMs = now;
            Toast.makeText(MainActivity.this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ JS 桥

    private class Bridge {

        /** 网页初始化完毕，补发一次安全区，避免首帧错位。 */
        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> {
                pageReady = true;
                lastInsetSig = Integer.MIN_VALUE;
                if (root != null) ViewCompat.requestApplyInsets(root);
                flushInsets();
            });
        }

        @JavascriptInterface
        public void setTheme(String mode) {
            boolean dark = "dark".equals(mode);
            rememberTheme(dark);
            pushTheme(dark);
        }

        /**
         * 触感反馈。用 View.performHapticFeedback 而非 Vibrator：
         * 尊重系统「触感反馈」开关、不需要 VIBRATE 权限，力度也和原生控件一致。
         */
        @JavascriptInterface
        public void haptic(int ms) {
            runOnUiThread(() -> {
                if (webView == null) return;
                int effect = ms >= 16
                        ? HapticFeedbackConstants.LONG_PRESS
                        : HapticFeedbackConstants.VIRTUAL_KEY;
                try {
                    webView.performHapticFeedback(effect);
                } catch (Exception e) {
                    Log.w(TAG, "触感反馈不可用", e);
                }
            });
        }

        /** 导出 Markdown：Q 及以上写入公共 Downloads，低版本写应用私有目录，均无需权限。 */
        @JavascriptInterface
        public void saveFile(String name, String text) {
            final String fileName = safeName(name);
            final String body = text == null ? "" : text;
            runOnUiThread(() -> {
                String where;
                try {
                    where = writeDocument(fileName, body);
                } catch (Exception e) {
                    Log.w(TAG, "导出失败", e);
                    where = null;
                }
                Toast.makeText(MainActivity.this,
                        where != null ? ("已导出到 " + where) : "导出失败",
                        Toast.LENGTH_LONG).show();
            });
        }

        /** 打开系统相册选图，结果通过 base64 回传给 JS（用于自定义背景壁纸）。 */
        @JavascriptInterface
        public void pickImage() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_PICK,
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                    startActivityForResult(intent, REQ_PICK_IMAGE);
                } catch (Exception e) {
                    // 部分设备可能没有相册应用，降级为通用选择器
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    startActivityForResult(Intent.createChooser(intent, "选择背景图片"), REQ_PICK_IMAGE);
                }
            });
        }

        /** 2026-08-12 19:16：网页侧"双按退出确认"确认后调用 —— 真正退出应用。
            与物理返回键的防误触(Java 侧)不同，这是给左滑手势退出用的专门桥。 */
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> {
                try { finish(); } catch (Exception e) { /* ignore */ }
            });
        }
    }

    /** 只留文件名本体：挡掉 ../、绝对路径与分隔符（旧实现直接 new File(dir, 网页传入名)）。 */
    private String safeName(String name) {
        String n = name == null ? "" : name.trim();
        int cut = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (cut >= 0) n = n.substring(cut + 1);
        n = n.replace("..", "").replace(":", "");
        if (n.isEmpty()) n = "netops.md";
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    /** 根据文件名后缀推断 MIME（2026-08-12 修复：导出 JSON 被当 markdown 处理）。 */
    private String mimeFor(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".md") || n.endsWith(".markdown")) return "text/markdown";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        return "application/octet-stream";
    }

    private String writeDocument(String fileName, String body) throws Exception {
        byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            cv.put(MediaStore.Downloads.MIME_TYPE, mimeFor(fileName));
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("MediaStore 拒绝写入");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("输出流为空");
                os.write(bytes);
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            return "下载/" + fileName;
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir == null) dir = getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("目录创建失败");
        File f = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(bytes);
        }
        return f.getAbsolutePath();
    }

    // ------------------------------------------------------------------ 配置变化

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        /* 文件导入：把系统文件选择器的结果回传给 WebView 的 <input type=file> */
        if (requestCode == REQ_CHOOSER) {
            if (uploadMessage == null) return;
            android.webkit.ValueCallback<Uri[]> cb = uploadMessage;
            uploadMessage = null;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                }
            }
            cb.onReceiveValue(results);
            return;
        }
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            /* 旧实现两处硬伤：available() 不保证等于全长度（内容流常返回 0），
               以及把 JPEG 从中间截成 2MB —— 得到的是一张必坏图，还谎报「壁纸已设置」。
               现在按界内缩放重压缩，控制在网页侧 localStorage 能吃下的量级。 */
            String dataUrl = null;
            try { dataUrl = readImageAsDataUrl(uri); } catch (Exception e) { Log.w(TAG, "解码选图失败", e); }
            if (dataUrl == null) {
                Toast.makeText(this, "读取图片失败，换一张或改用内置壁纸", Toast.LENGTH_LONG).show();
                return;
            }
            final String js = "if(window.NetOpsOnWallpaper) NetOpsOnWallpaper('" + dataUrl + "');";
            if (webView != null) webView.post(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
        }
    }

    /** 读相册图片 → 等比缩到长边 ≤2560 → JPEG 逐级降质压到 ≤1.8MB → dataURL；失败返回 null。 */
    private String readImageAsDataUrl(Uri uri) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        int sw = 0, sh = 0;
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            android.graphics.BitmapFactory.Options probe = new android.graphics.BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(is, null, probe);
            sw = probe.outWidth; sh = probe.outHeight;
            if (sw <= 0 || sh <= 0) return null;
            int sample = 1;
            while (Math.max(sw, sh) / sample > 2560) sample *= 2;
            bounds.set(0, 0, sw / sample, sh / sample);
        } catch (Exception e) {
            Log.w(TAG, "探测图片尺寸失败", e);
            return null;
        }

        android.graphics.Bitmap bmp;
        try (java.io.InputStream is2 = getContentResolver().openInputStream(uri)) {
            if (is2 == null) return null;
            android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
            int sample = 1;
            while (Math.max(sw, sh) / sample > 2560) sample *= 2;
            opt.inSampleSize = sample;
            bmp = android.graphics.BitmapFactory.decodeStream(is2, bounds, opt);
        } catch (Exception e) {
            Log.w(TAG, "解码图片失败", e);
            return null;
        }
        if (bmp == null) return null;

        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int q = 85;
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bos);
            while (bos.size() > 1_800_000 && q > 45) {
                bos.reset();
                q -= 10;
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bos);
            }
            byte[] out = bos.toByteArray();
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "压缩图片失败", e);
            return null;
        } finally {
            try { bmp.recycle(); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 系统深浅色切换时同步系统栏；网页侧 auto 模式由 CSS media query 自行响应
        applyBarAppearance(resolvedDark());
        if (root != null) {
            ViewCompat.requestApplyInsets(root);
            applyGestureExclusion();
        }
    }
}
