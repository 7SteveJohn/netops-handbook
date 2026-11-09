package com.netops.handbook.lens;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 原生通用文档列表页（2026-10-27 · N4）。
 *
 * 排障 / 面试 / 速查 / 术语都是同一形状：头部 + N 张「卡片(头行 + blocks 正文)」。
 * 三处以上共用，故抽成一份；每页只给一份 Config（文案 + chip 规则 + 状态标签）。
 *
 * 数据来自 assets/native-data.json 的某个数组（元素：{id,t,cat,blocks[]}）。
 * 状态：SharedPreferences `netops_native` 为唯一写入方，变更回灌页面层。
 */
public class NativeDocList extends LinearLayout {

    public interface StateListener {
        void onState(String id, String kind, boolean on);   /* kind: done | fav */
    }

    /** 每页的文案/规则；默认值 = 排障页口径。字段公开：由 MainActivity 装配。 */
    public static class Config {
        public String title = "排障案例";     // 头部标题
        public String unit = "案例";          // 「共 N 个案例 · 已解决 M」
        public String doneLabel = "已解决";    // meta 与按钮文案后缀
        public String actionOn = "✓ 已解决";
        public String actionOff = "标记已解决";
        public String scrollKey = "doc";
        public String dataKey = "faults";     // native-data.json 顶层键
        public String chipFrom = "", chipTo = "";  // chip 文本替换（面试：IVQ→Q）
        /** 按 cat 分组折叠。条目多（面试 53 题）时开启：平铺要建 500+ View，
         *  整页布局 250~350ms；分组后默认只建 7~8 个组标题。 */
        public boolean grouped = false;
    }

    private final Config cfg;
    private JSONArray items = new JSONArray();
    /** 页面已构建过？数据/主题/direct 变才重建 —— 重建一次 280~300ms（35 行 + 玻璃头卡），
     *  每次 show 都重建 = 切页时 UI 线程被按住 0.3 秒，侧栏弹簧直接顿住（2026-10-28 用户报「卡得要命」）。 */
    private boolean dirty = true;
    private boolean dark = false, direct = false;
    private final ScrollView scroller;
    private final LinearLayout page;
    private NativeBlocks.Palette pal;
    /** 玻璃件采样源（原生壁纸层）。 */
    private android.view.ViewGroup glassSource;
    private StateListener stateListener;

    private final SharedPreferences sp;
    private final Set<String> done = new HashSet<>();
    private final Set<String> fav = new HashSet<>();

    public NativeDocList(Context context, Config config) {
        super(context);
        cfg = config;
        setOrientation(VERTICAL);
        sp = context.getSharedPreferences("netops_native", Context.MODE_PRIVATE);
        loadState();
        pal = NativeBlocks.palette(context, false, false);
        setBackgroundColor(pal.bg);

        scroller = new NativeScrollView(context);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        page = new LinearLayout(context);
        page.setOrientation(VERTICAL);
        scroller.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroller, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setVisibility(GONE);
    }

    /* ---------------- 状态 ---------------- */
    private void loadState() {
        done.clear(); fav.clear();
        for (String s : sp.getString("done", "").split(",")) if (!s.isEmpty()) done.add(s);
        for (String s : sp.getString("fav", "").split(",")) if (!s.isEmpty()) fav.add(s);
    }

    private void saveState() {
        sp.edit().putString("done", TextUtils.join(",", done))
                .putString("fav", TextUtils.join(",", fav)).apply();
    }

    public void setStateListener(StateListener l) { stateListener = l; }

    public void mergeLegacyState(String doneJson, String favJson) {
        boolean changed = mergeInto(done, doneJson) | mergeInto(fav, favJson);
        if (changed) { saveState(); dirty = true; if (isShowing()) buildPage(); }
    }

    public void setState(String id, String kind, boolean on) {
        Set<String> target = "fav".equals(kind) ? fav : done;
        if (!(on ? target.add(id) : target.remove(id))) return;
        saveState();
        dirty = true;
        if ("done".equals(kind) && isShowing()) { buildPage(); dirty = false; }
    }

