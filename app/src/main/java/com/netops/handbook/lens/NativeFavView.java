package com.netops.handbook.lens;

import android.content.Context;
import android.content.SharedPreferences;
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
 * 原生收藏夹页（2026-10-27）。
 *
 * Web 版按 kind 分组渲染收藏项卡片；原生版给「可跳转的行」：
 * 模块 → phase-focus，排障/面试 → 对应原生页。跳转走统一导航（nativeGo），
 * 不在原生侧维护栈。
 */
public class NativeFavView extends LinearLayout {

    public interface Nav {
        void go(String route, String arg);
    }

    private JSONObject root;
    private boolean dark = false, direct = false;
    private final ScrollView scroller;
    private final LinearLayout page;
    private NativeBlocks.Palette pal;
    private Nav nav;

    private final SharedPreferences sp;

    public NativeFavView(Context context) {
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

    public boolean show() {
        if (root == null) return false;
        buildPage();
        scroller.scrollTo(0, 0);
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
        dark = d; pal = NativeBlocks.palette(getContext(), dark, direct);
        setBackgroundColor(pal.bg);
        if (isShowing()) buildPage();
    }

    /** 旋转后强制重建（单列⇄双列），land 由 MainActivity 显式下发。 */
    public void onOrientationChanged(boolean land) {
        if (lastLand == land) return;
        lastLand = land;
        if (isShowing()) buildPage();
    }

    public void setDirectMode(boolean on) {
        if (direct == on) return;
        direct = on; pal = NativeBlocks.palette(getContext(), dark, direct);
        setBackgroundColor(pal.bg);
        if (isShowing()) buildPage();
    }

private boolean lastLand = getResources().getConfiguration().orientation
            == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

    private void buildPage() {
        page.removeAllViews();
        page.setPadding(pal.dp(14), pal.dp(10), pal.dp(14), pal.dp(24));

        Set<String> fav = new HashSet<>();
        for (String s : sp.getString("fav", "").split(",")) if (!s.isEmpty()) fav.add(s);

        LinearLayout head = NativeBlocks.card(getContext(), pal);
        head.addView(NativeBlocks.tv(getContext(), pal, "收藏夹", 19, true, pal.textMain));
        TextView sub = NativeBlocks.tv(getContext(), pal,
                fav.isEmpty() ? "还没有收藏任何内容" : fav.size() + " 条已收藏 · 点行跳转",
                12.5f, false, pal.textMute);
        sub.setPadding(0, pal.dp(6), 0, 0);
        head.addView(sub);
        LinearLayout.LayoutParams hlp = NativeBlocks.matchP();
        hlp.bottomMargin = pal.dp(10);
        head.setLayoutParams(hlp);
        page.addView(head);

        if (fav.isEmpty()) return;

        /* 三组：模块 / 排障 / 面试（与 Web 分组一致） */
        page.addView(groupTitle("知识模块"));
        JSONArray phases = root.optJSONArray("phases");
        if (phases != null) {
            NativeBlocks.Flow flow = new NativeBlocks.Flow(page, lastLand);
            for (int i = 0; i < phases.length(); i++) {
                JSONObject p = phases.optJSONObject(i);
                JSONArray mods = p.optJSONArray("mods");
                if (mods == null) continue;
                for (int k = 0; k < mods.length(); k++) {
                    JSONObject m = mods.optJSONObject(k);
                    if (m != null && fav.contains(m.optString("id"))) {
                        final String pid = p.optString("id"), mid = m.optString("id");
                        flow.add(row(m.optString("t"), p.optString("short"),
                                () -> { if (nav != null) nav.go("phase-focus", pid + "|" + mid); }));
                    }
                }
            }
            flow.commit();
        }
        page.addView(groupTitle("排障案例"));
        JSONArray faults = root.optJSONArray("faults");
        if (faults != null) {
            NativeBlocks.Flow flow = new NativeBlocks.Flow(page, lastLand);
            for (int i = 0; i < faults.length(); i++) {
                JSONObject f = faults.optJSONObject(i);
                if (f != null && fav.contains(f.optString("id"))) {
                    flow.add(row(f.optString("t"), f.optString("cat"),
                            () -> { if (nav != null) nav.go("fault", ""); }));
                }
            }
            flow.commit();
        }
        page.addView(groupTitle("面试真题"));
        JSONArray ivs = root.optJSONArray("interview");
        if (ivs != null) {
            NativeBlocks.Flow flow = new NativeBlocks.Flow(page, lastLand);
            for (int i = 0; i < ivs.length(); i++) {
                JSONObject x = ivs.optJSONObject(i);
                if (x != null && fav.contains(x.optString("id"))) {
                    flow.add(row(x.optString("t"), x.optString("cat"),
                            () -> { if (nav != null) nav.go("iv", ""); }));
                }
            }
            flow.commit();
        }
    }

    private View groupTitle(String t) {
        TextView tv = NativeBlocks.tv(getContext(), pal, t, 12.5f, true, pal.textMute);
        tv.setPadding(0, pal.dp(10), 0, pal.dp(6));
        return tv;
    }

    private View row(String title, String meta, Runnable onClick) {
        LinearLayout card = NativeBlocks.card(getContext(), pal);
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.addView(NativeBlocks.tv(getContext(), pal, title, 14, true, pal.textMain));
        TextView s = NativeBlocks.tv(getContext(), pal, meta, 11.5f, false, pal.textMute);
        s.setPadding(0, pal.dp(3), 0, 0);
        col.addView(s);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(NativeBlocks.tv(getContext(), pal, "›", 20, true, pal.textMute));
        card.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = NativeBlocks.matchP();
        lp.bottomMargin = pal.dp(8);
        card.setLayoutParams(lp);
        return card;
    }
}
