package com.netops.handbook.lens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 原生拓扑渲染器（N3 · 2026-10-27）。
 *
 * 对齐 Web 版 10-topo.js 的四种声明式图形（数据里共 78 处）：
 *   flow  —— 横向流程：节点 + 连接箭头 + 连线上标注（59 处，主力）
 *   stack —— 纵向分层：整行色块 + 主标题/副标题（10 处）
 *   multi —— 多行小节点，行间用箭头与标注（7 处）
 *   ha    —— 主备两节点 + 中间 VIP 标签（2 处）
 *
 * 为什么自己画：Web 版是内联 SVG；原生页里没有 WebView，也不该为一句话的图开一个。
 * 只取「结构可读」这个目标：圆角盒 + 箭头 + 标注 + 主题色，不做设备图标与自动折行。
 */
class NativeTopoView extends View {

    private final JSONObject dg;
    private final NativeBlocks.Palette pal;

    private final Paint pBox = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Path arrow = new Path();

    private int height;

    NativeTopoView(Context c, NativeBlocks.Palette p, JSONObject dg) {
        super(c);
        this.pal = p;
        this.dg = dg;
        pBox.setStyle(Paint.Style.FILL);
        pBox.setColor(p.chipBgOff);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeWidth(p.dp(1));
        pStroke.setColor(p.border);
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setColor(p.textMain);
        pText.setTextSize(p.dp(11.5f));
        pSub.setTextAlign(Paint.Align.CENTER);
        pSub.setColor(p.textMute);
        pSub.setTextSize(p.dp(9.5f));
        pLabel.setTextAlign(Paint.Align.CENTER);
        pLabel.setColor(p.textMute);
        pLabel.setTextSize(p.dp(9f));
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(p.dp(1.2f));
        pLine.setColor(p.textMute);
        pLine.setStrokeCap(Paint.Cap.ROUND);
    }

