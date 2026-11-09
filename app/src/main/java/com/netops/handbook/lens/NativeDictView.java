package com.netops.handbook.lens;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生命令字典页（全量原生化 N4 · 2026-10-27）。
 *
 * 布局与排障/面试不同：不是可展开卡，而是「命令名 + 四家平台对照表」。
 * 数据：native-data.json 的 dict { cols[{k,n}], rows[{i,fn,hw,cs,zte,lx,cat}] }。
 * 分类筛选：顶部 chips（全部 + 数据里出现的分类），点击重建列表。
 * 点命令 → 复制到剪贴板（与 Web 版 data-cmd 一致）。
 */
public class NativeDictView extends LinearLayout {

    private JSONArray rows = new JSONArray();
    private String[] colKeys = new String[0];
    private String[] colNames = new String[0];

    private boolean dark = false, direct = false;
    /* 冷启动即横屏（平板常态）无 config change：方向初值按当前 config 定 */
    private boolean lastLand = getResources().getConfiguration().orientation
            == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    private final ScrollView scroller;
    private final LinearLayout page;
    private NativeBlocks.Palette pal;
    /** 玻璃件采样源（原生壁纸层）。 */
    private android.view.ViewGroup glassSource;

    public NativeDictView(Context context) {
        super(context);
        setOrientation(VERTICAL);
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

    /* ---------------- 数据 ---------------- */
    public void setPageData(JSONObject root) {
        JSONObject dict = root.optJSONObject("dict");
        if (dict == null) return;
        JSONArray cols = dict.optJSONArray("cols");
        if (cols != null) {
            colKeys = new String[cols.length()];
            colNames = new String[cols.length()];
            for (int i = 0; i < cols.length(); i++) {
                JSONObject c = cols.optJSONObject(i);
                colKeys[i] = c == null ? "" : c.optString("k");
                colNames[i] = c == null ? "" : c.optString("n");
            }
        }
        JSONArray r = dict.optJSONArray("rows");
        if (r != null) rows = r;
        android.util.Log.i("NativeDict", "setPageData rows=" + rows.length());
    }

    /* ---------------- 显示 ---------------- */
    public boolean show() {
        if (rows.length() == 0) return false;
        buildPage();
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

    /** 旋转后强制重建（单列⇄双列），land 由 MainActivity 从 newConfig 显式下发。 */
    public void onOrientationChanged(boolean land) {
        if (lastLand == land) return;
        lastLand = land;
        if (isShowing()) buildPage();
    }

    private void refreshPalette() {
        pal = NativeBlocks.palette(getContext(), dark, direct);
        setBackgroundColor(pal.bg);
        if (isShowing()) buildPage();
    }

    /* ---------------- 构建 ---------------- */
    private void buildPage() {
        page.removeAllViews();
        page.setPadding(pal.dp(14), pal.dp(10), pal.dp(14), pal.dp(24));

        /* 提示 + 计数 */
        LinearLayout head = NativeBlocks.card(getContext(), pal);
        head.addView(NativeBlocks.tv(getContext(), pal, "命令字典", 19, true, pal.textMain));
        TextView sub = NativeBlocks.tv(getContext(), pal,
                "四家平台命令横向对照 · 共 " + rows.length() + " 条。命令在不同版本/平台可能有差异，"
                        + "请以官方文档为准。", 12.5f, false, pal.textMute);
        sub.setLineSpacing(pal.dp(3), 1f);
        sub.setPadding(0, pal.dp(6), 0, 0);
        head.addView(sub);
        LinearLayout.LayoutParams hlp = NativeBlocks.matchP();
        hlp.bottomMargin = pal.dp(10);
        head.setLayoutParams(hlp);
        page.addView(NativeBlocks.glassWrap(getContext(), pal, head, glassSource, direct,
                page.getWidth() > 0 ? page.getWidth()
                        : getResources().getDisplayMetrics().widthPixels));

        int shown = 0;
        /* 分类只有一种入口：分组折叠。此前上方还有一排 chips 做同样的事，
           两者重复（2026-11-07 用户指出），已移除 chips。
           分组后默认只建 7 个组标题，点开某组才建该组的卡 —— 76 条平铺实测
           250~400ms，分组后 99th 降到 65ms。 */
        java.util.LinkedHashMap<String, java.util.List<JSONObject>> groups =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            String c = o.optString("cat");
            if (c.isEmpty()) c = "未分类";
            java.util.List<JSONObject> g = groups.get(c);
            if (g == null) { g = new java.util.ArrayList<>(); groups.put(c, g); }
            g.add(o);
        }
        for (java.util.Map.Entry<String, java.util.List<JSONObject>> e : groups.entrySet()) {
            page.addView(groupCard(e.getKey(), e.getValue()));
            shown += e.getValue().size();
        }
        if (shown == 0) {
            TextView empty = NativeBlocks.tv(getContext(), pal, "没有匹配的命令", 13, false, pal.textMute);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, pal.dp(30), 0, pal.dp(30));
            page.addView(empty);
        }
    }

