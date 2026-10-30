package com.netops.handbook;

import android.annotation.SuppressLint;
import android.content.ComponentCallbacks2;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
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
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.netops.handbook.lens.LensBarView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
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

    /**
     * 热加载钩子：应用专属外部目录下若有 devpage 文件（内容为 file:/// 开头的 URL），
     * 加载它而不是内置 assets —— 配合 tools/dev-push.js 免重打包调试。
     * 文件不存在/内容不合法/读取失败一律回退 PAGE，本钩子绝不影响正常启动。
     */
    private String page() {
        try {
            File f = new File(getExternalFilesDir(null), "devpage");
            if (f.isFile() && f.length() > 0 && f.length() < 4096) {
                byte[] buf;
                try (FileInputStream in = new FileInputStream(f)) {
                    buf = new byte[(int) f.length()];
                    int n = in.read(buf);
                    if (n < 0) n = 0;
                }
                String u = new String(buf, java.nio.charset.StandardCharsets.UTF_8).trim();
                u = u.replace("\uFEFF", "");   // 去 BOM，防记事本类工具写入的 UTF-8 签名
                if (u.startsWith("file:///")) {
                    Log.i(TAG, "devpage 生效：" + u);
                    /* 只在开发态（存在 devpage 文件时）打开远程调试：
                       production 包没有这个文件，永远不会暴露 CDP。
                       有了它，电脑上可用 chrome://inspect 直连这个页面，
                       直接读计算样式、抓元素截图 —— 调玻璃时不用再靠肉眼猜。 */
                    WebView.setWebContentsDebuggingEnabled(true);
                    return u;
                }
                Log.w(TAG, "devpage 内容非 file:/// 开头，忽略：" + u);
            }
        } catch (Throwable t) {
            Log.w(TAG, "devpage 读取失败（回退内置页）", t);
        }
        return PAGE;
    }

    private static final String PREFS = "netops.shell";
    /** 网页最近一次生效的配色（"dark" / "light"），用于下次冷启动首帧配色。 */
    private static final String KEY_THEME = "theme";

    private static final int BG_DARK = 0xFF0B1220;
    private static final int BG_LIGHT = 0xFFF5F7FB;

    /** 询问网页是否消费了本次返回；返回字符串 "true" / "false"。 */
    private static final String JS_BACK =
            "(function(){try{return (typeof NetOpsBack==='function')?!!NetOpsBack():false;}catch(e){return false;}})()";

    private WebView webView;
    private FrameLayout root;
    private com.netops.handbook.lens.NativePhaseView nativePhase;
    private com.netops.handbook.lens.NativeBackdrop nativeBackdrop;
    private com.netops.handbook.lens.NativeHomeView nativeHome;
    private com.netops.handbook.lens.NativeSearchView nativeSearch;
    private com.netops.handbook.lens.NativeMeView nativeMe;
    private com.netops.handbook.lens.NativeSettingsPanel settingsPanel;
    private com.netops.handbook.lens.NativeFavView nativeFav;
    private com.netops.handbook.lens.NativeDrawerView nativeDrawer;
    private com.netops.handbook.lens.NativeDocList nativeFault;
    private com.netops.handbook.lens.NativeDocList nativeIv;
    private com.netops.handbook.lens.NativeDictView nativeDict;
    private com.netops.handbook.lens.NativeRefsView nativeRefs;
    private com.netops.handbook.lens.NativeSimpleList nativeGlossary, nativeRoadmap;
    private com.netops.handbook.lens.NativeQuizView nativeQuiz;
    /** 原生液体玻璃透镜层（B 路线）。null = 未启用。 */
    private LensBarView lensBar;
    /** R2 原生顶栏。null = 未启用（挂载失败/已销毁）。 */
    private com.netops.handbook.lens.AppBarView appbarBar;

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
    /** 已有一发返回键在等网页应答：应答窗口内的连按直接忽略。 */
    private boolean backAckPending = false;
    /** 重 I/O（导出写盘/图片解码压缩）专用单线程执行器：这类活严禁在主线程做（N-1）。 */
    private final java.util.concurrent.ExecutorService ioPool =
            java.util.concurrent.Executors.newSingleThreadExecutor();
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
        bindBackKey();

        createWebView(bg, savedInstanceState);
        loadNativeData();
    }

    /* ================= 全量原生化 · N0 数据管道 =================
       native-data.json 由 web-src/tools/gen-native-data.js 从 js/data/* 生成
       （唯一真源仍是 js/data）。这里后台线程解析一次，视图阶段按需取用；
       解析放后台是因为 358KB JSON 在主线程会吃掉首帧预算。 */
    private org.json.JSONObject nativeData;

    /** 原生页互斥：显示一个之前先收起其它（否则透明底页面会互相透出——2026-10-27 实测
     *  首页透过阶段页的半透明卡). keep = 要保留的类型名，null = 全收。 */
    private void hideNativePagesExcept(String keep) {
        if (!"phase".equals(keep) && nativePhase != null) nativePhase.hide();
        if (!"fault".equals(keep) && nativeFault != null) nativeFault.hide();
        if (!"iv".equals(keep) && nativeIv != null) nativeIv.hide();
        if (!"dict".equals(keep) && nativeDict != null) nativeDict.hide();
        if (!"refs".equals(keep) && nativeRefs != null) nativeRefs.hide();
        if (!"glossary".equals(keep) && nativeGlossary != null) nativeGlossary.hide();
        if (!"roadmap".equals(keep) && nativeRoadmap != null) nativeRoadmap.hide();
        if (!"quiz".equals(keep) && nativeQuiz != null) nativeQuiz.hide();
        if (!"home".equals(keep) && nativeHome != null) nativeHome.hide();
        if (!"search".equals(keep) && nativeSearch != null) nativeSearch.hide();
        if (!"me".equals(keep) && nativeMe != null) nativeMe.hide();
        if (!"fav".equals(keep) && nativeFav != null) nativeFav.hide();
        /* keep==null = 本路由没有原生页（CLI 等 WebView 独占页）→ 壁纸层让位，
           否则它（z 序在 WebView 之上）会把 WebView 盖死：页面看不见、触摸却穿透。
           任何原生页显示时收回让位。 */
        if (nativeBackdrop != null) nativeBackdrop.setRevealed(keep == null);
        if (nativeDrawer != null && nativeDrawer.isOpen()) {
            nativeDrawer.close();
            if (lensBar != null) lensBar.setOverlaid(false);
        }
    }

    /** 原生页导航统一出口：全部经 JS 路由（标题/返回栈/互斥都在 render 里收口）。 */
    private void nativeGo(String route, String arg) {
        if (webView == null) return;
        String js = "window.NetOpsNativeGo && NetOpsNativeGo("
                + org.json.JSONObject.quote(route) + "," + org.json.JSONObject.quote(arg) + ");";
        webView.post(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    /** 让页面层把当前设置状态推给原生面板。 */
    private void pushSettingsState() {
        if (webView == null) return;
        String js = "window.NetOpsNativePushSettings && NetOpsNativePushSettings();";
        webView.post(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private void addNativePage(android.view.View v) {
        v.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        v.setVisibility(android.view.View.GONE);
        root.addView(v);
    }

    /** 横屏竖排 dock 占左缘 102dp（轨 88 + 左距 14）：原生页整体右移，
     *  否则侧栏把每页左侧内容压住（2026-10-28 真机：卡片/标题左半边被吃掉）。
     *  竖屏归零 —— 底栏横趴在底部，不吃横向空间。幂等：margin 已对就跳过，
     *  可以在每次布局回调里无脑调。 */
    private void applyDockInset() {
        if (root == null || root.getWidth() <= 0) return;
        /* 竖排侧栏宽 48dp + 左距 14dp + 8dp 呼吸（FlClash compact 栏宽 48dp） */
        int left = root.getWidth() > root.getHeight()
                ? Math.round(70f * getResources().getDisplayMetrics().density) : 0;
        android.view.View[] pages = {nativeHome, nativePhase, nativeFault, nativeIv, nativeDict,
                nativeRefs, nativeGlossary, nativeRoadmap, nativeQuiz, nativeSearch,
                nativeMe, nativeFav};
        for (android.view.View p : pages) {
            if (p == null) continue;
            android.view.ViewGroup.LayoutParams lp0 = p.getLayoutParams();
            if (!(lp0 instanceof FrameLayout.LayoutParams)) continue;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lp0;
            if (lp.leftMargin == left) continue;
            lp.leftMargin = left;
            p.setLayoutParams(lp);
        }
    }

    private com.netops.handbook.lens.NativeDocList addDocList(
            com.netops.handbook.lens.NativeDocList.Config cfg) {
        com.netops.handbook.lens.NativeDocList v = new com.netops.handbook.lens.NativeDocList(this, cfg);
        v.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        v.setVisibility(android.view.View.GONE);
        if (nativeBackdrop != null) v.setGlassSource(nativeBackdrop);
        v.setStateListener((id, kind, on) -> {
            if (webView == null) return;
            String js = "window.NetOpsNativeState && NetOpsNativeState("
                    + org.json.JSONObject.quote(id) + "," + org.json.JSONObject.quote(kind) + "," + on + ");";
            webView.post(() -> {
                if (webView != null) webView.evaluateJavascript(js, null);
            });
        });
        root.addView(v);
        return v;
    }

    private com.netops.handbook.lens.NativeDocList.Config docConfig(
            String title, String unit, String doneLabel, String dataKey, String scrollKey,
            String chipFrom, String chipTo) {
        com.netops.handbook.lens.NativeDocList.Config c = new com.netops.handbook.lens.NativeDocList.Config();
        c.title = title;
        c.unit = unit;
        c.doneLabel = doneLabel;
        c.dataKey = dataKey;
        c.scrollKey = scrollKey;
        c.actionOn = "✓ " + doneLabel;
        c.actionOff = "标记" + doneLabel;
        c.chipFrom = chipFrom;
        c.chipTo = chipTo;
        return c;
    }

    private void loadNativeData() {
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try (java.io.InputStream is = getAssets().open("native-data.json")) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1 << 19);
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                String txt = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                org.json.JSONObject root = new org.json.JSONObject(txt);
                org.json.JSONObject meta = root.getJSONObject("meta");
                org.json.JSONObject c = meta.getJSONObject("counts");
                nativeData = root;
                if (nativePhase != null) runOnUiThread(() -> nativePhase.setPageData(root));
                if (nativeFault != null) runOnUiThread(() -> nativeFault.setPageData(root));
                if (nativeIv != null) runOnUiThread(() -> nativeIv.setPageData(root));
                if (nativeDict != null) runOnUiThread(() -> nativeDict.setPageData(root));
                if (nativeRefs != null) runOnUiThread(() -> nativeRefs.setPageData(root));
                if (nativeGlossary != null) runOnUiThread(() -> nativeGlossary.setGlossary(root));
                if (nativeRoadmap != null) runOnUiThread(() -> nativeRoadmap.setRoadmap(root));
                if (nativeQuiz != null) runOnUiThread(() -> nativeQuiz.setPageData(root));
                if (nativeHome != null) runOnUiThread(() -> nativeHome.setPageData(root));
                if (nativeSearch != null) runOnUiThread(() -> nativeSearch.setPageData(root));
                if (nativeFav != null) runOnUiThread(() -> nativeFav.setPageData(root));
                if (nativeDrawer != null) runOnUiThread(() -> nativeDrawer.setPageData(root));
                if (nativeMe != null) runOnUiThread(() -> nativeMe.setPageData(root));
                if (settingsPanel != null) {
                    org.json.JSONArray wps = root.optJSONArray("wallpapers");
                    if (wps != null) runOnUiThread(() -> settingsPanel.setWallpapers(wps));
                }
                android.util.Log.i("NativeData", String.format(
                        "装载完成 %.1fKB %dms phases=%d modules=%d faults=%d iv=%d dict=%d hash=%s",
                        txt.length() / 1024f, System.currentTimeMillis() - t0,
                        c.optInt("phases"), c.optInt("modules"), c.optInt("faults"),
                        c.optInt("interview"), c.optInt("dict"), meta.optString("hash")));
            } catch (Throwable e) {
                android.util.Log.e("NativeData", "装载失败", e);
            }
        }, "native-data").start();
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
        /* 原生收藏夹页（N2 收尾）。 */
        nativeFav = new com.netops.handbook.lens.NativeFavView(this);
        addNativePage(nativeFav);
        nativeFav.setNav((route, arg) -> nativeGo(route, arg));

        /* 原生壁纸层：给原生页的玻璃件（AndroidLiquidGlassView）提供静态采样源。 */
        nativeBackdrop = new com.netops.handbook.lens.NativeBackdrop(this);
        nativeBackdrop.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(nativeBackdrop);
        if (lensBar != null) lensBar.setBackdropSource(nativeBackdrop);

        /* 原生学习首页（N2）：必须挂在壁纸层之上（曾先建后建壁纸层，被整屏壁纸挡住）。 */
        /* 原生学习首页（N2）：阶段入口 + 进度环 + 工具箱。 */
        nativeHome = new com.netops.handbook.lens.NativeHomeView(this);
        addNativePage(nativeHome);
        nativeHome.setNav((route, arg) -> nativeGo(route, arg));

        if (nativeHome != null) nativeHome.setGlassSource(nativeBackdrop);

        /* 原生「我的」页 + 设置面板（N2 收尾）。 */
        nativeMe = new com.netops.handbook.lens.NativeMeView(this);
        addNativePage(nativeMe);
        nativeMe.setNav(route -> {
            if ("settings".equals(route)) {
                runOnUiThread(() -> {
                    if (settingsPanel != null) {
                        settingsPanel.show();
                        pushSettingsState();
                    }
                });
            } else {
                nativeGo(route, "");
            }
        });
        settingsPanel = new com.netops.handbook.lens.NativeSettingsPanel(this,
                com.netops.handbook.lens.NativeBlocks.palette(this, resolvedDark(), false),
                (kind, value) -> {
                    android.util.Log.i("NativeSettings", "apply " + kind + " = " + value);
                    if (webView == null) return;
                    String js = "window.NetOpsNativeSet && NetOpsNativeSet("
                            + org.json.JSONObject.quote(kind) + "," + org.json.JSONObject.quote(value) + ");";
                    webView.post(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
                });
        settingsPanel.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(settingsPanel);     /* 面板必须在所有原生页之上 */
        /* 面板开合 → 侧栏退位（侧栏是独立弹窗，永远压在面板上） */
        settingsPanel.setOnOpen(open -> {
            if (lensBar != null) lensBar.setOverlaid(open);
            /* 面板开着的那次背景抓样把半透明遮罩采了进去（玻璃里见上页白卡幽灵字），
               关面板强制重抓一次（2026-10-30 用户报顶栏通透发脏）。 */
            if (!open && appbarBar != null) appbarBar.invalidateBackdrop();
        });

        /* 原生抽屉：最顶层 overlay。开合联动 dock（setOverlaid），否则药丸压住抽屉。 */
        nativeDrawer = new com.netops.handbook.lens.NativeDrawerView(this,
                com.netops.handbook.lens.NativeBlocks.palette(this, resolvedDark(), false),
                (route, arg) -> nativeGo(route, arg),
                () -> { if (lensBar != null) lensBar.setOverlaid(false); });
        nativeDrawer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        /* addView 顺序 = z 序：抽屉必须**最后**加（见下方 addView(nativeDrawer)），
           早加会被后面创建的原生页盖住 —— 抽屉里「阶段一：…」右半截被页面白卡压掉的
           真因（2026-10-28 复查；内容左缩进从 102dp 收到 70dp 后才暴露）。 */

        /* 原生搜索页（N5）：原生输入框 + 命中高亮 + 键盘自适应。 */
        nativeSearch = new com.netops.handbook.lens.NativeSearchView(this);
        addNativePage(nativeSearch);
        nativeSearch.setNav((type, id, pid) -> {
            if ("mod".equals(type)) {
                nativeGo("phase-focus", (pid == null || pid.isEmpty() ? "p-1" : pid) + "|" + id);
            } else {
                nativeGo(type.equals("gloss") ? "glossary" : type.equals("ref") ? "refs" : type, "");
            }
        });

        /* 原生阶段详情页（全量原生化 N1）：WebView 之上覆盖整个内容区；
           顶部让出 appbar（原生页自己不画顶栏），底部让出 dock。 */
        nativePhase = new com.netops.handbook.lens.NativePhaseView(this);
        nativePhase.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        nativePhase.setVisibility(android.view.View.GONE);
        nativePhase.setGlassSource(nativeBackdrop);
        nativePhase.setStateListener((id, kind, on) -> {
            if (webView == null) return;
            String js = "window.NetOpsNativeState && NetOpsNativeState("
                    + org.json.JSONObject.quote(id) + "," + org.json.JSONObject.quote(kind)
                    + "," + on + ");";
            webView.post(() -> {
                if (webView != null) webView.evaluateJavascript(js, null);
            });
        });
        root.addView(nativePhase);

        /* 原生文档列表页（N4）：排障 / 面试 共用 NativeDocList，各自一个 Config。 */
        nativeFault = addDocList(docConfig("排障案例", "案例", "已解决", "faults", "fault", "", ""));
        nativeIv = addDocList(docConfig("面试题库", "问题", "已准备", "interview", "iv", "IVQ", "Q"));

        /* 原生命令字典页（N4）：命令对照表 + 分类筛选。 */
        nativeDict = new com.netops.handbook.lens.NativeDictView(this);
        nativeDict.setGlassSource(nativeBackdrop);
        nativeDict.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        nativeDict.setVisibility(android.view.View.GONE);
        root.addView(nativeDict);

        /* 速查表 / 术语表 / 路线图（N4）：三个轻量原生页。 */
        nativeRefs = new com.netops.handbook.lens.NativeRefsView(this);
        nativeRefs.setGlassSource(nativeBackdrop);
        addNativePage(nativeRefs);
        nativeGlossary = new com.netops.handbook.lens.NativeSimpleList(this);
        nativeGlossary.setGlassSource(nativeBackdrop);
        addNativePage(nativeGlossary);
        nativeRoadmap = new com.netops.handbook.lens.NativeSimpleList(this);
        nativeRoadmap.setGlassSource(nativeBackdrop);
        addNativePage(nativeRoadmap);
        nativeQuiz = new com.netops.handbook.lens.NativeQuizView(this);
        nativeQuiz.setGlassSource(nativeBackdrop);
        addNativePage(nativeQuiz);

        /* 抽屉最后加：z 序压在全部原生页之上（见构造处注释） */
        root.addView(nativeDrawer);

        configureWebView();
        bridge = new Bridge();
        webView.addJavascriptInterface(bridge, "NetBridge");

        pageReady = false;
        rendererGone = false;

        /* 侧栏让位：每次布局后对齐原生页左缩进（旋转/分屏都走这里，幂等） */
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::applyDockInset);

        /* 原生透镜层：叠在 WebView 之上，只画底部药丸那一块。
           它需要 PixelCopy(API 33+) + AGSL，两者都不可用时内部自动降级为半透明，不崩。 */
        try {
            lensBar = LensBarView.attach(this, root, webView);
            lensBar.setDark(resolvedDark());
            if (nativeBackdrop != null) lensBar.setBackdropSource(nativeBackdrop);
            /* 侧栏松手 → 原生立刻切页（不等 JS 路由的 16~204ms） */
            lensBar.setOnCommit(i -> runOnUiThread(() -> {
                if (bridge == null) return;
                String jsRoute = i == 0 ? "learn" : i == 1 ? "fault" : i == 2 ? "dict"
                        : i == 3 ? "iv" : "me";
                switch (i) {
                    case 0: bridge.showNativeHome(); break;
                    case 1: bridge.showNativeFault(); break;
                    case 2: bridge.showNativeDict(); break;
                    case 3: bridge.showNativeIv(); break;
                    default: bridge.showNativeMe(); break;
                }
                /* JS 路由事后同步（6de7805 设计了「JS 确认」但 dock 路径一直没接）：
                   否则 HTML 顶栏标题/玻璃重抓触发器全部冻在旧路由——2026-10-30
                   用户报顶栏玻璃里残留 CLI 页白卡与标题幽灵字，根因即此。
                   go() 的原生 show/hide 幂等（已 VISIBLE 且非脏 → 直接返回）。 */
                nativeGo(jsRoute, "");
            }));
        } catch (Throwable t) {
            Log.w(TAG, "原生透镜层挂载失败（不影响主流程）", t);
            lensBar = null;
        }

        /* R2 原生顶栏：顶部独立窗，画玻璃背景 + 标题 + 三键；
           搜索/抽屉在场时整窗退位（见 AppBarView）。 */
        try {
            appbarBar = com.netops.handbook.lens.AppBarView.attach(this, root, webView);
            appbarBar.setDark(resolvedDark());
            /* 旋转后 onConfigurationChanged 里立即索取几何会量到「旧布局」
               （WebView 还没换向重排）→ 顶栏玻璃停在竖屏宽度（2026-10-30 真机：
               横屏玻璃带只有 1904px，右侧裸壁纸硬缝）。改为 WebView 尺寸真正
               变化后再索取：布局回调即新几何，防抖 120ms 合并连发。 */
            final Runnable[] geoTask = {null};
            webView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
                if (r - l == or_ - ol && b - t == ob - ot) return;
                if (geoTask[0] != null) webView.removeCallbacks(geoTask[0]);
                geoTask[0] = () -> { if (appbarBar != null) appbarBar.requestGeometry(); };
                webView.postDelayed(geoTask[0], 120);
            });
        } catch (Throwable t) {
            Log.w(TAG, "原生顶栏挂载失败（不影响主流程）", t);
            appbarBar = null;
        }

        boolean restored = false;
        if (state != null) {
            try { restored = webView.restoreState(state) != null; } catch (Exception e) { restored = false; }
        }
        if (!restored) webView.loadUrl(page());
    }

    /** 渲染进程没了 / 反复载入失败后的自我了断式重建。 */
    private void rebuildWebView() {
        runOnUiThread(() -> {
            /* 渲染进程被回收时系统文件选择器可能还开着：先按契约回掉挂起的
               <input type=file>（恰好一次 null），否则 onActivityResult 会把
               选择结果喂给已销毁的 WebView（N-3 状态迁移未闭环） */
            if (uploadMessage != null) {
                try { uploadMessage.onReceiveValue(null); } catch (Exception ignored) { }
                uploadMessage = null;
            }
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
            /* 原生层随旧 WebView 一起退役（旧实例的几何/背景抓图全部失效）。
               R1 曾漏了这一步：重建后新旧两个透镜窗叠画，几何各用各的 —— 双实例
               是「重影」类症状的候选根源之一，这里底栏顶栏一并收干净。 */
            if (lensBar != null) {
                try { lensBar.detach(); } catch (Throwable ignored) { }
                lensBar = null;
            }
            if (appbarBar != null) {
                try { appbarBar.detach(); } catch (Throwable ignored) { }
                appbarBar = null;
            }
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
            final WebView failed = webView;
            Log.w(TAG, "首页载入失败，第 " + reloadTries + " 次自动重试：" + why);
            failed.postDelayed(() -> {
                /* 实例身份比对（N-2）：重试窗口内渲染进程被回收重建时，守卫若读
                   字段当前值会放过期重试命中新实例（双启动）。捕获失败实例本身。 */
                if (failed == webView && !rendererGone) failed.loadUrl(page());
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        /* 回前台强制重抓顶栏玻璃底图：切后台期间 PixelCopy 回调可能被丢，
           copyBusy 闩锁会让玻璃冻在最后一次成功采样（2026-10-30 用户实测：
           正常用一阵后顶栏玻璃变成别的页的残像，重启才恢复）。 */
        if (hasFocus && appbarBar != null) appbarBar.invalidateBackdrop();
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
        ioPool.shutdownNow();
        /* 原生层活在独立 Window 里，不收会 window leak */
        if (lensBar != null) {
            try {
                lensBar.detach();
            } catch (Throwable ignored) {
                // 销毁期异常无意义
            }
            lensBar = null;
        }
        if (appbarBar != null) {
            try {
                appbarBar.detach();
            } catch (Throwable ignored) {
                // 销毁期异常无意义
            }
            appbarBar = null;
        }
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
        s.setAllowFileAccess(true);            // assets 与外部目录（devpage 热加载）都要
        s.setAllowContentAccess(false);
        /* 2026-09-24：devpage 指向应用外部目录后，file:// 页面加载同目录子资源
           （壁纸 img / CSS url）被 WebView 判为跨 file 访问而拒载（真机探针实证
           img FAIL；assets 源不受影响——android_asset 走内部资产通道不经文件系统）。
           打开两个开关；内容全部本地打包、blockExternal 拦外部导航，无远程输入面。 */
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
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

        /* 玻璃核心：offscreenPreRaster 预栅格化离屏缓冲，保障 backdrop-filter
           模糊/色散渲染。2026-09-29 定案：WebView 已升 Chromium 151（lens-probe
           实证 blur 无需硬件层），LAYER_TYPE_HARDWARE 反而令超长页 fling 时
           栅格被驱逐而整页丢内容 —— 回归 LAYER_TYPE_NONE 走默认合成。 */
        s.setOffscreenPreRaster(true);
        preRasterDropped = false;
        webView.setLayerType(View.LAYER_TYPE_NONE, null);

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
            /**
             * 2026-09-25（探针三臂鉴别实证）：Android 17 / Chrome 151 的 WebView 对
             * file→file 访问整体硬拦 —— 主文档可载，但子资源 img 与 XHR 全拒
             * （status=0），setAllowFileAccessFromFileURLs/Universal 两个开关已失效。
             * devpage 热加载源的子资源由此处从应用进程直接流式供给，绕开 file 策略；
             * 只接管应用外部目录，android_asset 等其余请求返回 null 走默认通道。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request == null ? null : request.getUrl();
                if (u == null || !"file".equalsIgnoreCase(u.getScheme())) return null;
                File ext = getExternalFilesDir(null);
                if (ext == null) return null;
                String url = u.toString();
                String prefix = "file://" + ext.getAbsolutePath() + "/";
                if (!url.startsWith(prefix)) return null;
                String rel = url.substring(prefix.length());
                /* URL 里的路径是百分号编码的（中文名 → %E7%BA%B3…），磁盘上是解码后的
                   文件名——不先解码就会全部 404（首次实测就是这么败的）。Uri.decode 只解
                   %XX、不动 '+'，正是这里要的语义。 */
                rel = Uri.decode(rel);
                try {
                    File f = new File(ext, rel);
                    if (rel.contains("..")
                            || !f.getCanonicalPath().startsWith(ext.getCanonicalPath() + File.separator)
                            || !f.isFile()) {
                        return emptyResponse(404, "not found");
                    }
                    return new WebResourceResponse(mimeFor(f.getName()), null,
                            new FileInputStream(f));
                } catch (Exception e) {
                    Log.w(TAG, "子资源接管失败：" + rel, e);
                    return emptyResponse(500, "intercept error");
                }
            }

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
                if (failingUrl != null && failingUrl.startsWith(page())) handleLoadFailure(description);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;   // 子资源（壁纸）失败忽略
                String u = request.getUrl() == null ? "" : request.getUrl().toString();
                if (!u.startsWith(page())) return;
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
                /* 键盘弹出/收起：透镜窗随之收起/还原（否则它的触摸拦截带会
                   悬在键盘上沿压住页面内容） */
                if (lensBar != null) lensBar.setImeVisible(keyboard > 0);
                /* 原生阶段页：让出顶黑边+appbar、底部+dock */
                if (nativePhase != null) {
                    final int t2 = bars.top, b2 = bars.bottom;
                    nativePhase.post(() -> {
                        if (nativePhase != null) nativePhase.setSafeArea(t2, b2);
                        if (nativeFault != null) nativeFault.setSafeArea(t2, b2);
                        if (nativeIv != null) nativeIv.setSafeArea(t2, b2);
                        if (nativeDict != null) nativeDict.setSafeArea(t2, b2);
                        if (nativeRefs != null) nativeRefs.setSafeArea(t2, b2);
                        if (nativeGlossary != null) nativeGlossary.setSafeArea(t2, b2);
                        if (nativeRoadmap != null) nativeRoadmap.setSafeArea(t2, b2);
                        if (nativeQuiz != null) nativeQuiz.setSafeArea(t2, b2);
                        if (nativeHome != null) nativeHome.setSafeArea(t2, b2);
                        if (nativeSearch != null) nativeSearch.setSafeArea(t2, b2);
                        if (nativeMe != null) nativeMe.setSafeArea(t2, b2);
                        if (nativeFav != null) nativeFav.setSafeArea(t2, b2);
                        if (settingsPanel != null) settingsPanel.setSafeArea(t2, b2);
                    });
                }
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
        remeasureLensSoon();
        if (appbarBar != null) appbarBar.requestGeometry();   // 安全区变了顶栏矩形必变
    }

    /** 三连发重测透镜几何（120/500/900ms）。
     *  安全区落进网页后底栏会挪位（env(safe-area-inset-*) 生效），随后重测透镜
     *  几何。底栏自身有 ~300ms 的宽度/位置过渡（旋转、浮动收起），单发 120ms
     *  会量在动画半途 —— 2026-10-26 横屏实测报到「药丸 519px / 格 182px」的
     *  中间态，药丸带画错位置后横屏完全拖不动。以最后一次（过渡已 settles）为准。
     *  ⚠ 不能只挂在 flushInsets 上： insets 监听仅在签名变化时才走 flushInsets，
     *  而手势导航平板横竖屏的 systemBars insets 完全相同（2026-10-26 小米平板
     *  实测 logcat -c 后旋转零几何日志）→ 旋转后原生几何永不重测，药丸带钳在
     *  旧方向位置，按压整条被转发进 WebView。旋转必须无条件补测。 */
    private void remeasureLensSoon() {
        if (lensBar == null || webView == null) return;
        webView.postDelayed(() -> {
            if (lensBar != null && webView != null) lensBar.requestGeometry();
        }, 120);
        webView.postDelayed(() -> {
            if (lensBar != null && webView != null) lensBar.requestGeometry();
        }, 500);
        webView.postDelayed(() -> {
            if (lensBar != null && webView != null) lensBar.requestGeometry();
        }, 900);
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
        runOnUiThread(() -> {
            applyBarAppearance(dark);
            if (lensBar != null) lensBar.setDark(dark);
            if (nativePhase != null) nativePhase.setDark(dark);
            if (nativeFault != null) nativeFault.setDark(dark);
            if (nativeIv != null) nativeIv.setDark(dark);
            if (nativeDict != null) nativeDict.setDark(dark);
            if (nativeRefs != null) nativeRefs.setDark(dark);
            if (nativeGlossary != null) nativeGlossary.setDark(dark);
            if (nativeRoadmap != null) nativeRoadmap.setDark(dark);
            if (nativeQuiz != null) nativeQuiz.setDark(dark);
            if (nativeHome != null) nativeHome.setDark(dark);
            if (nativeSearch != null) nativeSearch.setDark(dark);
            if (nativeMe != null) nativeMe.setDark(dark);
            if (nativeFav != null) nativeFav.setDark(dark);   // 透镜暗色亮度 1.33 跟随主题
            if (appbarBar != null) appbarBar.setDark(dark);       // 顶栏配色/背景整套跟随
        });
    }

    // ------------------------------------------------------------------ 返回键

    private void bindBackKey() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView == null) { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); return; }
                /* 应答窗口内的第二次返回直接忽略：冷启动网页未就绪时 500ms 超时
                   会按「在根页面」处理，连按两下会被误判成确认退出（2026-09-30
                   用户反馈「第一次打开就弹退出提示、之后确认机制消失」的竞态根源）。 */
                if (backAckPending) return;
                backAckPending = true;
                final AtomicBoolean answered = new AtomicBoolean(false);
                webView.evaluateJavascript(JS_BACK, value -> {
                    if (answered.compareAndSet(false, true)) {
                        backAckPending = false;
                        onBackConsumed("true".equals(value));
                    }
                });
                webView.postDelayed(() -> {
                    if (answered.compareAndSet(false, true)) {
                        backAckPending = false;
                        Log.w(TAG, "网页未在时限内应答返回键，按根页面处理");
                        onBackConsumed(false);
                    }
                }, BACK_ACK_TIMEOUT_MS);
            }
        });
    }

    /** 返回键归属：网页消费则什么都不做；处于根页面走「再按一次退出」防误触。
        窗口期内直接 finishAndRemoveTask——不走 dispatcher 回退链（setEnabled(false)
        后的回退行为依赖 androidx 兜底实现，存在不 finish 的陷阱；残留的任务还会让
        下次进入时的退出确认错乱）。 */
    private void onBackConsumed(boolean consumed) {
        if (consumed) return;
        long now = System.currentTimeMillis();
        if (now - lastBackPressMs < BACK_EXIT_INTERVAL_MS) {
            lastBackPressMs = 0;
            finishAndRemoveTask();
        } else {
            lastBackPressMs = now;
            Toast.makeText(MainActivity.this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ JS 桥

    private Bridge bridge;

    private class Bridge {

        /** 网页初始化完毕，补发一次安全区，避免首帧错位。 */
        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> {
                pageReady = true;
                lastInsetSig = Integer.MIN_VALUE;
                if (root != null) ViewCompat.requestApplyInsets(root);
                flushInsets();
                /* 页面就绪 = 布局已定，这时索取底栏几何最准（早了量到的矩形是错的）。
                   由原生主动索取，成功与否都会打日志，不再静默失败。 */
                if (lensBar != null) lensBar.requestGeometry();
                if (appbarBar != null) appbarBar.requestGeometry();
            });
        }

        /** 透镜几何诊断：页面原始测量值无条件回传（排查旋转期护栏误拦）。 */
        @JavascriptInterface
        public void lensDiag(String s) {
            Log.i("LensBar", "诊断 " + s);
        }

        @JavascriptInterface
        public void setTheme(String mode) {
            boolean dark = "dark".equals(mode);
            rememberTheme(dark);
            pushTheme(dark);
        }

        /**
         * 页面量好底栏几何后上报（CSS px）。几何真源仍在 CSS，原生层不做推导
         * （栏有边距、药丸比一格宽 8px、还有 safe-area，推导必错）。
         * 走到这里就说明 JS 侧代码真的执行了 —— 这条日志本身就是"接管是否成功"的判据。
         */
        @JavascriptInterface
        public void setLensGeometry(float slot, float pillW, float pillH,
                                    float firstCx, float pillCy, int count, float viewportH) {
            if (lensBar == null) return;
            runOnUiThread(() -> {
                lensBar.setLensGeometry(slot, pillW, pillH, firstCx, pillCy, count, viewportH);
                Log.i("LensBar", String.format(
                        "几何已同步 一格%.1f 药丸%.1f×%.1f 首格中心(%.1f,%.1f) 共%d格",
                        slot, pillW, pillH, firstCx, pillCy, count));
                if (!lensBar.isDockVertical()) {
                    lensBar.warmUp();
                }
            });
        }

        /** 页面自己切了 tab（例如深链/返回）时回传，避免两边选中态不一致。 */
        @JavascriptInterface
        public void setSelectedIndex(int index) {
            if (lensBar == null) return;
            runOnUiThread(() -> lensBar.setSelectedIndex(index));
        }

        /** 阶段页全量数据走 assets/native-data.json（loadNativeData 解析后直接喂给视图），
         *  页面层只需把旧 localStorage 状态并进来做迁移。 */
        @JavascriptInterface
        public void setLegacyState(String doneJson, String favJson) {
            if (nativePhase == null) return;
            runOnUiThread(() -> nativePhase.mergeLegacyState(doneJson, favJson));
        }

        /** 页面层（旧 WebView 视图）改了掌握/收藏：同步给原生，避免两边口径分叉。 */
        @JavascriptInterface
        public void setNativeState(String id, String kind, boolean on) {
            if (nativePhase == null) return;
            runOnUiThread(() -> nativePhase.setState(id, kind, on));
        }

        /** 直铺模式（有壁纸）：原生页透明底 + 半透明卡，与 Web 层观感一致。 */
        /** 抽屉开关：抽屉是 WebView 画的一层，原生页在它之上 → 打开抽屉时必须
         *  把所有原生页收起，关闭后由页面层按当前路由重新 show（无记忆状态）。 */
        @JavascriptInterface
        public void setDrawerOpen(boolean open) {
            if (!open) return;
            runOnUiThread(() -> {
                if (nativePhase != null) nativePhase.hide();
                if (nativeFault != null) nativeFault.hide();
                if (nativeIv != null) nativeIv.hide();
                if (nativeDict != null) nativeDict.hide();
                if (nativeRefs != null) nativeRefs.hide();
                if (nativeGlossary != null) nativeGlossary.hide();
                if (nativeRoadmap != null) nativeRoadmap.hide();
                if (nativeQuiz != null) nativeQuiz.hide();
                if (nativeHome != null) nativeHome.hide();
            });
        }

        @JavascriptInterface
        public void setNativePageMode(boolean direct) {
            applyNativePageMode(direct, "", 8f);
        }

        /** 带壁纸文件的版本：玻璃件（LiquidGlassView）用它采样原生壁纸层。 */
        @JavascriptInterface
        public void setNativePageMode2(boolean direct, String wpFile, float blurDp) {
            applyNativePageMode(direct, wpFile, blurDp);
        }

        /** 壁纸已解码落画（JS floorBands 回调）：dock 栏体玻璃此刻才能抓到新壁纸。 */
        @JavascriptInterface
        public void setNativeDockBgRev(int rev) {
            runOnUiThread(() -> { if (lensBar != null) lensBar.onBackdropChanged(); });
        }

        private void applyNativePageMode(boolean direct, String wpFile, float blurDp) {
            runOnUiThread(() -> {
                if (nativeBackdrop != null) nativeBackdrop.setWallpaper(direct ? wpFile : "", blurDp);
                if (lensBar != null) lensBar.onBackdropChanged();   // 栏体玻璃采样源换代，重抓
                if (nativePhase != null) nativePhase.setDirectMode(direct);
                if (nativeFault != null) nativeFault.setDirectMode(direct);
                if (nativeIv != null) nativeIv.setDirectMode(direct);
                if (nativeDict != null) nativeDict.setDirectMode(direct);
                if (nativeRefs != null) nativeRefs.setDirectMode(direct);
                if (nativeGlossary != null) nativeGlossary.setDirectMode(direct);
                if (nativeRoadmap != null) nativeRoadmap.setDirectMode(direct);
                if (nativeQuiz != null) nativeQuiz.setDirectMode(direct);
                if (nativeHome != null) nativeHome.setDirectMode(direct);
                if (nativeSearch != null) nativeSearch.setDirectMode(direct);
                if (nativeFav != null) nativeFav.setDirectMode(direct);
                if (nativeMe != null) nativeMe.setDirectMode(direct);
            });
        }

        @JavascriptInterface
        public void showNativeFault() {
            if (nativeFault == null) return;
            runOnUiThread(() -> { hideNativePagesExcept("fault"); nativeFault.show(); });
        }

        @JavascriptInterface
        public void hideNativeFault() {
            if (nativeFault == null) return;
            runOnUiThread(() -> nativeFault.hide());
        }

        @JavascriptInterface
        public void showNativeDict() {
            if (nativeDict == null) return;
            runOnUiThread(() -> { hideNativePagesExcept("dict"); nativeDict.show(); });
        }

        @JavascriptInterface
        public void hideNativeDict() {
            if (nativeDict == null) return;
            runOnUiThread(() -> nativeDict.hide());
        }

        @JavascriptInterface
        public void showNativeRefs() { if (nativeRefs != null) runOnUiThread(() -> { hideNativePagesExcept("refs"); nativeRefs.show(); }); }

        @JavascriptInterface
        public void hideNativeRefs() { if (nativeRefs != null) runOnUiThread(() -> nativeRefs.hide()); }

        @JavascriptInterface
        public void showNativeGlossary() { if (nativeGlossary != null) runOnUiThread(() -> { hideNativePagesExcept("glossary"); nativeGlossary.show(); }); }

        @JavascriptInterface
        public void hideNativeGlossary() { if (nativeGlossary != null) runOnUiThread(() -> nativeGlossary.hide()); }

        @JavascriptInterface
        public void showNativeRoadmap() { if (nativeRoadmap != null) runOnUiThread(() -> { hideNativePagesExcept("roadmap"); nativeRoadmap.show(); }); }

        @JavascriptInterface
        public void hideNativeRoadmap() { if (nativeRoadmap != null) runOnUiThread(() -> nativeRoadmap.hide()); }

        @JavascriptInterface
        public void showNativeFav() {
            if (nativeFav == null) return;
            runOnUiThread(() -> { hideNativePagesExcept("fav"); nativeFav.show(); });
        }

        @JavascriptInterface
        public void hideNativeFav() { if (nativeFav != null) runOnUiThread(() -> nativeFav.hide()); }

        @JavascriptInterface
        public void showNativeDrawer() {
            if (nativeDrawer == null) return;
            runOnUiThread(() -> {
                nativeDrawer.open();
                if (lensBar != null) lensBar.setOverlaid(true);
            });
        }

        @JavascriptInterface
        public void hideNativeDrawer() {
            if (nativeDrawer == null) return;
            runOnUiThread(() -> nativeDrawer.close());
        }

        @JavascriptInterface
        public void hideAllNativePages() {
            runOnUiThread(() -> hideNativePagesExcept(null));
        }

        @JavascriptInterface
        public void showNativeMe() {
            if (nativeMe == null) return;
            runOnUiThread(() -> { hideNativePagesExcept("me"); nativeMe.show(); });
        }

        @JavascriptInterface
        public void hideNativeMe() { if (nativeMe != null) runOnUiThread(() -> nativeMe.hide()); }

        /** 设置面板打开时，页面层把当前状态推回来。 */
        @JavascriptInterface
        public void setSettingsState(String theme, String wallpaper, String glassJson) {
            if (settingsPanel == null) return;
            try {
                org.json.JSONObject g = new org.json.JSONObject(glassJson);
                boolean dk = resolvedDark();
                runOnUiThread(() -> {
                    settingsPanel.setDark(dk);
                    settingsPanel.updateState(theme, wallpaper, g);
                });
            } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void showNativeSearch() {
            if (nativeSearch == null) return;
            runOnUiThread(() -> { hideNativePagesExcept("search"); nativeSearch.show(); });
        }

        @JavascriptInterface
        public void hideNativeSearch() { if (nativeSearch != null) runOnUiThread(() -> nativeSearch.hide()); }

        @JavascriptInterface
        public void showNativeHome() { if (nativeHome != null) runOnUiThread(() -> { hideNativePagesExcept("home"); nativeHome.show(); }); }

        @JavascriptInterface
        public void hideNativeHome() { if (nativeHome != null) runOnUiThread(() -> nativeHome.hide()); }

        @JavascriptInterface
        public void showNativeQuiz() { if (nativeQuiz != null) runOnUiThread(() -> { hideNativePagesExcept("quiz"); nativeQuiz.show(); }); }

        @JavascriptInterface
        public void hideNativeQuiz() { if (nativeQuiz != null) runOnUiThread(() -> nativeQuiz.hide()); }

        @JavascriptInterface
        public void showNativeIv() {
            if (nativeIv == null) return;
            runOnUiThread(() -> { hideNativePagesExcept("iv"); nativeIv.show(); });
        }

        @JavascriptInterface
        public void hideNativeIv() {
            if (nativeIv == null) return;
            runOnUiThread(() -> nativeIv.hide());
        }

        /** 原生阶段详情页显示（N1 起这就是阶段页本体，不再是渲染代理）。 */
        @JavascriptInterface
        public void showNativePhase(String pid) {
            if (nativePhase == null) return;
            runOnUiThread(() -> { hideNativePagesExcept("phase"); nativePhase.show(pid); });
        }

        /** 「继续学习」/收藏跳转：直接展开目标模块卡并滚到它。 */
        @JavascriptInterface
        public void showNativePhaseFocus(String pid, String mid) {
            if (nativePhase == null) return;
            runOnUiThread(() -> { hideNativePagesExcept("phase"); nativePhase.show(pid, mid); });
        }

        @JavascriptInterface
        public void hideNativePhase() {
            if (nativePhase == null) return;
            runOnUiThread(() -> nativePhase.hide());
        }

        /** 原生底栏开关（页面 boot 读 localStorage netops_native_dock 上报）。
         *  开 = LensBarView 画全部底栏外观、HTML 底栏 visibility:hidden；关 = 回退 HTML。 */
        @JavascriptInterface
        public void setNativeDock(boolean on) {
            if (lensBar == null) return;
            runOnUiThread(() -> lensBar.setNativeDock(on));
        }

        /* ---- R2 原生顶栏桥 ---- */

        /** 原生顶栏开关（页面 boot 读 localStorage netops_native_appbar 上报，默认开）。
         *  开 = AppBarView 画全部顶栏外观、HTML 顶栏 visibility:hidden；关 = 回退 HTML。 */
        @JavascriptInterface
        public void setNativeAppbar(boolean on) {
            if (appbarBar == null) return;
            runOnUiThread(() -> appbarBar.setNativeBar(on));
        }

        /** 顶栏状态推送（标题/导航键/主题键/滚动/搜索/抽屉/玻璃/壁纸版本）。
         *  32-boot 的 MutationObserver 合并 30ms 后调用，JSON 很小，UI 线程直接解析。 */
        @JavascriptInterface
        public void setAppBarState(String json) {
            if (appbarBar == null) return;
            runOnUiThread(() -> appbarBar.setState(json));
        }

        /** 顶栏几何上报（原生索取制应答）：bar/title/nav/search/theme 五个矩形，
         *  屏幕物理 px（CSS×devicePixelRatio）。落地后预抓一次背景。 */
        @JavascriptInterface
        public void setAppBarGeometry(String json) {
            if (appbarBar == null) return;
            runOnUiThread(() -> {
                appbarBar.setGeometry(json);
                appbarBar.warmUp();
                /* 竖排侧栏让开顶栏：顶栏底边 + 8dp 呼吸，否则两条玻璃叠着切一刀 */
                float below = appbarBar.barBottomPx()
                        + 8f * getResources().getDisplayMetrics().density;
                if (lensBar != null) lensBar.setRailTopInset(below);
                if (nativeDrawer != null) nativeDrawer.setTopInset(below);
            });
        }

        /** 栏矩形上报（CSS×dpr 物理像素）——原生底栏容器/触摸判定的几何真源。 */
        @JavascriptInterface
        public void setLensBarRect(float l, float t, float w, float h) {
            if (lensBar == null) return;
            runOnUiThread(() -> lensBar.setLensBarRect(l, t, w, h));
        }

        /** 页面底部弹层（壁纸/全局质感 sheet）开合推送：弹层在场时收起透镜窗，
         *  否则药丸触摸带（独立窗口永远置顶）会吃掉 sheet 底部控件（玻璃开关）的
         *  点击。旧页面没有此方法时静默忽略。 */
        @JavascriptInterface
        public void setSheetOpen(boolean open) {
            if (lensBar == null) return;
            runOnUiThread(() -> lensBar.setOverlaid(open));
        }

        /**
         * 触感反馈。用 View.performHapticFeedback 而非 Vibrator：
         * 尊重系统「触感反馈」开关、不需要 VIBRATE 权限，力度也和原生控件一致。
         */
        @JavascriptInterface
        public void haptic(int ms) {
            runOnUiThread(() -> {
                if (webView == null) return;
                /* ms <= 0：选中态轻点。用 CLOCK_TICK 对齐 FlClash/iOS 的分段选择反馈
                   （2026-10-22 真机抓 logcat：performHapticFeedback constant=4，约 15ms）。 */
                int effect = ms >= 16
                        ? HapticFeedbackConstants.LONG_PRESS
                        : (ms <= 0 ? HapticFeedbackConstants.CLOCK_TICK
                                   : HapticFeedbackConstants.VIRTUAL_KEY);
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
            /* 写盘/MediaStore 是阻塞 I/O，放后台执行器（N-1）；完成后回主线程只做 Toast */
            ioPool.execute(() -> {
                String where;
                try {
                    where = writeDocument(fileName, body);
                } catch (Exception e) {
                    Log.w(TAG, "导出失败", e);
                    where = null;
                }
                final String msg = where != null ? ("已导出到 " + where) : "导出失败";
                runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
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
        /* 2026-09-25：子资源接管用——壁纸/样式/脚本按正确 MIME 流出 */
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript";
        return "application/octet-stream";
    }

    /** 拦截路径上的最小错误响应（404/500），绝不让接管逻辑自身抛异常打断加载。 */
    private WebResourceResponse emptyResponse(int code, String reason) {
        try {
            return new WebResourceResponse("text/plain", "utf-8", code, reason, null,
                    new java.io.ByteArrayInputStream(new byte[0]));
        } catch (Exception e) {
            return null;
        }
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
               现在按界内缩放重压缩，控制在网页侧 localStorage 能吃下的量级。
               解码+多轮压缩是重活，放后台执行器（N-1）；结果回主线程才能碰 WebView。 */
            ioPool.execute(() -> {
                String dataUrl = null;
                try { dataUrl = readImageAsDataUrl(uri); } catch (Exception e) { Log.w(TAG, "解码选图失败", e); }
                if (dataUrl == null) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "读取图片失败，换一张或改用内置壁纸", Toast.LENGTH_LONG).show());
                    return;
                }
                final String js = "if(window.NetOpsOnWallpaper) NetOpsOnWallpaper('" + dataUrl + "');";
                if (webView != null) webView.post(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
            });
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
        boolean isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (nativeBackdrop != null) {
            nativeBackdrop.notifyOrientationChanged(isLandscape);
        }
        // 系统深浅色切换时同步系统栏；网页侧 auto 模式由 CSS media query 自行响应
        applyBarAppearance(resolvedDark());
        if (lensBar != null) {
            lensBar.setDark(resolvedDark());
            lensBar.reflow();
            lensBar.onBackdropChanged(); // 旋转后壁纸切换，通知玻璃层重采样
        }
        if (settingsPanel != null) {
            settingsPanel.build();
        }
        if (nativePhase != null) nativePhase.setDark(resolvedDark());
        if (nativeFault != null) nativeFault.setDark(resolvedDark());
        if (nativeIv != null) nativeIv.setDark(resolvedDark());
        if (nativeDict != null) nativeDict.setDark(resolvedDark());
        if (nativeRefs != null) nativeRefs.setDark(resolvedDark());
        if (nativeGlossary != null) nativeGlossary.setDark(resolvedDark());
        if (nativeRoadmap != null) nativeRoadmap.setDark(resolvedDark());
        if (nativeQuiz != null) nativeQuiz.setDark(resolvedDark());
        if (nativeHome != null) nativeHome.setDark(resolvedDark());
        if (nativeSearch != null) nativeSearch.setDark(resolvedDark());
        if (nativeFav != null) nativeFav.setDark(resolvedDark());
        if (nativeMe != null) nativeMe.setDark(resolvedDark());
        if (appbarBar != null) {
            appbarBar.setDark(resolvedDark());
            appbarBar.requestGeometry();   // 旋转后顶栏矩形无条件补测（R1 同款教训）
        }
        if (root != null) {
            ViewCompat.requestApplyInsets(root);
        }
        /* 旋转后透镜几何无条件补测：insets 签名在横竖屏间可能不变（见
           remeasureLensSoon 注释），不能指望 insets 分发顺带重测 */
        remeasureLensSoon();
    }
}
