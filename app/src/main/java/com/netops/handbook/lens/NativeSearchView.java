package com.netops.handbook.lens;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生搜索页（2026-10-27 · N5）。
 *
 * 与 Web 版 searchAll 同语义：查询按空格分词，**所有词都命中**才算命中；
 * 检索范围 = 模块 / 排障 / 面试 / 字典 / 术语 / 速查表标题+正文。
 *
 * 索引不落盘：打开时把各页 blocks 的文本拍平进内存（68+35+53+58+48+12 ≈ 274 条，
 * 一次性拍平约几十毫秒，远小于每次输入重扫的代价）。
 * 命中高亮用 Spannable（比 Web 的 U.hl 更直接）。
 */
public class NativeSearchView extends LinearLayout {

    /** 结果点击：type = mod/fault/iv/dict/gloss/ref。 */
    public interface Nav {
        void open(String type, String id, String pid);
    }

    private static class Item {
        String type, id, title, meta, pid, text;
    }

    private final List<Item> items = new ArrayList<>();
    private final EditText input;
    private final ScrollView scroller;
    private final LinearLayout results;
    private final LinearLayout header;
    private NativeBlocks.Palette pal;
    private boolean dark = false, direct = false;
    private Nav nav;

    public NativeSearchView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        pal = NativeBlocks.palette(context, false, false);
        setBackgroundColor(pal.bg);

        header = new LinearLayout(context);
        header.setOrientation(VERTICAL);
        header.setPadding(pal.dp(14), pal.dp(8), pal.dp(14), pal.dp(8));
        input = new EditText(context);
        input.setHint("搜索模块 / 排障 / 面试 / 命令 / 术语");
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setPadding(pal.dp(14), pal.dp(10), pal.dp(14), pal.dp(10));
        input.setBackground(NativeBlocks.shape(pal, pal.chipBgOff, pal.dp(12)));
        input.setTextColor(pal.textMain);
        input.setHintTextColor(pal.textMute);
        header.addView(input);
        addView(header, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        scroller = new ScrollView(context);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        results = new LinearLayout(context);
        results.setOrientation(VERTICAL);
        scroller.addView(results, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroller, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { run(e.toString()); }
        });

