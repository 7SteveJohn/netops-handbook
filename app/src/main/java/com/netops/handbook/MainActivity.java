package com.netops.handbook;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
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

    /** 询问网页是否消费了本次返回；返回字符串 "true" / "false"。 */
    private static final String JS_BACK =
            "(function(){try{return (typeof NetOpsBack==='function')?!!NetOpsBack():false;}catch(e){return false;}})()";

    private WebView webView;
    private FrameLayout root;

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

        root = new FrameLayout(this);
        root.setId(View.generateViewId());
        root.setFitsSystemWindows(false);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(bgColor());
        root.setBackgroundColor(bgColor());
        root.addView(webView);
        setContentView(root);

        configureWebView();
        applyBarAppearance(isNightMode());
        bindInsets();
        bindBackKey();

        webView.addJavascriptInterface(new Bridge(), "NetBridge");
        webView.loadUrl(PAGE);
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

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

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
                flushInsets();
                pushTheme(isNightMode());
            }
        });
    }

    /** 只允许留在本地页面内；其余（含 http/https）全部丢弃。 */
    private boolean blockExternal(Uri uri) {
        if (uri == null) return true;
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
            int bottom = Math.round(Math.max(bars.bottom, ime.bottom) / dp);

            int sig = (top * 31 + bottom) * 31 * 31 + left * 31 + right;
            if (sig != lastInsetSig) {
                lastInsetSig = sig;
                pendingInsetsJs = "window.NetOpsSetInsets && NetOpsSetInsets("
                        + top + "," + bottom + "," + left + "," + right + ");";
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

    // ------------------------------------------------------------------ 主题

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int bgColor() {
        return isNightMode() ? 0xFF0B1220 : 0xFFF5F7FB;
    }

    /** 网页主题变化后同步系统栏图标明暗与窗口底色。 */
    private void applyBarAppearance(boolean dark) {
        Window w = getWindow();
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setAppearanceLightStatusBars(!dark);
        c.setAppearanceLightNavigationBars(!dark);
        int bg = dark ? 0xFF0B1220 : 0xFFF5F7FB;
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
                webView.evaluateJavascript(JS_BACK, value -> {
                    if (!"true".equals(value)) {
                        // 网页已在根页面，交还给系统（退出）
                        self.setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                });
            }
        });
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
            pushTheme("dark".equals(mode));
        }

        /** 导出 Markdown：Q 及以上写入公共 Downloads，低版本写应用私有目录，均无需权限。 */
        @JavascriptInterface
        public void saveFile(String name, String text) {
            final String fileName = (name == null || name.trim().isEmpty()) ? "netops.md" : name.trim();
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
    }

    private String writeDocument(String fileName, String body) throws Exception {
        byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/markdown");
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
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 系统深浅色切换时同步系统栏；网页侧 auto 模式由 CSS media query 自行响应
        applyBarAppearance(isNightMode());
        if (root != null) ViewCompat.requestApplyInsets(root);
    }
}
