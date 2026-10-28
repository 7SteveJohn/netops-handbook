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

        scroller = new ScrollView(context);
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
        if (dirty || page.getChildCount() == 0) { buildPage(); dirty = false; }
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

        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null) page.addView(itemCard(o));
        }
    }

    private View itemCard(JSONObject o) {
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
