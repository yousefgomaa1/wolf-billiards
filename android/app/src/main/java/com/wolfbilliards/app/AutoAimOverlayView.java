package com.wolfbilliards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * Passive, full-screen visualization of detections from live MediaProjection frames.
 * Phase 4: when WolfOverlayService delivers a WolfEngineHost.Solution, this view
 * draws the engine paths (ghost ball, aim line, object-ball path, target pocket,
 * predicted cue-ball path). The original pixel-space drawing is kept untouched and
 * continues to work as the fallback whenever no engine solution is available.
 */
final class AutoAimOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private WolfVisionAnalyzer.Result result;
    private int sourceWidth, sourceHeight;
    // المرحلة الرابعة: آخر حل من محرك التصويب (null = الرسم التقليدي fallback)
    private WolfEngineHost.Solution engineSolution;

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

    // ==================================================================
    // المرحلة الرابعة: استلام حل المحرك (null لمسحه والعودة للرسم التقليدي)
    // ==================================================================
    void setEngineResult(WolfEngineHost.Solution solution) {
        engineSolution = solution;
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

        // المرحلة الرابعة: مسارات المحرك — الكرة الشبحية + خط التصويب + مسار
        // الهدف + الجيب + مسار البيضاء بعد التلامس. تُرسم فوق نفس إطار الرؤية
        // الذي حُسبت عليه (لقطة مدمجة في الحل نفسه) — أي أنها مستقلة عن
        // sx/sy الحالية ولا تتأثر بتغير أبعاد الإطار.
        WolfEngineHost.Solution engine = engineSolution;
        boolean engineActive = engine != null && engine.ok && engine.hasContent();
        if (engineActive) {
            drawEngineSolution(canvas, engine, density);
        }

        if (r.cueBallFound) {
            float x = r.cueBallX * sx, y = r.cueBallY * sy;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * density);
            paint.setColor(0xFF36D7FF);
            canvas.drawCircle(x, y, 15f * density, paint);
            canvas.drawLine(x - 20f * density, y, x + 20f * density, y, paint);
            canvas.drawLine(x, y - 20f * density, x, y + 20f * density, paint);

            // Show the estimated shot direction only when the cue-stick scan passed its threshold.
            // (المرحلة الرابعة: هذا المسار التقريبي القديم يعمل فقط حين لا يوجد
            // حل من المحرك — هو الـ fallback الرسمي ويظل كما هو دون أي حذف.)
            if (!engineActive && r.cueDirectionFound) {
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
            // (fallback تقليدي — يُرسم فقط في غياب حل المحرك)
            if (!engineActive && r.pathFound) {
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

    // ==================================================================
    // المرحلة الرابعة: رسم مسارات محرك التصويب (wolf-engine.js)
    // كل الإحداثيات في الحل موحدة (x∈[0,2], y∈[0,1]) وتحوَّل عبر لقطة
    // الإطار المدمجة في الحل ثم تُضرب في مقياس العرض الحالي.
    // ==================================================================

    private void drawEngineSolution(Canvas canvas, WolfEngineHost.Solution engine, float density) {
        float sx = getWidth() / (float) engine.snapshot.sourceWidth;
        float sy = getHeight() / (float) engine.snapshot.sourceHeight;

        // أفضل اقتراحين: الأساسي كامل التفاصيل والثانوي باهت خلفه
        int shown = Math.min(engine.aims.length, 2);
        for (int i = shown - 1; i >= 0; i--) {
            drawEngineAim(canvas, engine, engine.aims[i], i == 0, sx, sy, density);
        }

        // لا اقتراحات متاحة (طاولة مسدودة مثلًا) → أظهر تقييم اتجاه العصا الحالي
        if (shown == 0 && engine.currentShot != null) {
            drawCurrentShot(canvas, engine, engine.currentShot, sx, sy, density);
        }
    }

    /** يرسم اقتراح تصويب واحدًا: خط التصويب + الكرة الشبحية + مسار الهدف + الجيب. */
    private void drawEngineAim(Canvas canvas, WolfEngineHost.Solution engine, WolfEngineHost.Aim aim,
                               boolean primary, float sx, float sy, float density) {
        int alpha = primary ? 255 : 110;

        // نقطة انطلاق البيضاء = أول نقطة في مسار البيضاء الذي حاكاه المحرك
        float startX, startY;
        if (aim.cuePath != null && aim.cuePath.length >= 2) {
            startX = engine.px(aim.cuePath[0]) * sx;
            startY = engine.py(aim.cuePath[1]) * sy;
        } else {
            startX = engine.px(aim.ghostX) * sx;
            startY = engine.py(aim.ghostY) * sy;
        }
        float ghostX = engine.px(aim.ghostX) * sx;
        float ghostY = engine.py(aim.ghostY) * sy;
        float pocketX = engine.px(aim.pocketX) * sx;
        float pocketY = engine.py(aim.pocketY) * sy;

        // 1) مسار الهدف من الكرة نحو الجيب (يشمل الارتدادات المحاكاة)
        //    أخضر = المحاكاة الفيزيائية أكدت إدخال الجيب، برتقالي = احتمالي
        int pathColor = withAlpha(aim.makeable ? 0x16C784 : 0xFFB347, alpha);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((primary ? 3.5f : 2.5f) * density);
        paint.setColor(pathColor);
        drawEnginePath(canvas, engine, aim.objectPath, sx, sy);

        // 2) خط التصويب: من البيضاء إلى الكرة الشبحية
        int aimColor = withAlpha(0x36D7FF, alpha);
        paint.setStrokeWidth((primary ? 4f : 2.5f) * density);
        paint.setColor(aimColor);
        canvas.drawLine(startX, startY, ghostX, ghostY, paint);
        if (primary) {
            drawArrowHead(canvas, startX, startY, ghostX, ghostY, aimColor, 13f * density);
        }

        // 3) الكرة الشبحية: موضع مركز البيضاء لحظة التلامس التام
        float ghostRadius = Math.max(engine.ballRadiusPx() * sx, 6f * density);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFFFFFF, (alpha * 25) / 100));
        canvas.drawCircle(ghostX, ghostY, ghostRadius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f * density);
        paint.setColor(withAlpha(0xFFFFFF, alpha));
        canvas.drawCircle(ghostX, ghostY, ghostRadius, paint);

        // 4) الجيب المستهدف: حلقة + نقطة داخلية
        paint.setStrokeWidth((primary ? 3.5f : 2.5f) * density);
        paint.setColor(pathColor);
        float pocketRadius = Math.max(11f * density, ghostRadius * 0.9f);
        canvas.drawCircle(pocketX, pocketY, pocketRadius, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(pocketX, pocketY, 3.5f * density, paint);

        // تحذير: المحاكاة تتوقع سقوط البيضاء مع الهدف (scratch)
        if (primary && aim.scratch) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * density);
            paint.setColor(0xFFFF5C7A);
            float d = pocketRadius * 0.55f;
            canvas.drawLine(pocketX - d, pocketY - d, pocketX + d, pocketY + d, paint);
            canvas.drawLine(pocketX - d, pocketY + d, pocketX + d, pocketY - d, paint);
        }

        // 5) مسار البيضاء بعد التلامس (متقطع رفيع) — يظهر ارتدادها المتوقع
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setColor(withAlpha(0xFFFFFF, (alpha * 70) / 100));
        paint.setPathEffect(new DashPathEffect(new float[]{10f * density, 9f * density}, 0f));
        drawEnginePath(canvas, engine, aim.cuePath, sx, sy);
        paint.setPathEffect(null);

        // 6) وسم الاقتراح الأساسي: زاوية القطع + الجودة
        if (primary) {
            String label = String.format(Locale.US, "%.1f° · %d%%",
                aim.cutAngleDeg, Math.round(aim.quality * 100f));
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(12f * density);
            paint.setPathEffect(null);
            float textWidth = paint.measureText(label);
            float labelX = Math.min(ghostX + ghostRadius + 4f * density,
                getWidth() - textWidth - 4f * density);
            float labelY = Math.max(ghostY - ghostRadius - 6f * density, 18f * density);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * density);
            paint.setColor(0xE6000000);
            canvas.drawText(label, labelX, labelY, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFD45C);
            canvas.drawText(label, labelX, labelY, paint);
        }
    }

    /** تقييم الضربة الحالية حين لا توجد اقتراحات: مسار البيضاء المتوقع + اتجاه الهدف. */
    private void drawCurrentShot(Canvas canvas, WolfEngineHost.Solution engine,
                                 WolfEngineHost.CurrentShot shot, float sx, float sy, float density) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f * density);
        paint.setColor(0xE636D7FF);
        paint.setPathEffect(new DashPathEffect(new float[]{12f * density, 10f * density}, 0f));
        drawEnginePath(canvas, engine, shot.cuePath, sx, sy);
        paint.setPathEffect(null);

        if (!shot.hasFirstHit || Float.isNaN(shot.contactX) || Float.isNaN(shot.contactY)) return;
        float contactX = engine.px(shot.contactX) * sx;
        float contactY = engine.py(shot.contactY) * sy;

        // نقطة التلامس المتوقعة
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(contactX, contactY, 4f * density, paint);

        // اتجاه انطلاق الكرة الهدف بعد التلامس (الفضاء الموحد متساوي الاتجاه
        // على طاولة 2:1 — يُرسم مباشرة كمقياس موحد على الشاشة)
        if (Math.hypot(shot.objectDirX, shot.objectDirY) > 0.001f) {
            float length = Math.max(90f * density, getWidth() * 0.14f);
            float endX = contactX + shot.objectDirX * length;
            float endY = contactY + shot.objectDirY * length;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.5f * density);
            paint.setColor(0xFFFFB347);
            canvas.drawLine(contactX, contactY, endX, endY, paint);
            drawArrowHead(canvas, contactX, contactY, endX, endY, 0xFFFFB347, 13f * density);
        }
    }

    /** يرسم خطًا متعدد الأجزاء من مصفوفة أزواج [x0,y0,x1,y1,...] موحدة. */
    private void drawEnginePath(Canvas canvas, WolfEngineHost.Solution engine, float[] path,
                                float sx, float sy) {
        if (path == null) return;
        for (int i = 0; i + 3 < path.length; i += 2) {
            canvas.drawLine(engine.px(path[i]) * sx, engine.py(path[i + 1]) * sy,
                engine.px(path[i + 2]) * sx, engine.py(path[i + 3]) * sy, paint);
        }
    }

    /** رأس سهم عند نقطة النهاية باتجاه الخط (start → tip). */
    private void drawArrowHead(Canvas canvas, float startX, float startY,
                               float tipX, float tipY, int color, float size) {
        double angle = Math.atan2(tipY - startY, tipX - startX);
        float a1 = (float) (angle + 2.55f);
        float a2 = (float) (angle - 2.55f);
        paint.setColor(color);
        canvas.drawLine(tipX, tipY, tipX + (float) Math.cos(a1) * size,
            tipY + (float) Math.sin(a1) * size, paint);
        canvas.drawLine(tipX, tipY, tipX + (float) Math.cos(a2) * size,
            tipY + (float) Math.sin(a2) * size, paint);
    }

    /** يركّب لونًا بشفافية محسوبة فوق قناة RGB. */
    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }
}