    /* ---------------- 测量 ---------------- */
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h;
        String k = dg.optString("k");
        if ("stack".equals(k)) {
            JSONArray n = dg.optJSONArray("n");
            int count = n == null ? 1 : n.length();
            h = pal.dp(6) + count * pal.dp(34) + (count - 1) * pal.dp(4) + pal.dp(6);
        } else if ("multi".equals(k)) {
            JSONArray rows = dg.optJSONArray("rows");
            int rc = rows == null ? 1 : rows.length();
            h = rc * pal.dp(46) + (rc - 1) * pal.dp(26);
        } else if ("ha".equals(k)) {
            h = pal.dp(120);
        } else {                       /* flow 与未知类型都按横向一行处理 */
            h = pal.dp(84);
        }
        height = Math.max(pal.dp(60), h);
        setMeasuredDimension(w, height);
    }

    /* ---------------- 绘制 ---------------- */
    @Override
    protected void onDraw(Canvas c) {
        String k = dg.optString("k");
        if ("stack".equals(k)) drawStack(c);
        else if ("multi".equals(k)) drawMulti(c);
        else if ("ha".equals(k)) drawHa(c);
        else drawFlow(c);
    }

    /** flow：一行节点 + 箭头 + 连接标注。 */
    private void drawFlow(Canvas c) {
        JSONArray n = dg.optJSONArray("n");
        JSONArray l = dg.optJSONArray("l");
        if (n == null || n.length() == 0) return;
        int count = n.length();
        float w = getWidth();
        float gap = pal.dp(22);
        float boxW = Math.min(pal.dp(96), (w - gap * (count - 1)) / count);
        float totalW = boxW * count + gap * (count - 1);
        float x0 = (w - totalW) / 2f;
        float boxH = pal.dp(38);
        float cy = pal.dp(38);

        for (int i = 0; i < count; i++) {
            JSONObject o = n.optJSONObject(i);
            if (o == null) continue;
            float left = x0 + i * (boxW + gap);
            drawNode(c, left, cy - boxH / 2, boxW, boxH, o.optString("t"), o.optString("s"));
            if (i < count - 1) {
                float ax = left + boxW, bx = left + boxW + gap;
                drawArrow(c, ax + pal.dp(2), cy, bx - pal.dp(2), cy);
                if (l != null) {
                    String lab = l.optString(i, "");
                    if (!lab.isEmpty()) {
                        c.drawText(lab, (ax + bx) / 2f, cy - pal.dp(9), pLabel);
                    }
                }
            }
        }
    }

    /** stack：整行分层色块（层名 + 说明）。 */
    private void drawStack(Canvas c) {
        JSONArray n = dg.optJSONArray("n");
        if (n == null) return;
        float w = getWidth();
        float top = pal.dp(6);
        float h = pal.dp(34);
        for (int i = 0; i < n.length(); i++) {
            JSONObject o = n.optJSONObject(i);
            if (o == null) continue;
            float y = top + i * (h + pal.dp(4));
            rf.set(pal.dp(8), y, w - pal.dp(8), y + h);
            pBox.setColor(pal.chipBgOff);
            c.drawRoundRect(rf, pal.dp(9), pal.dp(9), pBox);
            c.drawRoundRect(rf, pal.dp(9), pal.dp(9), pStroke);
            pText.setTextAlign(Paint.Align.LEFT);
            c.drawText(o.optString("t"), pal.dp(18), y + h / 2f + pal.dp(4), pText);
            pText.setTextAlign(Paint.Align.CENTER);
            String s = o.optString("s");
            if (!s.isEmpty()) {
                pSub.setTextAlign(Paint.Align.RIGHT);
                c.drawText(s, w - pal.dp(18), y + h / 2f + pal.dp(3.5f), pSub);
                pSub.setTextAlign(Paint.Align.CENTER);
            }
        }
    }

    /** multi：多行小节点，行内横排、行间箭头与标注。 */
    private void drawMulti(Canvas c) {
        JSONArray rows = dg.optJSONArray("rows");
        if (rows == null) return;
        float w = getWidth();
        float rowH = pal.dp(40);
        float y = 0;
        for (int r = 0; r < rows.length(); r++) {
            JSONObject row = rows.optJSONObject(r);
            if (row == null) continue;
            JSONArray ns = row.optJSONArray("n");
            int cnt = ns == null ? 1 : ns.length();
            float gap = pal.dp(14);
            float boxW = Math.min(pal.dp(150), (w - gap * (cnt - 1)) / cnt);
            float totalW = boxW * cnt + gap * (cnt - 1);
            float x0 = (w - totalW) / 2f;
            for (int i = 0; i < cnt; i++) {
                JSONObject o = ns.optJSONObject(i);
                if (o == null) continue;
                drawNode(c, x0 + i * (boxW + gap), y, boxW, rowH, o.optString("t"), o.optString("s"));
            }
            /* 行间箭头：从本行中点指向下一行 */
            if (r < rows.length() - 1) {
                float ax = w / 2f;
                drawArrow(c, ax, y + rowH + pal.dp(3), ax, y + rowH + pal.dp(20));
                JSONArray ls = row.optJSONArray("l");
                if (ls != null && ls.length() > 0) {
                    String lab = ls.optString(0, "");
                    if (!lab.isEmpty()) c.drawText(lab, ax, y + rowH + pal.dp(16), pLabel);
                }
            }
            y += rowH + pal.dp(26);
        }
    }

    /** ha：主/备两节点 + 中间 VIP 标签。 */
    private void drawHa(Canvas c) {
        JSONArray n = dg.optJSONArray("n");
        if (n == null) return;
        float w = getWidth();
        float boxW = Math.min(pal.dp(150), w * 0.55f);
        float x = (w - boxW) / 2f;
        float boxH = pal.dp(34);
        for (int i = 0; i < Math.min(2, n.length()); i++) {
            JSONObject o = n.optJSONObject(i);
            if (o == null) continue;
            float y = i == 0 ? pal.dp(4) : pal.dp(4) + boxH + pal.dp(44);
            drawNode(c, x, y, boxW, boxH, o.optString("t"), o.optString("s"));
            if (i == 0) {
                String mid = dg.optString("mid");
                if (!mid.isEmpty()) {
                    float my = y + boxH + pal.dp(9);
                    rf.set(x + boxW * 0.18f, my, x + boxW * 0.82f, my + pal.dp(22));
                    pBox.setColor(pal.chipBgOn);
                    c.drawRoundRect(rf, pal.dp(11), pal.dp(11), pBox);
                    c.drawText(mid, rf.centerX(), my + pal.dp(15), pText);
                }
            }
        }
    }

    /* ---------------- 元件 ---------------- */
    private void drawNode(Canvas c, float x, float y, float w, float h, String t, String s) {
        rf.set(x, y, x + w, y + h);
        pBox.setColor(pal.chipBgOff);
        c.drawRoundRect(rf, pal.dp(10), pal.dp(10), pBox);
        c.drawRoundRect(rf, pal.dp(10), pal.dp(10), pStroke);
        if (s == null || s.isEmpty()) {
            c.drawText(t, rf.centerX(), y + h / 2f + pal.dp(4), pText);
        } else {
            c.drawText(t, rf.centerX(), y + h / 2f - pal.dp(1), pText);
            c.drawText(s, rf.centerX(), y + h / 2f + pal.dp(11), pSub);
        }
    }

    private void drawArrow(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x1, y1, x2, y2, pLine);
        float ang = (float) Math.atan2(y2 - y1, x2 - x1);
        float len = pal.dp(5);
        arrow.reset();
        arrow.moveTo(x2, y2);
        arrow.lineTo((float) (x2 - len * Math.cos(ang - 0.5)), (float) (y2 - len * Math.sin(ang - 0.5)));
        arrow.lineTo((float) (x2 - len * Math.cos(ang + 0.5)), (float) (y2 - len * Math.sin(ang + 0.5)));
        arrow.close();
        Paint fill = pBox;
        fill.setColor(pal.textMute);
        c.drawPath(arrow, fill);
    }
}
