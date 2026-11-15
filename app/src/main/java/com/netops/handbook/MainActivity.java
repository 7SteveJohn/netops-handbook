package com.netops.handbook;

import android.annotation.SuppressLint;
import com.netops.handbook.lens.NetOpsShell;
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
import com.netops.handbook.lens.ThemeBus;

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
    /** 用户选的配色模式（"auto" / "light" / "dark"）—— 全应用唯一真源：
     *  原生各层、网页层（NetBridge.themeMode）都读它，冷启动首帧也用它。 */
    private static final String KEY_THEME = "theme";

    private static final int BG_DARK = 0xFF0B1220;
    private static final int BG_LIGHT = 0xFFF5F7FB;

    /** 询问网页是否消费了本次返回；返回字符串 "true" / "false"。 */
    private static final String JS_BACK =
            "(function(){try{return (typeof NetOpsBack==='function')?!!NetOpsBack():false;}catch(e){return false;}})()";

    private WebView webView;
    private FrameLayout root;
    /** View 层退役(2026-11-14)：覆盖层容器(搜索/设置/抽屉/切换淡出)，z 在 ComposeView 之上。 */
    private FrameLayout overLayer;
    /** USE_GLASS2 宿主的 ComposeView：AppBarView 触摸转发目标(anchor)。 */
    private androidx.compose.ui.platform.ComposeView composeHost;
    /** 液态玻璃内容容器：壁纸+WebView，挂折射 RenderEffect（2026-11-02）。 */
    private FrameLayout contentContainer;
    /** JS 开关在 attach 前上报时的缓存态（boot 时序：attach 后补施）。 */
    private boolean nativeDockPending = true;
    private com.netops.handbook.lens.NativePhaseView nativePhase;
    private com.netops.handbook.lens.NativeBackdrop nativeBackdrop;
    /** 快照交叉淡化覆盖层（P1-3，2026-11-09）：页之上、抽屉之下，抓帧后 160ms 淡出。 */
    private android.widget.ImageView switchOverlay;
    /** 当前显示的原生页 keep 名（null=WebView 路由）：重复桥调用同页短路用。 */
    private String currentNativeKeep = null;
    private com.netops.handbook.lens.NativeHomeView nativeHome;
    private com.netops.handbook.lens.NativeSearchView nativeSearch;
    /** M1b(2026-11-14)：CLI 页 Compose 宿主（唯一非 View 原生页；M1d 摘 WebView 后
     *  全部页面进 Compose 树的先行样本）。 */

    /** M3-2②：模拟测验 Compose 页宿主。 */

    /** M3-2③：收藏夹/我的 Compose 页宿主。 */

    /** M3-2④：学习首页 Compose 页宿主。 */

    /** M3-2⑤：命令字典 Compose 页宿主。 */

    /** M3-2⑥：排障/面试 Compose 页宿主（DocListConfig 驱动双路由）。 */

    /** M3-2⑦：阶段详情 Compose 页宿主。 */
    private androidx.compose.ui.platform.ComposeView composePhase;
    private org.json.JSONObject nativeDataRoot;
    private com.netops.handbook.lens.NativeMeView nativeMe;
    /* M4-3：NativeSettingsPanel 退役（Compose SettingsPage 接管）。 */
    private com.netops.handbook.lens.NativeFavView nativeFav;
    /* M4-2(2026-11-16)：抽屉 Compose 化（DrawerOverlay），View 版退役。 */
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
    private static final int REQ_IMPORT_BACKUP = 9002;
    /** 文件导入（<input type=file> 选择器）请求码。 */
    private static final int REQ_CHOOSER = 9002;
    /** 文件导入回调（onShowFileChooser 暂存，onActivityResult 回传）。 */
    private android.webkit.ValueCallback<Uri[]> uploadMessage = null;

    private boolean pageReady = false;
    private String pendingInsetsJs = null;
    private int lastInsetSig = Integer.MIN_VALUE;
    /** 顶部安全区（状态栏 + 刘海）高度，物理 px。原生顶栏自测要它当栏顶 padding，
     *  取最近一次 insets 分发的 bars.top（P0-2：不再问 WebView 要几何）。 */
    private int statusTopInsetPx = 0;
    private int safeBottomPx = 0;
    private int safeLeftPx = 0;

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
        /* ❗先于 Compose 首帧触碰 WallpaperPrefs：object 的 Compose State 若在
           组合快照内才类初始化，会落进未 apply 的子快照 → 「Reading a state that
           was created after the snapshot was taken」崩溃（2026-11-14 18:54 实锤，
           纯时序竞态：native-data 后台线程先碰则幸存）。 */
        com.netops.handbook.lens.WallpaperPrefs.INSTANCE.getClass();
        /* View 层退役(2026-11-14):USE_GLASS2 时三层宿主——
           root(壁纸 backdrop+View 残页) → ComposeView(壁纸 Image+页面+dock) →
           overLayer(搜索/设置/抽屉 View 覆盖层)。树内零 AndroidView：MagicOS
           interop 三坑(嵌套 ComposeView 不合成/View 盖 Compose 兄弟/guard
           2×bar 双重定位)全因 View 层内嵌。overLayer 各孩子自管显隐(默认
           GONE)，容器不拦截触摸;false = 旧 PopupWindow 链。 */
        overLayer = new FrameLayout(this);
        overLayer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (com.netops.handbook.lens.glass2.DockBus.USE_GLASS2) {
            composeHost = com.netops.handbook.lens.glass2.GlassHostKt.installGlassHost(this, root, overLayer,
                new com.netops.handbook.lens.glass2.GlassHostHooks() {
                    @Override public void onGo(String route, String arg) {
                        com.netops.handbook.lens.Router.INSTANCE.go(route, arg, false);
                    }
                    @Override public void onExport() { exportBackup(); }
                    @Override public void onImport() { importBackup(); }
                    @Override public void onSettings() { /* M4-3：settings 路由化（onGo("settings")），此钩子退役。 */ }
                    @Override public void onState(String id, String kind, boolean on) {
                        markStatePagesDirty();
                    }
                    @Override public void onDrawerClosed() {
                        /* M4-2：Compose 抽屉收起动画走完 → dock/顶栏退位收回。 */
                        pushOverlaidAll(false);
                    }
                    @Override public void onSettingsApply(String kind, String value) {
                        applySetting(kind, value);
                    }
                    @Override public void onAlbum() { launchAlbumPicker(); }
                    @Override public void onBack() {
                        com.netops.handbook.lens.Router.INSTANCE.back();
                    }
                });
            /* dock 弹窗玻璃的内容源=内容树 View(PageSnap 软录,View 层退役终态)。 */
            com.netops.handbook.lens.glass2.DockBus.INSTANCE.contentView = composeHost;
        } else {
            setContentView(root);
        }

        applyBarAppearance(dark);
        bindInsets();
        bindBackKey();

        createContentShell(bg);
        /* ThemeBus 单源初值：所有消费者已在 createWebView 里 register（register 自带
           当前值回调），这里把总线状态同步成记忆主题——首帧前统一广播。 */
        ThemeBus.setDark(resolvedDark());
        loadNativeData();
        wireNativeRouter();
        /* M1c：设置真源 SP 启动应用（JS 推送链到达前先有正确初值；M1d 摘 WebView
           后 SP 即唯一真源）。theme 已在 ThemeBus/resolvedDark 链上，不重复。 */
        applyGlassLevel(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_GLASS, "ultra"));
        applyDockStyle(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DOCKSTYLE_DOCKED, false));
    }

    /** M2-4(2026-11-14)：WallpaperPrefs 驱动入口（原 applyNativePageMode 的公开包装）。 */
    public void applyPageMode(boolean direct, String wpFile, float blurDp) {
        applyNativePageMode(direct, wpFile, blurDp);
    }

    /** 壁纸/直铺模式全页广播（M1d-3 手术误删，按 HEAD 原样恢复）。 */
    private void applyNativePageMode(boolean direct, String wpFile, float blurDp) {
        runOnUiThread(() -> {
            if (nativeBackdrop != null) nativeBackdrop.setWallpaper(direct ? wpFile : "", blurDp);
            com.netops.handbook.ui.AppShellState.INSTANCE.setDirect(direct);
            if (lensBar != null) lensBar.onBackdropChanged();
            if (appbarBar != null) appbarBar.invalidateBackdrop();
            if (nativeHome != null) nativeHome.setDirectMode(direct);
            if (nativePhase != null) nativePhase.setDirectMode(direct);
            if (nativeFault != null) nativeFault.setDirectMode(direct);
            if (nativeIv != null) nativeIv.setDirectMode(direct);
            if (nativeDict != null) nativeDict.setDirectMode(direct);
            if (nativeRefs != null) nativeRefs.setDirectMode(direct);
            if (nativeGlossary != null) nativeGlossary.setDirectMode(direct);
            if (nativeRoadmap != null) nativeRoadmap.setDirectMode(direct);
            if (nativeQuiz != null) nativeQuiz.setDirectMode(direct);
            if (nativeSearch != null) nativeSearch.setDirectMode(direct);
            if (nativeFav != null) nativeFav.setDirectMode(direct);
            if (nativeMe != null) nativeMe.setDirectMode(direct);
        });
    }

    /* ---- M1c：设置真源收口（SP 落地 + 原生直驱；桥与面板回调共用） ---- */

    public void applyThemeMode(String mode) {
        String m = ("light".equals(mode) || "dark".equals(mode)) ? mode : "auto";
        rememberTheme(m);
        boolean dark = resolvedDark(m);
        ThemeBus.setDark(dark);
        pushTheme(dark);
        applyBarAppearance(dark);
    }

    public void applyGlassLevel(String level) {
        if (!"none".equals(level) && !"standard".equals(level) && !"ultra".equals(level)) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_GLASS, level).apply();
        com.netops.handbook.lens.NativeBlocks.GLASS_LEVEL = level;
        com.netops.handbook.lens.glass2.DockBus.pushGlassLevel(level);
        if (lensBar != null) lensBar.onGlassLevelChanged();
        if (appbarBar != null) appbarBar.invalidateBackdrop();
    }

    public void applyDockStyle(boolean docked) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_DOCKSTYLE_DOCKED, docked).apply();
        dockDocked = docked;
        com.netops.handbook.ui.AppShellState.INSTANCE.setDockDocked(docked);
        com.netops.handbook.lens.glass2.DockBus.setDockedStyle(docked);
        if (lensBar != null) {
            lensBar.setDocked(docked);
            root.postDelayed(this::computeDockGeometry, 450);
        }
    }

    /** M4-3(2026-11-16)：设置页 Compose 化（SettingsPage 路由化），View 面板退役。
     *  设置四项真源落地（原 NativeSettingsPanel setter lambda 逻辑抽取）。 */
    public void applySetting(String kind, String value) {
        android.util.Log.i("NativeSettings", "apply " + kind + " = " + value);
        if ("wallpaper".equals(kind)) {
            com.netops.handbook.lens.WallpaperPrefs.setSelect(this, value);
        } else if ("theme".equals(kind)) {
            applyThemeMode(value);
        } else if ("glasslevel".equals(kind)) {
            applyGlassLevel(value);
        } else if ("dockstyle".equals(kind)) {
            applyDockStyle("regular".equals(value));
        }
        /* JS 双写链随 WebView 摘除退役（M1d 后原生为唯一源）。 */
    }

    /** 覆盖层开合单点（2026-11-15）：dock 弹窗退位 + 顶栏整窗退位 + 壳层镜像。
     *  旧链三处各自为政漏了顶栏 setState({"overlaid"})——JS 死后没人推。 */
    private void pushOverlaidAll(boolean open) {
        com.netops.handbook.lens.glass2.DockBus.pushOverlaid(open);
        NetOpsShell.INSTANCE.setOverlaid(open);
        if (appbarBar != null) appbarBar.setState(
                "{\"overlaid\":" + open + "}");
    }

    private static final String KEY_GLASS = "glass_level";
    private static final String KEY_DOCKSTYLE_DOCKED = "dockstyle_docked";

    /** M1d(2026-11-14)：备份恢复后刷新原生状态页（done/fav SP 已由 merge 合并）。 */
    public void refreshNativeState(org.json.JSONObject done, org.json.JSONObject fav) {
        if (nativePhase != null) nativePhase.mergeLegacyState(
                done == null ? "{}" : done.toString(),
                fav == null ? "{}" : fav.toString());
        markStatePagesDirty();
    }

    /** M1d：备份导出（bridge.saveFile 自带异步落盘+Toast）。 */
    public kotlin.Unit exportBackup() {
        if (bridge == null) return kotlin.Unit.INSTANCE;
        bridge.saveFile(com.netops.handbook.lens.BackupManager.fileName(),
                com.netops.handbook.lens.BackupManager.buildJson(this));
        return kotlin.Unit.INSTANCE;
    }

    /** M1d：备份恢复入口（系统文件选择器）。 */
    public kotlin.Unit importBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        try { startActivityForResult(i, REQ_IMPORT_BACKUP); }
        catch (Throwable e) { Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show(); }
        return kotlin.Unit.INSTANCE;
    }

    /** M2-1(2026-11-14)：Router.onNavigate 动作表——route→(keep, showTarget)
     *  与 Bridge.showNativeX 逐条对应（keep 口径注意 learn→"home"）。
     *  cli = 唯一 WebView 独占路由（M1 迁走）：keep=null 壁纸层让位走 WebView。 */
    /** M3 终局：页面路由落点——写 AppShellState 页面态，GlassDockHost 组合内
     *  when 切页（退役 11 个 ComposeView 页宿主与显隐白名单）。 */
    private void pagesGo(String route, String arg) {
        com.netops.handbook.ui.AppShellState.INSTANCE.pagesGo(route, arg);
    }

    private void wireNativeRouter() {
        com.netops.handbook.lens.Router.INSTANCE.onNavigate = (st, restoreScroll) -> {
            String route = st.route;
            final String arg = st.arg;
            final String keep;
            final Runnable target;
            /* M4-3：settings 路由化（SettingsPage，PagesOverlay when 分支）——
               原 V 面板覆盖层特例退役。 */
            switch (route) {
                /* M3 终局 + View 层退役：全部路由落 Compose 页（AppShellState.pageRoute）。
                   ❗pagesGo 键 = PagesOverlay when 分支键（learn，非 JS keep 的 home）；
                   phase/phase-focus 曾漏接 nativePhase.show（View 残页=空屏），2026-11-14 接上。 */
                case "learn":  keep = "home";  target = () -> pagesGo("learn", null); break;
                case "fault":  keep = "fault"; target = () -> pagesGo("fault", ""); break;
                case "iv":     keep = "iv";    target = () -> pagesGo("iv", ""); break;
                case "dict":   keep = "dict";  target = () -> pagesGo("dict", ""); break;
                case "refs":   keep = "refs";  target = () -> pagesGo("refs", ""); break;
                case "ref":    keep = "refs";  target = () -> pagesGo("ref", arg); break;
                case "glossary": keep = "glossary"; target = () -> pagesGo("glossary", ""); break;
                case "roadmap":  keep = "roadmap";  target = () -> pagesGo("roadmap", ""); break;
                case "quiz":   keep = "quiz";  target = () -> pagesGo("quiz", ""); break;
                case "me":     keep = "me";    target = () -> pagesGo("me", ""); break;
                case "fav":    keep = "fav";   target = () -> pagesGo("fav", ""); break;
                case "search": keep = "search"; target = () -> pagesGo("search", ""); break;
                case "settings": keep = "settings"; target = () -> pagesGo("settings", ""); break;
                case "phase": {
                    keep = "phase";
                    final String pid = (arg == null || arg.isEmpty()) ? "p-1" : arg;
                    target = () -> pagesGo("phase", pid);
                    break;
                }
                case "phase-focus": {
                    keep = "phase";
                    final String[] pp = (arg == null ? "p-1|" : arg).split("\\|");
                    target = () -> pagesGo("phase-focus", arg == null ? "p-1|" : arg);
                    break;
                }
                case "cli":
                    /* M1b：cli 已迁 Compose 页（CliScreen）——keep="cli" 走通用互斥。 */
                    keep = "cli"; target = () -> {
                        pagesGo("cli", "");
                    };
                    break;
                default:
                    return kotlin.Unit.INSTANCE;
            }
            switchNativeTo(keep, target);
            /* 顶栏标题/副标题/返回态：Router 直接产出（原 setAppBarState JSON 的
               name/sub/navBack 三键；bgrev/overlaid 等仍走原链路）。 */
            if (appbarBar != null) {
                String name = com.netops.handbook.lens.Router.INSTANCE.getTitle();
                String sub = com.netops.handbook.lens.Router.INSTANCE.getSubtitle();
                appbarBar.setState("{\"name\":" + org.json.JSONObject.quote(name)
                        + ",\"sub\":" + org.json.JSONObject.quote(sub)
                        + ",\"navBack\":" + com.netops.handbook.lens.Router.INSTANCE.getNavBack() + "}");
            }
            return kotlin.Unit.INSTANCE;
        };
        /* 返回键覆盖层收口（优先级链与原 JS back 同构：drawer→…；卡片收起随页面
           迁 Compose 后由各页 back 处理器接管）。M4-2：抽屉=Compose BackHandler
           优先消费（带滑出动画）；此处保留兜底判定。 */
        com.netops.handbook.lens.Router.INSTANCE.overlayCloser = () -> {
            if (com.netops.handbook.ui.AppShellState.INSTANCE.getDrawerOpen()) {
                com.netops.handbook.ui.AppShellState.INSTANCE.setDrawerOpen(false);
                pushOverlaidAll(false);
                return true;
            }
            return false;
        };
    }

    /* ================= 全量原生化 · N0 数据管道 =================
       native-data.json 由 web-src/tools/gen-native-data.js 从 js/data/* 生成
       （唯一真源仍是 js/data）。这里后台线程解析一次，视图阶段按需取用；
       解析放后台是因为 358KB JSON 在主线程会吃掉首帧预算。 */
    private org.json.JSONObject nativeData;

    /** 原生页互斥：显示一个之前先收起其它（否则透明底页面会互相透出——2026-10-27 实测
     *  首页透过阶段页的半透明卡). keep = 要保留的类型名，null = 全收。 */
    /* 交叉淡化已回退：13 个原生页的根 View 是半透明底，旧页 alpha 降下来时
       壁纸/下层直接透出 → 切页「疯狂闪烁」（2026-11-06 用户报）。原 hide()
       立即 GONE 正是为躲这个。要做平滑转场，得先给页面补不透明底，另立任务。 */
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
        /* 设置面板是模态覆盖层：任何页面切换都收起。面板开着时顶栏（独立弹窗）仍可点，
           点搜索/主题键会切页——新页被压在面板底下、面板不退 = 「卡住」（2026-11-09 用户报）。
           hide() 幂等（非 VISIBLE 直接 return），面板没开时零开销。 */
        /* M4-2：抽屉 Compose 化——切页时路由 effect 自收（DrawerOverlay.routeSeen）。 */
        if (com.netops.handbook.ui.AppShellState.INSTANCE.getDrawerOpen()) {
            com.netops.handbook.ui.AppShellState.INSTANCE.setDrawerOpen(false);
            pushOverlaidAll(false);
        }
    }

    /** 快照交叉淡化（2026-11-09 P1-3）：先把当前主窗 PixelCopy 成一帧盖上覆盖层，
     *  页面在覆盖层之下瞬时切换，覆盖层 160ms 淡出。视图 alpha 直接加在玻璃卡页面上要建
     *  1904×3040 离屏层（2026-10-28 实测 99th 200ms，已回退）；11-06 交叉淡化闪烁
     *  （半透明页互透）被整帧快照绕开。dock/顶栏是独立置顶弹窗，永远盖在覆盖层之上
     *  不受影响；覆盖层不可点击，命中测试穿透到下层页面。
     *  ❗代号校验+自愈超时（2026-11-09 用户报「切到面试页偶尔粘滞 1-2s」）：
     *  快速/连续切页时两轮 PixelCopy 回调乱序，旧回调的 endAction 会撤掉新一轮
     *  刚显示的快照（或新一轮动画被旧 cancel 后不再回 GONE）——覆盖层偶尔残留
     *  VISIBLE 盖住新页面，用户看到「旧画面不动、滑动无视觉响应」。 */
    private int switchGen = 0;

    private void switchNativeTo(String keep, Runnable showTarget) {
        /* 同页重复桥调用短路：JS 迟到确认/路由恢复会对同一页重复 showNativeX，
           若也走覆盖层会中途取消上一轮淡出、重抓、再淡——可见过渡被压缩成 ~40ms
           （2026-11-09 真机诊断实证），且每重复一次白烧一次全屏 PixelCopy。 */
        if (keep != null && keep.equals(currentNativeKeep)) {
            /* 同页重复调用：作废在途转场回调并撤掉可能残留的覆盖层（防旧快照滞留）。 */
            switchGen++;
            if (switchOverlay != null) {
                switchOverlay.animate().cancel();
                switchOverlay.setVisibility(android.view.View.GONE);
            }
            if (lensBar != null) lensBar.setTransitionHold(false);
            if (appbarBar != null) appbarBar.setTransitionHold(false);
            hideNativePagesExcept(keep); showTarget.run(); return;
        }
        if (switchOverlay == null) {
            currentNativeKeep = keep;
            hideNativePagesExcept(keep); showTarget.run();
            root.postDelayed(this::notifyBarsSwitchSettled, 160);   /* 无覆盖层路径：页面画完再补抓 */
            return;
        }
        /* 转场保持：覆盖层存活期两栏不抓帧（抓到的是旧页/混合像素），
           收尾 notifyBarsSwitchSettled 统一放行+重抓。 */
        if (lensBar != null) lensBar.setTransitionHold(true);
        if (appbarBar != null) appbarBar.setTransitionHold(true);
        switchOverlay.animate().cancel();      /* 上一轮过渡未结束：撤旧层重抓当帧 */
        switchOverlay.setVisibility(android.view.View.GONE);
        final int myGen = ++switchGen;         /* 本轮代号：旧回调一律丢弃 */
        try {
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
                    root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(getWindow(), bm, res -> {
                if (myGen != switchGen) return;   /* 已被新一轮切页接管 */
                if (res != android.view.PixelCopy.SUCCESS) {
                    /* 抓帧失败必须留痕：静默回退会让「淡入没生效」无从排查
                       （2026-11-09 真机诊断教训——res 失败分支曾无日志）。 */
                    android.util.Log.w("SwitchFade", "PixelCopy res=" + res + "，回退瞬时切换");
                    currentNativeKeep = keep;
                    hideNativePagesExcept(keep); showTarget.run();
                    root.postDelayed(this::notifyBarsSwitchSettled, 160);
                    return;
                }
                switchOverlay.setImageBitmap(bm);
                switchOverlay.setAlpha(1f);
                switchOverlay.setVisibility(android.view.View.VISIBLE);
                hideNativePagesExcept(keep);
                showTarget.run();
                currentNativeKeep = keep;
                switchOverlay.animate().alpha(0f).setDuration(160)
                        .withEndAction(() -> {
                            if (myGen != switchGen) return;
                            switchOverlay.setVisibility(android.view.View.GONE);
                            notifyBarsSwitchSettled();
                        }).start();
                /* 自愈兜底：动画被任何意外打断（cancel/重复转场）时也在 400ms 内撤层，
                   残留快照最多糊 0.4s，不再出现「旧画面不动 1-2 秒」。 */
                switchOverlay.postDelayed(() -> {
                    if (myGen == switchGen && switchOverlay.getVisibility() == android.view.View.VISIBLE) {
                        android.util.Log.w("SwitchFade", "覆盖层自愈撤层（动画未收尾）");
                        switchOverlay.animate().cancel();
                        switchOverlay.setVisibility(android.view.View.GONE);
                        notifyBarsSwitchSettled();
                    }
                }, 400);
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable t) {
            android.util.Log.w("SwitchFade", "快照抓帧失败，回退瞬时切换", t);
            currentNativeKeep = keep;
            hideNativePagesExcept(keep); showTarget.run();
            root.postDelayed(this::notifyBarsSwitchSettled, 160);
        }
    }

    /** 切页收尾：转场覆盖层一撤就强制顶/底栏重抓（2026-11-09 用户报「切完玻璃
     *  不跟手、碰一下才刷新」的根因修复）。两段事实：①转场 160ms 内玻璃若触发
     *  抓帧，PixelCopy 抓到的是覆盖层上的旧页像素（覆盖层在主窗内）；②原生页
     *  切换在 JS 侧没有 dock 的触发链——setNativeDockBgRev 只在壁纸换代时由
     *  paintWallpaper 调用，标题变化的 bgrev bump 不走 dock 桥。appbar 虽有
     *  450ms 补抓，但用户在这 450ms 里的一次触摸仍是「不跟手」体感来源，
     *  这里在 160ms 转场结束点统一收口，两栏一次补齐。 */
    /** 进度/收藏一变，「我的」（圆环+四格数字）与「收藏夹」（列表）就必须下次重建：
     *  这两个页面的视图是缓存的（2026-11-11 切页提速），不置脏会显示旧数字。 */
    private void markStatePagesDirty() {
        if (nativeMe != null) nativeMe.invalidateData();
        if (nativeFav != null) nativeFav.invalidateData();
    }

    private void notifyBarsSwitchSettled() {
        com.netops.handbook.lens.glass2.DockBus.bump();   // glass2: 转场收口重绘玻璃
        if (lensBar != null) {
            lensBar.setTransitionHold(false);
            /* onSwitchSettled = 解除活抓冷却 + requestFrostRefresh（三连发防抖）。
               onBackdropChanged 是空方法（壁纸霜链已退役）勿用——2026-11-09 曾
               因调它导致收口触发空操作。 */
            lensBar.onSwitchSettled();
        }
        if (appbarBar != null) {
            appbarBar.setTransitionHold(false);
            appbarBar.invalidateBackdrop();
        }
    }

    /** 打开系统相册选图（原生设置面板「从相册选取」与 HTML 壁纸弹窗桥共用；
     *  结果统一走 onActivityResult REQ_PICK_IMAGE：解码压缩 → base64 → JS。 */
    private void launchAlbumPicker() {
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

    /** 原生页导航统一出口：M2-1(2026-11-14) 起走原生 Router，拆除「原生→JS→原生」
     *  导航环；JS 降级为状态存储服务（localStorage 读写链仍被设置面板/壁纸使用，
     *  真源迁移见 M2-4）。Router 直接驱动 switchNativeTo（onNavigate 动作表见 onCreate）。 */
    private void nativeGo(String route, String arg) {
        com.netops.handbook.lens.glass2.DockBus.bump();   // glass2: 路由变化重绘玻璃
        com.netops.handbook.lens.Router.INSTANCE.go(route, arg, false);
    }

    /** 设置面板状态刷新（M1d：原生直读 SP——原 JS NetOpsNativePushSettings 环随
     *  WebView 摘除退役；theme/wallpaper 真源 = PREFS.theme + WallpaperPrefs）。 */
    /* M4-3：pushSettingsState 退役（Compose SettingsPage 直读 SP/WallpaperPrefs State）。 */

    /** 底栏样式镜像（netops_tabbar=regular → 贴底方角；float → 悬浮胶囊）。
     *  JS 是真源（localStorage），经 setDockDocked 桥同步；面板开合时以此刷新按钮态。 */
    private boolean dockDocked = false;

    private void addNativePage(android.view.View v) {
        v.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        v.setVisibility(android.view.View.GONE);
        root.addView(v);
    }

    /** 覆盖层宿主（View 层退役 2026-11-14）：与 addNativePage 同参，但挂 overLayer
     *  （ComposeView 之上的兄弟层）——Compose 页之下会被盖住，必须在其上。 */
    private void addOverlayPage(android.view.View v) {
        v.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        v.setVisibility(android.view.View.GONE);
        overLayer.addView(v);
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
            markStatePagesDirty();
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
            String chipFrom, String chipTo, boolean grouped) {
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
        c.grouped = grouped;
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
                nativeDataRoot = root;   // M1b：CliScreen 数据源
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
                /* M3-1：数据版本源 bump（Compose 页重读）+ 壁纸初值驱动（boot 后
                   direct/directMode 不再依赖 JS syncNativePageMode——M1d 该环已死）。
                   init 必须先于 apply：apply 里的 wallBitmap 解码依赖 init 挂的
                   appContext（View 层退役 2026-11-14）。 */
                {
                    org.json.JSONArray wps0 = root.optJSONArray("wallpapers");
                    android.util.Log.i("WallDebug", "native-data wallpapers=" + (wps0 == null ? "null" : wps0.length()));
                    if (wps0 != null) com.netops.handbook.lens.WallpaperPrefs.INSTANCE.init(
                            MainActivity.this, wps0);
                }
                runOnUiThread(() -> {
                    com.netops.handbook.ui.PageData.INSTANCE.setRoot(root);
                    com.netops.handbook.ui.PageData.INSTANCE.bumpVersion();
                    android.util.Log.i("WallDebug", "apply select=" + com.netops.handbook.lens.WallpaperPrefs.INSTANCE.getSelect()
                            + " customFile=" + com.netops.handbook.lens.WallpaperPrefs.INSTANCE.getCustomFile());
                    try {
                        java.io.File spf = new java.io.File(getApplicationInfo().dataDir, "shared_prefs/netops.shell.xml");
                        java.io.FileInputStream fis = new java.io.FileInputStream(spf);
                        byte[] pbuf = new byte[2048]; int pn = fis.read(pbuf); fis.close();
                        String body = new String(pbuf, 0, Math.max(0, pn));
                        android.util.Log.i("WallDebug", "spFile=" + spf.getAbsolutePath()
                                + " hasCustom=" + body.contains("custom") + " len=" + pn);
                    } catch (Throwable t) {
                        android.util.Log.i("WallDebug", "spRead fail: " + t);
                    }
                    com.netops.handbook.lens.WallpaperPrefs.INSTANCE.apply(MainActivity.this);
                });
                if (nativeMe != null) runOnUiThread(() -> nativeMe.setPageData(root));
                if (false) {
                    org.json.JSONArray wps = root.optJSONArray("wallpapers");
                }
                /* M2-4：壁纸清单喂 WallpaperPrefs（真源）——init 已上移至 apply 之前
                   （wallBitmap 解码依赖 init 的 appContext，2026-11-14）。 */
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
    /** M1d-3(2026-11-14)：WebView 摘除——内容壳只承载壁纸层+原生页（WebView
     *  独占路由 cli 已于 M1b 迁 Compose；状态真源已 SP 化 M2-4/M1c）。 */
    private void createContentShell(int bg) {
        /* 2026-11-02 液态玻璃内容容器：壁纸层包进一层，整层挂
           RenderEffect(RuntimeShader)——shader 的 uContent=本层实时内容，
           药丸区域内折射、区域外原样（Kyant0/AndroidLiquidGlass 机制移植，
           GPU 逐帧零回读）。挂载点见 attach 时 lensBar.attachContentContainer。 */
        contentContainer = new FrameLayout(this);
        contentContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(contentContainer);
        /* 首帧主题色：NetOpsShell 已向 ThemeBus 注册（onCreate 末尾
           ThemeBus.setDark(resolvedDark()) 统一广播），此处不再点名。 */
        /* 原生收藏夹页（N2 收尾）。 */
        nativeFav = new com.netops.handbook.lens.NativeFavView(this);
        ThemeBus.register(nativeFav);
        addNativePage(nativeFav);
        nativeFav.setNav((route, arg) -> nativeGo(route, arg));

        /* 原生壁纸层：给原生页的玻璃件（AndroidLiquidGlassView）提供静态采样源。 */
        nativeBackdrop = new com.netops.handbook.lens.NativeBackdrop(this);
        ThemeBus.register(nativeBackdrop);
        nativeBackdrop.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(nativeBackdrop);
        if (lensBar != null) lensBar.setBackdropSource(nativeBackdrop);

        /* 原生学习首页（N2）：必须挂在壁纸层之上（曾先建后建壁纸层，被整屏壁纸挡住）。 */
        /* 原生学习首页（N2）：阶段入口 + 进度环 + 工具箱。 */
        nativeHome = new com.netops.handbook.lens.NativeHomeView(this);
        ThemeBus.register(nativeHome);
        addNativePage(nativeHome);
        nativeHome.setNav((route, arg) -> nativeGo(route, arg));

        if (nativeHome != null) nativeHome.setGlassSource(nativeBackdrop);

        /* 原生「我的」页 + 设置面板（N2 收尾）。 */
        nativeMe = new com.netops.handbook.lens.NativeMeView(this);
        ThemeBus.register(nativeMe);
        addNativePage(nativeMe);
        nativeMe.setBackupHooks(new com.netops.handbook.lens.NativeMeView.BackupHooks() {
            @Override public void onExport() { exportBackup(); }
            @Override public void onImport() { importBackup(); }
        });
        /* M4-3(2026-11-16)：设置页 Compose 化——NativeSettingsPanel(342 行 View)
           整段退役（类保留至 M6 物理删除；settings 路由=SettingsPage）。 */

        /* 原生抽屉：M4-2(2026-11-16)退役——Compose DrawerOverlay 接管
           （GlassContentHost 内 PagesOverlay 之上挂载，AppShellState.drawerOpen
           驱动）。NativeDrawerView 类保留至 M6 物理删除。 */
        /* 原生搜索页（N5）：原生输入框 + 命中高亮 + 键盘自适应。
           View 层退役(2026-11-14)：覆盖层归 overLayer（ComposeView 之上），否则被 Compose 页盖住。 */
        nativeSearch = new com.netops.handbook.lens.NativeSearchView(this);
        ThemeBus.register(nativeSearch);
        addOverlayPage(nativeSearch);
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
        ThemeBus.register(nativePhase);
        nativePhase.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        nativePhase.setVisibility(android.view.View.GONE);
        nativePhase.setGlassSource(nativeBackdrop);
        nativePhase.setStateListener((id, kind, on) -> {
            markStatePagesDirty();
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
        nativeFault = addDocList(docConfig("排障案例", "案例", "已解决", "faults", "fault", "", "", true));
        nativeIv = addDocList(docConfig("面试题库", "问题", "已准备", "interview", "iv", "IVQ", "Q", true));
        ThemeBus.register(nativeFault);
        ThemeBus.register(nativeIv);

        /* 原生命令字典页（N4）：命令对照表 + 分类筛选。 */
        nativeDict = new com.netops.handbook.lens.NativeDictView(this);
        ThemeBus.register(nativeDict);
        nativeDict.setGlassSource(nativeBackdrop);
        nativeDict.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        nativeDict.setVisibility(android.view.View.GONE);
        root.addView(nativeDict);

        /* 速查表 / 术语表 / 路线图（N4）：三个轻量原生页。 */
        nativeRefs = new com.netops.handbook.lens.NativeRefsView(this);
        ThemeBus.register(nativeRefs);
        nativeRefs.setGlassSource(nativeBackdrop);
        addNativePage(nativeRefs);
        nativeGlossary = new com.netops.handbook.lens.NativeSimpleList(this);
        ThemeBus.register(nativeGlossary);
        nativeGlossary.setGlassSource(nativeBackdrop);
        addNativePage(nativeGlossary);
        nativeRoadmap = new com.netops.handbook.lens.NativeSimpleList(this);
        ThemeBus.register(nativeRoadmap);
        nativeRoadmap.setGlassSource(nativeBackdrop);
        addNativePage(nativeRoadmap);
        nativeQuiz = new com.netops.handbook.lens.NativeQuizView(this);
        ThemeBus.register(nativeQuiz);
        nativeQuiz.setGlassSource(nativeBackdrop);
        addNativePage(nativeQuiz);

        /* 快照交叉淡化覆盖层（P1-3）：z 序在全部原生页之上、抽屉之下（先于 drawer addView）。
           不可点击 → 命中测试穿透，淡出期间触摸直达新页面。 */
        switchOverlay = new android.widget.ImageView(this);
        switchOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        switchOverlay.setVisibility(android.view.View.GONE);
        overLayer.addView(switchOverlay);

        /* 原生页滚动 → 顶栏玻璃实时重抓：这些页的滚动不经过 WebView，
           JS 侧监听不到，玻璃会静止不动（2026-10-30 用户：「通透在哪儿」） */
        hookAppbarScroll(nativePhase);
        hookAppbarScroll(nativeFault);
        hookAppbarScroll(nativeIv);
        hookAppbarScroll(nativeDict);
        hookAppbarScroll(nativeHome);
        hookAppbarScroll(nativeMe);
        hookAppbarScroll(nativeQuiz);
        hookAppbarScroll(nativeRefs);
        hookAppbarScroll(nativeFav);
        hookAppbarScroll(nativeSearch);
        hookAppbarScroll(nativeGlossary);
        hookAppbarScroll(nativeRoadmap);

        bridge = new Bridge();

        pageReady = true;
        rendererGone = false;

        /* 侧栏让位：每次布局后对齐原生页左缩进（旋转/分屏都走这里，幂等） */
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::applyDockInset);

        /* 原生透镜层：叠在 WebView 之上，只画底部药丸那一块。
           它需要 PixelCopy(API 33+) + AGSL，两者都不可用时内部自动降级为半透明，不崩。
           ❗USE_GLASS2(2026-11-12):dock 换 Compose 玻璃实现,旧 PopupWindow/PixelCopy
           链整条不挂载(LensBarView 保留库内作回退,见 DockBus.USE_GLASS2)。 */
        if (com.netops.handbook.lens.glass2.DockBus.USE_GLASS2) {
            com.netops.handbook.lens.glass2.DockBus.setOnCommit(i -> {
                runOnUiThread(() -> {
                    if (bridge == null) return;
                    String jsRoute = i == 0 ? "learn" : i == 1 ? "fault" : i == 2 ? "dict" : "me";
                    switch (i) {
                        case 0: bridge.showNativeHome(); break;
                        case 1: bridge.showNativeFault(); break;
                        case 2: bridge.showNativeDict(); break;
                        default: bridge.showNativeMe(); break;
                    }
                    nativeGo(jsRoute, "");
                });
                return kotlin.Unit.INSTANCE;
            });
            /* 滚动信号: dock 玻璃 25fps 跟随内容(旧 attach 内同款节流在 DockBus 侧) */
            root.getViewTreeObserver().addOnScrollChangedListener(
                    () -> com.netops.handbook.lens.glass2.DockBus.onScrollThrottled());
            ThemeBus.register(d -> com.netops.handbook.lens.glass2.DockBus.pushDark(d));
            if (nativeDockPending) {
                runOnUiThread(() -> com.netops.handbook.lens.glass2.DockBus.pushNativeDock(true));
            }
        }
        /* ❗root 布局尺寸变化 → dock 形态重算 + 顶栏自测(两实现共用):
           冷启动直接横屏（无 config change 事件）时 attach 那次判定用的 anchor
           还是 0×0/旧 config——布局尺寸一变必须补测（2026-11-09 真机）。 */
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            if ((r - l) != (or2 - ol) || (b - t) != (ob - ot)) {
                if (!com.netops.handbook.lens.glass2.DockBus.USE_GLASS2 && lensBar != null) {
                    lensBar.reflow();
                } else {
                    remeasureLensSoon();   // glass2: 旋转后重测 JS 几何(几何真源在 JS)
                }
                if (appbarBar != null) applyAppBarSelfGeometry();
            }
        });
        try { if (!com.netops.handbook.lens.glass2.DockBus.USE_GLASS2) {
            lensBar = LensBarView.attach(this, root, webView);
            ThemeBus.register(lensBar);   /* 替代原 attach 时 setDark：register 自带当前值回调 */
            if (contentContainer != null) lensBar.attachContentContainer(contentContainer);
            runOnUiThread(() -> { if (lensBar != null) lensBar.setNativeDock(nativeDockPending); });
            if (nativeBackdrop != null) lensBar.setBackdropSource(nativeBackdrop);
            /* 侧栏松手 → 原生立刻切页（不等 JS 路由的 16~204ms） */
            lensBar.setOnCommit(i -> runOnUiThread(() -> {
                if (bridge == null) return;
                /* dock 4 格（2026-11-10）：面试降为「我的」页内的二级分类，
                   不再占一格。面试页本体 route "iv" 保留，由 navRow 进入。 */
                String jsRoute = i == 0 ? "learn" : i == 1 ? "fault" : i == 2 ? "dict" : "me";
                switch (i) {
                    case 0: bridge.showNativeHome(); break;
                    case 1: bridge.showNativeFault(); break;
                    case 2: bridge.showNativeDict(); break;
                    default: bridge.showNativeMe(); break;
                }
                /* JS 路由事后同步（6de7805 设计了「JS 确认」但 dock 路径一直没接）：
                   否则 HTML 顶栏标题/玻璃重抓触发器全部冻在旧路由——2026-10-30
                   用户报顶栏玻璃里残留 CLI 页白卡与标题幽灵字，根因即此。
                   go() 的原生 show/hide 幂等（已 VISIBLE 且非脏 → 直接返回）。 */
                nativeGo(jsRoute, "");
            }));
        }
        } catch (Throwable t) {
            Log.w(TAG, "原生透镜层挂载失败（不影响主流程）", t);
            lensBar = null;
        }

        /* R2 原生顶栏：顶部独立窗，画玻璃背景 + 标题 + 三键；
           搜索/抽屉在场时整窗退位（见 AppBarView）。
           几何由 AppBarView 自测（P0-2，2026-10-31）：不再向网页索取，
           因此也不需要 2026-10-30 那个「等 WebView 布局变化再量」的钩子。 */
        try {
            appbarBar = com.netops.handbook.lens.AppBarView.attach(this, root, webView);
            ThemeBus.register(appbarBar);   /* 替代原 attach 时 setDark：register 自带当前值回调 */
            if (nativeBackdrop != null) appbarBar.setWallpaperSource(nativeBackdrop);
            /* View 层退役(2026-11-14)：非按钮区透传改派 Compose 树（root 里已无活页面）。 */
            if (composeHost != null) appbarBar.setAnchor(composeHost);
            /* 主题键原生翻转：只改**模式**（auto 之外的显式深浅），
               不必再指望 evaluateJavascript 写得进网页层——pushTheme 会推
               apply()，JS 的 UI.theme.get() 直接走 themeMode() 桥回读，失败面为零。 */
            appbarBar.themeFlip = () -> runOnUiThread(() -> {
                String m = resolvedDark() ? "light" : "dark";
                rememberTheme(m);
                pushTheme(resolvedDark(m));
            });
            /* View 层退役(2026-11-14)：抽屉/搜索键原生直触（HTML click 链已死）。
               0=抽屉（open + dock 退位）；1=搜索（Router.go 落 nativeSearch+页面态）。 */
            appbarBar.navAction = i -> runOnUiThread(() -> {
                if (i == 0) {
                    /* navBack 态(阶段/搜索等子路由)左键=返回；根路由=抽屉。
                       M4-2：抽屉 Compose 化（DrawerOverlay 读 AppShellState.drawerOpen）。 */
                    if (com.netops.handbook.lens.Router.INSTANCE.getNavBack()) {
                        com.netops.handbook.lens.Router.INSTANCE.back();
                    } else {
                        com.netops.handbook.ui.AppShellState.INSTANCE.setDrawerOpen(true);
                        pushOverlaidAll(true);
                    }
                } else if (i == 1) {
                    com.netops.handbook.lens.Router.INSTANCE.go("search", "", false);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "原生顶栏挂载失败（不影响主流程）", t);
            appbarBar = null;
        }
        /* ❗attach 后补算几何：applyAppBarSelfGeometry 只在 insets 变化时触发，
           若首次回调早于 attach（appbarBar 为 null 被跳过），hasGeometry 永远 false →
           顶栏一帧不画、露出 CSS 顶栏白块（2026-11-07 定位）。 */
        applyAppBarSelfGeometry();

        /* M1d-3：无 WebView 载入链——几何自算一次落位（网页就绪钩子已不存在）。 */
        computeDockGeometry();
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

    /** 给原生页的所有 ScrollView 挂滚动监听：滚动时顶栏玻璃跟着栏下内容重抓，
     *  否则原生页滚动（NativeDocList 等）时玻璃是静止的（2026-10-30 用户：
     *  「背景一直固定在那儿，通透在哪儿」）。递归找 ScrollView，无需各页配合。 */
    private void hookAppbarScroll(android.view.View page) {
        if (page == null || appbarBar == null) return;
        final android.os.Handler dh = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] bgRefresh = {null};   // 停止滚动后延迟触发的那一次重抓
        android.view.ViewTreeObserver.OnScrollChangedListener l = () -> {
            /* ❗顶栏已改用 anchor.draw 同步录制（~17ms，与底栏同源），
               滚动中可以像底栏一样实时重抓（AppBarView 内部 40ms 节流）。
               dock 同链补喂（2026-11-09 用户报「我的页滑动时 dock 里还是
               上一个页面的字」）：原生页滚动从来没有 dock 的重抓信号，
               requestFrostRefresh 三连发防抖自带「停手 200ms」语义。 */
            appbarBar.setScrolling();
            if (lensBar != null) lensBar.requestFrostRefresh();
        };
        java.util.ArrayDeque<android.view.View> stack = new java.util.ArrayDeque<>();
        stack.push(page);
        while (!stack.isEmpty()) {
            android.view.View v = stack.pop();
            if (v instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) v).getViewTreeObserver().addOnScrollChangedListener(l);
                continue;   // ScrollView 内部不再递归，避免重复挂同一个容器
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
            }
        }
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
        /* ThemeBus 消费者不做 unregister：Activity 生命周期 = 进程级（singleTask 常驻），
           ThemeBus 也是进程级单例，二者同生共死，无泄漏窗口。 */
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
            /* 顶栏几何自测的唯一外部输入（物理 px）。每次分发都刷新，即使安全区
               签名没变也保持最新（旋转后签名可能相同但高度取自新方向）。 */
            statusTopInsetPx = bars.top;
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
                    });
                }
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void flushInsets() {
        if (!pageReady) return;
        /* M1d-3：JS --sa-bottom 推送环随 WebView 摘除退役；原生几何/顶栏照常自算。 */
        computeDockGeometry();
        applyAppBarSelfGeometry();   // 安全区变了顶栏矩形必变
    }

    /**
     * 顶栏几何自测落地（P0-2，2026-10-31）。原生按 density 常量算 bar/title/三键
     * 矩形，不再注入 JS 量 DOM —— 顺带把原先挂在 setAppBarGeometry 桥上的两件
     * 副作用搬回来（预热采样 + 竖排侧栏让开顶栏），桥因此可以整块删掉。
     */
    private void applyAppBarSelfGeometry() {
        if (appbarBar == null) return;
        /* 状态栏高每次现取（root 的 WindowInsets 已是当前方向的值）：旋转时下一次
           insets 分发可能晚一两帧，靠缓存值会让横屏头一帧的栏高按竖屏算（闪一下）。 */
        if (root != null) {
            try {
                WindowInsetsCompat wi = ViewCompat.getRootWindowInsets(root);
                if (wi != null) {
                    statusTopInsetPx = wi.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()).top;
                }
            } catch (Throwable ignored) { }
        }
        /* ❗宽用主窗口实宽：旋转瞬间 displayMetrics 可能停在中间态（2026-11-09
           真机横屏顶栏窗宽停在 2665px 的实证）。root 未 layout 完成时传 0 回退
           resources 宽。 */
        int rootW = (root != null && root.getWidth() > 0) ? root.getWidth() : 0;
        appbarBar.applySelfGeometry(statusTopInsetPx, rootW);
        appbarBar.warmUp();
        /* 竖排侧栏让开顶栏：顶栏底边 + 8dp 呼吸，否则两条玻璃叠着切一刀 */
        float below = appbarBar.barBottomPx()
                + 8f * getResources().getDisplayMetrics().density;
        if (lensBar != null) lensBar.setRailTopInset(below);
        com.netops.handbook.lens.glass2.DockBus.setRailTop(below);   // glass2 竖排让位同源
    }

    /** 旋转后顶栏几何三连发补测（120/500/900ms）：与 remeasureLensSoon 同模型——
     *  旋转瞬间 displayMetrics/root 布局都是中间态，最后一次（layout 已 settles）
     *  才是可信值。insets 路径的立即调用保留（安全区变化非旋转）。 */
    private void remeasureAppBarSoon() {
        if (appbarBar == null) return;
        root.postDelayed(this::applyAppBarSelfGeometry, 120);
        root.postDelayed(this::applyAppBarSelfGeometry, 500);
        root.postDelayed(this::applyAppBarSelfGeometry, 900);
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
        root.postDelayed(this::computeDockGeometry, 120);
        root.postDelayed(this::computeDockGeometry, 500);
        root.postDelayed(this::computeDockGeometry, 900);
    }

    /** M1c(2026-11-14)：dock 几何原生自量（退役 JS getBoundingClientRect 测量链）。
     *  复刻 CSS 口径（06-anim 265-300 float 规则 + 05-views 600px 断点）：
     *  悬浮——左右 max(24dp, safe)、底 8dp+safeBottom、高 64dp、内容 pad 8×6、
     *  药丸 = slot+8dp 夹 [94/48 手机 · 96/52 平板]、420dp 平板居中；
     *  贴底——满宽、高 58dp+safeBottom；横屏 = 竖排 rail（GlassDock 自算，不进此链）。 */
    private void computeDockGeometry() {
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        int winW = root.getWidth(), winH = root.getHeight();
        boolean landscape = winW > winH;
        try {
            WindowInsetsCompat wi = ViewCompat.getRootWindowInsets(root);
            if (wi != null) {
                androidx.core.graphics.Insets bars = wi.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout());
                safeBottomPx = bars.bottom;
                safeLeftPx = bars.left;
            }
        } catch (Throwable ignored) { }
        if (landscape || dockDocked) {
            /* 横屏 rail：GlassDock 竖排自算，仅保证 DockBus 有 viewport 与让位；
               贴底(docked)：满宽玻璃板高 58dp+safeBottom。 */
            float barH = dockDocked ? 58f * d + safeBottomPx : 64f * d;
            com.netops.handbook.lens.glass2.DockBus.setBarRect(
                    0f, winH - barH, winW, barH);
            float slot = (winW - 2f * 8f * d) / 4f;
            float pillW = Math.min(slot + 8f * d, 96f * d);
            float pillH = 52f * d;
            com.netops.handbook.lens.glass2.DockBus.setGeometry(
                    slot, pillW, pillH, slot / 2f, winH - barH + barH / 2f, winH);
            if (lensBar != null && !com.netops.handbook.lens.glass2.DockBus.USE_GLASS2) {
                lensBar.setLensBarRect(0f, winH - barH, winW, barH);
                lensBar.setLensGeometry(slot, pillW, pillH,
                        slot / 2f, winH - barH + barH / 2f, 4, winH);
            }
            float pad = (15.6f * d - 0.5f) * 2.0784f + 12f;
            com.netops.handbook.lens.glass2.DockBus.pushPopupGeometry(
                    winW, winH,
                    com.netops.handbook.lens.glass2.DockBus.INSTANCE.getBarRect(), pad);
            return;
        }
        /* 竖屏悬浮:catalog 全宽口径(2026-11-15 用户示图拍板「得像这样」,
           覆盖 0ac4b96 紧凑 KSU 宽)——bar=屏宽−左右 12dp,高 64dp,底距
           8dp+safeBottom;tabWidth=(barW−8dp)/tabs(catalog LiquidBottomTabs);
           药丸=FlClash「格距+8dp」真源,cap 96dp(平板防超长条),比例≈1.57
           胶囊(旧紧凑 64×56≈方圆,按压同比放大仍是圆盘=用户点名的球)。 */
        float barW = winW - 2f * 12f * d;
        float barL = 12f * d;
        float barH = 64f * d;
        float barT = winH - 8f * d - safeBottomPx - barH;
        com.netops.handbook.lens.glass2.DockBus.setBarRect(barL, barT, barW, barH);
        float slot = (barW - 8f * d) / 4f;
        float pillW = Math.min(slot + 16f * d, 96f * d);
        float pillH = 56f * d;
        com.netops.handbook.lens.glass2.DockBus.setGeometry(
                slot, pillW, pillH, barL + 4f * d + slot / 2f, barT + barH / 2f, winH);
        /* 过渡期双写：旧 LensBarView 路线（USE_GLASS2=false 回退位）同吃原生几何。 */
        if (lensBar != null && !com.netops.handbook.lens.glass2.DockBus.USE_GLASS2) {
            lensBar.setLensBarRect(barL, barT, barW, barH);
            lensBar.setLensGeometry(slot, pillW, pillH,
                    barL + 8f * d + slot / 2f, barT + barH / 2f, 4, winH);
        }
        /* dock 独立弹窗(2026-11-14)：窗口尺寸+栏矩形喂 DockPopupHost
           (GlassDock 自算矩形经 SideEffect 二次精化；弹窗内部去重)。 */
        float pad = (15.6f * d - 0.5f) * 2.0784f + 12f;
        com.netops.handbook.lens.glass2.DockBus.pushPopupGeometry(
                winW, winH,
                com.netops.handbook.lens.glass2.DockBus.INSTANCE.getBarRect(), pad);
    }

    /** M1c(2026-11-14)：HTML 药丸隐藏 one-shot（退役测量职责——几何已原生自算；
     *  此前 requestDockGeometry 每次测量的副作用只剩这一件仍有意义）。 */
    private void hideHtmlPill() {
        if (webView == null) return;
        String js = "(function(){try{"
                + "document.body.classList.add('native-lens-on');"
                + "var pp=document.querySelector('.tabbar > .tab-pill');"
                + "if(pp)pp.style.display='none';"
                + "}catch(e){}})()";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "隐藏 HTML 药丸的 JS 注入失败", t);
        }
    }

    // ------------------------------------------------------------------ 主题

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 当前配色模式：auto 跟随系统，light/dark 是用户手选（缺省 auto）。 */
    private String themeMode() {
        String t = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_THEME, null);
        return ("light".equals(t) || "dark".equals(t)) ? t : "auto";
    }

    /** 模式 → 实际深浅。 */
    private boolean resolvedDark(String mode) {
        if ("dark".equals(mode)) return true;
        if ("light".equals(mode)) return false;
        return isNightMode();
    }

    private boolean resolvedDark() { return resolvedDark(themeMode()); }

    private void rememberTheme(String mode) {
        /* ❗同步落盘（2026-11-10 用户报「切深色划掉后台重进变浅」）：apply() 异步写，
           切完立刻杀后台会丢。模式字符串是唯一真源，网页层用 themeMode() 桥回读。 */
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_THEME, mode)
                .commit();
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
            /* 推系统深色真源（__netopsSysDark）并触发 JS 重算：JS theme 是用户偏好真源
               （auto 跟随本值 / dark|light 用户手选优先）。WebView 的 matchMedia 恒 light
               （不跟随系统），必须由 App 推系统值（2026-11-08「深色半成品」根因之一）。 */
            if (webView != null) {
                String mode = themeMode();
                String js = "(function(){try{"
                        + "window.__netopsSysDark=" + dark + ";"
                        + "try{localStorage.setItem('theme','" + mode + "');}catch(e){}"
                        + "if(window.UI&&UI.theme){if(UI.store)UI.store.set('theme','" + mode + "');UI.theme.apply();}"
                        + "}catch(e){}})()";
                try { webView.evaluateJavascript(js, null); } catch (Throwable ignored) { }
            }
            applyBarAppearance(dark);
            /* 手工点名名册退役（2026-11-10）：16+ 消费者全部改为 ThemeBus 注册制，
               单源广播替代逐个 setDark —— 新界面只需 implements DarkAware + register，
               漏通知在结构上不可能发生。此前的逐点名单（nativeBackdrop/lensBar/
               NetOpsShell/nativePhase/nativeFault/nativeIv/nativeDict/nativeRefs/
               nativeGlossary/nativeRoadmap/nativeQuiz/nativeHome/nativeSearch/
               nativeMe/nativeFav/nativeDrawer/appbarBar）全部删除。 */
            ThemeBus.setDark(dark);
        });
    }

    // ------------------------------------------------------------------ 返回键

    private void bindBackKey() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                /* M2-1(2026-11-14):返回键走原生 Router——同步应答，退役 500ms JS
                   应答窗口与 backAckPending 竞态防护（JS_BACK 字段保留至 M6 清）。 */
                boolean consumed = com.netops.handbook.lens.Router.INSTANCE.back();
                onBackConsumed(consumed);
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
                   M1c：几何原生自算（computeDockGeometry），JS 只剩药丸隐藏 one-shot。 */
                computeDockGeometry();
                hideHtmlPill();
                if (appbarBar != null) applyAppBarSelfGeometry();
            });
        }

        /** 透镜几何诊断：页面原始测量值无条件回传（排查旋转期护栏误拦）。 */
        @JavascriptInterface
        public void lensDiag(String s) {
            Log.i("LensBar", "诊断 " + s);
        }

        @JavascriptInterface
        public void setTheme(String mode) {
            /* 网页侧主题入口（「我的」页三态 / 顶栏翻转的共同落点）：
               只接受模式字符串，落盘后由原生广播实际深浅。M1c：走原生 apply。 */
            runOnUiThread(() -> applyThemeMode(mode));
        }

        /** 模式真源回读：网页在 boot 时用它对账（localStorage 可能落后/被清）。 */
        @JavascriptInterface
        public String themeMode() {
            return MainActivity.this.themeMode();
        }

        /**
         * 页面量好底栏几何后上报（CSS px）。几何真源仍在 CSS，原生层不做推导
         * （栏有边距、药丸比一格宽 8px、还有 safe-area，推导必错）。
         * 走到这里就说明 JS 侧代码真的执行了 —— 这条日志本身就是"接管是否成功"的判据。
         */
        @JavascriptInterface
        public void setLensGeometry(float slot, float pillW, float pillH,
                                    float firstCx, float pillCy, int count, float viewportH) {
            runOnUiThread(() -> {
                com.netops.handbook.lens.glass2.DockBus.setGeometry(
                        slot, pillW, pillH, firstCx, pillCy, viewportH);   // glass2 几何真源
                if (lensBar != null) {
                    lensBar.setLensGeometry(slot, pillW, pillH, firstCx, pillCy, count, viewportH);
                    if (!lensBar.isDockVertical()) lensBar.warmUp();
                }
                Log.i("LensBar", String.format(
                        "几何已同步 一格%.1f 药丸%.1f×%.1f 首格中心(%.1f,%.1f) 共%d格",
                        slot, pillW, pillH, firstCx, pillCy, count));
            });
        }

        /** 页面自己切了 tab（例如深链/返回）时回传，避免两边选中态不一致。 */
        @JavascriptInterface
        public void setSelectedIndex(int index) {
            runOnUiThread(() -> {
                NetOpsShell.INSTANCE.setSelected(index);
                com.netops.handbook.lens.glass2.DockBus.pushSelectedIndex(index);   // glass2 选中态同源
            });
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
                if (appbarBar != null) appbarBar.invalidateBackdrop();  // ❗顶栏壁纸重抓（原缺：换壁纸顶栏时有时不实时，时序丢失）
                if (nativeHome != null) nativeHome.setDirectMode(direct);
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
            runOnUiThread(() -> { switchNativeTo("fault", () -> nativeFault.show()); });
        }

        @JavascriptInterface
        public void hideNativeFault() {
            if (nativeFault == null) return;
            runOnUiThread(() -> nativeFault.hide());
        }

        @JavascriptInterface
        public void showNativeDict() {
            if (nativeDict == null) return;
            runOnUiThread(() -> { switchNativeTo("dict", () -> nativeDict.show()); });
        }

        @JavascriptInterface
        public void hideNativeDict() {
            if (nativeDict == null) return;
            runOnUiThread(() -> nativeDict.hide());
        }

        @JavascriptInterface
        public void showNativeRefs() { if (nativeRefs != null) runOnUiThread(() -> { switchNativeTo("refs", () -> nativeRefs.show()); }); }

        /** 速查单表直达（原 WebView `ref` 页的入口：搜索「速查」结果点开）。 */
        @JavascriptInterface
        public void showNativeRef(String id) {
            if (nativeRefs != null) runOnUiThread(() -> { switchNativeTo("refs", () -> nativeRefs.showDetail(id)); });
        }

        @JavascriptInterface
        public void hideNativeRefs() { if (nativeRefs != null) runOnUiThread(() -> nativeRefs.hide()); }

        @JavascriptInterface
        public void showNativeGlossary() { if (nativeGlossary != null) runOnUiThread(() -> { switchNativeTo("glossary", () -> nativeGlossary.show()); }); }

        @JavascriptInterface
        public void hideNativeGlossary() { if (nativeGlossary != null) runOnUiThread(() -> nativeGlossary.hide()); }

        @JavascriptInterface
        public void showNativeRoadmap() { if (nativeRoadmap != null) runOnUiThread(() -> { switchNativeTo("roadmap", () -> nativeRoadmap.show()); }); }

        @JavascriptInterface
        public void hideNativeRoadmap() { if (nativeRoadmap != null) runOnUiThread(() -> nativeRoadmap.hide()); }

        @JavascriptInterface
        public void showNativeFav() {
            if (nativeFav == null) return;
            runOnUiThread(() -> { switchNativeTo("fav", () -> nativeFav.show()); });
        }

        /* M4-2：showNativeDrawer 退役（抽屉 Compose 化，JS 桥已死无人调）。 */

        @JavascriptInterface
        public void hideAllNativePages() {
            runOnUiThread(() -> switchNativeTo(null, () -> {}));
        }

        @JavascriptInterface
        public void showNativeMe() {
            if (nativeMe == null) return;
            runOnUiThread(() -> { switchNativeTo("me", () -> nativeMe.show()); });
        }

        /** 设置面板打开时，页面层把当前状态推回来。 */
        @JavascriptInterface
        public void setSettingsState(String theme, String wallpaper) {
            try {
                runOnUiThread(() -> {
                });
            } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void showNativeSearch() {
            if (nativeSearch == null) return;
            runOnUiThread(() -> { switchNativeTo("search", () -> nativeSearch.show()); });
        }

        @JavascriptInterface
        public void showNativeHome() { if (nativeHome != null) runOnUiThread(() -> { switchNativeTo("home", () -> nativeHome.show()); }); }

        @JavascriptInterface
        public void showNativeQuiz() { if (nativeQuiz != null) runOnUiThread(() -> { switchNativeTo("quiz", () -> nativeQuiz.show()); }); }

        @JavascriptInterface
        public void showNativeIv() {
            if (nativeIv == null) return;
            runOnUiThread(() -> { switchNativeTo("iv", () -> nativeIv.show()); });
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
            runOnUiThread(() -> { switchNativeTo("phase", () -> nativePhase.show(pid)); });
        }

        /** 「继续学习」/收藏跳转：直接展开目标模块卡并滚到它。 */
        @JavascriptInterface
        public void showNativePhaseFocus(String pid, String mid) {
            if (nativePhase == null) return;
            runOnUiThread(() -> { switchNativeTo("phase", () -> nativePhase.show(pid, mid)); });
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
            /* 2026-11-02 boot 时序：JS 在 attach 前就上报开关（lensBar 尚为
               null）——缓存 pending 态，attach 后补施；否则 attach 无条件
               showPopup 会把竖屏 HTML dock 永久盖死（真机实锤）。 */
            nativeDockPending = on;
            com.netops.handbook.lens.glass2.DockBus.pushNativeDock(on);   // glass2 接管开关
            if (lensBar == null) return;
            runOnUiThread(() -> NetOpsShell.INSTANCE.setNativeBar(on));
        }

        /* ---- R2 原生顶栏桥 ---- */

        /** 原生顶栏开关（页面 boot 读 localStorage netops_native_appbar 上报，默认开）。
         *  开 = AppBarView 画全部顶栏外观、HTML 顶栏 visibility:hidden；关 = 回退 HTML。 */
        @JavascriptInterface
        public void setNativeAppbar(boolean on) {
            runOnUiThread(() -> NetOpsShell.INSTANCE.setNativeBar(on));
        }

        /** 顶栏状态推送（标题/导航键/主题键/滚动/搜索/抽屉/玻璃/壁纸版本）。
         *  32-boot 的 MutationObserver 合并 30ms 后调用，JSON 很小，UI 线程直接解析。 */
        @JavascriptInterface
        public void setAppBarState(String json) {
            runOnUiThread(() -> {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    /* ❗原生 AppBarView 的状态消费必须保留（2026-11-09 修复）：这条
                       桥在 Compose 实验期被改成只喂 NetOpsShell（「其余字段忽略」
                       ——bgrev/标题/主题/搜索/退位全断），Compose 回滚后没回卷，
                       顶栏自此冻在开机状态：壁纸换了玻璃不重抓（backdropRev 永远
                       == bgRev）、标题/返回态不更新，用户体感「切壁纸顶栏延迟、
                       碰一下才跟上」。 */
                    if (appbarBar != null) appbarBar.setState(json);
                    String n = o.optString("name", null);
                    String sub = o.optString("sub", null);
                    if (n != null) NetOpsShell.INSTANCE.setTitle(n);
                    if (sub != null) NetOpsShell.INSTANCE.setSubtitle(sub);
                    NetOpsShell.INSTANCE.setOverlaid(o.optBoolean("overlaid", false));
                    NetOpsShell.INSTANCE.setNativeBar(o.optBoolean("nativebar", NetOpsShell.INSTANCE.getNativeBar()));
                } catch (Throwable ignored) { }
            });
        }

        /** 栏矩形上报（CSS×dpr 物理像素）——原生底栏容器/触摸判定的几何真源。 */
        @JavascriptInterface
        public void setLensBarRect(float l, float t, float w, float h) {
            com.netops.handbook.lens.glass2.DockBus.setBarRect(l, t, w, h);   // glass2 几何真源
            if (lensBar == null) return;
            runOnUiThread(() -> lensBar.setLensBarRect(l, t, w, h));
        }

        /** 页面底部弹层（壁纸/全局质感 sheet）开合推送：弹层在场时收起透镜窗，
         *  否则药丸触摸带（独立窗口永远置顶）会吃掉 sheet 底部控件（玻璃开关）的
         *  点击。旧页面没有此方法时静默忽略。 */
        @JavascriptInterface
        public void setSheetOpen(boolean open) {
            com.netops.handbook.lens.glass2.DockBus.pushOverlaid(open);   // glass2 弹层让位
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
        public void pickImage() { launchAlbumPicker(); }

        /** M2-4(2026-11-14)：壁纸状态一次性迁移（JS boot 推送，netops_wp_mig_done
         *  双侧标记防重复）。select = WP_KEY 存量；customDataUrl = 相册 dataURL
         *  （仅 select=custom 时非空，落盘 files/wallpaper_custom.jpg）；
         *  glassTint = netops_glass JSON 的 tint（blur 公式输入）。 */
        @JavascriptInterface
        public void migrateWallpaperState(String select, String customDataUrl, int glassTint) {
            runOnUiThread(() -> com.netops.handbook.lens.WallpaperPrefs.migrate(
                    MainActivity.this, select, customDataUrl == null ? "" : customDataUrl, glassTint));
        }

        /** 底栏样式镜像（2026-11-09 面板「底栏样式」行回归）：JS 真源
         *  （netops_tabbar）切换时通知——贴底=方角满宽栏，悬浮=胶囊。
         *  M1c：转发 applyDockStyle（SP 落地+原生直驱
         *  回推由 apply 内承担）。 */
        @JavascriptInterface
        public void setDockDocked(boolean d) {
            runOnUiThread(() -> {
                applyDockStyle(d);
            });
        }

        /** 液态玻璃三档镜像（netops_lglass）：ultra 全效 / standard 砍色散+阴影 /
         *  none 纯磨砂（平绘回退）。JS 真源，切换即刷 dock/顶栏。 */
        @JavascriptInterface
        public void setGlassLevel(String level) {
            runOnUiThread(() -> {
                if (!"none".equals(level) && !"standard".equals(level) && !"ultra".equals(level)) return;
                com.netops.handbook.lens.NativeBlocks.GLASS_LEVEL = level;
                com.netops.handbook.lens.glass2.DockBus.pushGlassLevel(level);   // glass2 档位
                if (lensBar != null) lensBar.onGlassLevelChanged();
                if (appbarBar != null) appbarBar.invalidateBackdrop();
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
        /* M1d：备份恢复文件读取（OpenDocument JSON）。 */
        if (requestCode == REQ_IMPORT_BACKUP && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            ioPool.execute(() -> {
                String text = null;
                try (java.io.InputStream is = getContentResolver().openInputStream(uri);
                     java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                    text = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Throwable e) { Log.w(TAG, "读取备份失败", e); }
                final String t = text;
                runOnUiThread(() -> {
                    if (t == null) { Toast.makeText(this, "读取备份失败", Toast.LENGTH_LONG).show(); return; }
                    com.netops.handbook.lens.BackupManager.Summary r =
                            com.netops.handbook.lens.BackupManager.importData(this, t);
                    Toast.makeText(this, r.getMessage(), Toast.LENGTH_LONG).show();
                });
            });
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
                final String pickedUrl = dataUrl;   // lambda 捕获需 effectively final
                /* M2-4：相册图先落盘进 WallpaperPrefs（原生真源；dataURL 仍发 JS
                   双写过渡——WebView .wall 显示与 localStorage 备份用）。 */
                if (pickedUrl != null) {
                    runOnUiThread(() -> com.netops.handbook.lens.WallpaperPrefs
                            .setCustomFromDataUrl(MainActivity.this, pickedUrl));
                }
                if (dataUrl == null) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "读取图片失败，换一张或改用内置壁纸", Toast.LENGTH_LONG).show());
                    return;
                }
                final String js = "if(window.NetOpsOnWallpaper) NetOpsOnWallpaper('" + dataUrl + "');";
                if (webView != null) webView.post(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
                /* 面板若开着，刷新按钮态（「从相册选取」→「更换自定义壁纸」）：
                   JS 侧 applyWallpaper 已同步写完 WP_KEY='custom'，FIFO 队列里
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
        com.netops.handbook.lens.WallpaperPrefs.applyForOrientationChange(this);   // M2-4：横竖配对重解析
        // ❗系统深浅切换统一走 pushTheme（含推 JS html.dark + __netopsDark——
        //   WebView 的 matchMedia 恒 light，不推=HTML 卡片停在浅色，2026-11-08 实锤）
        boolean darkNow = resolvedDark();
        pushTheme(darkNow);   /* 内部走 ThemeBus.setDark：主题有变才广播，消费者自行跟随 */
        applyBarAppearance(darkNow);
        if (lensBar != null) {
            lensBar.reflow();
            lensBar.onBackdropChanged(); // 旋转后壁纸切换，通知玻璃层重采样
        }
        boolean land = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (nativePhase != null) {
            nativePhase.onOrientationChanged(land);   /* 单列⇄双列重建 */
        }
        if (nativeFault != null) nativeFault.onOrientationChanged(land);
        if (nativeIv != null) nativeIv.onOrientationChanged(land);
        if (nativeDict != null) nativeDict.onOrientationChanged(land);
        if (nativeQuiz != null) nativeQuiz.onOrientationChanged(land);
        if (nativeFav != null) nativeFav.onOrientationChanged(land);
        /* nativeRefs(NativeRefsView)/nativeGlossary(NativeSimpleList)/nativeRoadmap
           保持单列：速查表=整表卡、术语=分组大卡列表、路线图=时间轴，形态不适合双列。
           setDark 已全部退役（ThemeBus 注册制），此处只保留 onOrientationChanged 等几何调用。 */
        if (nativeHome != null) nativeHome.onOrientationChanged(land);
        if (appbarBar != null) {
            /* 旋转后顶栏几何三连发补测（同 remeasureLensSoon 模型）：旋转瞬间
               displayMetrics/root 布局都是中间态（单发量到 2665px 的实证），
               必须等 layout settles 后用最终值重算。 */
            remeasureAppBarSoon();
        }
        if (root != null) {
            ViewCompat.requestApplyInsets(root);
        }
        /* 旋转后透镜几何无条件补测：insets 签名在横竖屏间可能不变（见
           remeasureLensSoon 注释），不能指望 insets 分发顺带重测 */
        remeasureLensSoon();
    }
}
