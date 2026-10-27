package com.netops.handbook.lens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
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

    private final ImageView ambient;   // 横屏环境层：cover + 重模糊 + 压暗（垫在两侧）
    private final ImageView image;     // 主壁纸：竖屏 cover / 横屏 FIT_CENTER（整张可见）
    private String current;
    private boolean landscape;

    public NativeBackdrop(Context c) {
        super(c);
        ambient = new ImageView(c);
        ambient.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ambient.setVisibility(GONE);
        addView(ambient, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(image, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        boolean land = w > h;
        if (land != landscape) {
            landscape = land;
            applyLayoutMode();
        }
    }

    /** 竖屏：壁纸 cover 铺满（现状）。横屏：竖版壁纸若继续 cover 会裁掉上下
     *  一大半（用户报「壁纸被遮挡一大半」）—— 改 letterbox：整张等高居中可见，
     *  两侧露出的空档垫重模糊压暗的环境层；侧边 dock 正好落在环境层上。 */
    private void applyLayoutMode() {
        image.setScaleType(landscape
                ? ImageView.ScaleType.FIT_CENTER
                : ImageView.ScaleType.CENTER_CROP);
        ambient.setVisibility(landscape && image.getDrawable() != null ? VISIBLE : GONE);
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
        android.graphics.drawable.Drawable dr = new android.graphics.drawable.BitmapDrawable(
                getResources(), bmp);
        image.setImageDrawable(dr);
        ambient.setImageDrawable(dr);
        applyBlur(blurDp);
        applyLayoutMode();
        setVisibility(VISIBLE);
    }

    private void applyBlur(float blurDp) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            float dp = getResources().getDisplayMetrics().density;
            float r = Math.max(0.01f, blurDp) * dp;
            image.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    r, r, android.graphics.Shader.TileMode.CLAMP));
            /* 环境层：固定重模糊 + 压暗，只做「两侧延伸」的氛围垫，不参与通透度。
               压暗走 ImageView 级 setColorFilter（RenderEffect.createColorEffect
               在本 compileSdk 解析不到，实测）。 */
            float ra = 40f * dp;
            ambient.setRenderEffect(RenderEffect.createBlurEffect(
                    ra, ra, android.graphics.Shader.TileMode.CLAMP));
            ambient.setColorFilter(android.graphics.Color.argb(150, 12, 16, 22));
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
