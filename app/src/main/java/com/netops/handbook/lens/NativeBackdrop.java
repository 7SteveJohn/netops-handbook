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
    /** WebView 独占路由（CLI）时让位。本层 z 序在 WebView 之上（给原生页当壁纸底），
     *  一旦常显就把 WebView 盖死：竖屏整屏盖、横屏被 FIT_CENTER 竖带切穿，
     *  而触摸仍穿透到 WebView —— 页面看不见、点了没反应（2026-10-29 平板实测）。 */
    private boolean revealedForWeb;

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
            image.setImageDrawable(null);
            ambient.setImageDrawable(null);
            current = null;
            refreshVisibility();
            return;
        }
        android.util.Log.i("NativeBackdrop", "setWallpaper " + file + " blur=" + blurDp);
        Bitmap bmp = CACHE.get(file);
        if (bmp == null) {
            bmp = decode(file);
            if (bmp != null) CACHE.put(file, bmp);
        }
        if (bmp == null) {
            current = null;
            refreshVisibility();
            return;
        }
        current = file;
        android.graphics.drawable.Drawable dr = new android.graphics.drawable.BitmapDrawable(
                getResources(), bmp);
        image.setImageDrawable(dr);
        ambient.setImageDrawable(dr);
        applyBlur(blurDp);
        applyLayoutMode();
        refreshVisibility();
    }

    /** WebView 路由（CLI）→ true 让位；任何原生页显示 → false 收回。
     *  由 MainActivity.hideNativePagesExcept(keep) 按 keep==null 统一驱动。 */
    public void setRevealed(boolean revealed) {
        if (revealedForWeb == revealed) return;
        revealedForWeb = revealed;
        refreshVisibility();
    }

    /** 有壁纸且没在给 WebView 让位时才显示；否则 GONE（GONE 不参与命中测试）。 */
    private void refreshVisibility() {
        setVisibility(current != null && !revealedForWeb ? VISIBLE : GONE);
    }

    private void applyBlur(float blurDp) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            float dp = getResources().getDisplayMetrics().density;
            float r = Math.max(0.01f, blurDp) * dp;
            image.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    r, r, android.graphics.Shader.TileMode.CLAMP));
            /* 环境层（横屏两侧垫）与主图同 blur：同一张图同一档模糊，contain 竖带与
               cover 垫在边界处颜色连续，没有接缝 —— 这才是 Web 层 .wall 的口径
               （整张壁纸统一轻度模糊、颜色场景保留，2026-10-26 用户认可的那种）。
               首版这里用过 40dp 重模糊 + 59% 暗夜压暗，与主图 blur2dp 拼出三条观感、
               两条硬接缝，人物从卡片背后穿过 —— 用户报「越改越丑，没有一开始的
               美感」（2026-10-29 横屏真机截图实证）。 */
            ambient.setRenderEffect(RenderEffect.createBlurEffect(
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
