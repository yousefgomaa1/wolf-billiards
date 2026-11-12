package com.wolfbilliards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Transparent diagnostic layer for live WOLF screen analysis. Coordinates are screen pixels. */
public final class WolfVisionOverlayView extends View {
    public static final class Detection {
        public final float x, y, radius;
        public Detection(float x, float y, float radius) {
            this.x = x; this.y = y; this.radius = radius;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Detection> balls = new ArrayList<>();
    private RectF table;
    private String status = "WOLF: waiting for game frame";

    public WolfVisionOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    public synchronized void update(RectF tableBounds, List<Detection> detected, String message) {
        table = tableBounds == null ? null : new RectF(tableBounds);
        balls.clear();
        if (detected != null) balls.addAll(detected);
        status = message == null ? "WOLF: analyzing" : message;
        postInvalidate();
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (table != null) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(Color.rgb(0, 230, 160));
            canvas.drawRect(table, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f);
        paint.setColor(Color.rgb(255, 210, 70));
        for (Detection ball : balls) {
            canvas.drawCircle(ball.x, ball.y, ball.radius, paint);
            canvas.drawLine(ball.x - 5, ball.y, ball.x + 5, ball.y, paint);
            canvas.drawLine(ball.x, ball.y - 5, ball.x, ball.y + 5, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(28f);
        paint.setShadowLayer(4f, 1f, 1f, Color.BLACK);
        paint.setColor(Color.WHITE);
        canvas.drawText(status, 24f, 44f, paint);
        paint.clearShadowLayer();
    }
}
