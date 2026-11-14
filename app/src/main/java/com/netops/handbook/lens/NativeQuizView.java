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
public class NativeQuizView extends LinearLayout implements ThemeBus.DarkAware {

    private JSONArray quiz = new JSONArray();
    private boolean dark = false, direct = false;
    private boolean lastLand = getResources().getConfiguration().orientation
            == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
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

    public void setPageData(JSONObject root) {
        dirty = true;
        JSONArray a = root.optJSONArray("quiz");
        if (a != null) quiz = a;
        android.util.Log.i("NativeQuiz", "setPageData n=" + quiz.length());
    }

    /** 已构建过就复用：buildPage 一次要几十到几百 ms，每次 show 重建会把 UI 线程按住，
     *  切页时侧栏弹簧直接顿住（2026-10-28）。数据/主题变化才置脏。 */
    private boolean dirty = true;

    public boolean show() {
        if (quiz.length() == 0) return false;
        if (getVisibility() == VISIBLE && !dirty) return true;
        if (dirty || page.getChildCount() == 0) { buildPage(); dirty = false; }
        scroller.scrollTo(0, 0);
        animate().cancel();          /* 取消未完成的 hide 动画，否则其 endAction 会把刚显示的页面又置 GONE */
        setVisibility(VISIBLE);
        setAlpha(1f);   /* 不做整屏淡入：带玻璃卡的全屏视图 alpha 动画要新建离屏层，
                           切页时实测 95th 53ms / 99th 200ms（2026-10-28）*/
        return true;
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        setVisibility(GONE);
    }

    public boolean isShowing() { return getVisibility() == VISIBLE; }

    public void setSafeArea(int topPx, int bottomPx) {
        scroller.setPadding(0, topPx + pal.dp(56) + pal.dp(28), 0, bottomPx + pal.dp(84));
    }

    /* ThemeBus 订阅入口：只换通知来源，setDark 逻辑原样。 */
    @Override public void onThemeChanged(boolean dark) { setDark(dark); }

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
        dirty = true;
        if (isShowing()) buildPage();
    }

    /** 旋转后强制重建（单列⇄双列），land 由 MainActivity 显式下发。 */
    public void onOrientationChanged(boolean land) {
        if (lastLand == land) return;
        lastLand = land;
        dirty = true;
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
        /* M1d-2(2026-11-14)：错题重刷入口（HTML act=wrong 同语义）——判分写
           netops_native/quizWrong（qKey 指纹→{m,n}），此处按指纹过滤重建。 */
        JSONObject wrongMap = wrongMap();
        int wrongN = wrongMap.length();
        LinearLayout chips = new LinearLayout(getContext());
        chips.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams clp0 = NativeBlocks.matchP();
        clp0.topMargin = pal.dp(10);
        head.addView(chips, clp0);
        TextView chipAll = NativeBlocks.tv(getContext(), pal, "全部", 11.5f, true,
                wrongOnly ? pal.textMute : pal.accent);
        chipAll.setBackground(NativeBlocks.shape(pal, wrongOnly ? pal.chipBgOff : pal.chipBgOn, pal.dp(9)));
        chipAll.setPadding(pal.dp(10), pal.dp(5), pal.dp(10), pal.dp(5));
        chipAll.setOnClickListener(v -> { if (!wrongOnly) return; wrongOnly = false; dirty = true; buildPage(); });
        chips.addView(chipAll);
        TextView chipWrong = NativeBlocks.tv(getContext(), pal,
                "只刷错题" + (wrongN > 0 ? " · " + wrongN : ""), 11.5f, true,
                wrongOnly ? pal.accent : pal.textMute);
        chipWrong.setBackground(NativeBlocks.shape(pal, wrongOnly ? pal.chipBgOn : pal.chipBgOff, pal.dp(9)));
        chipWrong.setPadding(pal.dp(10), pal.dp(5), pal.dp(10), pal.dp(5));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = pal.dp(8);
        chipWrong.setLayoutParams(wlp);
        chipWrong.setOnClickListener(v -> {
            if (wrongN == 0) {
                android.widget.Toast.makeText(getContext(), "错题本是空的，先做一组题",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            wrongOnly = true; dirty = true; buildPage();
        });
        chips.addView(chipWrong);
        LinearLayout.LayoutParams hlp = NativeBlocks.matchP();
        hlp.bottomMargin = pal.dp(10);
        head.setLayoutParams(hlp);
        page.addView(NativeBlocks.glassWrap(getContext(), pal, head, glassSource, direct,
                page.getWidth() > 0 ? page.getWidth()
                        : getResources().getDisplayMetrics().widthPixels));

        int no = 0;
        for (int i = 0; i < quiz.length(); i++) {
            JSONObject q = quiz.optJSONObject(i);
            if (q == null) continue;
            if (wrongOnly && !wrongMap.has(qKey(q.optString("q")))) continue;
            no++;
            page.addView(quizCard(q, no));
        }
    }

    /* ---- M1d-2：错题本持久化（qKey=FNV-1a 32bit→base36，32-boot.js:1882 同款） ---- */
    private boolean wrongOnly = false;

    private JSONObject wrongMap() {
        try {
            return new JSONObject(getContext().getSharedPreferences("netops_native", MODE_PRIVATE)
                    .getString("quizWrong", "{}"));
        } catch (Throwable e) { return new JSONObject(); }
    }

    private static String qKey(String text) {
        String s = text == null ? "" : text;
        int h = 2166136261;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 16777619;
        }
        return Integer.toUnsignedLong(h).toString(36);
    }

    /** 判分落账（对=清错、错=计数+1）。 */
    private void recordJudge(JSONObject q, boolean right) {
        try {
            android.content.SharedPreferences sp = getContext()
                    .getSharedPreferences("netops_native", MODE_PRIVATE);
            JSONObject w = wrongMap();
            String k = qKey(q.optString("q"));
            if (right) { w.remove(k); }
            else {
                JSONObject e = w.optJSONObject(k);
                if (e == null) e = new JSONObject();
                e.put("m", q.optString("m"));
                e.put("n", e.optInt("n", 0) + 1);
                w.put(k, e);
            }
            sp.edit().putString("quizWrong", w.toString()).apply();
        } catch (Throwable ignored) { }
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
                recordJudge(q, right);   // M1d-2：错题本落账（对=清错）
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
