package com.netops.handbook.lens;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 主题总线 —— 照抄 FlClash ThemeState / KernelSU MaterialTheme 的**单一状态源 +
 * 响应式订阅**思路（View 版落地，2026-11-10 用户定案）。
 *
 * 背景：主题切换此前靠 MainActivity onDarkChange 的手工点名名册（16 个 if），
 * 每加一个界面都要记得往里加一行 —— 漏了就是「侧栏不符主题」「药丸白色」
 * 「半深半浅」类 bug（2026-11-10 三例）。注册制下：新界面实现 DarkAware 并
 * 构造时 register，漏通知在结构上不可能发生。
 *
 * 使用规范（照抄 FlClash 的语义）：
 *   1. 消费者实现 DarkAware，构造时 ThemeBus.register(this)；
 *      onThemeChanged(dark) 里必须完成**全部**主题相关重设（含背景 drawable、
 *      位图缓存失效 —— 构造时快照的配色在这里显式重建）。
 *   2. 切换入口只调 ThemeBus.setDark(d)，不直接通知任何单个界面。
 *   3. View detach 时 unregister 防泄漏。
 */
public final class ThemeBus {

    public interface DarkAware { void onThemeChanged(boolean dark); }

    private static final String TAG = "ThemeBus";
    private static final List<DarkAware> SINKS = new ArrayList<>();
    private static volatile boolean dark = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ThemeBus() {}

    /** 注册（幂等）；立即回调一次当前主题，消费者无需自己判初始值。 */
    public static void register(DarkAware s) {
        MAIN.post(() -> {
            if (!SINKS.contains(s)) SINKS.add(s);
            s.onThemeChanged(dark);
        });
    }

    /** 注销（View detach 时调用，防泄漏）。 */
    public static void unregister(DarkAware s) {
        MAIN.post(() -> SINKS.remove(s));
    }

    /** 当前主题（消费者构造时同步初始状态用）。 */
    public static boolean isDark() { return dark; }

    /** 切换主题：广播给全部注册者（主线程）。 */
    public static void setDark(boolean d) {
        MAIN.post(() -> {
            if (dark == d) return;
            dark = d;
            com.netops.handbook.ui.AppShellState.INSTANCE.setDark(d);
            Log.i(TAG, "setDark=" + d + " sinks=" + SINKS.size());
            for (DarkAware s : new ArrayList<>(SINKS)) {
                try {
                    s.onThemeChanged(d);
                } catch (Throwable t) {
                    Log.w(TAG, "sink 通知失败", t);
                }
            }
        });
    }
}
