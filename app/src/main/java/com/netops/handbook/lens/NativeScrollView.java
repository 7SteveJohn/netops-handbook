package com.netops.handbook.lens;

import android.content.Context;
import android.widget.ScrollView;

/** 原生页统一滚动容器：fling 速度 ×1.35（距离∝v²，惯性 ≈1.8 倍）。
 *  Android 默认摩擦比 iOS 重、大屏（平板）上短惯性尤其明显——用户报
 *  「滑动有粘滞感」（2026-11-09）。clamp 上限防速度过冲。 */
public class NativeScrollView extends ScrollView {

    public NativeScrollView(Context c) {
        super(c);
    }

    @Override
    public void fling(int velocityY) {
        int v = Math.max(-14000, Math.min(14000, Math.round(velocityY * 1.35f)));
        super.fling(v);
    }
}
