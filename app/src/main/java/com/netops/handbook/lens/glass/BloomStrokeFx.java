package com.netops.handbook.lens.glass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RuntimeShader;

/**
 * BloomStroke 双光源高光的绘制助手（miuix-blur HighlightStyle/HighlightDrawing
 * 的 Java 直译，Apache-2.0）。LensBarView（底栏）与 AppBarView（顶栏）共用，
 * shader 原文见 {@link LiquidGlassShaders#BLOOM_STROKE_DUAL}。
 *
 * 坐标取当前画布空间；cornerRadii 顺序 = [TL, TR, BL, BR]（pickRadius 约定，
 * 四角同值时无歧义）。合成用 BlendMode.PLUS（调用方 Paint 需 ADD xfermode）。
 */
public final class BloomStrokeFx {
    private BloomStrokeFx() {
    }

    private static final float LIGHT_REF_X = 0.5f, LIGHT_REF_Y = 0.7f;
    private static final float GRAVITY_DIR_THRESHOLD_SQ = 0.01f;          // |g_xy| > 0.1，≈ 6° 倾斜
    private static final float GRAVITY_ANGLE_STEP_RAD = (float) (Math.PI * 3.0 / 180.0);

    /** rememberQuantizedGravityAngle 原式：|g|>0.1 → atan2(gy,gx) 按 3° 量化；否则 −90°。 */
    public static float gravityLightAngle(float gravityX, float gravityY) {
        float magSq = gravityX * gravityX + gravityY * gravityY;
        if (magSq > GRAVITY_DIR_THRESHOLD_SQ) {
            float raw = (float) (Math.atan2(gravityY, gravityX) / GRAVITY_ANGLE_STEP_RAD);
            return (float) Math.round(raw) * GRAVITY_ANGLE_STEP_RAD;
        }
        return (float) (-Math.PI / 2);
    }

    /** applyLightUniforms 原式：pos(UV) − 参照点(0.5,0.7) 归一化成 3D 方向。 */
    public static void applyLight(RuntimeShader shader, String suffix,
                                  float px, float py, float pz, float intensity) {
        float dx = px - LIGHT_REF_X, dy = py - LIGHT_REF_Y, dz = pz;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) len = 1e-6f;
        shader.setFloatUniform("lightDir" + suffix, dx / len, dy / len, dz / len);
        shader.setColorUniform("lightColor" + suffix, Color.WHITE);
        shader.setFloatUniform("lightIntensity" + suffix, intensity);
    }

    /**
     * 画一圈 BloomStroke 高光（iosIndicatorSpecular 原值：stroke 白 0.12、
     * innerBlur 2dp、主光强 1 / 副光强 0.4；主光方位 = 重力角 + extraDegrees）。
     * alpha 为整体高光强度（底栏 0.75 / 药丸 = lift）。
     */
    public static void draw(RuntimeShader shader, Paint paint, Canvas canvas,
                            float l, float t, float r, float b,
                            float radius, float alpha, float extraDegrees,
                            float gravityX, float gravityY, float density) {
        if (alpha <= 0.01f) return;
        float halfW = (l + r) * 0.5f, halfH = (t + b) * 0.5f;
        float innerR = 2f * density;
        float rad = gravityLightAngle(gravityX, gravityY) + extraDegrees * ((float) Math.PI / 180f);
        shader.setFloatUniform("halfView", halfW, halfH);
        shader.setFloatUniform("halfViewFloor", (float) Math.floor(halfW), (float) Math.floor(halfH));
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        shader.setFloatUniform("strokeWidth", density);   // Highlight width 1dp
        shader.setFloatUniform("innerBlurRadius", innerR);
        shader.setFloatUniform("innerBlurRadiusSq", innerR * innerR);
        shader.setFloatUniform("highlightAlpha", alpha);
        shader.setColorUniform("strokeColor", Color.WHITE);
        shader.setFloatUniform("strokeAlphaMul", 0.12f);
        applyLight(shader, "1",
                LIGHT_REF_X + (float) Math.cos(rad), LIGHT_REF_Y + (float) Math.sin(rad), -0.05f, 1f);
        applyLight(shader, "2", 0.5f, 0.8f, -0.5f, 0.4f);
        paint.setShader(shader);
        canvas.drawRect(l, t, r, b, paint);
        paint.setShader(null);
    }
}
