package com.wolfbilliards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Passive, full-screen visualization of detections from live MediaProjection frames. */
final class AutoAimOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private WolfVisionAnalyzer.Result result;
    private int sourceWidth, sourceHeight;

    AutoAimOverlayView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    void setAnalysis(WolfVisionAnalyzer.Result next, int width, int height) {
        result = next;
        sourceWidth = width;
        sourceHeight = height;
        postInvalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        WolfVisionAnalyzer.Result r = result;
        if (r == null || !r.tableFound || sourceWidth <= 0 || sourceHeight <= 0) return;

        float sx = getWidth() / (float) sourceWidth;
        float sy = getHeight() / (float) sourceHeight;
        float l = r.tableLeft * sx, t = r.tableTop * sy;
        float right = r.tableRight * sx, bottom = r.tableBottom * sy;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * getResources().getDisplayMetrics().density);
        paint.setColor(0xE6FFD45C);
        canvas.drawRoundRect(new RectF(l, t, right, bottom), 12f, 12f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6FFFFFF);
        for (int i = 0; i < r.ballCount; i++) {
            float x = r.ballX[i] * sx, y = r.ballY[i] * sy;
            canvas.drawCircle(x, y, 9f * getResources().getDisplayMetrics().density, paint);
            paint.setColor(0xFF16C784);
            canvas.drawCircle(x, y, 5f * getResources().getDisplayMetrics().density, paint);
            paint.setColor(0xE6FFFFFF);
        }
    }
}