    /** 分类组卡：标题行常驻，展开时才建该组命令卡。 */
    private View groupCard(String name, java.util.List<JSONObject> list) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);

        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(VERTICAL);
        texts.addView(NativeBlocks.tv(getContext(), pal, name, 15.5f, true, pal.textMain));
        TextView cnt = NativeBlocks.tv(getContext(), pal, list.size() + " 条", 12, false, pal.textMute);
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

        /* 展开时才建卡：这是折叠能省掉 90% View 的关键，常态只留组标题。 */
        final boolean[] built = {false};
        head.setOnClickListener(v -> {
            if (body.getVisibility() == VISIBLE) {
                body.setVisibility(GONE);
                /* ❗收起即销毁（同 DocList）：命令卡常驻会让滚动 draw 遍历爆炸。 */
                body.removeAllViews();
                built[0] = false;
                arrow.setRotation(0f);
            } else {
                if (!built[0]) {
                    /* 组内命令卡横屏双列（Flow 分列，cmdCard 无展开态）。 */
                    NativeBlocks.Flow flow = new NativeBlocks.Flow(body, lastLand);
                    for (JSONObject o : list) flow.add(cmdCard(o));
                    flow.commit();
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

    private View cmdCard(JSONObject o) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);

        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView fn = NativeBlocks.tv(getContext(), pal, o.optString("fn"), 14.5f, true, pal.textMain);
        head.addView(fn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String c = o.optString("cat");
        if (!c.isEmpty()) {
            TextView badge = NativeBlocks.tv(getContext(), pal, c, 10.5f, true, pal.accent);
            badge.setBackground(NativeBlocks.shape(pal, pal.chipBgOn, pal.dp(8)));
            badge.setPadding(pal.dp(7), pal.dp(3), pal.dp(7), pal.dp(3));
            head.addView(badge);
        }
        card.addView(head);

        for (int i = 0; i < colKeys.length; i++) {
            String v = o.optString(colKeys[i], "");
            boolean na = v.isEmpty() || "-".equals(v);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setPadding(0, pal.dp(7), 0, 0);

            TextView label = NativeBlocks.tv(getContext(), pal, colNames[i], 11, false, pal.textMute);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(pal.dp(92),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(label, llp);

            TextView val = NativeBlocks.tv(getContext(), pal, na ? "不适用" : v, 12.5f, false,
                    na ? pal.textMute : pal.textMain);
            val.setTypeface(na ? null : android.graphics.Typeface.MONOSPACE);
            val.setPadding(0, 0, 0, 0);
            row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!na) {
                val.setOnClickListener(v1 -> copyToClipboard(v));
            }
            card.addView(row);
        }

        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("command", text));
            android.widget.Toast.makeText(getContext(), "已复制：" + text, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }
}
