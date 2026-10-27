package com.netops.handbook.lens;

/**
 * 二阶阻尼振子的解析解（1:1 移植 Flutter 的 SpringSimulation）。
 *
 * 为什么必须解析解而不是每帧欧拉积分：FlClash 的形变是"速度 + 刹车"耦合出来的
 * （见 LensBarView 的 wobble），它依赖弹簧在任意时刻的**精确**速度和加速度。
 * 数值积分在帧间隔抖动时会把速度和加速度算歪，形变就发假。
 *
 * 与 Flutter 一致的三个分支：
 *   damping² - 4mk > 0 过阻尼；< 0 欠阻尼（会振荡，Q 弹就靠它）；= 0 临界。
 */
public final class Spring {

    private static final double TOL_DISTANCE = 1e-3;
    private static final double TOL_VELOCITY = 1e-3;

    private static final int CRITICAL = 0;
    private static final int UNDER = 1;
    private static final int OVER = 2;

    private double value;
    private double target;
    private double velocity;
    private SpringSpec spec;
    private int type = CRITICAL;
    private boolean simulating;
    private double t0;
    private double w, r, r1, r2, c1, c2;

    public Spring(double value) {
        this.value = value;
        this.target = value;
    }

    public double value() {
        return value;
    }

    public double target() {
        return target;
    }

    public double velocity() {
        return velocity;
    }

    public boolean active() {
        return simulating;
    }

    /** Hooke 定律直接算，与 Flutter _Spring.acceleration 同一式子。 */
    public double acceleration() {
        if (spec == null) return 0;
        return (spec.stiffness * (target - value) - spec.damping * velocity) / spec.mass;
    }

    /**
     * 重定向到新目标。**保留当前速度与位置**再起一条新曲线 —— 这是 Flutter
     * _Spring 的关键（它用 SpringSimulation(spring, value, target, velocity) 重建），
     * 也是拖动手感连续、不会"一顿一顿"的原因。
     */
    public void springTo(double newTarget, SpringSpec s, double nowSec) {
        advance(nowSec);
        this.target = newTarget;
        this.spec = s;
        double dist = value - newTarget;
        double v = velocity;
        double cmk = s.damping * s.damping - 4.0 * s.mass * s.stiffness;
        if (cmk > 0) {
            type = OVER;
            r1 = (-s.damping - Math.sqrt(cmk)) / (2.0 * s.mass);
            r2 = (-s.damping + Math.sqrt(cmk)) / (2.0 * s.mass);
            c2 = (v - r1 * dist) / (r2 - r1);
            c1 = dist - c2;
        } else if (cmk < 0) {
            type = UNDER;
            w = Math.sqrt(4.0 * s.mass * s.stiffness - s.damping * s.damping) / (2.0 * s.mass);
            r = -s.damping / (2.0 * s.mass);
            c1 = dist;
            c2 = (v - r * dist) / w;
        } else {
            type = CRITICAL;
            r = -s.damping / (2.0 * s.mass);
            c1 = dist;
            c2 = v - r * dist;
        }
        t0 = nowSec;
        simulating = true;
        advance(nowSec);
    }

    public void advance(double nowSec) {
        if (!simulating) return;
        double t = nowSec - t0;
        double dist;
        double dx;
        switch (type) {
            case UNDER: {
                double e = Math.exp(r * t);
                double cos = Math.cos(w * t);
                double sin = Math.sin(w * t);
                dist = e * (c1 * cos + c2 * sin);
                dx = e * (c2 * w * cos - c1 * w * sin) + r * e * (c2 * sin + c1 * cos);
                break;
            }
            case OVER: {
                double e1 = Math.exp(r1 * t);
                double e2 = Math.exp(r2 * t);
                dist = c1 * e1 + c2 * e2;
                dx = c1 * r1 * e1 + c2 * r2 * e2;
                break;
            }
            default: {
                double e = Math.exp(r * t);
                dist = (c1 + c2 * t) * e;
                dx = r * (c1 + c2 * t) * e + c2 * e;
                break;
            }
        }
        value = target + dist;
        velocity = dx;
        if (Math.abs(dist) < TOL_DISTANCE && Math.abs(dx) < TOL_VELOCITY) {
            value = target;
            velocity = 0;
            simulating = false;
        }
    }
}
