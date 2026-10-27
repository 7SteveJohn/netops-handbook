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
    private List<String> cats = new ArrayList<>();
    private String cat = "全部";

    private boolean dark = false, direct = false;
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
        cats.clear();
        cats.add("全部");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            String c = o == null ? "" : o.optString("cat");
            if (!c.isEmpty() && !cats.contains(c)) cats.add(c);
        }
        android.util.Log.i("NativeDict", "setPageData rows=" + rows.length() + " cats=" + cats.size());
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

        page.addView(chipsRow());

        int shown = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            String c = o.optString("cat");
            if (!"全部".equals(cat) && !cat.equals(c)) continue;
            page.addView(cmdCard(o));
            shown++;
        }
        if (shown == 0) {
            TextView empty = NativeBlocks.tv(getContext(), pal, "没有匹配的命令", 13, false, pal.textMute);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, pal.dp(30), 0, pal.dp(30));
            page.addView(empty);
        }
    }

    private View chipsRow() {
        HorizontalScrollView hs = new HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, 0, 0, pal.dp(10));

        for (final String c : cats) {
            boolean on = c.equals(cat);
            TextView chip = NativeBlocks.tv(getContext(), pal, c, 12.5f, on, on ? Color.WHITE : pal.textSub);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(pal.dp(12), pal.dp(6), pal.dp(12), pal.dp(6));
            chip.setBackground(NativeBlocks.shape(pal, on ? pal.accent : pal.chipBgOff, pal.dp(14)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = pal.dp(8);
            row.addView(chip, lp);
            chip.setOnClickListener(v -> {
                cat = c;
                buildPage();
            });
        }
        hs.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hs;
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
