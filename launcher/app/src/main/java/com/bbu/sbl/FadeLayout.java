package com.bbu.sbl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Draws its children and fades them out towards the left edge, so a picture or a muted video on the
 * right of the hero melts into the screen behind it instead of ending in a hard edge.
 */
public class FadeLayout extends FrameLayout {
    private final Paint mask = new Paint();
    private final Paint vertical = new Paint();

    public FadeLayout(Context context) {
        this(context, null);
    }

    public FadeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        vertical.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mask.setShader(new LinearGradient(0, 0, w, 0,
                new int[]{0x00000000, 0x66000000, 0xFF000000, 0xFF000000},
                new float[]{0f, 0.22f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        // A soft top and bottom edge too, so the picture floats instead of ending in a hard line.
        vertical.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[]{0f, 0.14f, 0.86f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        super.dispatchDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), mask);
        canvas.drawRect(0, 0, getWidth(), getHeight(), vertical);
        canvas.restoreToCount(save);
    }
}
