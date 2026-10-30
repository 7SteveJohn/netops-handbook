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

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(8);

    private final ImageView ambient;   // 横屏环境层
    private final ImageView image;     // 主壁纸：统一 CENTER_CROP 满画幅显示
    private String baseFile;           // 外部下发的壁纸文件名
    private String current;            // 当前正在显示的实际文件名
    private float currentBlurDp = 8f;  // 当前模糊深度
    private boolean landscape;
    /** WebView 独占路由（CLI）时让位。本层 z 序在 WebView 之上（给原生页当壁纸底），
     *  一旦常显就把 WebView 盖死：竖屏整屏盖、横屏被 FIT_CENTER 竖带切穿，
     *  而触摸仍穿透到 WebView —— 页面看不见、点了没反应（2026-10-29 平板实测）。 */
    private boolean revealedForWeb;

    public NativeBackdrop(Context c) {
        super(c);
        landscape = c.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
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
        if (w > 0 && h > 0) {
            boolean land = w > h;
            if (land != landscape) {
                landscape = land;
                updateImageForOrientation();
            }
        }
    }

    /** 无论横屏还是竖屏，壁纸统一 CENTER_CROP 铺满全屏，杜绝两侧虚化垫边（模糊黑边） */
    private void applyLayoutMode() {
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ambient.setVisibility(GONE);
    }

    /** 横竖屏文件名自动成对转换算法：_p.webp 与 _l.webp 互转 */
    public static String resolveOrientationFile(String file, boolean isLandscape) {
        if (file == null || file.isEmpty()) return file;
        if (isLandscape) {
            if (file.endsWith("_p.webp")) {
                return file.substring(0, file.length() - 7) + "_l.webp";
            }
        } else {
            if (file.endsWith("_l.webp")) {
                return file.substring(0, file.length() - 7) + "_p.webp";
            }
        }
        return file;
    }

    /** 屏幕旋转显式通知（由 Activity onConfigurationChanged 第一时间调用，0延迟换图） */
    public void notifyOrientationChanged(boolean isLandscape) {
        if (landscape != isLandscape) {
            landscape = isLandscape;
            updateImageForOrientation();
        }
    }

    private void updateImageForOrientation() {
        if (baseFile == null || baseFile.isEmpty()) return;
        String target = resolveOrientationFile(baseFile, landscape);
        if (target != null && !target.equals(current)) {
            android.util.Log.i("NativeBackdrop", "auto-switching orientation wallpaper to " + target
                    + " (land=" + landscape + ")");
            loadBitmapAndDisplay(target);
        }
    }

    /** 供外部玻璃件（LensBarView 等）直接采样当前壁纸原图，避免 PixelCopy 动态截屏开销与穿透问题 */
    public Bitmap getCurrentBitmap() {
        return (image != null && image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)
                ? ((android.graphics.drawable.BitmapDrawable) image.getDrawable()).getBitmap()
                : null;
    }

    /** file = assets/wallpapers 下的文件名；空串/未知 = 隐藏（无壁纸）。 */
    public void setWallpaper(String file) { setWallpaper(file, 8f); }

    /** blurDp 跟随通透度（JS 传 --wp-blur），与 Web 层 .wall 同一口径。 */
    public void setWallpaper(String file, float blurDp) {
        baseFile = file;
        currentBlurDp = blurDp;
        if (file == null || file.isEmpty()) {
            image.setImageDrawable(null);
            ambient.setImageDrawable(null);
            current = null;
            refreshVisibility();
            return;
        }
        String target = resolveOrientationFile(file, landscape);
        android.util.Log.i("NativeBackdrop", "setWallpaper base=" + file + " target=" + target
                + " land=" + landscape + " blur=" + blurDp);
        loadBitmapAndDisplay(target);
    }

    private void loadBitmapAndDisplay(String file) {
        if (file == null || file.isEmpty()) {
            image.setImageDrawable(null);
            ambient.setImageDrawable(null);
            current = null;
            refreshVisibility();
            return;
        }
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
        applyBlur(currentBlurDp);
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
