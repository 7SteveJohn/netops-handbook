package com.netops.handbook.lens;

import android.content.Context;
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
 * 原生「我的」页（2026-10-27 · N2 收尾）。
 *
 * 结构对齐 Web 版 V.me：总进度环卡 → 四格统计 → 收藏夹 → 资源 → 设置。
 * 设置行打开原生设置面板（NativeSettingsPanel），设置的**真源仍在页面层**。
 * 动画效果/速度档等小开关留在 HTML 版（低频，暂不搬）。
 */
public class NativeMeView extends LinearLayout {

    public interface Nav {
        void go(String route);   /* "settings" = 打开原生设置面板 */
    }

    private JSONObject root;
    private boolean dark = false, direct = false;
    private final ScrollView scroller;
    private final LinearLayout page;
    private NativeBlocks.Palette pal;
    private Nav nav;

    private final android.content.SharedPreferences sp;
    private final Set<String> done = new HashSet<>();
    private final Set<String> fav = new HashSet<>();

    public NativeMeView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        sp = context.getSharedPreferences("netops_native", Context.MODE_PRIVATE);
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

    public void setNav(Nav n) { nav = n; }

    public void setPageData(JSONObject data) { root = data; }

    /** 已构建过就复用：buildPage 一次要几十到几百 ms，每次 show 重建会把 UI 线程按住，
     *  切页时侧栏弹簧直接顿住（2026-10-28）。数据/主题变化才置脏。 */
    private boolean dirty = true;

    public boolean show() {
        android.util.Log.i("NativeMe", "show root=" + (root != null));
        if (root == null) return false;
        if (getVisibility() == VISIBLE && !dirty) return true;
        done.clear(); fav.clear();
        for (String s : sp.getString("done", "").split(",")) if (!s.isEmpty()) done.add(s);
        for (String s : sp.getString("fav", "").split(",")) if (!s.isEmpty()) fav.add(s);
        buildPage();
        scroller.scrollTo(0, sp.getInt("scroll_me", 0));
        animate().cancel();          /* 取消未完成的 hide 动画，否则其 endAction 会把刚显示的页面又置 GONE */
        setVisibility(VISIBLE);
        setAlpha(1f);   /* 不做整屏淡入：带玻璃卡的全屏视图 alpha 动画要新建离屏层，
                           切页时实测 95th 53ms / 99th 200ms（2026-10-28）*/
        return true;
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        sp.edit().putInt("scroll_me", scroller.getScrollY()).apply();
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

    private void refreshPalette() {
        pal = NativeBlocks.palette(getContext(), dark, direct);
        setBackgroundColor(pal.bg);
        dirty = true;
        if (isShowing()) buildPage();
    }

    private void buildPage() {
        page.removeAllViews();
        page.setPadding(pal.dp(14), pal.dp(10), pal.dp(14), pal.dp(24));

        int dm = 0, df = 0, di = 0;
        JSONArray phases = root.optJSONArray("phases");
        if (phases != null) for (int i = 0; i < phases.length(); i++) {
            JSONArray ms = phases.optJSONObject(i).optJSONArray("mods");
            if (ms == null) continue;
            for (int k = 0; k < ms.length(); k++) {
                JSONObject m = ms.optJSONObject(k);
                if (m != null && done.contains(m.optString("id"))) dm++;
            }
        }
        JSONArray faults = root.optJSONArray("faults");
        if (faults != null) for (int i = 0; i < faults.length(); i++) {
            JSONObject f = faults.optJSONObject(i);
            if (f != null && done.contains(f.optString("id"))) df++;
        }
        JSONArray ivs = root.optJSONArray("interview");
        if (ivs != null) for (int i = 0; i < ivs.length(); i++) {
            JSONObject q = ivs.optJSONObject(i);
            if (q != null && done.contains(q.optString("id"))) di++;
        }
        int all = dm + df + di;
        int total = modTotal(phases) + (faults == null ? 0 : faults.length()) + (ivs == null ? 0 : ivs.length());
        int pct = total == 0 ? 0 : Math.round(all * 100f / total);

        page.addView(ringCard(pct, all, total));
        page.addView(statGrid(String.valueOf(dm), "知识模块",
                String.valueOf(faults == null ? 0 : faults.length()), "排障案例",
                String.valueOf(ivs == null ? 0 : ivs.length()), "面试真题",
                quizCount() + " 题", "题库"));

        page.addView(section("我的收藏"));
        page.addView(navRow("收藏夹", fav.isEmpty() ? "还没有收藏任何内容" : fav.size() + " 条已收藏", "fav"));

        /* ❗「资源」区下线（2026-11-08 用户指出重复）：速查表/术语/路线图/测验/CLI
           五项与学习页工具箱一字不差——工具入口主区在学习页工具箱，「我的」页
           聚焦个人数据（进度/收藏/设置）。 */
        page.addView(section("设置"));
        page.addView(navRow("外观与壁纸", "主题 · 壁纸", "settings"));

        page.addView(footer());
    }

    private int modTotal(JSONArray phases) {
        if (phases == null) return 0;
        int n = 0;
        for (int i = 0; i < phases.length(); i++) {
            JSONArray ms = phases.optJSONObject(i).optJSONArray("mods");
            if (ms != null) n += ms.length();
        }
        return n;
    }

    private int refsCount() { JSONArray a = root.optJSONArray("refs"); return a == null ? 0 : a.length(); }
    private int glossaryCount() { JSONArray a = root.optJSONArray("glossary"); return a == null ? 0 : a.length(); }
    private int quizCount() { JSONArray a = root.optJSONArray("quiz"); return a == null ? 0 : a.length(); }

    private View ringCard(int pct, int all, int total) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        NativeRing ring = new NativeRing(getContext(), pal);
        ring.setPct(pct);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(pal.dp(112), pal.dp(112));
        rlp.topMargin = pal.dp(4);
        card.addView(ring, rlp);
        TextView sub = NativeBlocks.tv(getContext(), pal, all + " / " + total + " 项已完成",
                11.5f, false, pal.textMute);
        sub.setPadding(0, pal.dp(10), 0, pal.dp(4));
        card.addView(sub);
        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private View statGrid(String a1, String l1, String a2, String l2, String a3, String l3, String a4, String l4) {
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(VERTICAL);
        LinearLayout r1 = statRow(a1, l1, a2, l2);
        LinearLayout r2 = statRow(a3, l3, a4, l4);
        grid.addView(r1, NativeBlocks.matchP());
        LinearLayout.LayoutParams l2p = NativeBlocks.matchP();
        l2p.topMargin = pal.dp(8);
        grid.addView(r2, l2p);
        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(12);
        grid.setLayoutParams(lp);
        return grid;
    }

    private LinearLayout statRow(String a1, String l1, String a2, String l2) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.addView(statCell(a1, l1), cellLp());
        row.addView(statCell(a2, l2), cellLp());
        return row;
    }