        setVisibility(GONE);
    }

    public void setNav(Nav n) { nav = n; }

    public void setPageData(JSONObject root) {
        items.clear();
        try {
            JSONArray phases = root.optJSONArray("phases");
            if (phases != null) for (int i = 0; i < phases.length(); i++) {
                JSONObject p = phases.getJSONObject(i);
                JSONArray mods = p.optJSONArray("mods");
                if (mods == null) continue;
                for (int k = 0; k < mods.length(); k++) {
                    JSONObject m = mods.getJSONObject(k);
                    Item it = new Item();
                    it.type = "mod"; it.id = m.optString("id"); it.pid = p.optString("id");
                    it.title = m.optString("t");
                    it.meta = p.optString("short") + " " + m.optInt("n") + "/" + m.optInt("tc");
                    it.text = flat(m.optJSONArray("blocks"));
                    items.add(it);
                }
            }
            addDoc(root, "faults", "fault", "排障", "");
            addDoc(root, "interview", "iv", "面试", "");
            addDict(root);
            addSimple(root, "glossary", "gloss", "术语");
            addSimple(root, "refs", "ref", "速查");
        } catch (Throwable t) {
            android.util.Log.w("NativeSearch", "索引构建失败", t);
        }
        android.util.Log.i("NativeSearch", "索引 " + items.size() + " 条");
    }

    private void addDoc(JSONObject root, String key, String type, String label, String pid) {
        JSONArray arr = root.optJSONArray(key);
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.type = type;
            it.id = o.optString("id");
            it.pid = pid;
            it.title = o.optString("t");
            it.meta = label + " · " + o.optString("cat");
            it.text = flat(o.optJSONArray("blocks"));
            items.add(it);
        }
    }

    private void addDict(JSONObject root) {
        JSONObject d = root.optJSONObject("dict");
        JSONArray rows = d == null ? null : d.optJSONArray("rows");
        if (rows == null) return;
        JSONArray cols = d.optJSONArray("cols");
        List<String> keys = new ArrayList<>();
        if (cols != null) for (int i = 0; i < cols.length(); i++) {
            keys.add(cols.optJSONObject(i).optString("k"));
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            Item it = new Item();
            it.type = "dict";
            it.id = "dict" + i;
            it.title = r.optString("fn");
            it.meta = "命令 · " + r.optString("cat");
            StringBuilder sb = new StringBuilder();
            for (String k : keys) sb.append(r.optString(k)).append(' ');
            it.text = sb.toString();
            items.add(it);
        }
    }

    private void addSimple(JSONObject root, String key, String type, String label) {
        JSONArray arr = root.optJSONArray(key);
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.type = type;
            it.id = o.optString("id", label + i);
            it.title = o.optString("t");
            it.meta = label;
            it.text = o.optString("d");
            items.add(it);
        }
    }

    /** blocks → 纯文本（用于检索）。 */
    private String flat(JSONArray blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) continue;
            sb.append(b.optString("t")).append(' ');
            JSONArray arr = b.optJSONArray("items");
            if (arr != null) for (int k = 0; k < arr.length(); k++) sb.append(arr.optString(k)).append(' ');
            JSONObject dg = b.optJSONObject("dg");
            if (dg != null) sb.append(flatTopo(dg));
        }
        return sb.toString();
    }

    private String flatTopo(JSONObject dg) {
        StringBuilder sb = new StringBuilder();
        JSONArray n = dg.optJSONArray("n");
        if (n != null) for (int i = 0; i < n.length(); i++) {
            JSONObject o = n.optJSONObject(i);
            if (o != null) sb.append(o.optString("t")).append(' ').append(o.optString("s")).append(' ');
        }
        return sb.toString();
    }

    /* ---------------- 显示 ---------------- */
    public boolean show() {
        buildHeaderChrome();
        results.removeAllViews();
        animate().cancel();          /* 取消未完成的 hide 动画，否则其 endAction 会把刚显示的页面又置 GONE */
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(140).start();
        input.postDelayed(() -> {
            input.requestFocus();
            try {
                InputMethodManager im = (InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            } catch (Throwable ignored) { }
        }, 120);
        return true;
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        try {
            InputMethodManager im = (InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Throwable ignored) { }
        animate().alpha(0f).setDuration(120).withEndAction(() -> setVisibility(GONE)).start();
    }

    public boolean isShowing() { return getVisibility() == VISIBLE; }

    public void setSafeArea(int topPx, int bottomPx) {
        /* 底部已由调用方把「键盘高度」并进 bottomPx（insets 里取的是 max(bars, ime)），
           这里直接让位即可，内容不会被输入法压住。 */
        header.setPadding(pal.dp(14), topPx + pal.dp(56) + pal.dp(28), pal.dp(14), pal.dp(8));
        scroller.setPadding(0, 0, 0, bottomPx + pal.dp(16));
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
        input.setBackground(NativeBlocks.shape(pal, pal.chipBgOff, pal.dp(12)));
        input.setTextColor(pal.textMain);
        input.setHintTextColor(pal.textMute);
        if (isShowing()) run(input.getText().toString());
    }

    private void buildHeaderChrome() {
        header.setBackgroundColor(Color.TRANSPARENT);
    }

    /* ---------------- 检索 ---------------- */
    private void run(String q) {
        results.removeAllViews();
        String query = q == null ? "" : q.trim().toLowerCase();
        if (query.isEmpty()) {
            TextView hint = NativeBlocks.tv(getContext(), pal,
                    "输入关键词：模块名、命令、现象、术语都行\n多个词用空格分隔，会取交集", 12.5f, false, pal.textMute);
            hint.setPadding(0, pal.dp(20), 0, 0);
            results.addView(hint);
            return;
        }
        String[] toks = query.split("\\s+");
        int hit = 0;
        for (Item it : items) {
            String hay = (it.title + " " + it.text + " " + it.meta).toLowerCase();
            boolean ok = true;
            for (String t : toks) if (hay.indexOf(t) < 0) { ok = false; break; }
            if (!ok) continue;
            results.addView(resultCard(it, toks));
            if (++hit >= 60) break;         /* 结果上限：再多也没人翻 */
        }
        if (hit == 0) {
            TextView empty = NativeBlocks.tv(getContext(), pal, "没有匹配的内容", 13, false, pal.textMute);
            empty.setPadding(0, pal.dp(24), 0, 0);
            results.addView(empty);
        }
    }

    private View resultCard(Item it, String[] toks) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        card.addView(NativeBlocks.tv(getContext(), pal, it.meta, 10.5f, true, pal.accent));
        TextView title = NativeBlocks.tv(getContext(), pal, "", 14.5f, true, pal.textMain);
        title.setText(highlight(it.title, toks));
        title.setPadding(0, pal.dp(4), 0, 0);
        card.addView(title);

        String preview = previewOf(it.text, toks);
        if (preview != null) {
            TextView pv = NativeBlocks.tv(getContext(), pal, "", 12, false, pal.textSub);
            pv.setText(highlight(preview, toks));
            pv.setPadding(0, pal.dp(5), 0, 0);
            card.addView(pv);
        }
        card.setOnClickListener(v -> {
            if (nav != null) nav.open(it.type, it.id, it.pid);
        });
        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.topMargin = pal.dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    /** 摘要：命中词前后各取一段，最多 70 字。 */
    private String previewOf(String text, String[] toks) {
        if (text == null || text.isEmpty()) return null;
        String lower = text.toLowerCase();
        int at = -1;
        for (String t : toks) {
            int i = lower.indexOf(t);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        if (at < 0) return null;
        int start = Math.max(0, at - 24);
        int end = Math.min(text.length(), start + 70);
        String cut = text.substring(start, end).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + cut + (end < text.length() ? "…" : "");
    }

    /** 命中词高亮（粗体 + 淡黄底）。 */
    private CharSequence highlight(String text, String[] toks) {
        SpannableString ss = new SpannableString(text);
        String lower = text.toLowerCase();
        for (String t : toks) {
            if (t.isEmpty()) continue;
            int from = 0;
            while (true) {
                int i = lower.indexOf(t, from);
                if (i < 0) break;
                int end = Math.min(ss.length(), i + t.length());
                ss.setSpan(new StyleSpan(Typeface.BOLD), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new BackgroundColorSpan(pal.dark
                                ? Color.argb(70, 250, 204, 21)
                                : Color.argb(90, 253, 224, 71)),
                        i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = end;
            }
        }
        return ss;
    }
}
