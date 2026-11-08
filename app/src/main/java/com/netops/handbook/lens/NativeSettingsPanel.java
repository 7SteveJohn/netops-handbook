package com.netops.handbook.lens;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 原生设置面板（2026-10-27 · 我的页原生化前置件）。
 *
 * 主题 / 壁纸 / 全局质感 三块原先是「我的」页 innerHTML 注入的面板 DOM，
 * 「我的」一旦原生化它们就没了，所以这里做原生版。应用动作全部回给页面层
 * （Set 接口 → window.NetOpsNativeSet），**设置的真源仍是页面层**，原生不自持。
 *
 * 交互值域对齐 Web 版：
 *   theme = auto | light | dark
 *   wallpaper = 'none' 或 WALLPAPERS[].id
 *   glass = { on, mode: liquid|frosted|gaussian, blur: 8-20, tint: 10-95 }
 */
public class NativeSettingsPanel extends FrameLayout {

    /** 开合回调：面板是原生 View，侧栏/透镜是独立弹窗永远置顶 —— 面板一开就得让侧栏退位，
     *  否则侧栏压在面板左半，壁纸选项被挡（2026-10-28 用户报）。 */
    public interface OnOpen { void onOpen(boolean open); }

    public interface Set {
        void apply(String kind, String value);
    }

    private final LinearLayout sheet;
    private NativeBlocks.Palette pal;
    private final Set setter;
    private ScrollView sc;
    private int safeTop, safeBottom;

    /* 当前状态（JS 推进来） */
    private String theme = "auto";
    private String wallpaper = "none";
    private JSONObject glass;
    private boolean nativeDock = true, nativeAppbar = true;
    private JSONArray wallpapers = new JSONArray();

