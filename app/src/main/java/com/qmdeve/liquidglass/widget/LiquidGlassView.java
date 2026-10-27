package com.qmdeve.liquidglass.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.qmdeve.liquidglass.LiquidGlass;
import com.qmdeve.liquidglass.Config;
import com.qmdeve.liquidglass.util.LiquidTracker;
import com.qmdeve.liquidglass.util.Utils;

public class LiquidGlassView extends ViewGroup {

    private LiquidGlass glass;
    private ViewGroup customSource;
    private final Context context;
    private float cornerRadius = Utils.dp2px(getResources(), 40), refractionHeight = Utils.dp2px(getResources(), 20), refractionOffset = -Utils.dp2px(getResources(), 70), tintAlpha = 0.0f, tintColorRed = 1.0f, tintColorGreen = 1.0f, tintColorBlue = 1.0f, blurRadius = 0.01f, dispersion = 0.5f, downX, downY, startTx, startTy;
    private boolean draggableEnabled = false;
    private boolean elasticEnabled = false;
    private boolean touchEffectEnabled = false;
    private Config config;
    private LiquidTracker liquidTracker;

    // Glow effect variables
    private Paint glowPaint;
    private float glowX, glowY;
    private boolean isTouching = false;

    private final Path clipPath = new Path();
    private final RectF clipRect = new RectF();

    public LiquidGlassView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        this.context = context;
        init();
    }

    public LiquidGlassView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        this.context = context;
        init();
    }

    public LiquidGlassView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        this.context = context;
        init();
    }

    private void init() {
        setClipToPadding(false);
        setClipChildren(false);
        liquidTracker = new LiquidTracker(this);

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {

        clipRect.set(0, 0, getWidth(), getHeight());
        clipPath.reset();
        clipPath.addRoundRect(
                clipRect,
                cornerRadius,
                cornerRadius,
                Path.Direction.CW
        );

        int save = canvas.save();
        canvas.clipPath(clipPath);

        super.dispatchDraw(canvas);

        canvas.restoreToCount(save);

        if (touchEffectEnabled && isTouching) {
            canvas.save();
            canvas.clipPath(clipPath);

            float radius = Math.max(getWidth(), getHeight()) * 0.8f;
            int[] colors = {Color.argb(60,255,255,255), Color.TRANSPARENT};
            float[] stops = {0f,1f};

            RadialGradient gradient =
                    new RadialGradient(glowX, glowY, radius, colors, stops, Shader.TileMode.CLAMP);

            glowPaint.setShader(gradient);
            canvas.drawRect(clipRect, glowPaint);

            canvas.restore();
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // Layout all child views
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                // Layout children to fill the entire view
                child.layout(
                        getPaddingLeft(),
                        getPaddingTop(),
                        getPaddingLeft() + child.getMeasuredWidth(),
                        getPaddingTop() + child.getMeasuredHeight()
                );
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Measure all child views
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                measureChild(child, widthMeasureSpec, heightMeasureSpec);
            }
        }
    }

    /**
     * Bind sampling source
     *
     * @param source ViewGroup
     */
    public void bind(ViewGroup source) {
        this.customSource = source;
        if (glass != null && source != null) {
            glass.init(source);
        }
    }

    /**
     * Set the corner radius px
     *
     * @param px float
     */
    public void setCornerRadius(float px) {
        float maxPx = getHeight() > 0 ? getHeight() / 2f : Utils.dp2px(getResources(), 99);
        this.cornerRadius = Math.max(0, Math.min(px, maxPx));
        invalidate();
        updateConfig();
    }

    /**
     * Set the refraction height px
     *
     * @param px float
     */
    public void setRefractionHeight(float px) {
        float minPx = Utils.dp2px(getResources(), 12);
        float maxPx = Utils.dp2px(getResources(), 50);
        this.refractionHeight = Math.max(minPx, Math.min(maxPx, px));
        updateConfig();
    }

    /**
     * Set the refraction offset px
     * Positive value will be converted to negative
     *
     * @param px float
     */
    public void setRefractionOffset(float px) {
        float minPx = Utils.dp2px(getResources(), 20);
        float maxPx = Utils.dp2px(getResources(), 120);
        px = Math.max(minPx, Math.min(maxPx, px));
        this.refractionOffset = -px;
        updateConfig();
    }

    /**
     * Set the tint color (R)
     *
     * @param red float (0f-1f)
     */
    public void setTintColorRed(float red) {
        this.tintColorRed = red;
        updateConfig();
    }

    /**
     * Set the tint color (G)
     *
     * @param green float (0f-1f)
     */
    public void setTintColorGreen(float green) {
        this.tintColorGreen = green;
        updateConfig();
    }

    /**
     * Set the tint color (B)
     *
     * @param blue float (0f-1f)
     */
    public void setTintColorBlue(float blue) {
        this.tintColorBlue = blue;
        updateConfig();
    }

    /**
     * Set the tint Alpha
     *
     * @param alpha float (0f-1f)
     */
}