    private LinearLayout.LayoutParams cellLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View statCell(String num, String label) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(pal.dp(10), pal.dp(12), pal.dp(10), pal.dp(12));
        card.addView(NativeBlocks.tv(getContext(), pal, num, 17, true, pal.textMain));
        TextView l = NativeBlocks.tv(getContext(), pal, label, 10.5f, false, pal.textMute);
        l.setPadding(0, pal.dp(3), 0, 0);
        card.addView(l);
        return card;
    }

    private View section(String t) {
        TextView tv = NativeBlocks.tv(getContext(), pal, t, 12.5f, true, pal.textMute);
        tv.setPadding(0, pal.dp(8), 0, pal.dp(8));
        return tv;
    }

    private View navRow(String title, String sub, final String route) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.addView(NativeBlocks.tv(getContext(), pal, title, 14, true, pal.textMain));
        TextView s = NativeBlocks.tv(getContext(), pal, sub, 11.5f, false, pal.textMute);
        s.setPadding(0, pal.dp(3), 0, 0);
        col.addView(s);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(NativeBlocks.tv(getContext(), pal, "›", 20, true, pal.textMute));
        card.setOnClickListener(x -> {
            android.util.Log.i("NativeMe", "row tap: " + route);
            if (nav != null) nav.go(route);
        });
        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    private View footer() {
        TextView tv = NativeBlocks.tv(getContext(), pal, "NetOps 2.0 · 仅供学习交流，禁止商用", 10.5f, false, pal.textMute);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, pal.dp(14), 0, 0);
        return tv;
    }
}
