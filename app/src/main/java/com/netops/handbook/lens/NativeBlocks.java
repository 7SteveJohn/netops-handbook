package com.netops.handbook.lens;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 原生视图通用件（2026-10-27 从 NativePhaseView 抽出）。
 *
 * 为什么抽：阶段页 / 排障页 / 面试页是同一形状 —— 头部 + 卡片(头行+blocks 正文)。
 * 复制三份渲染代码是明确的重复，抽成一份后新增视图只写数据装配。
 *
 * 只做渲染，不持有状态：配色/密度由 Palette 传入，交互（点击/状态）留在各页。
 */
public class NativeBlocks {

    /** 液态玻璃三档（2026-11-09 面板「液态玻璃」行，FlClash 对齐）：
     *  ultra=全效（默认）/ standard=砍色散+阴影 / none=纯磨砂（平绘回退）。
     *  JS 真源（netops_lglass）经桥镜像；draw 位点直读，变更走各视图重建。 */
    public static String GLASS_LEVEL = "ultra";

    public static boolean glassNone() { return "none".equals(GLASS_LEVEL); }

    public static boolean glassStandard() { return "standard".equals(GLASS_LEVEL); }

    static class Palette {
        float density;
        boolean dark, direct;
        int bg, cardBg, border, textMain, textSub, textMute, accent, chipBgOn, chipBgOff, hairline;
        int noteInfo, noteWarn, noteDanger, noteOk, notePlain, codeBg, codeLabel;

        int dp(float v) { return Math.round(v * density); }
    }

    /** 对齐 01-tokens 的两套配色；direct = 直铺（有壁纸，页底透明 + 卡片半透明）。 */
    public static Palette palette(Context c, boolean dark, boolean direct) {
        Palette p = new Palette();
        p.density = c.getResources().getDisplayMetrics().density;
        p.dark = dark;
        p.direct = direct;
        if (dark) {
            p.bg = Color.rgb(12, 13, 16);
            p.cardBg = Color.rgb(28, 28, 30);
            p.border = Color.rgb(56, 56, 58);
            p.hairline = Color.rgb(48, 48, 50);
            p.textMain = Color.rgb(245, 245, 247);
            p.textSub = Color.rgb(199, 199, 204);
            p.textMute = Color.rgb(142, 142, 147);
            p.accent = Color.rgb(10, 132, 255);
            p.chipBgOn = Color.argb(46, 10, 132, 255);
            p.chipBgOff = Color.rgb(44, 44, 46);
            p.noteInfo = Color.argb(32, 10, 132, 255);
            p.noteWarn = Color.argb(38, 245, 158, 11);
            p.noteDanger = Color.argb(38, 239, 68, 68);
            p.noteOk = Color.argb(38, 34, 197, 94);
            p.notePlain = Color.rgb(44, 44, 46);
            p.codeBg = Color.rgb(24, 24, 27);
            p.codeLabel = Color.rgb(148, 163, 184);
        } else {
            p.bg = Color.rgb(242, 242, 247);
            p.cardBg = Color.WHITE;
            p.border = Color.rgb(227, 227, 232);
            p.hairline = Color.rgb(235, 235, 240);
            p.textMain = Color.rgb(29, 29, 31);
            p.textSub = Color.rgb(81, 81, 84);
            p.textMute = Color.rgb(142, 142, 147);
            p.accent = Color.rgb(37, 99, 235);
            p.chipBgOn = Color.argb(28, 37, 99, 235);
            p.chipBgOff = Color.rgb(241, 241, 245);
            p.noteInfo = Color.argb(20, 37, 99, 235);
            p.noteWarn = Color.argb(26, 245, 158, 11);
            p.noteDanger = Color.argb(26, 239, 68, 68);
            p.noteOk = Color.argb(26, 34, 197, 94);
            p.notePlain = Color.rgb(246, 246, 248);
            p.codeBg = Color.rgb(15, 23, 42);
            p.codeLabel = Color.rgb(148, 163, 184);
        }
        if (direct) {
            p.bg = Color.TRANSPARENT;
            p.cardBg = dark ? Color.argb(214, 28, 28, 30) : Color.argb(214, 255, 255, 255);
            p.border = dark ? Color.argb(40, 255, 255, 255) : Color.argb(36, 0, 0, 0);
        }
        return p;
    }

