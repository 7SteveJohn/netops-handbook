package com.netops.handbook.lens;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 原生题库页（2026-10-27 · N4）。
 *
 * 数据：native-data.json 的 quiz[{p,m,q,o[4],a,e}]。
 * 交互：点选项即判分（对=绿、错=红并标出正确项），随后展开解析。
 * 一页铺完，不分组不翻页——72 题的定位靠滚动，比翻页少一层状态。
 */
public class NativeQuizView extends LinearLayout {

    private JSONArray quiz = new JSONArray();
    private boolean dark = false, direct = false;
    private final ScrollView scroller;
    private final LinearLayout page;
    private NativeBlocks.Palette pal;
    /** 玻璃件采样源（原生壁纸层）。 */
    private android.view.ViewGroup glassSource;

    public NativeQuizView(Context context) {
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
        JSONArray a = root.optJSONArray("quiz");
        if (a != null) quiz = a;
        android.util.Log.i("NativeQuiz", "setPageData n=" + quiz.length());
    }

    public boolean show() {
        if (quiz.length() == 0) return false;
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
        head.addView(NativeBlocks.tv(getContext(), pal, "自测题", 19, true, pal.textMain));
        TextView sub = NativeBlocks.tv(getContext(), pal,
                "共 " + quiz.length() + " 题 · 点选项即判分，不做记忆、只做查漏", 12.5f, false, pal.textMute);
        sub.setPadding(0, pal.dp(6), 0, 0);
        head.addView(sub);
        LinearLayout.LayoutParams hlp = NativeBlocks.matchP();
        hlp.bottomMargin = pal.dp(10);
        head.setLayoutParams(hlp);
        page.addView(NativeBlocks.glassWrap(getContext(), pal, head, glassSource, direct,
                page.getWidth() > 0 ? page.getWidth()
                        : getResources().getDisplayMetrics().widthPixels));

        for (int i = 0; i < quiz.length(); i++) {
            JSONObject q = quiz.optJSONObject(i);
            if (q != null) page.addView(quizCard(q, i + 1));
        }
    }

    private View quizCard(JSONObject q, int no) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);

        TextView title = NativeBlocks.tv(getContext(), pal, no + ". " + q.optString("q"), 14.5f, true, pal.textMain);
        title.setLineSpacing(pal.dp(4), 1f);
        card.addView(title);

        JSONArray opts = q.optJSONArray("o");
        int answer = q.optInt("a", -1);
        if (opts == null) return card;

        TextView explain = NativeBlocks.tv(getContext(), pal, q.optString("e"), 12.5f, false, pal.textSub);
        explain.setLineSpacing(pal.dp(4), 1f);
        explain.setVisibility(GONE);
        explain.setPadding(pal.dp(10), pal.dp(8), pal.dp(10), pal.dp(8));
        explain.setBackground(NativeBlocks.shape(pal, pal.noteOk, pal.dp(10)));

        for (int i = 0; i < opts.length(); i++) {
            final int idx = i;
            String label = String.valueOf((char) ('A' + i));
            TextView opt = NativeBlocks.tv(getContext(), pal, label + ".  " + opts.optString(i),
                    13.5f, false, pal.textMain);
            opt.setPadding(pal.dp(12), pal.dp(10), pal.dp(12), pal.dp(10));
            opt.setBackground(NativeBlocks.shape(pal, pal.chipBgOff, pal.dp(10)));
            LinearLayout.LayoutParams lp = NativeBlocks.matchP();
            lp.topMargin = pal.dp(8);
            card.addView(opt, lp);
            opt.setOnClickListener(v -> {
                boolean right = idx == answer;
                opt.setBackground(NativeBlocks.shape(pal, right ? pal.noteOk : pal.noteDanger, pal.dp(10)));
                opt.setTextColor(right ? (pal.dark ? Color.WHITE : Color.rgb(21, 128, 61)) : Color.rgb(185, 28, 28));
                if (!right && answer >= 0) {
                    View correct = card.getChildAt(1 + answer);   /* 0=标题，1 起是选项 */
                    if (correct instanceof TextView) {
                        correct.setBackground(NativeBlocks.shape(pal, pal.noteOk, pal.dp(10)));
                        ((TextView) correct).setTextColor(pal.dark ? Color.WHITE : Color.rgb(21, 128, 61));
                    }
                }
                explain.setVisibility(VISIBLE);
            });
        }

        LinearLayout.LayoutParams elp = NativeBlocks.matchP();
        elp.topMargin = pal.dp(10);
        card.addView(explain, elp);

        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(10);
        card.setLayoutParams(lp);
        return card;
    }
}
