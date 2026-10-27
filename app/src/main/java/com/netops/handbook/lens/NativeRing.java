package com.netops.handbook.lens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** 进度环（原生首页 / 我的页共用）：底圈 + 进度弧 + 中心百分比。 */
class NativeRing extends View {

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final NativeBlocks.Palette pal;
    private int pct;

    NativeRing(Context c, NativeBlocks.Palette p) {
        super(c);
        pal = p;
        pBg.setStyle(Paint.Style.STROKE);
        pBg.setStrokeWidth(p.dp(5));
        pBg.setColor(p.hairline);
        pArc.setStyle(Paint.Style.STROKE);
        pArc.setStrokeWidth(p.dp(5));
        pArc.setStrokeCap(Paint.Cap.ROUND);
        pArc.setColor(p.accent);
        pTxt.setTextAlign(Paint.Align.CENTER);
        pTxt.setColor(p.textMain);
        pTxt.setTextSize(p.dp(16));
        pTxt.setFakeBoldText(true);
    }

    void setPct(int p) { pct = p; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float pad = pal.dp(6);
        rf.set(pad, pad, getWidth() - pad, getHeight() - pad);
        c.drawArc(rf, 0, 360, false, pBg);
        c.drawArc(rf, -90, 360f * Math.min(100, Math.max(0, pct)) / 100f, false, pArc);
        c.drawText(pct + "%", getWidth() / 2f, getHeight() / 2f + pal.dp(5), pTxt);
    }
}
