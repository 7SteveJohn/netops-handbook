package com.netops.handbook.lens;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 原生简单列表页（2026-10-27 · N4）。
 *
 * 术语表 / 学习路线图共用：都是「标题 + 说明文字」的一维列表，没有展开块、
 * 没有状态。数据由各自的 setter 适配成 [{t, d}] 后交给同一个渲染体。
 */
public class NativeSimpleList extends LinearLayout {

    private JSONArray items = new JSONArray();
    private String title = "", subtitle = "";
    private boolean dark = false, direct = false;
    private final ScrollView scroller;
    private final LinearLayout page;
    private NativeBlocks.Palette pal;
    /** 玻璃件采样源（原生壁纸层）。 */
    private android.view.ViewGroup glassSource;

    public NativeSimpleList(Context context) {
        super(context);
        setOrientation(VERTICAL);
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

    /** items 元素：{t: 标题, d: 说明}（术语/路线图都是这个形状）。 */
    public void setItems(String headTitle, String headSub, JSONArray list) {
        title = headTitle;
        subtitle = headSub;
        items = list == null ? new JSONArray() : list;
        android.util.Log.i("NativeSimple", headTitle + " n=" + items.length());
    }

    public void setGlossary(JSONObject root) {
        JSONArray g = root.optJSONArray("glossary");
        if (g == null) return;
        JSONArray out = new JSONArray();
        for (int i = 0; i < g.length(); i++) {
            JSONObject o = g.optJSONObject(i);
            if (o == null) continue;
            JSONObject it = new JSONObject();
            try {
                it.put("t", o.optString("t"));
                it.put("d", o.optString("d"));
            } catch (Throwable ignored) { }
            out.put(it);
        }
        setItems("术语表", "共 " + out.length() + " 个术语 · 按字母/拼音排列", out);
    }

    public void setRoadmap(JSONObject root) {
        JSONArray r = root.optJSONArray("roadmap");
        if (r == null) return;
        JSONArray out = new JSONArray();
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.optJSONObject(i);
            if (o == null) continue;
            JSONObject it = new JSONObject();
            try {
                it.put("t", o.optString("t"));
                it.put("d", o.optString("d"));
            } catch (Throwable ignored) { }
            out.put(it);
        }
        setItems("学习路线图", "从打地基到云原生 · 共 " + out.length() + " 个阶段", out);
    }

    public boolean show() {
        if (items.length() == 0) return false;
        buildPage();
        scroller.scrollTo(0, 0);
        animate().cancel();          /* 取消未完成的 hide 动画，否则其 endAction 会把刚显示的页面又置 GONE */
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(140).start();
        return true;
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        animate().alpha(0f).setDuration(120).withEndAction(() -> {
            setVisibility(GONE);
            page.removeAllViews();
        }).start();
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

    private void buildPage() {
        page.removeAllViews();
        page.setPadding(pal.dp(14), pal.dp(10), pal.dp(14), pal.dp(24));

        LinearLayout head = NativeBlocks.card(getContext(), pal);
        head.addView(NativeBlocks.tv(getContext(), pal, title, 19, true, pal.textMain));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = NativeBlocks.tv(getContext(), pal, subtitle, 12.5f, false, pal.textMute);
            sub.setPadding(0, pal.dp(6), 0, 0);
            head.addView(sub);
        }
        LinearLayout.LayoutParams hlp = NativeBlocks.matchP();
        hlp.bottomMargin = pal.dp(10);
        head.setLayoutParams(hlp);
        page.addView(NativeBlocks.glassWrap(getContext(), pal, head, glassSource, direct,
                page.getWidth() > 0 ? page.getWidth()
                        : getResources().getDisplayMetrics().widthPixels));

        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            LinearLayout card = NativeBlocks.card(getContext(), pal);
            card.addView(NativeBlocks.tv(getContext(), pal, o.optString("t"), 14.5f, true, pal.textMain));
            String d = o.optString("d");
            if (!d.isEmpty()) {
                TextView body = NativeBlocks.tv(getContext(), pal, d, 13, false, pal.textSub);
                body.setLineSpacing(pal.dp(4), 1f);
                body.setPadding(0, pal.dp(6), 0, 0);
                card.addView(body);
            }
            LinearLayout.LayoutParams lp = NativeBlocks.matchP();
            lp.bottomMargin = pal.dp(8);
            card.setLayoutParams(lp);
            page.addView(card);
        }
    }
}
