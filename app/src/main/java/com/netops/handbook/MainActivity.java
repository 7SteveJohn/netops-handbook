package com.netops.handbook;

import android.annotation.SuppressLint;
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

    /** 「再按一次退出」防误触：记录上次根页面按下返回键的时间戳（毫秒）。 */
    private long lastBackPressMs = 0;
    /** 两次返回键的最大间隔（毫秒），超出则重置计数。 */
    private static final int BACK_EXIT_INTERVAL_MS = 2000;
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

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(bg);
        root.setBackgroundColor(bg);
        root.addView(webView);
        setContentView(root);

        configureWebView();
        applyBarAppearance(dark);
        bindInsets();
        bindGestureExclusion();
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

        /* 玻璃核心：让 backdrop-filter 模糊/色散在安卓 WebView 可靠渲染。
           offscreenPreRaster 预栅格化离屏缓冲；LAYER_TYPE_HARDWARE 强制 WebView
           走硬件合成层，否则中低端机 blur 会失效（纯透明/平涂）。 */
        s.setOffscreenPreRaster(true);
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
                webView.evaluateJavascript(JS_BACK, value -> {
                    if ("true".equals(value)) {
                        // 网页已消费（关闭 Drawer / Sheet / 返回上一页），不做任何事
                    } else {
                        // 网页在根页面 —— 防误触：首次按提示，2 秒内再按才真退
                        long now = System.currentTimeMillis();
                        if (now - lastBackPressMs < BACK_EXIT_INTERVAL_MS) {
                            // 第二次按下，确认退出
                            lastBackPressMs = 0;
                            self.setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                        } else {
                            // 第一次按下，Toast 提示
                            lastBackPressMs = now;
                            Toast.makeText(MainActivity.this,
                                    "再按一次退出应用", Toast.LENGTH_SHORT).show();
                        }
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
            try {
                java.io.InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return;
                byte[] bytes = new byte[is.available()];
                int total = 0;
                int read;
                while ((read = is.read(bytes, total, bytes.length - total)) > 0) {
                    total += read;
                    if (total >= bytes.length) break;
                }
                is.close();
                // 只取前 2MB（背景图不需要超高分辨率）
                int len = Math.min(total, 2 * 1024 * 1024);
                String b64 = android.util.Base64.encodeToString(bytes, 0, len,
                        android.util.Base64.NO_WRAP);
                String js = "if(window.NetOpsOnWallpaper) NetOpsOnWallpaper('data:image/jpeg;base64," + b64 + "');";
                if (webView != null) webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.w(TAG, "读取选图失败", e);
                Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show();
            }
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
