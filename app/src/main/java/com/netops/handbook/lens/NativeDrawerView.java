package com.netops.handbook.lens;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 原生抽屉（2026-10-27）。
 *
 * Web 版抽屉是 HTML 层；原生页盖在 WebView 上之后，HTML 抽屉会被盖死，
 * 所以做原生版。结构对齐：技能目录（4 阶段）+ 查询工具（排障/命令/面试/速查）。
 * 导航统一走 Set 回调 → nativeGo → JS 路由（标题/返回栈不旁路）。
 * 打开时必须让 dock 收起（lensBar.setOverlaid），否则药丸触摸带压住抽屉底部。
 */
public class NativeDrawerView extends FrameLayout {

    public interface Go {
        void go(String route, String arg);
    }

    private final LinearLayout panel;
    private final ScrollView sheet;
    private final View scrim;
    /** 顶栏底边（px）：抽屉从顶栏下沿起，别压在顶栏下面（2026-10-28 顶栏铺满后左侧
     *  ☰ 压不进、抽屉标题也被顶栏盖住）。 */
    private float topInset = 0f;
    private final NativeBlocks.Palette pal;
    private final Go go;
    private final Runnable onClosed;
    private JSONObject root;

    private static final long DUR = 200;
    /** 滑动关闭判定（MD3/iOS 抽屉惯例）：位移过 40% 宽，或向左甩速 > 500dp/s。 */
    private static final float CLOSE_DIST_FRAC = 0.4f;
    private static final float CLOSE_FLING_DP = 500f;

    private final float touchSlop;
    private float downX, downY;
    private boolean dragging;
    private VelocityTracker vt;

    public NativeDrawerView(Context c, NativeBlocks.Palette p, Go go, Runnable onClosed) {
        super(c);
        pal = p;
        this.go = go;
        this.onClosed = onClosed;
        setVisibility(GONE);

        scrim = new View(c);
        scrim.setBackgroundColor(android.graphics.Color.argb(110, 0, 0, 0));
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView sc = new ScrollView(c);
        sheet = sc;
        sc.setFillViewport(false);
        sc.setVerticalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        /* 抽屉是**导航面板**，必须实色：直铺模式 cardBg = argb(214,255,255,255)，
           底下的页面卡片/标题会 16% 透上来，和抽屉自己的文字叠成「两层字」
           （2026-10-28 用户报「侧栏打开直接重叠了」）。只强制 alpha=1，RGB 不动。 */
        bg.setColor(pal.cardBg | 0xFF000000);
        bg.setCornerRadii(new float[]{0, 0, pal.dp(22), pal.dp(22), pal.dp(22), pal.dp(22), 0, 0});
        panel.setBackground(bg);
        panel.setPadding(pal.dp(16), pal.dp(20), pal.dp(16), pal.dp(24));
        sc.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(sc, new LayoutParams(pal.dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        setOnClickListener(v -> close());
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    /* ── 跟手滑动关闭（2026-10-29）：面板/遮罩上横向拖拽 → 面板跟手平移，
          松手按「位移 40% 宽」或「向左甩速」判收，否则弹回。
          之前只能点遮罩关（记录在案的缺失手势）。竖向滚动不受影响：
          只有横向位移超过 touchSlop 且占优才拦截，条目点击照常。 ── */

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (getVisibility() != VISIBLE) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                if (!dragging && dx < -touchSlop && Math.abs(dx) > Math.abs(ev.getY() - downY)) {
                    dragging = true;
                    if (vt == null) vt = VelocityTracker.obtain(); else vt.clear();
                    vt.addMovement(ev);
                    panel.animate().cancel();
                    animate().cancel();
                    setAlpha(1f);   /* 开合动画中被抓住时 alpha 可能停在半途 */
                    return true;
                }
                break;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return super.onTouchEvent(ev);
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dx = Math.max(-panel.getWidth(), Math.min(0f, ev.getX() - downX));
                panel.setTranslationX(dx);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity();
                boolean doClose = panel.getTranslationX() < -panel.getWidth() * CLOSE_DIST_FRAC
                        || vx < -CLOSE_FLING_DP * getResources().getDisplayMetrics().density;
                dragging = false;
                vt.recycle();
                vt = null;
                if (doClose) {
                    close();
                } else {
                    panel.animate().translationX(0).setDuration(150).start();
                }
                break;
            }
        }
        return true;
    }

