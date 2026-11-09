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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原生阶段详情页（全量原生化 N1 · 2026-10-27）。
 *
 * 数据来自 assets/native-data.json（构建期由 tools/gen-native-data.js 生成），
 * 内容不经过 WebView。渲染件复用 NativeBlocks（与排障/面试页共用）。
 *
 * 状态：SharedPreferences `netops_native` 为唯一写入方，变更通过 StateListener
 * 回灌页面层，保证 WebView 侧视图与原生一致。
 */
public class NativePhaseView extends LinearLayout {

    private static class Phase {
        String id, title, shortName, desc;
        JSONArray mods;          /* 原样保留 JSON，渲染交给 NativeBlocks */
    }

    public interface StateListener {
        void onState(String id, String kind, boolean on);   /* kind: done | fav */
    }

    private final List<Phase> phases = new ArrayList<>();
    private final Map<String, Phase> byId = new HashMap<>();
    private Phase current;
    private boolean dark = false, direct = false;
    private String pendingFocus;

    private final ScrollView scroller;
    private final LinearLayout page;
    private NativeBlocks.Palette pal;
    private StateListener stateListener;
    /** 玻璃件采样源（原生壁纸层）；为 null 或 SDK<33 时头部卡退回普通卡面。 */
    private android.view.ViewGroup glassSource;

    private final SharedPreferences sp;
    private final Set<String> done = new HashSet<>();
    private final Set<String> fav = new HashSet<>();

    public NativePhaseView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setWillNotDraw(false);
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

    public void setGlassSource(android.view.ViewGroup src) { glassSource = src; }

    /** 旧 localStorage 状态并入（迁移；取并集不丢数据）。 */
    public void mergeLegacyState(String doneJson, String favJson) {
        boolean changed = mergeInto(done, doneJson);
        changed |= mergeInto(fav, favJson);
        if (changed) { saveState(); dirty = true; if (isShowing()) buildPage(); }
    }