    private boolean mergeInto(Set<String> target, String json) {
        if (json == null || json.isEmpty()) return false;
        boolean changed = false;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) if (target.add(a.optString(i))) changed = true;
        } catch (Throwable ignored) { }
        return changed;
    }

    /* ---------------- 数据 ---------------- */
    public void setPageData(JSONObject root) {
        JSONArray arr = root.optJSONArray(cfg.dataKey);
        if (arr != null) items = arr;
        dirty = true;
        android.util.Log.i("NativeDoc", cfg.dataKey + " n=" + items.length());
    }

    /* ---------------- 显示 ---------------- */
    public boolean show() {
        if (items.length() == 0) return false;
        if (getVisibility() == VISIBLE && !dirty) return true;   /* 原生已先切过来：JS 迟到的确认不再重置滚动 */
        if (dirty || page.getChildCount() == 0) {
            long __t0 = android.os.SystemClock.uptimeMillis();
            buildPage();
            dirty = false;
            /* PerfProbe：首显构建耗时（gfxinfo 在 HyperOS 上全局失效的替代口径，
               2026-11-09 实测整机 ViewRootImpl=0）。改造前后同探针对比。 */
            android.util.Log.i("PerfProbe", cfg.dataKey + " firstShow build="
                    + (android.os.SystemClock.uptimeMillis() - __t0) + "ms items=" + items.length());
        }
        scroller.scrollTo(0, sp.getInt("scroll_" + cfg.scrollKey, 0));
        animate().cancel();          /* 取消未完成的 hide 动画，否则其 endAction 会把刚显示的页面又置 GONE */
        setVisibility(VISIBLE);
        setAlpha(1f);   /* 不做整屏淡入：带玻璃卡的全屏视图 alpha 动画要新建离屏层，
                           切页时实测 95th 53ms / 99th 200ms（2026-10-28）*/
        return true;
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        sp.edit().putInt("scroll_" + cfg.scrollKey, scroller.getScrollY()).apply();
        setVisibility(GONE);
    }

    public boolean isShowing() { return getVisibility() == VISIBLE; }

    public void setSafeArea(int topPx, int bottomPx) {
        scroller.setPadding(0, topPx + pal.dp(56) + pal.dp(28), 0, bottomPx + pal.dp(84));
    }

    public void setDark(boolean d) {
        if (dark == d) return;
        dark = d; refreshPalette();
    }

    public void setDirectMode(boolean on) {
        if (direct == on) return;
        direct = on; refreshPalette();
    }

    public void setGlassSource(android.view.ViewGroup src) { glassSource = src; }

    private void refreshPalette() {
        pal = NativeBlocks.palette(getContext(), dark, direct);
        setBackgroundColor(pal.bg);
        if (isShowing()) buildPage();
    }

    /* ---------------- 构建 ---------------- */
    private void buildPage() {
        page.removeAllViews();
        page.setPadding(pal.dp(14), pal.dp(10), pal.dp(14), pal.dp(24));

        int doneN = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && done.contains(o.optString("id"))) doneN++;
        }
        LinearLayout head = NativeBlocks.card(getContext(), pal);
        head.addView(NativeBlocks.tv(getContext(), pal, cfg.title, 19, true, pal.textMain));
        TextView sub = NativeBlocks.tv(getContext(), pal,
                "共 " + items.length() + " 个" + cfg.unit + " · " + cfg.doneLabel + " " + doneN,
                12.5f, false, pal.textMute);
        sub.setPadding(0, pal.dp(6), 0, 0);
        head.addView(sub);
        LinearLayout.LayoutParams hlp = NativeBlocks.matchP();
        hlp.bottomMargin = pal.dp(10);
        head.setLayoutParams(hlp);
        page.addView(NativeBlocks.glassWrap(getContext(), pal, head, glassSource, direct,
                page.getWidth() > 0 ? page.getWidth()
                        : getResources().getDisplayMetrics().widthPixels));

        if (cfg.grouped) {
            /* 分组折叠：只建组标题，点开才建该组卡片（2026-11-07）。 */
            java.util.LinkedHashMap<String, java.util.List<JSONObject>> groups =
                    new java.util.LinkedHashMap<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                String c = o.optString("cat");
                if (c.isEmpty()) c = "未分类";
                java.util.List<JSONObject> g = groups.get(c);
                if (g == null) { g = new java.util.ArrayList<>(); groups.put(c, g); }
                g.add(o);
            }
            for (java.util.Map.Entry<String, java.util.List<JSONObject>> e : groups.entrySet()) {
                page.addView(groupCard(e.getKey(), e.getValue()));
            }
        } else {
            NativeBlocks.Flow flow = new NativeBlocks.Flow(page, isLandscape());
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o != null) flow.add(itemCard(o, flow));
            }
            flow.commit();
        }
    }

    private boolean lastLand; { /* 冷启动即横屏（平板常态）无 config change：初值必须按当前 config 定 */
        lastLand = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }   /* onConfigurationChanged 显式下发（View 的
        resources config 更新晚于回调，自读会拿到旧方向——2026-11-09 实证） */

    private boolean isLandscape() { return lastLand; }

    /** 旋转后强制重建（深浅不变时 setDark 不触发 build，单列⇄双列切换收不到）。
     *  land 由 MainActivity 从 newConfig 显式传入。 */
    public void onOrientationChanged(boolean land) {
        lastLand = land;
        dirty = true;
        if (isShowing()) buildPage();
    }

    /** 分组卡：标题常驻，展开时才建该组条目卡。 */
    private View groupCard(String name, java.util.List<JSONObject> list) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(VERTICAL);
        texts.addView(NativeBlocks.tv(getContext(), pal, name, 15.5f, true, pal.textMain));
        TextView cnt = NativeBlocks.tv(getContext(), pal, list.size() + " 题", 12, false, pal.textMute);
        cnt.setPadding(0, pal.dp(4), 0, 0);
        texts.addView(cnt);
        head.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = NativeBlocks.tv(getContext(), pal, "›", 20, false, pal.textMute);
        head.addView(arrow);

        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(VERTICAL);
        body.setVisibility(GONE);
        body.setPadding(0, pal.dp(8), 0, 0);

        final boolean[] built = {false};
        head.setOnClickListener(v -> {
            if (body.getVisibility() == VISIBLE) {
                body.setVisibility(GONE);
                /* ❗收起即销毁：展开组的题目卡+正文 blocks 全量常驻视图树，竖屏展开
                   几组后页面高 2 万 px+，滚动每帧 draw 遍历上千 View（2026-11-09
                   用户报竖屏面试滚动十分卡）。收起清空、再点重建（探针监控 <100ms）。 */
                body.removeAllViews();
                built[0] = false;
                arrow.setRotation(0f);
            } else {
                if (!built[0]) {
                    /* 组内条目横屏双列（2026-11-09）：一次建齐，Flow 管分列与展开跨行。 */
                    long __t0 = android.os.SystemClock.uptimeMillis();
                    NativeBlocks.Flow flow = new NativeBlocks.Flow(body, isLandscape());
                    for (JSONObject o : list) flow.add(itemCard(o, flow));
                    flow.commit();
                    /* PerfProbe：组展开建卡耗时（用户报竖屏面试「加载很久」，先定位）。 */
                    android.util.Log.i("PerfProbe", cfg.dataKey + " group expand build="
                            + (android.os.SystemClock.uptimeMillis() - __t0) + "ms n=" + list.size());
                    built[0] = true;
                }
                body.setVisibility(VISIBLE);
                arrow.setRotation(90f);
            }
        });
        card.addView(head);
        card.addView(body);
        return card;
    }

    private View itemCard(JSONObject o, NativeBlocks.Flow flow) {
        String id = o.optString("id");
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        boolean isDone = done.contains(id), isFav = fav.contains(id);

        String meta = o.optString("cat");
        if (isDone) meta += " · ✓ " + cfg.doneLabel;
        if (isFav) meta += " · ★ 收藏";

        TextView[] arrowOut = new TextView[1];
        LinearLayout head = NativeBlocks.head(getContext(), pal, chipText(id), o.optString("t"), meta, isDone, arrowOut);
        card.addView(head);

        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(VERTICAL);
        body.setVisibility(GONE);
        body.setPadding(0, pal.dp(10), 0, 0);
        NativeBlocks.blocks(getContext(), pal, body, o.optJSONArray("blocks"));
        body.addView(actionBar(id, isDone, isFav));
        card.addView(body);

        head.setOnClickListener(v -> {
            boolean open = body.getVisibility() == VISIBLE;
            if (open) {
                body.setVisibility(GONE);
                arrowOut[0].setRotation(90f);
            } else {
                body.setVisibility(VISIBLE);
                body.setAlpha(0f);
                body.animate().alpha(1f).setDuration(180).start();
                arrowOut[0].setRotation(-90f);
            }
            /* 横屏展开跨行（同 NativePhaseView）；open 是点击前状态。 */
            if (flow != null) flow.setWide(card, !open);
        });

        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    /** chip 文本：面试 IVQ1 → Q1；排障 G1 原样（Config 决定是否替换）。 */
    private String chipText(String id) {
        String s = id == null ? "" : id;
        return cfg.chipFrom.isEmpty() ? s : s.replace(cfg.chipFrom, cfg.chipTo);
    }

    private View actionBar(String id, boolean isDone, boolean isFav) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, pal.dp(12), 0, 0);

        TextView btnDone = NativeBlocks.tv(getContext(), pal, isDone ? cfg.actionOn : cfg.actionOff,
                13, true, isDone ? Color.WHITE : pal.accent);
        btnDone.setGravity(Gravity.CENTER);
        btnDone.setPadding(0, pal.dp(9), 0, pal.dp(9));
        btnDone.setBackground(NativeBlocks.shape(pal, isDone ? pal.accent : pal.chipBgOff, pal.dp(12)));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = pal.dp(8);
        row.addView(btnDone, lp1);
        btnDone.setOnClickListener(v -> {
            boolean on = !done.contains(id);
            if (on) done.add(id); else done.remove(id);
            saveState();
            if (stateListener != null) stateListener.onState(id, "done", on);
            buildPage();
        });

        TextView btnFav = NativeBlocks.tv(getContext(), pal, isFav ? "★ 已收藏" : "☆ 收藏", 13, false,
                isFav ? pal.textMain : pal.textSub);
        btnFav.setGravity(Gravity.CENTER);
        btnFav.setPadding(pal.dp(14), pal.dp(9), pal.dp(14), pal.dp(9));
        btnFav.setBackground(NativeBlocks.shape(pal, pal.chipBgOff, pal.dp(12)));
        row.addView(btnFav);
        btnFav.setOnClickListener(v -> {
            boolean on = !fav.contains(id);
            if (on) fav.add(id); else fav.remove(id);
            saveState();
            if (stateListener != null) stateListener.onState(id, "fav", on);
            buildPage();
        });
        return row;
    }
}