    static TextView tv(Context c, Palette p, String text, float sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable shape(Palette p, int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static LinearLayout.LayoutParams matchP() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout card(Context c, Palette p) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = shape(p, p.cardBg, p.dp(16));
        g.setStroke(p.dp(1), p.border);
        card.setBackground(g);
        card.setPadding(p.dp(14), p.dp(13), p.dp(14), p.dp(13));
        return card;
    }

    /** 卡片头行：芯片 + 标题 + 元信息 + 展开箭头（返回 [head, arrow]）。 */
    static LinearLayout head(Context c, Palette p, String chip, String title, String meta,
                             boolean metaAccent, TextView[] arrowOut) {
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView chipV = tv(c, p, chip, 11, true, p.accent);
        chipV.setBackground(shape(p, p.chipBgOn, p.dp(9)));
        chipV.setPadding(p.dp(8), p.dp(4), p.dp(8), p.dp(4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = p.dp(10);
        head.addView(chipV, clp);

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(tv(c, p, title, 14.5f, true, p.textMain));
        TextView metaV = tv(c, p, meta, 11, false, metaAccent ? p.accent : p.textMute);
        metaV.setPadding(0, p.dp(3), 0, 0);
        mid.addView(metaV);
        head.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = tv(c, p, "›", 20, true, p.textMute);
        arrow.setRotation(90f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = p.dp(8);
        head.addView(arrow, alp);
        if (arrowOut != null) arrowOut[0] = arrow;
        return head;
    }

    /** 渲染 blocks（h/note/code/ul/steps/tree/topo）。 */
    static void blocks(Context c, Palette p, LinearLayout parent, JSONArray bs) {
        if (bs == null) return;
        for (int i = 0; i < bs.length(); i++) {
            JSONObject b = bs.optJSONObject(i);
            if (b == null) continue;
            String k = b.optString("k");
            String t = b.optString("t");
            switch (k) {
                case "h": {
                    TextView h = tv(c, p, t, 12, true, p.textMute);
                    h.setPadding(0, p.dp(12), 0, p.dp(6));
                    parent.addView(h);
                    break;
                }
                case "note": {
                    TextView note = tv(c, p, t, 13.5f, false, p.textMain);
                    note.setLineSpacing(p.dp(4), 1f);
                    GradientDrawable g = shape(p, noteColor(p, b.optString("tone")), p.dp(12));
                    g.setStroke(p.dp(1), Color.argb(128, Color.red(p.border),
                            Color.green(p.border), Color.blue(p.border)));
                    note.setBackground(g);
                    note.setPadding(p.dp(12), p.dp(10), p.dp(12), p.dp(10));
                    LinearLayout.LayoutParams lp = matchP();
                    lp.bottomMargin = p.dp(8);
                    parent.addView(note, lp);
                    break;
                }
                case "code":
                    parent.addView(code(c, p, t, b.optString("label")), withMargin(p, matchP(), 8));
                    break;
                case "ul": {
                    JSONArray items = b.optJSONArray("items");
                    if (items == null) break;
                    boolean danger = "danger".equals(b.optString("tone"));
                    for (int j = 0; j < items.length(); j++) {
                        LinearLayout r = new LinearLayout(c);
                        r.setOrientation(LinearLayout.HORIZONTAL);
                        TextView dot = tv(c, p, danger ? "!" : "·", 13, true,
                                danger ? p.noteDanger : p.textMute);
                        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        dlp.rightMargin = p.dp(8);
                        r.addView(dot, dlp);
                        TextView item = tv(c, p, items.optString(j), 13.5f, false, p.textMain);
                        item.setLineSpacing(p.dp(4), 1f);
                        r.addView(item, new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                        parent.addView(r, withMargin(p, matchP(), 6));
                    }
                    break;
                }
                case "steps":
                case "tree": {
                    JSONArray items = b.optJSONArray("items");
                    if (items == null) break;
                    boolean tree = "tree".equals(k);
                    for (int j = 0; j < items.length(); j++) {
                        String s = items.optString(j);
                        String headT = null, rest = s;
                        if (tree) {
                            String[] parts = s.split("[：:]", 2);
                            if (parts.length == 2) { headT = parts[0]; rest = parts[1]; }
                        }
                        LinearLayout r = new LinearLayout(c);
                        r.setOrientation(LinearLayout.HORIZONTAL);
                        TextView num = tv(c, p, String.valueOf(j + 1), 11, true, p.accent);
                        num.setGravity(Gravity.CENTER);
                        num.setBackground(shape(p, p.chipBgOn, p.dp(9)));
                        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(p.dp(20), p.dp(20));
                        nlp.rightMargin = p.dp(10);
                        nlp.topMargin = p.dp(2);
                        r.addView(num, nlp);
                        LinearLayout col = new LinearLayout(c);
                        col.setOrientation(LinearLayout.VERTICAL);
                        if (headT != null) col.addView(tv(c, p, headT, 13, true, p.textMain));
                        TextView d = tv(c, p, rest, 13, false, headT != null ? p.textSub : p.textMain);
                        d.setLineSpacing(p.dp(3), 1f);
                        col.addView(d);
                        r.addView(col, new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                        parent.addView(r, withMargin(p, matchP(), 8));
                    }
                    break;
                }
                case "topo": {
                    JSONObject dg = b.optJSONObject("dg");
                    if (dg == null) break;
                    NativeTopoView topo = new NativeTopoView(c, p, dg);
                    parent.addView(topo, withMargin(p, matchP(), 8));
                    break;
                }
            }
        }
    }

    /** 终端块：等宽 + 横向滚动 + 标题栏（可长按复制）。 */
    static View code(Context c, Palette p, String text, String label) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(p, p.codeBg, p.dp(12)));
        if (!TextUtils.isEmpty(label)) {
            TextView lab = tv(c, p, label, 10.5f, true, p.codeLabel);
            lab.setPadding(p.dp(12), p.dp(7), p.dp(12), p.dp(2));
            box.addView(lab);
        }
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        TextView code = new TextView(c);
        code.setText(text);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        code.setTextColor(p.dark ? Color.rgb(226, 232, 240) : Color.rgb(203, 213, 225));
        code.setTypeface(Typeface.MONOSPACE);
        code.setLineSpacing(p.dp(3), 1f);
        code.setTextIsSelectable(true);
        code.setPadding(p.dp(12), p.dp(4), p.dp(12), p.dp(12));
        hs.addView(code, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(hs);
        return box;
    }

    private static LinearLayout.LayoutParams withMargin(Palette p, LinearLayout.LayoutParams lp, int bottomDp) {
        lp.bottomMargin = p.dp(bottomDp);
        return lp;
    }

    /**
     * 把一张已建好的卡片包成玻璃面（AndroidLiquidGlassView）。
     * 只在 API33+、直铺模式、有采样源时启用；任何异常都回退成普通卡（不半吊子降级）。
     * 每个页面只包头卡：一个着色器面 = 一次 backdrop 重绘，卡多了会成倍放。
     */
    static LinearLayout glassWrap(Context c, Palette p, LinearLayout card,
                                  android.view.ViewGroup source, boolean direct, int widthPx) {
        if (!direct || source == null || android.os.Build.VERSION.SDK_INT < 33) return card;
        /* 液态玻璃三档（2026-11-09）：无=头卡退平卡；标准=砍色散。 */
        if (NativeBlocks.glassNone()) return card;
        try {
            int cw = Math.max(1, widthPx - p.dp(28));
            card.measure(android.view.View.MeasureSpec.makeMeasureSpec(cw, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
            int h = Math.max(p.dp(72), card.getMeasuredHeight());
            com.qmdeve.liquidglass.widget.LiquidGlassView glass =
                    new com.qmdeve.liquidglass.widget.LiquidGlassView(c);
            glass.bind(source);
            glass.setCornerRadius(p.dp(16));
            glass.setRefractionHeight(p.dp(18));
            glass.setRefractionOffset(p.dp(56));
            glass.setBlurRadius(0.55f);
            glass.setDispersion(NativeBlocks.glassStandard() ? 0f : 0.35f);
            /* ❗深色 tint 必须跟深色卡（28,28,30）：原硬编码纯白 → 深色下玻璃
               白 84% + 深色 palette 白字 = 白字白底看不见（2026-11-08 用户报
               「网络学习手册 2.0 还是白色、标题看不见」）。glassWrap 包所有
               原生页头卡（首页/字典/面试/路线图/CLI），一处修正全覆盖。 */
            if (p.dark) {
                glass.setTintColorRed(0.11f);
                glass.setTintColorGreen(0.11f);
                glass.setTintColorBlue(0.12f);
            } else {
                glass.setTintColorRed(1f);
                glass.setTintColorGreen(1f);
                glass.setTintColorBlue(1f);
            }
            /* 承载文字的玻璃面必须够实：0.55/0.62 在亮壁纸（云、白墙）上会把正文吃掉
               —— 二级页头卡右半截“看起来是空白”、副标题读不出来（2026-10-28 用户报
               「显示不全」）。提到 0.82/0.84：仍能透出壁纸的明暗关系，正文回到白卡水平。 */
            glass.setTintAlpha(p.dark ? 0.84f : 0.82f);
            card.setBackground(null);
            glass.addView(card);
            /* ❗bind(source) 是延迟抓取的：首帧玻璃里还没有内容 → 全透明，
               抓到后才变白，表现就是「切页时卡片先纯透、再变白、闪一下」
               （2026-11-07 用户精确描述）。这里先用不透明底盖住这段窗口期，
               220ms 后撤掉，露出真正的玻璃 —— 人眼看到的是正常卡片，不闪。 */
            /* ❗占位底不能用白色：glassWrap 常包整张大卡（术语词典/路线图/速查表/
               CLI 模拟器），白色占位在玻璃就绪前会是一大块白 —— 用户报「出现一大块
               白色色块，短时间或长时间」（2026-11-07）。
               改用**页面背景色**：与底色融为一体，即使玻璃迟迟不就绪也看不出来；
               同时必须圆角（直角矩形同样会被看出，前者已踩过一次）。 */
            android.graphics.drawable.GradientDrawable ph =
                    new android.graphics.drawable.GradientDrawable();
            ph.setColor(p.bg);
            ph.setCornerRadius(p.dp(16));
            glass.setBackground(ph);
            glass.postDelayed(() -> glass.setBackground(null), 220L);
            LinearLayout holder = new LinearLayout(c);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.addView(glass, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, h));
            holder.setLayoutParams(card.getLayoutParams());   /* 顶替卡片原位置，外边距不丢 */
            return holder;
        } catch (Throwable t) {
            android.util.Log.w("NativeBlocks", "玻璃包装失败，回退普通卡", t);
            return card;
        }
    }

    private static int noteColor(Palette p, String tone) {
        if (tone == null) return p.noteInfo;
        switch (tone) {
            case "warn": return p.noteWarn;
            case "danger": return p.noteDanger;
            case "ok": return p.noteOk;
            case "plain": return p.notePlain;
            default: return p.noteInfo;
        }
    }
}
