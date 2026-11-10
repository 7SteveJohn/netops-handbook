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
public class NativeBackdrop extends FrameLayout implements ThemeBus.DarkAware {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(8);
    /** 预模糊结果（file|blurDp → 已糊位图，~0.65MB/张）。 */
    private static final LruCache<String, Bitmap> BLURRED = new LruCache<>(4);

    /** 预模糊后的显示位图宽度：屏幕宽度的 1/6 左右。位图越小，一次模糊越便宜；
     *  放大回屏幕由 ImageView 的双线性插值补足（本来就要糊，放大不丢信息）。 */
    private static final int BLUR_TARGET_W = 320;

    private final ImageView ambient;   // 横屏环境层
    private final ImageView image;     // 主壁纸：统一 CENTER_CROP 满画幅显示
    private Bitmap sharpBitmap;        // 原图（玻璃件采样源；显示用的是糊图）
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

    /** 供外部玻璃件（LensBarView 等）直接采样当前壁纸原图，避免 PixelCopy 动态截屏开销与穿透问题。
     *  ❗返回**原图**不是糊图：玻璃件自己还要再模糊一次，喂糊图等于糊两遍（2026-11-11）。 */
    public Bitmap getCurrentBitmap() {
        return sharpBitmap;
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
            sharpBitmap = null;
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
            sharpBitmap = null;
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
        sharpBitmap = bmp;
        Bitmap shown = blurredOf(file, bmp, currentBlurDp);
        image.setImageBitmap(shown);
        ambient.setImageBitmap(shown);
        applyTint();
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

    private boolean dark = false;

    /** 深色时壁纸压暗（brightness 0.36）：原生 direct 模式下壁纸由本层画，
     *  HTML 的 .wall 滤镜管不到（2026-11-08「深色半成品」壁纸亮根因）。 */
    /* ThemeBus 订阅入口：只换通知来源，setDark 逻辑原样。 */
    @Override public void onThemeChanged(boolean dark) { setDark(dark); }

    public void setDark(boolean d) {
        if (dark == d) return;
        dark = d;
        applyTint();
    }

    /** 深色压暗改用 ColorFilter（同样的 0.36 颜色矩阵），不再挂 RenderEffect。
     *  ❗原实现给全屏壁纸挂 RenderEffect 模糊链：换壁纸时要逐帧重算 1904×3040 的
     *  整层模糊，实测 16 帧 × 101ms（约 1.6s 卡顿，2026-11-11 实测）。改成一次性
     *  预模糊位图后，换壁纸只剩换图 + 重绘。 */
    private void applyTint() {
        if (dark) {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setScale(0.36f, 0.36f, 0.36f, 1f);
            android.graphics.ColorMatrixColorFilter f = new android.graphics.ColorMatrixColorFilter(cm);
            image.setColorFilter(f);
            ambient.setColorFilter(f);
        } else {
            image.setColorFilter(null);
            ambient.setColorFilter(null);
        }
    }

    private Bitmap blurredOf(String file, Bitmap sharp, float blurDp) {
        String key = file + "|" + blurDp;
        Bitmap b = BLURRED.get(key);
        if (b != null) return b;
        b = preBlur(sharp, blurDp);
        BLURRED.put(key, b);
        return b;
    }

    /** 一次性预糊：缩到 BLUR_TARGET_W 宽 → 盒式模糊 → 交给 ImageView 放大。
     *  半径按「屏幕像素模糊量 ÷ 放大倍数」折算，观感与原 RenderEffect 同量级。 */
    private Bitmap preBlur(Bitmap src, float blurDp) {
        int tw = Math.min(BLUR_TARGET_W, src.getWidth());
        int th = Math.max(1, Math.round((float) src.getHeight() * tw / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, tw, th, true);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float scale = Math.max(1f, (float) dm.widthPixels / tw);
        int r = Math.max(1, Math.round(Math.max(0.01f, blurDp) * dm.density / scale));
        boxBlur(small, r, 3);
        return small;
    }

    /** 就地盒式模糊（小图上跑，几毫秒）；passes 次近似高斯。 */
    private static void boxBlur(Bitmap bmp, int r, int passes) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            blurH(px, tmp, w, h, r);
            blurV(tmp, px, w, h, r);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurH(int[] src, int[] dst, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[row + Math.min(w - 1, Math.max(0, i))];
                a += c >>> 24; rr += (c >> 16) & 0xff; gg += (c >> 8) & 0xff; bb += c & 0xff;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = ((int) (a / div) << 24) | ((int) (rr / div) << 16)
                        | ((int) (gg / div) << 8) | (int) (bb / div);
                int add = src[row + Math.min(w - 1, x + r + 1)];
                int sub = src[row + Math.max(0, x - r)];
                a += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xff) - ((sub >> 16) & 0xff);
                gg += ((add >> 8) & 0xff) - ((sub >> 8) & 0xff);
                bb += (add & 0xff) - (sub & 0xff);
            }
        }
    }

    private static void blurV(int[] src, int[] dst, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            long a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[Math.min(h - 1, Math.max(0, i)) * w + x];
                a += c >>> 24; rr += (c >> 16) & 0xff; gg += (c >> 8) & 0xff; bb += c & 0xff;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = ((int) (a / div) << 24) | ((int) (rr / div) << 16)
                        | ((int) (gg / div) << 8) | (int) (bb / div);
                int add = src[Math.min(h - 1, y + r + 1) * w + x];
                int sub = src[Math.max(0, y - r) * w + x];
                a += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xff) - ((sub >> 16) & 0xff);
                gg += ((add >> 8) & 0xff) - ((sub >> 8) & 0xff);
                bb += (add & 0xff) - (sub & 0xff);
            }
        }
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
