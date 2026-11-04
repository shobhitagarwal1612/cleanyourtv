package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a faint outer glow.
 * Combines SpringAnimation focus scale with a 3.5° D-pad perspective card tilt and dynamically
 * slides the top specular rim gradient across the glass edge in the direction pressed so the
 * card looks like real refracting glass catching overhead light.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint catchLight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix rimMatrix = new Matrix();
    private final Matrix specMatrix = new Matrix();
    private final Matrix catchMatrix = new Matrix();
    private Shader rimShader;
    private final Shader specShader = new LinearGradient(
            0f, 0f, 0f, 1f, 0x6AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private final Shader catchShader = new RadialGradient(
            0f, 0f, 1f,
            new int[]{0xE0FFFFFF, 0x28FFFFFF, 0x00FFFFFF},
            new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: white normally, a festival's accent (diya gold) while a festive skin is on. */
    private int glowRgb = 0xFFFFFF;
    /** Glow strength: the white glow stays faint, a coloured one needs more alpha to read as warm light. */
    private int glowAlpha = 0x38;
    /** glass_style rim or halo: no sheen and no fill, only the crisp rim (and, for halo, a lighter outer glow). */
    private boolean flat;
    private boolean halo;
    /** The rim's colour at the top, where the light catches it; the rim shader fades it downwards. */
    private int rimColor = 0xF2FFFFFF;
    /** Share of the slab's height the top sheen covers. */
    private static final float SHEEN = 0.42f;
    /** Max 3D perspective tilt angle in degrees on D-pad impulse. */
    static final float TILT_DEG = 3.5f;

    /** Dynamic specular light-shift (-1 = left edge, 0 = center, +1 = right edge). */
    private float lightShiftX = 0f;
    private float lightShiftY = 0f;

    private final SpringAnimation springRotX;
    private final SpringAnimation springRotY;
    private final SpringAnimation springScaleX;
    private final SpringAnimation springScaleY;
    private final SpringAnimation springLightX;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        setCameraDistance(8000f * density);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(rimColor);
        catchLight.setStyle(Paint.Style.STROKE);
        catchLight.setStrokeWidth(2.2f * density);
        glow.setStyle(Paint.Style.STROKE);
        specular.setShader(specShader);
        rebuildRimShader();
        setLayerType(LAYER_TYPE_HARDWARE, null);

        springRotX = new SpringAnimation(this, SpringAnimation.ROTATION_X, 0f);
        springRotX.getSpring().setStiffness(300f).setDampingRatio(0.58f);

        springRotY = new SpringAnimation(this, SpringAnimation.ROTATION_Y, 0f);
        springRotY.getSpring().setStiffness(300f).setDampingRatio(0.58f);

        springScaleX = new SpringAnimation(this, SpringAnimation.SCALE_X, 1f);
        springScaleX.getSpring().setStiffness(380f).setDampingRatio(0.65f);

        springScaleY = new SpringAnimation(this, SpringAnimation.SCALE_Y, 1f);
        springScaleY.getSpring().setStiffness(380f).setDampingRatio(0.65f);

        springLightX = new SpringAnimation(this, new FloatPropertyCompat<GlassView>("lightShiftX") {
            @Override
            public float getValue(GlassView v) {
                return v.lightShiftX * 100f;
            }

            @Override
            public void setValue(GlassView v, float value) {
                v.lightShiftX = value * 0.01f;
                v.updateDynamicLightShaders();
                v.invalidate();
            }
        }, 0f);
        springLightX.getSpring().setStiffness(220f).setDampingRatio(0.62f);
    }

    private void rebuildRimShader() {
        int bottom = (Math.round((rimColor >>> 24) * 0.38f) << 24) | (rimColor & 0xFFFFFF);
        rimShader = new LinearGradient(
                0f, 0f, 0f, 1f,
                new int[]{rimColor, bottom, bottom},
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP);
    }

    /**
     * Triggers the 3.5° D-pad perspective tilt, spring scale micro-pulse, and dynamic top specular
     * rim light-shift in the pressed direction (dx in [-1, 1], dy in [-1, 1]).
     */
    void impulseLight(float dx, float dy, boolean boundaryBump) {
        float tiltMult = boundaryBump ? 1.35f : 1.0f;
        springRotY.cancel();
        springRotX.cancel();
        setRotationY(dx * TILT_DEG * tiltMult);
        setRotationX(-dy * TILT_DEG * tiltMult);
        springRotY.animateToFinalPosition(0f);
        springRotX.animateToFinalPosition(0f);

        springScaleX.cancel();
        springScaleY.cancel();
        float startScale = boundaryBump ? 0.965f : 1.032f;
        setScaleX(startScale);
        setScaleY(startScale);
        springScaleX.animateToFinalPosition(1f);
        springScaleY.animateToFinalPosition(1f);

        springLightX.cancel();
        lightShiftX = dx != 0f ? dx : (dy * 0.5f);
        lightShiftY = dy;
        updateDynamicLightShaders();
        invalidate();
        springLightX.animateToFinalPosition(0f);
    }

    /** Tints the rim and the outer glow; 0 restores the plain white glass. */
    void setAccent(int argb) {
        if (argb == 0) {
            glowRgb = 0xFFFFFF;
            glowAlpha = 0x38;
            rimColor = 0xF2FFFFFF;
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x78;
            // The rim is the accent lifted towards white, so the edge still reads as glass, not paint.
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3, g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3,
                    b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            rimColor = 0xF2000000 | (r << 16) | (g << 8) | b;
        }
        rim.setColor(rimColor);
        rebuildRimShader();
        updateDynamicLightShaders();
        invalidate();
    }

    /** One of Config's GLASS_* styles. */
    void setStyle(String style) {
        flat = Config.GLASS_RIM.equals(style) || Config.GLASS_HALO.equals(style);
        halo = Config.GLASS_HALO.equals(style);
        rim.setStrokeWidth((flat ? 2f : 1.5f) * density);
        updateDynamicLightShaders();
        invalidate();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    /**
     * Updates the rim shader, top sheen shader, and traveling overhead catch-light hotspot
     * based on the current lightShiftX / lightShiftY refraction angle without allocating per frame.
     */
    private void updateDynamicLightShaders() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (flat) {
            rim.setShader(null);
            catchLight.setShader(null);
            return;
        }
        float shiftPx = lightShiftX * w * 0.45f;
        rimMatrix.reset();
        rimMatrix.setScale(1f, h);
        rimMatrix.postSkew(-shiftPx * 1.6f / Math.max(1f, h), 0f);
        rimMatrix.postTranslate(w * 0.5f + shiftPx, 0f);
        rimShader.setLocalMatrix(rimMatrix);
        rim.setShader(rimShader);

        float sheenH = Math.max(1f, h * SHEEN);
        specMatrix.reset();
        specMatrix.setScale(1f, sheenH);
        specMatrix.postSkew(-shiftPx * 1.2f / sheenH, 0f);
        specMatrix.postTranslate(w * 0.5f + shiftPx * 0.8f, 0f);
        specShader.setLocalMatrix(specMatrix);

        float hotX = w * (0.5f + lightShiftX * 0.36f);
        float hotY = 6f * density + Math.max(0f, lightShiftY * h * 0.12f);
        float hotR = Math.max(w * 0.48f, 1f);
        int hotAlpha = Math.min(255, Math.round((0.55f + 0.45f * Math.abs(lightShiftX)) * 255f));
        catchMatrix.reset();
        catchMatrix.setScale(hotR, hotR);
        catchMatrix.postTranslate(hotX, hotY);
        catchShader.setLocalMatrix(catchMatrix);
        catchLight.setShader(catchShader);
        catchLight.setAlpha(hotAlpha);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Nearly clear in the middle, so a dark banner (Netflix, YouTube) keeps its depth under the focus.
        fill.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x22FFFFFF, 0x04FFFFFF, 0x0EFFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        updateDynamicLightShaders();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the glow
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Outer glow: thin rings across the inset, each a little fainter, so it fades out smoothly.
        if (!flat || halo) {
            int rings = 12;
            float step = inset / rings;
            int alpha = flat ? glowAlpha / 2 : glowAlpha;
            glow.setStrokeWidth(step + 0.5f);
            for (int i = 0; i < rings; i++) {
                float o = step * (i + 0.5f);
                float t = 1f - (float) i / rings;
                glow.setColor(Math.round(alpha * t) << 24 | glowRgb);
                canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
            }
        }
        if (flat) {
            canvas.drawRoundRect(rect, radius, radius, rim);
            return;
        }

        canvas.drawRoundRect(rect, radius, radius, fill);

        // Sheen across the top 42%: clipRect stays on the GPU and the round rect under it keeps the top corners rounded.
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * SHEEN);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        canvas.drawRoundRect(rect, radius, radius, rim);
        if (catchLight.getShader() != null) {
            canvas.drawRoundRect(rect, radius, radius, catchLight);
        }
    }
}
