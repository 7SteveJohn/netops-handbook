package com.netops.handbook.lens;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 原生速查表页（2026-10-27 · N4）。
 *
 * Web 版是「列表 → 点进单表」两级；原生直接**一页铺完 12 张表**，
 * 理由：速查表的用法就是快速扫，两级反而多一次点击，且省掉原生层的
 * 内部返回栈（返回键仍归页面路由，不会出现「返回退一层表格」的歧义）。
 */
public class NativeRefsView extends LinearLayout {

    private JSONArray refs = new JSONArray();
    private boolean dark = false, direct = false;
    private final ScrollView scroller;
    private final LinearLayout page;
    private NativeBlocks.Palette pal;
    /** 玻璃件采样源（原生壁纸层）。 */
    private android.view.ViewGroup glassSource;

    public NativeRefsView(Context context) {
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

    public void setPageData(JSONObject root) {
        JSONArray a = root.optJSONArray("refs");
        if (a != null) refs = a;
        android.util.Log.i("NativeRefs", "setPageData n=" + refs.length());
    }

    public boolean show() {
        if (refs.length() == 0) return false;
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
        head.addView(NativeBlocks.tv(getContext(), pal, "速查表", 19, true, pal.textMain));
        TextView sub = NativeBlocks.tv(getContext(), pal,
                "高频速查表 · 共 " + refs.length() + " 张。面试前 10 分钟过一遍最有效。",
                12.5f, false, pal.textMute);
        sub.setPadding(0, pal.dp(6), 0, 0);
        head.addView(sub);
        LinearLayout.LayoutParams hlp = NativeBlocks.matchP();
        hlp.bottomMargin = pal.dp(10);
        head.setLayoutParams(hlp);
        page.addView(NativeBlocks.glassWrap(getContext(), pal, head, glassSource, direct,
                page.getWidth() > 0 ? page.getWidth()
                        : getResources().getDisplayMetrics().widthPixels));

        for (int i = 0; i < refs.length(); i++) {
            JSONObject r = refs.optJSONObject(i);
            if (r != null) page.addView(tableCard(r));
        }
    }

    private android.view.View tableCard(JSONObject r) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        card.addView(NativeBlocks.tv(getContext(), pal, r.optString("t"), 15, true, pal.textMain));
        String d = r.optString("d");
        if (!d.isEmpty()) {
            TextView dv = NativeBlocks.tv(getContext(), pal, d, 11.5f, false, pal.textMute);
            dv.setPadding(0, pal.dp(4), 0, 0);
            card.addView(dv);
        }

        JSONArray cols = r.optJSONArray("cols");
        JSONArray rows = r.optJSONArray("rows");
        int n = cols == null ? 0 : cols.length();
        if (n == 0 || rows == null) return card;

        card.addView(headerRow(cols, n));
        for (int i = 0; i < rows.length(); i++) {
            JSONArray cells = rows.optJSONArray(i);
            if (cells == null) continue;
            card.addView(dataRow(cells, n, i % 2 == 1));
        }
        return card;
    }

    private android.view.View headerRow(JSONArray cols, int n) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, pal.dp(10), 0, pal.dp(4));
        for (int i = 0; i < n; i++) {
            TextView c = NativeBlocks.tv(getContext(), pal, cols.optString(i), 11, true, pal.textMute);
            c.setPadding(pal.dp(4), 0, pal.dp(4), 0);
            row.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                    i == 0 ? 1.4f : 1f));
        }
        return row;
    }

    private android.view.View dataRow(JSONArray cells, int n, boolean alt) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, pal.dp(6), 0, pal.dp(6));
        if (alt) row.setBackground(NativeBlocks.shape(pal, pal.chipBgOff, pal.dp(6)));
        for (int i = 0; i < n; i++) {
            String v = cells.optString(i, "");
            TextView c = NativeBlocks.tv(getContext(), pal, v, 12, i == 0, i == 0 ? pal.textMain : pal.textSub);
            c.setPadding(pal.dp(4), 0, pal.dp(4), 0);
            row.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                    i == 0 ? 1.4f : 1f));
        }
        return row;
    }
}
