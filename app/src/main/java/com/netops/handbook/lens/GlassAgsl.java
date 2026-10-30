package com.netops.handbook.lens;

import android.content.res.Resources;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 库玻璃 AGSL（assets 里的 liquidglass_effect）的加载与缓存。
 *
 * 由 LensBarView（底栏/侧栏）与 AppBarView（顶栏）共用：两处都要同一个 shader，
 * 复制一份字符串/加载逻辑会漂移。加载失败返回 null，调用方各自回退实色/位图绘制。
 */
final class GlassAgsl {

    private static String cached;

    private GlassAgsl() { }

    /** 读 R.raw.liquidglass_effect；失败返回 null（调用方须有降级路径）。 */
    static String load(Resources res) {
        if (cached != null) return cached;
        try (InputStream is = res.openRawResource(com.netops.handbook.R.raw.liquidglass_effect);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            cached = sb.toString();
            return cached;
        } catch (Throwable t) {
            return null;
        }
    }
}