    /** 页面层改了状态：直接设值（取消也要生效，不能只做并集）。 */
    public void setState(String id, String kind, boolean on) {
        Set<String> target = "fav".equals(kind) ? fav : done;
        if (!(on ? target.add(id) : target.remove(id))) return;
        saveState();
        dirty = true;
        if ("done".equals(kind) && isShowing()) buildPage();
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
        try {
            JSONArray arr = root.optJSONArray("phases");
            phases.clear(); byId.clear();
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Phase p = new Phase();
                p.id = o.optString("id");
                p.title = o.optString("title");
                p.shortName = o.optString("short");
                p.desc = o.optString("desc");
                p.mods = o.optJSONArray("mods");
                if (p.mods == null) p.mods = new JSONArray();
                phases.add(p); byId.put(p.id, p);
            }
            android.util.Log.i("NativePhase", "setPageData phases=" + phases.size());
        } catch (Throwable e) {
            android.util.Log.e("NativePhase", "setPageData 失败", e);
        }
    }

    /* ---------------- 显示 ---------------- */
    public boolean show(String pid) { return show(pid, null); }

    /** 已构建过就复用：buildPage 一次几十~几百 ms，每次 show 重建会按住 UI 线程
     *  （2026-10-28 侧栏切页「卡得要命」）。换阶段/带 focus/数据变化才重建。 */
    private boolean dirty = true;

    public boolean show(String pid, String focusId) {
        Phase p = byId.get(pid);
        if (p == null) return false;
        if (getVisibility() == VISIBLE && current == p && focusId == null && !dirty) return true;
        boolean rebuild = dirty || current != p || focusId != null || page.getChildCount() == 0;
        current = p;
        pendingFocus = focusId;
        if (rebuild) { buildPage(); dirty = false; }
        pendingFocus = null;
        scroller.scrollTo(0, focusId == null ? sp.getInt("scroll_" + pid, 0) : 0);
        animate().cancel();          /* 取消未完成的 hide 动画，否则其 endAction 会把刚显示的页面又置 GONE */
        setVisibility(VISIBLE);
        setAlpha(1f);   /* 不做整屏淡入：带玻璃卡的全屏视图 alpha 动画要新建离屏层，
                           切页时实测 95th 53ms / 99th 200ms（2026-10-28）*/
        return true;
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        if (current != null) sp.edit().putInt("scroll_" + current.id, scroller.getScrollY()).apply();
        setVisibility(GONE);
    }

    public boolean isShowing() { return getVisibility() == VISIBLE; }

    public void setSafeArea(int topPx, int bottomPx) {
        /* +28dp 让开顶栏窗口的投影带（落入带内的触摸归顶栏窗） */
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

    /** 旋转后强制重建：深浅不变时 setDark 直接 return，单列⇄双列切换收不到
     *  （2026-11-09 横屏双列配套）。 */
    private boolean lastLand; { /* 冷启动即横屏（平板常态）无 config change：初值必须按当前 config 定 */
        lastLand = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    public void onOrientationChanged(boolean land) {
        lastLand = land;
        dirty = true;
        if (isShowing()) buildPage();
    }

    private void refreshPalette() {
        pal = NativeBlocks.palette(getContext(), dark, direct);
        setBackgroundColor(pal.bg);
        dirty = true;
        if (current != null && isShowing()) buildPage();
    }

    /* ---------------- 构建 ---------------- */
    private void buildPage() {
        page.removeAllViews();
        page.setPadding(pal.dp(14), pal.dp(10), pal.dp(14), pal.dp(24));
        page.addView(headerCard(current));

        /* 横屏双列（2026-11-09 用户拍板）：单栏满宽在横屏一行拖 50+ 字、滚动翻倍。
           展开跨行：正文卡（命令/表格）点开时满宽独占一行（is-wide 语义），
           否则双列窄卡里正文没法读。 */
        boolean land = lastLand;
        NativeBlocks.Flow flow = new NativeBlocks.Flow(page, land);
        View focusView = null;
        for (int i = 0; i < current.mods.length(); i++) {
            JSONObject m = current.mods.optJSONObject(i);
            if (m == null) continue;
            View card = moduleCard(m, flow);
            if (pendingFocus != null && pendingFocus.equals(m.optString("id"))) focusView = card;
            flow.add(card);
        }
        flow.commit();
        if (focusView != null) {
            final View fv = focusView;
            scroller.postDelayed(() -> scroller.smoothScrollTo(0, Math.max(0, fv.getTop() - pal.dp(20))), 80);
        }
    }

    private View headerCard(Phase p) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        int doneN = 0;
        for (int i = 0; i < p.mods.length(); i++) {
            JSONObject m = p.mods.optJSONObject(i);
            if (m != null && done.contains(m.optString("id"))) doneN++;
        }
        card.addView(NativeBlocks.tv(getContext(), pal, p.title, 19, true, pal.textMain));

        if (!TextUtils.isEmpty(p.desc)) {
            TextView d = NativeBlocks.tv(getContext(), pal, p.desc, 12.5f, false, pal.textMute);
            d.setLineSpacing(pal.dp(3), 1f);
            d.setPadding(0, pal.dp(6), 0, 0);
            card.addView(d);
        }

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, pal.dp(10), 0, 0);
        LinearLayout track = new LinearLayout(getContext());
        track.setBackground(NativeBlocks.shape(pal, pal.hairline, pal.dp(3)));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, pal.dp(6), 1f);
        trackLp.rightMargin = pal.dp(10);
        row.addView(track, trackLp);
        LinearLayout fill = new LinearLayout(getContext());
        fill.setBackground(NativeBlocks.shape(pal, pal.accent, pal.dp(3)));
        float frac = p.mods.length() == 0 ? 0 : (float) doneN / p.mods.length();
        track.addView(fill, new LinearLayout.LayoutParams(0, pal.dp(6), Math.max(0.0001f, frac)));
        track.addView(new View(getContext()),
                new LinearLayout.LayoutParams(0, pal.dp(6), Math.max(0.0001f, 1f - frac)));
        row.addView(NativeBlocks.tv(getContext(), pal, doneN + " / " + p.mods.length(), 12, true, pal.textSub));
        card.addView(row);

        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(10);
        card.setLayoutParams(lp);

        /* 玻璃头卡（公共件）：直铺 + API33 + 有采样源才启用，异常自动回退。 */
        return NativeBlocks.glassWrap(getContext(), pal, card, glassSource, direct,
                page.getWidth() > 0 ? page.getWidth()
                        : getResources().getDisplayMetrics().widthPixels);
    }

    private View moduleCard(JSONObject m, NativeBlocks.Flow flow) {
        String id = m.optString("id");
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        boolean isDone = done.contains(id), isFav = fav.contains(id);

        String meta = m.optString("pshort") + " " + m.optInt("n") + "/" + m.optInt("tc");
        if (m.optBoolean("dg")) meta += " · 拓扑";
        if (m.optBoolean("lab")) meta += " · 实验";
        if (isDone) meta += " · ✓ 已掌握";
        if (isFav) meta += " · ★ 收藏";

        TextView[] arrowOut = new TextView[1];
        LinearLayout head = NativeBlocks.head(getContext(), pal, id, m.optString("t"), meta, isDone, arrowOut);
        card.addView(head);

        boolean autoOpen = pendingFocus != null && pendingFocus.equals(id);
        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(VERTICAL);
        body.setVisibility(autoOpen ? VISIBLE : GONE);
        body.setPadding(0, pal.dp(10), 0, 0);
        NativeBlocks.blocks(getContext(), pal, body, m.optJSONArray("blocks"));
        body.addView(actionBar(id, isDone, isFav));
        card.addView(body);
        arrowOut[0].setRotation(autoOpen ? -90f : 90f);

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
            /* 横屏展开跨行：正文卡独占满宽，收起回双列（竖屏 Flow 恒单列无感）。
               ❗open 是点击前状态，点击后目标态 = !open。 */
            if (flow != null) flow.setWide(card, !open);
        });

        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private View actionBar(String id, boolean isDone, boolean isFav) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, pal.dp(12), 0, 0);

        TextView btnDone = NativeBlocks.tv(getContext(), pal, isDone ? "✓ 已掌握" : "标记掌握", 13, true,
                isDone ? Color.WHITE : pal.accent);
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
