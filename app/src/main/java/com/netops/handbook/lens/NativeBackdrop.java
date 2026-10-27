package com.netops.handbook.lens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.InputStream;

/**
 * 原生壁纸层（2026-10-27 · 玻璃件采样源）。
 *
 * 为什么需要：AndroidLiquidGlassView 的玻璃是「采样某个 ViewGroup 的绘制结果」，
 * 直接绑 WebView 会在每帧 record 一遍网页（必卡）。这里用一张静态壁纸位图当采样源：
 * 壁纸不滚动，采样结果恒定，成本只有一次 decode。
 *
 * 同时这也是「原生接管壁纸」的第一步：直铺模式下原生页自带底图，
 * 不再依赖 WebView 的 .wall 层。
 */
public class NativeBackdrop extends FrameLayout {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(3);

    private final ImageView image;
    private String current;

    public NativeBackdrop(Context c) {
        super(c);
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(image, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    /** file = assets/wallpapers 下的文件名；空串/未知 = 隐藏（无壁纸）。 */
    public void setWallpaper(String file) { setWallpaper(file, 8f); }

    /** blurDp 跟随通透度（JS 传 --wp-blur），与 Web 层 .wall 同一口径。 */
    public void setWallpaper(String file, float blurDp) {
        if (file == null || file.isEmpty()) {
            setVisibility(GONE);
            image.setImageDrawable(null);
            current = null;
            return;
        }
        current = file;
        android.util.Log.i("NativeBackdrop", "setWallpaper " + file + " blur=" + blurDp);
        Bitmap bmp = CACHE.get(file);
        if (bmp == null) {
            bmp = decode(file);
            if (bmp != null) CACHE.put(file, bmp);
        }
        if (bmp == null) {
            setVisibility(GONE);
            return;
        }
        image.setImageBitmap(bmp);
        applyBlur(blurDp);
        setVisibility(VISIBLE);
    }

    private void applyBlur(float blurDp) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            float r = Math.max(0.01f, blurDp) * getResources().getDisplayMetrics().density;
            image.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    r, r, android.graphics.Shader.TileMode.CLAMP));
        } catch (Throwable ignored) { }
    }

    private Bitmap decode(String file) {
        try (InputStream is = getContext().getAssets().open("wallpapers/" + file)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;                       // 采样源不需要原分辨率
            return BitmapFactory.decodeStream(is, null, o);
        } catch (Throwable e) {
            android.util.Log.w("NativeBackdrop", "decode 失败 " + file, e);
            return null;
        }
    }
}
