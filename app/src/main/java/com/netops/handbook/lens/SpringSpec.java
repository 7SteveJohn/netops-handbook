package com.netops.handbook.lens;

/**
 * 弹簧参数（对应 Flutter 的 SpringDescription）。
 *
 * 两个构造口径与 Flutter 完全一致，这样 FlClash 源码里写死的那些参数可以原样搬过来，
 * 不用再凭手感猜 cubic-bezier：
 *   withDampingRatio(mass, stiffness, ratio) -> damping = ratio * 2 * sqrt(mass * stiffness)
 *   withDurationAndBounce(duration, bounce)  -> mass = 1, stiffness = 4π²/T²,
 *                                              ratio = bounce > 0 ? 1 - bounce : 1/(bounce + 1)
 */
public final class SpringSpec {

    public final double mass;
    public final double stiffness;
    public final double damping;

    private SpringSpec(double mass, double stiffness, double damping) {
        this.mass = mass;
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public static SpringSpec ratio(double stiffness, double ratio) {
        return new SpringSpec(1.0, stiffness, ratio * 2.0 * Math.sqrt(stiffness));
    }

    public static SpringSpec durationBounce(double durationMs, double bounce) {
        double t = durationMs / 1000.0;
        double k = (4.0 * Math.PI * Math.PI) / (t * t);
        double ratio = bounce > 0 ? (1.0 - bounce) : (1.0 / (bounce + 1.0));
        return new SpringSpec(1.0, k, ratio * 2.0 * Math.sqrt(k));
    }
}