    /** 顶栏底边（px）：面板整体下移，避免压在顶栏玻璃下面。 */
    public void setTopInset(float px) {
        px = Math.max(0f, px);
        if (Math.abs(px - topInset) < 0.5f) return;
        topInset = px;
        ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        if (lp instanceof LayoutParams) {
            ((LayoutParams) lp).topMargin = Math.round(px);
            sheet.setLayoutParams(lp);
        }
    }

    public void setPageData(JSONObject data) { root = data; }

    public void open() {
        build();
        setVisibility(VISIBLE);
        panel.setTranslationX(-panel.getWidth() > 0 ? -pal.dp(300) : -pal.dp(300));
        panel.post(() -> {
            panel.setTranslationX(-pal.dp(300));
            panel.animate().translationX(0).setDuration(DUR).start();
        });
        setAlpha(0f);
        animate().alpha(1f).setDuration(DUR).start();
    }

    public void close() {
        if (getVisibility() != VISIBLE) return;
        panel.animate().translationX(-pal.dp(300)).setDuration(DUR).start();
        animate().alpha(0f).setDuration(DUR).withEndAction(() -> {
            setVisibility(GONE);
            if (onClosed != null) onClosed.run();
        }).start();
    }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    private void build() {
        panel.removeAllViews();

        TextView brand = NativeBlocks.tv(getContext(), pal, "NetOps 2.0", 19, true, pal.textMain);
        panel.addView(brand);
        TextView sub = NativeBlocks.tv(getContext(), pal, "技能目录", 11.5f, false, pal.textMute);
        sub.setPadding(0, pal.dp(3), 0, pal.dp(10));
        panel.addView(sub);

        panel.addView(section("技能目录"));
        JSONArray phases = root == null ? null : root.optJSONArray("phases");
        if (phases != null) for (int i = 0; i < phases.length(); i++) {
            final JSONObject p = phases.optJSONObject(i);
            if (p == null) continue;
            JSONArray mods = p.optJSONArray("mods");
            int n = mods == null ? 0 : mods.length();
            panel.addView(item(p.optString("title"), n + " 模块", () ->
                    go.go("phase", p.optString("id"))));
        }

        panel.addView(section("查询工具"));
        panel.addView(item("排障字典", "35 个案例", () -> go.go("fault", "")));
        panel.addView(item("命令字典", "58 条对照", () -> go.go("dict", "")));
        panel.addView(item("面试题库", "53 题", () -> go.go("iv", "")));
        panel.addView(item("速查表", "12 张", () -> go.go("refs", "")));
    }

    private View section(String t) {
        TextView tv = NativeBlocks.tv(getContext(), pal, t, 12, true, pal.textMute);
        tv.setPadding(0, pal.dp(10), 0, pal.dp(6));
        return tv;
    }

    private View item(String title, String meta, Runnable onClick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(pal.dp(12), pal.dp(10), pal.dp(12), pal.dp(10));
        row.setBackground(NativeBlocks.shape(pal, pal.chipBgOff, pal.dp(12)));
        row.addView(NativeBlocks.tv(getContext(), pal, title, 14, true, pal.textMain));
        TextView m = NativeBlocks.tv(getContext(), pal, meta, 11, false, pal.textMute);
        m.setPadding(0, pal.dp(2), 0, 0);
        row.addView(m);
        row.setOnClickListener(v -> {
            close();
            panel.postDelayed(() -> onClick.run(), 220);   /* 等抽屉收起动画走完再跳 */
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = pal.dp(8);
        row.setLayoutParams(lp);
        return row;
    }
}