    /* 质感控件（状态推送后重建） */
    public NativeSettingsPanel(Context c, NativeBlocks.Palette p, Set setter) {
        super(c);
        pal = p;
        this.setter = setter;
        setBackgroundColor(Color.argb(120, 0, 0, 0));       /* 半透明压暗背景 */
        setVisibility(GONE);
        setOnClickListener(v -> hide());                     /* 点空白收起 */

        ScrollView sc = new ScrollView(c);
        this.sc = sc;
        sc.setFillViewport(false);
        sc.setVerticalScrollBarEnabled(false);
        sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(pal.cardBg);
        /* 四角全圆：setSafeArea 后 sheet 底边悬在 dock 上方，方角会显得断头 */
        bg.setCornerRadii(new float[]{pal.dp(22), pal.dp(22), pal.dp(22), pal.dp(22),
                pal.dp(22), pal.dp(22), pal.dp(22), pal.dp(22)});
        sheet.setBackground(bg);
        int pad = p.dp(16);
        sheet.setPadding(pad, pad, pad, pad + p.dp(18));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        slp.setMargins(0, 0, 0, 0);
        sc.addView(sheet, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(sc, slp);
    }

    /** 顶部让开 appbar 窗、底部让开 dock（与其他原生页同口径：top+84 / bottom+84）。
     *  sheet 锚 BOTTOM 且此前零让位 → 「模糊强度」滑杆和「完成」按钮正好压在
     *  dock 窗底下（dock 是 PopupWindow 恒在页面层之上），滚到底也调不了。 */
    public void setSafeArea(int topPx, int bottomPx) {
        safeTop = topPx;
        safeBottom = bottomPx;
        applySafeArea();
    }

    private void applySafeArea() {
        if (sc == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sc.getLayoutParams();
        int top = safeTop + pal.dp(84);
        int bottom = safeBottom + pal.dp(84);
        if (lp.topMargin == top && lp.bottomMargin == bottom) return;
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        sc.setLayoutParams(lp);
    }

    public void setDark(boolean d) {
        pal = NativeBlocks.palette(getContext(), d, false);
        if (getVisibility() == VISIBLE) build();
    }

    public void setWallpapers(JSONArray arr) {
        if (arr != null) wallpapers = arr;
    }

    /** JS 推当前状态 → 重建 UI。 */
    public void updateState(String theme, String wallpaper, JSONObject glass,
                            boolean nativeDock, boolean nativeAppbar) {
        this.theme = theme;
        this.wallpaper = wallpaper;
        this.glass = glass;
        this.nativeDock = nativeDock;
        this.nativeAppbar = nativeAppbar;
        build();
    }

    private OnOpen onOpen;

    public void setOnOpen(OnOpen l) { onOpen = l; }

    public void show() {
        android.util.Log.i("NativeSettings", "show");
        if (onOpen != null) onOpen.onOpen(true);
        try {
            build();
        } catch (Throwable t) {
            android.util.Log.e("NativeSettings", "build 失败", t);
        }
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(160).start();
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        animate().alpha(0f).setDuration(140).withEndAction(() -> {
            setVisibility(GONE);
            if (onOpen != null) onOpen.onOpen(false);
        }).start();
    }

    /* ---------------- 构建 ---------------- */
    public void build() {
        sheet.removeAllViews();
        sheet.addView(title("设置"));

        sheet.addView(section("外观主题"));
        sheet.addView(chipRow(new String[][]{
                {"auto", "自动"}, {"light", "浅色"}, {"dark", "深色"}
        }, theme, v -> {
            theme = v;
            setter.apply("theme", v);
        }));

        sheet.addView(section("背景壁纸（自适应横竖屏）"));
        sheet.addView(chipRow(new String[][]{{"none", "无壁纸"}}, wallpaper, v -> {
            wallpaper = v;
            setter.apply("wallpaper", v);
        }));

        java.util.Map<String, java.util.List<JSONObject>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < wallpapers.length(); i++) {
            JSONObject wp = wallpapers.optJSONObject(i);
            if (wp == null) continue;
            if ("landscape".equals(wp.optString("orientation"))) continue;
            String cat = wp.optString("cat", "其他");
            if (cat.contains("二次元")) cat = "二次元壁纸（自适应横竖屏）";
            else if (cat.contains("简约")) cat = "简约质感（自适应横竖屏）";
            if (!groups.containsKey(cat)) groups.put(cat, new java.util.ArrayList<>());
            groups.get(cat).add(wp);
        }

        boolean isLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        for (java.util.Map.Entry<String, java.util.List<JSONObject>> entry : groups.entrySet()) {
            TextView catTv = NativeBlocks.tv(getContext(), pal, entry.getKey(), 11, true, pal.textMute);
            catTv.setPadding(0, pal.dp(10), 0, pal.dp(4));
            sheet.addView(catTv);

            java.util.List<JSONObject> list = entry.getValue();
            int total = list.size();
            LinearLayout row = null;
            for (int i = 0; i < total; i++) {
                if (i % 3 == 0) {
                    row = new LinearLayout(getContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    sheet.addView(row, NativeBlocks.matchP());
                }
                JSONObject wp = list.get(i);
                final String idP = wp.optString("id");
                final String idL = wp.optString("pair");
                boolean active = wallpaper.equals(idP) || (!idL.isEmpty() && wallpaper.equals(idL));
                TextView chip = chip(wp.optString("name"), active);
                chip.setGravity(Gravity.CENTER);
                chip.setOnClickListener(v -> {
                    String targetId = (isLandscape && !idL.isEmpty()) ? idL : idP;
                    wallpaper = targetId;
                    setter.apply("wallpaper", targetId);
                    build();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                if (i % 3 < 2) lp.rightMargin = pal.dp(8);
                lp.bottomMargin = pal.dp(6);
                row.addView(chip, lp);
            }
            int remainder = total % 3;
            if (remainder != 0 && row != null) {
                int dummyNeeded = 3 - remainder;
                for (int d = 0; d < dummyNeeded; d++) {
                    View stub = new View(getContext());
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                    if (d < dummyNeeded - 1) lp.rightMargin = pal.dp(8);
                    row.addView(stub, lp);
                }
            }
        }

        /* ❗「原生界面」区下线（2026-11-08 用户：没必要留，保证恒为液态玻璃）：
           原生 dock/顶栏恒开（JS nativeDockOn/nativeAppbarOn 已改恒 true），
           回退网页渲染的路径仅保留代码（设备异常时手动改源），UI 不再暴露。 */
        TextView close = NativeBlocks.tv(getContext(), pal, "完成", 14, true, pal.accent);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, pal.dp(14), 0, 0);
        close.setOnClickListener(v -> hide());
        sheet.addView(close);
    }

    private View title(String t) {
        TextView tv = NativeBlocks.tv(getContext(), pal, t, 18, true, pal.textMain);
        tv.setPadding(0, 0, 0, pal.dp(6));
        return tv;
    }

    private View section(String t) {
        TextView tv = NativeBlocks.tv(getContext(), pal, t, 12.5f, true, pal.textMute);
        tv.setPadding(0, pal.dp(12), 0, pal.dp(6));
        return tv;
    }

    private TextView chip(String text, boolean active) {
        TextView chip = NativeBlocks.tv(getContext(), pal, text, 12.5f, active,
                active ? Color.WHITE : pal.textSub);
        chip.setPadding(pal.dp(12), pal.dp(7), pal.dp(12), pal.dp(7));
        chip.setBackground(NativeBlocks.shape(pal, active ? pal.accent : pal.chipBgOff, pal.dp(13)));
        return chip;
    }

    private View chipRow(String[][] options, String current, StringConsumer onPick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final String[] opt : options) {
            boolean active = opt[0].equals(current);
            TextView chip = chip(opt[1], active);
            final String key = opt[0];
            chip.setOnClickListener(v -> {
                if (!active) {
                    onPick.accept(key);
                    build();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = pal.dp(8);
            row.addView(chip, lp);
        }
        return row;
    }

    private View toggleRow(String title, String sub, boolean on, BoolConsumer onToggle) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(NativeBlocks.tv(getContext(), pal, title, 14, true, pal.textMain));
        TextView s = NativeBlocks.tv(getContext(), pal, sub, 11, false, pal.textMute);
        s.setPadding(0, pal.dp(2), 0, 0);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView sw = NativeBlocks.tv(getContext(), pal, on ? "开" : "关", 12.5f, true,
                on ? Color.WHITE : pal.textSub);
        sw.setGravity(Gravity.CENTER);
        sw.setPadding(pal.dp(14), pal.dp(6), pal.dp(14), pal.dp(6));
        sw.setBackground(NativeBlocks.shape(pal, on ? pal.accent : pal.chipBgOff, pal.dp(13)));
        row.addView(sw);
        sw.setOnClickListener(v -> onToggle.accept(!on));
        return row;
    }

    private View sliderRow(String title, int value, int min, int max, String unit, java.util.function.IntConsumer onChange) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(NativeBlocks.tv(getContext(), pal, title, 14, true, pal.textMain),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = NativeBlocks.tv(getContext(), pal, value + unit, 12.5f, false, pal.accent);
        head.addView(val);
        col.addView(head);

        SeekBar bar = new SeekBar(getContext());
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.getThumb().setTint(pal.accent);
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(pal.hairline));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(pal.accent));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + min;
                val.setText(v + unit);
                onChange.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams blp = NativeBlocks.matchP();
        blp.topMargin = pal.dp(6);
        col.addView(bar, blp);
        return col;
    }

    private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    interface StringConsumer { void accept(String v); }
    interface BoolConsumer { void accept(boolean v); }
}
