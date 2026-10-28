package com.netops.handbook.lens;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
 * 原生学习首页（全量原生化 N2 · 2026-10-27）。
 *
 * 结构对齐 Web 版 V.learn：英雄卡（离线声明+四项统计）→ 进度卡（环形+继续学习）
 * → 学习路径（4 张阶段卡）→ 工具箱（5 个入口）。
 * 状态读 netops_native（与阶段页同一份 done），导航回给页面层（NetOpsNativeGo），
 * 不在原生侧重写路由栈。
 */
public class NativeHomeView extends LinearLayout {

    /** 导航回传：route/arg 交给页面层 go()，原生不自己维护栈。 */
    public interface Nav {
        void go(String route, String arg);
    }

    private JSONObject root;
    private boolean dark = false, direct = false;
    private final ScrollView scroller;
    private final LinearLayout page;
    private NativeBlocks.Palette pal;
    /** 玻璃件采样源（原生壁纸层）。 */
    private android.view.ViewGroup glassSource;
    private Nav nav;

    private final SharedPreferences sp;
    private final Set<String> done = new HashSet<>();

    public NativeHomeView(Context context) {
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

    private void loadState() {
        done.clear();
        for (String s : sp.getString("done", "").split(",")) if (!s.isEmpty()) done.add(s);
    }

    public void setNav(Nav n) { nav = n; }

    public void setGlassSource(android.view.ViewGroup src) { glassSource = src; }

    public void setPageData(JSONObject data) {
        root = data;
        android.util.Log.i("NativeHome", "setPageData ok=" + (data != null));
    }

    /** 已构建过就复用：buildPage 一次要几十到几百 ms，每次 show 重建会把 UI 线程按住，
     *  切页时侧栏弹簧直接顿住（2026-10-28）。数据/主题变化才置脏。 */
    private boolean dirty = true;

    public boolean show() {
        if (root == null) return false;
        loadState();
        if (dirty || page.getChildCount() == 0) { buildPage(); dirty = false; }
        scroller.scrollTo(0, sp.getInt("scroll_home", 0));
        animate().cancel();          /* 取消未完成的 hide 动画，否则其 endAction 会把刚显示的页面又置 GONE */
        setVisibility(VISIBLE);
        setAlpha(1f);   /* 不做整屏淡入：带玻璃卡的全屏视图 alpha 动画要新建离屏层，
                           切页时实测 95th 53ms / 99th 200ms（2026-10-28）*/
        return true;
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        sp.edit().putInt("scroll_home", scroller.getScrollY()).apply();
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

    /* ---------------- 构建 ---------------- */
    private void buildPage() {
        page.removeAllViews();
        page.setPadding(pal.dp(14), pal.dp(10), pal.dp(14), pal.dp(24));

        int modTotal = 0, doneTotal = 0;
        JSONArray phases = root.optJSONArray("phases");
        if (phases != null) {
            for (int i = 0; i < phases.length(); i++) {
                JSONArray mods = phases.optJSONObject(i).optJSONArray("mods");
                if (mods == null) continue;
                modTotal += mods.length();
                for (int k = 0; k < mods.length(); k++) {
                    JSONObject m = mods.optJSONObject(k);
                    if (m != null && done.contains(m.optString("id"))) doneTotal++;
                }
            }
        }
        int pct = modTotal == 0 ? 0 : Math.round(doneTotal * 100f / modTotal);

        page.addView(heroCard());
        page.addView(progressCard(pct, doneTotal, modTotal));
        page.addView(sectionTitle("学习路径"));
        if (phases != null) {
            for (int i = 0; i < phases.length(); i++) {
                JSONObject p = phases.optJSONObject(i);
                if (p != null) page.addView(phaseCard(p, i + 1));
            }
        }
        page.addView(sectionTitle("工具箱"));
        page.addView(toolRow("搜索", "全库搜模块 / 排障 / 面试 / 命令 / 术语", "search"));
        page.addView(toolRow("速查表", refsCount() + " 张 · 端口 / 掩码 / OSPF / BGP", "refs"));
        page.addView(toolRow("学习路线图", "0-12 个月成长节奏与目标", "roadmap"));
        page.addView(toolRow("术语词典", glossaryCount() + " 条云原生与数通黑话", "glossary"));
        page.addView(toolRow("模拟测验", quizCount() + " 道选择题", "quiz"));
        page.addView(toolRow("CLI 模拟器", "离线练习 VRP / Linux 常用命令", "cli"));
    }

    private int refsCount() { JSONArray a = root.optJSONArray("refs"); return a == null ? 0 : a.length(); }
    private int glossaryCount() { JSONArray a = root.optJSONArray("glossary"); return a == null ? 0 : a.length(); }
    private int quizCount() { JSONArray a = root.optJSONArray("quiz"); return a == null ? 0 : a.length(); }

    private View heroCard() {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        TextView eye = NativeBlocks.tv(getContext(), pal, "OFFLINE READY", 10.5f, true, pal.accent);
        card.addView(eye);
        TextView title = NativeBlocks.tv(getContext(), pal, "网络学习手册 2.0", 22, true, pal.textMain);
        title.setPadding(0, pal.dp(6), 0, 0);
        card.addView(title);
        TextView desc = NativeBlocks.tv(getContext(), pal,
                "从零基础数通到云原生架构 · 排障字典 · 面试题库\n辅助工具，配合教材与真机实验使用\n纯离线运行，无需任何网络连接",
                12.5f, false, pal.textMute);
        desc.setLineSpacing(pal.dp(4), 1f);
        desc.setPadding(0, pal.dp(8), 0, 0);
        card.addView(desc);

        LinearLayout stats = new LinearLayout(getContext());
        stats.setOrientation(HORIZONTAL);
        stats.setPadding(0, pal.dp(12), 0, 0);
        int mods = 0;
        JSONArray phases = root.optJSONArray("phases");
        if (phases != null) for (int i = 0; i < phases.length(); i++) {
            JSONArray ms = phases.optJSONObject(i).optJSONArray("mods");
            if (ms != null) mods += ms.length();
        }
        stats.addView(stat(v(mods), "知识模块"));
        stats.addView(stat(v(arrLen("faults")), "排障案例"));
        stats.addView(stat(v(dictRows()), "命令对照"));
        stats.addView(stat(v(arrLen("interview")), "面试真题"));
        card.addView(stats);

        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(10);
        card.setLayoutParams(lp);
        return NativeBlocks.glassWrap(getContext(), pal, card, glassSource, direct,
                page.getWidth() > 0 ? page.getWidth()
                        : getResources().getDisplayMetrics().widthPixels);
    }

    private int arrLen(String key) { JSONArray a = root.optJSONArray(key); return a == null ? 0 : a.length(); }
    private int dictRows() {
        JSONObject d = root.optJSONObject("dict");
        JSONArray r = d == null ? null : d.optJSONArray("rows");
        return r == null ? 0 : r.length();
    }
    private String v(int n) { return String.valueOf(n); }

    private View stat(String num, String label) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(NativeBlocks.tv(getContext(), pal, num, 17, true, pal.textMain));
        TextView l = NativeBlocks.tv(getContext(), pal, label, 10, false, pal.textMute);
        l.setPadding(0, pal.dp(2), 0, 0);
        col.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(lp);
        return col;
    }

    private View progressCard(int pct, int doneTotal, int modTotal) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        NativeRing ring = new NativeRing(getContext(), pal);
        ring.setPct(pct);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(pal.dp(64), pal.dp(64));
        rlp.rightMargin = pal.dp(14);
        row.addView(ring, rlp);

        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.addView(NativeBlocks.tv(getContext(), pal, "学习进度", 14.5f, true, pal.textMain));
        TextView sub = NativeBlocks.tv(getContext(), pal, doneTotal + " / " + modTotal + " 个模块",
                11.5f, false, pal.textMute);
        sub.setPadding(0, pal.dp(3), 0, 0);
        col.addView(sub);

        String nextPid = null, nextId = null, nextTitle = null;
        JSONArray phases = root.optJSONArray("phases");
        outer:
        if (phases != null) for (int i = 0; i < phases.length(); i++) {
            JSONObject p = phases.optJSONObject(i);
            JSONArray mods = p.optJSONArray("mods");
            if (mods == null) continue;
            for (int k = 0; k < mods.length(); k++) {
                JSONObject m = mods.optJSONObject(k);
                if (m != null && !done.contains(m.optString("id"))) {
                    nextPid = p.optString("id");
                    nextId = m.optString("id");
                    nextTitle = m.optString("t");
                    break outer;
                }
            }
        }

        if (nextPid != null) {
            final String fPid = nextPid, fId = nextId;
            String label = "继续：" + (nextTitle == null ? "" : nextTitle)
                    + ((nextTitle != null && nextTitle.length() > 10) ? "…" : "");
            TextView btn = NativeBlocks.tv(getContext(), pal, label, 12.5f, true, Color.WHITE);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(pal.dp(12), pal.dp(8), pal.dp(12), pal.dp(8));
            btn.setBackground(NativeBlocks.shape(pal, pal.accent, pal.dp(11)));
            LinearLayout.LayoutParams blp = NativeBlocks.matchP();
            blp.topMargin = pal.dp(9);
            col.addView(btn, blp);
            btn.setOnClickListener(x -> { if (nav != null) nav.go("phase-focus", fPid + "|" + fId); });
        } else {
            TextView doneAll = NativeBlocks.tv(getContext(), pal, "全部模块已掌握", 12, true, pal.textMain);
            doneAll.setPadding(0, pal.dp(8), 0, 0);
            col.addView(doneAll);
        }

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private View sectionTitle(String t) {
        TextView tv = NativeBlocks.tv(getContext(), pal, t, 12.5f, true, pal.textMute);
        tv.setPadding(0, pal.dp(6), 0, pal.dp(8));
        return tv;
    }

    private View phaseCard(JSONObject p, int idx) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        int total = 0, dn = 0;
        JSONArray mods = p.optJSONArray("mods");
        if (mods != null) {
            total = mods.length();
            for (int i = 0; i < total; i++) {
                JSONObject m = mods.optJSONObject(i);
                if (m != null && done.contains(m.optString("id"))) dn++;
            }
        }
        int pc = total == 0 ? 0 : Math.round(dn * 100f / total);

        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView index = NativeBlocks.tv(getContext(), pal, String.valueOf(idx), 13, true, pal.accent);
        index.setGravity(Gravity.CENTER);
        index.setBackground(NativeBlocks.shape(pal, pal.chipBgOn, pal.dp(10)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(pal.dp(26), pal.dp(26));
        ilp.rightMargin = pal.dp(10);
        top.addView(index, ilp);

        LinearLayout mid = new LinearLayout(getContext());
        mid.setOrientation(VERTICAL);
        String name = p.optString("title").replaceAll("^阶段[一二三四五]：", "");
        mid.addView(NativeBlocks.tv(getContext(), pal, name, 14.5f, true, pal.textMain));
        TextView meta = NativeBlocks.tv(getContext(), pal,
                p.optString("short") + " · " + total + " 模块", 11, false, pal.textMute);
        meta.setPadding(0, pal.dp(3), 0, 0);
        mid.addView(meta);
        top.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(NativeBlocks.tv(getContext(), pal, "›", 20, true, pal.textMute));
        card.addView(top);

        String desc = p.optString("desc");
        if (!TextUtils.isEmpty(desc)) {
            TextView d = NativeBlocks.tv(getContext(), pal, desc, 12, false, pal.textSub);
            d.setLineSpacing(pal.dp(3), 1f);
            d.setPadding(0, pal.dp(8), 0, 0);
            card.addView(d);
        }

        LinearLayout barRow = new LinearLayout(getContext());
        barRow.setOrientation(HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        barRow.setPadding(0, pal.dp(10), 0, 0);
        LinearLayout track = new LinearLayout(getContext());
        track.setBackground(NativeBlocks.shape(pal, pal.hairline, pal.dp(3)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, pal.dp(5), 1f);
        tlp.rightMargin = pal.dp(8);
        barRow.addView(track, tlp);
        track.addView(new LinearLayout(getContext()),
                new LinearLayout.LayoutParams(0, pal.dp(5), Math.max(0.0001f, pc / 100f)));
        track.addView(new LinearLayout(getContext()),
                new LinearLayout.LayoutParams(0, pal.dp(5), Math.max(0.0001f, 1f - pc / 100f)));
        barRow.addView(NativeBlocks.tv(getContext(), pal, dn + "/" + total, 10.5f, false, pal.textMute));
        card.addView(barRow);

        final String pid = p.optString("id");
        card.setOnClickListener(x -> { if (nav != null) nav.go("phase", pid); });

        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private View toolRow(String title, String sub, final String route) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        card.setOrientation(VERTICAL);
        card.addView(NativeBlocks.tv(getContext(), pal, title, 14, true, pal.textMain));
        TextView s = NativeBlocks.tv(getContext(), pal, sub, 11.5f, false, pal.textMute);
        s.setPadding(0, pal.dp(3), 0, 0);
        card.addView(s);
        card.setOnClickListener(x -> { if (nav != null) nav.go(route, ""); });
        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(8);
        card.setLayoutParams(lp);
        return card;
    }
}
