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
        float density = getResources().getDisplayMetrics().density;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * density);
        paint.setColor(0xE6FFD45C);
        canvas.drawRoundRect(new RectF(l, t, right, bottom), 12f, 12f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6FFFFFF);
        for (int i = 0; i < r.ballCount; i++) {
            float x = r.ballX[i] * sx, y = r.ballY[i] * sy;
            canvas.drawCircle(x, y, 9f * density, paint);
            paint.setColor(0xFF16C784);
            canvas.drawCircle(x, y, 5f * density, paint);
            paint.setColor(0xE6FFFFFF);
        }

        // A distinct cyan target marks a probable cue ball, not a verified identity.
        if (r.cueBallFound) {
            float x = r.cueBallX * sx, y = r.cueBallY * sy;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * density);
            paint.setColor(0xFF36D7FF);
            canvas.drawCircle(x, y, 15f * density, paint);
            canvas.drawLine(x - 20f * density, y, x + 20f * density, y, paint);
            canvas.drawLine(x, y - 20f * density, x, y + 20f * density, paint);

            // Show the estimated shot direction only when the cue-stick scan passed its threshold.
            if (r.cueDirectionFound) {
                float length = Math.max(90f * density, (right - l) * 0.22f);
                float endX = x + (float) Math.cos(r.aimAngleRadians) * length;
                float endY = y + (float) Math.sin(r.aimAngleRadians) * length;
                paint.setStrokeWidth(4f * density);
                paint.setColor(0xFF36D7FF);
                canvas.drawLine(x, y, endX, endY, paint);
                float head = 13f * density;
                float a1 = r.aimAngleRadians + 2.55f;
                float a2 = r.aimAngleRadians - 2.55f;
                canvas.drawLine(endX, endY,
                    endX + (float) Math.cos(a1) * head,
                    endY + (float) Math.sin(a1) * head, paint);
                canvas.drawLine(endX, endY,
                    endX + (float) Math.cos(a2) * head,
                    endY + (float) Math.sin(a2) * head, paint);
            }

            // The first predicted contact is drawn separately from the aim direction.
            if (r.pathFound) {
                float px = r.pathEndX * sx, py = r.pathEndY * sy;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f * density);
                paint.setColor(r.hitBallFound ? 0xFFFFB347 : 0xFF36D7FF);
                canvas.drawLine(x, y, px, py, paint);
                canvas.drawCircle(px, py, 10f * density, paint);
                if (r.hitBallFound) {
                    float tx = r.hitBallX * sx, ty = r.hitBallY * sy;
                    paint.setColor(0xFFFFB347);
                    canvas.drawCircle(tx, ty, 13f * density, paint);
                    // Predicted initial travel of the object ball after contact.
                    if (r.targetPathFound) {
                        float objectEndX = r.targetPathEndX * sx;
                        float objectEndY = r.targetPathEndY * sy;
                        paint.setColor(0xFFFFD166);
                        paint.setStrokeWidth(4f * density);
                        canvas.drawLine(tx, ty, objectEndX, objectEndY, paint);
                        canvas.drawCircle(objectEndX, objectEndY, 8f * density, paint);
                        if (r.targetBounceFound) {
                            float bounceX = r.targetBounceEndX * sx;
                            float bounceY = r.targetBounceEndY * sy;
                            canvas.drawLine(objectEndX, objectEndY, bounceX, bounceY, paint);
                            canvas.drawCircle(bounceX, bounceY, 7f * density, paint);
                        }
                    }
                }
            }
        }
    }
}
