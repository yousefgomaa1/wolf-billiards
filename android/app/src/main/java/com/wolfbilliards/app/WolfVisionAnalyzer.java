package com.wolfbilliards.app;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Live-frame vision stage.
 * Finds the felt region and conservative ball candidates, then marks a likely
 * white cue ball only when its center and nearby inner samples are near-white.
 * Cue-stick direction and collision prediction are intentionally not inferred here.
 */
final class WolfVisionAnalyzer {
    static final class Result {
        final int tableLeft, tableTop, tableRight, tableBottom;
        final int ballCount;
        final boolean tableFound;
        final int[] ballX, ballY;
        final boolean cueBallFound;
        final int cueBallX, cueBallY;
        final boolean cueDirectionFound;
        final float aimAngleRadians;
        final boolean pathFound;
        final float pathEndX, pathEndY;
        final boolean hitBallFound;
        final int hitBallX, hitBallY;

        Result(int left, int top, int right, int bottom, int[] xs, int[] ys,
               boolean found, int cueX, int cueY, boolean directionFound, float aimAngle,
               boolean hasPath, float endX, float endY,
               boolean hasHit, int hitX, int hitY) {
            tableLeft = left; tableTop = top; tableRight = right; tableBottom = bottom;
            ballX = xs; ballY = ys; ballCount = xs.length; tableFound = found;
            cueBallFound = cueX >= 0 && cueY >= 0;
            cueBallX = cueX; cueBallY = cueY;
            cueDirectionFound = directionFound;
            aimAngleRadians = aimAngle;
            pathFound = hasPath; pathEndX = endX; pathEndY = endY;
            hitBallFound = hasHit; hitBallX = hitX; hitBallY = hitY;
        }

        String describe() {
            if (!tableFound) return "تحليل مباشر: جاري البحث عن الطاولة";
            return "الطاولة مكتشفة · كرات مرشحة: " + ballCount
                + (cueBallFound ? " · كرة بيضاء محتملة" : " · جاري تحديد الكرة البيضاء")
                + (cueDirectionFound ? " · اتجاه تصويب مبدئي" : " · جاري البحث عن العصاية");
        }
    }

    private WolfVisionAnalyzer() {}

    static Result analyze(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled())
            return emptyResult();
        final int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width < 100 || height < 100) return emptyResult();

        int left = width, top = height, right = -1, bottom = -1, feltPixels = 0;
        int[] row = new int[width];
        // Sample every second pixel to keep CPU use bounded on full-screen frames.
        for (int y = 0; y < height; y += 2) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x += 2) {
                if (!isFelt(row[x])) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
                feltPixels++;
            }
        }

        if (feltPixels < (width * height) / 100 || right <= left || bottom <= top)
            return emptyResult();

        int padX = Math.max(2, (right - left) / 100);
        int padY = Math.max(2, (bottom - top) / 100);
        left = Math.max(0, left - padX);
        right = Math.min(width - 1, right + padX);
        top = Math.max(0, top - padY);
        bottom = Math.min(height - 1, bottom + padY);

        int tableWidth = right - left;
        int radius = Math.max(5, Math.min(18, tableWidth / 55));
        int step = Math.max(3, radius / 2);
        int[] candidateX = new int[16], candidateY = new int[16];
        int candidates = 0;
        int lastX = -10000, lastY = -10000;
        int cueX = -1, cueY = -1, bestCueScore = -1;

        // Detect compact bright/colored centers surrounded by felt.
        for (int y = top + radius * 2; y < bottom - radius * 2; y += step) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = left + radius * 2; x < right - radius * 2; x += step) {
                if (Math.hypot(x - lastX, y - lastY) < radius * 2.1) continue;
                int center = row[x];
                if (!looksLikeBallCenter(center)) continue;

                int feltRing = 0, samples = 12;
                for (int k = 0; k < samples; k++) {
                    double angle = Math.PI * 2.0 * k / samples;
                    int sx = clamp(x + (int) Math.round(Math.cos(angle) * radius * 1.35), 0, width - 1);
                    int sy = clamp(y + (int) Math.round(Math.sin(angle) * radius * 1.35), 0, height - 1);
                    if (isFelt(bitmap.getPixel(sx, sy))) feltRing++;
                }
                if (feltRing >= 8) {
                    if (candidates < candidateX.length) {
                        candidateX[candidates] = x;
                        candidateY[candidates] = y;
                        candidates++;
                    }
                    if (hasWhiteCore(bitmap, x, y, radius)) {
                        int score = whiteScore(center);
                        if (score > bestCueScore) {
                            bestCueScore = score;
                            cueX = x;
                            cueY = y;
                        }
                    }
                    lastX = x; lastY = y;
                    if (candidates >= 16) break;
                }
            }
            if (candidates >= 16) break;
        }
        int[] xs = new int[candidates], ys = new int[candidates];
        System.arraycopy(candidateX, 0, xs, 0, candidates);
        System.arraycopy(candidateY, 0, ys, 0, candidates);
        float aimAngle = Float.NaN;
        boolean directionFound = false;
        if (cueX >= 0 && cueY >= 0) {
            float stickAngle = findCueStickAngle(bitmap, cueX, cueY, radius);
            if (!Float.isNaN(stickAngle)) {
                // The cue extends behind the cue ball; the shot travels in the opposite direction.
                aimAngle = (float) (stickAngle + Math.PI);
                directionFound = true;
            }
        }
        RayResult ray = directionFound
            ? traceFirstCollision(left, top, right, bottom, xs, ys,
                cueX, cueY, aimAngle, radius)
            : RayResult.none();
        return new Result(left, top, right, bottom, xs, ys, true,
            cueX, cueY, directionFound, aimAngle,
            ray.found, ray.endX, ray.endY, ray.hitBall, ray.hitX, ray.hitY);
    }

    private static Result emptyResult() {
        return new Result(0, 0, 0, 0, new int[0], new int[0],
            false, -1, -1, false, Float.NaN, false, 0f, 0f, false, -1, -1);
    }

    private static boolean hasWhiteCore(Bitmap bitmap, int x, int y, int radius) {
        int offset = Math.max(1, radius / 3);
        int[][] points = {
            {x, y}, {x - offset, y}, {x + offset, y},
            {x, y - offset}, {x, y + offset}
        };
        int whiteSamples = 0;
        for (int[] point : points) {
            int color = bitmap.getPixel(
                clamp(point[0], 0, bitmap.getWidth() - 1),
                clamp(point[1], 0, bitmap.getHeight() - 1));
            int min = Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
            int max = Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)));
            float saturation = max == 0 ? 1f : (max - min) / (float) max;
            if (min >= 155 && saturation <= 0.30f) whiteSamples++;
        }
        return whiteSamples >= 4;
    }

    private static int whiteScore(int color) {
        return Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
    }

    /**
     * Estimates the cue-stick axis by scanning rays outward from the cue ball.
     * The stick is expected behind the cue ball, so callers reverse this angle
     * to obtain the initial shot direction. This is a color heuristic, not ML.
     */
    private static final class RayResult {
        final boolean found, hitBall;
        final float endX, endY;
        final int hitX, hitY;
        RayResult(boolean found, float endX, float endY,
                  boolean hitBall, int hitX, int hitY) {
            this.found = found; this.endX = endX; this.endY = endY;
            this.hitBall = hitBall; this.hitX = hitX; this.hitY = hitY;
        }
        static RayResult none() {
            return new RayResult(false, 0f, 0f, false, -1, -1);
        }
    }

    /**
     * Traces a straight first segment from the cue ball to the nearest object:
     * another detected ball or the table cushion. This is geometric guidance,
     * not a full spin/energy/friction physics simulation.
     */
    private static RayResult traceFirstCollision(int left, int top, int right, int bottom,
            int[] xs, int[] ys, int cueX, int cueY, float angle, int radius) {
        double dx = Math.cos(angle), dy = Math.sin(angle);
        double bestDistance = Double.POSITIVE_INFINITY;
        boolean hitBall = false;
        int hitX = -1, hitY = -1;
        double ballDiameter = radius * 2.0;

        // Ray-circle intersections: first contact with each other ball.
        for (int i = 0; i < xs.length; i++) {
            if (Math.hypot(xs[i] - cueX, ys[i] - cueY) < radius * 1.5) continue;
            double ox = cueX - xs[i], oy = cueY - ys[i];
            double b = 2.0 * (ox * dx + oy * dy);
            double cc = ox * ox + oy * oy - ballDiameter * ballDiameter;
            double discriminant = b * b - 4.0 * cc;
            if (discriminant < 0.0) continue;
            double distance = (-b - Math.sqrt(discriminant)) / 2.0;
            if (distance > radius && distance < bestDistance) {
                bestDistance = distance;
                hitBall = true;
                hitX = xs[i]; hitY = ys[i];
            }
        }

        // Find the first cushion boundary reached by the cue-ball center.
        double cushionDistance = Double.POSITIVE_INFINITY;
        if (dx > 0.0001) cushionDistance = Math.min(cushionDistance, (right - radius - cueX) / dx);
        else if (dx < -0.0001) cushionDistance = Math.min(cushionDistance, (left + radius - cueX) / dx);
        if (dy > 0.0001) cushionDistance = Math.min(cushionDistance, (bottom - radius - cueY) / dy);
        else if (dy < -0.0001) cushionDistance = Math.min(cushionDistance, (top + radius - cueY) / dy);
        if (cushionDistance > radius && cushionDistance < bestDistance) {
            bestDistance = cushionDistance;
            hitBall = false;
            hitX = -1; hitY = -1;
        }

        if (!Double.isFinite(bestDistance) || bestDistance <= 0.0) return RayResult.none();
        return new RayResult(true,
            (float) (cueX + dx * bestDistance),
            (float) (cueY + dy * bestDistance),
            hitBall, hitX, hitY);
    }

    private static float findCueStickAngle(Bitmap bitmap, int cueX, int cueY, int radius) {
        final int angleSteps = 72; // 5-degree angular resolution
        final int samples = 24;
        final double start = radius * 1.8;
        final double end = radius * 13.0;
        float bestAngle = Float.NaN;
        double bestScore = 0.0;

        for (int a = 0; a < angleSteps; a++) {
            double angle = Math.PI * 2.0 * a / angleSteps;
            double dx = Math.cos(angle), dy = Math.sin(angle);
            int hits = 0, longestRun = 0, run = 0;
            for (int i = 0; i < samples; i++) {
                double distance = start + (end - start) * i / (samples - 1);
                int x = (int) Math.round(cueX + dx * distance);
                int y = (int) Math.round(cueY + dy * distance);
                if (x < 2 || y < 2 || x >= bitmap.getWidth() - 2 || y >= bitmap.getHeight() - 2) {
                    run = 0;
                    continue;
                }
                int px = bitmap.getPixel(x, y);
                boolean stickPixel = isCueLikeColor(px);
                // A narrow cue may be offset by a pixel due to antialiasing or scaling.
                if (!stickPixel) {
                    int ox = (int) Math.round(-dy * 1.5);
                    int oy = (int) Math.round(dx * 1.5);
                    stickPixel = isCueLikeColor(bitmap.getPixel(x + ox, y + oy))
                        || isCueLikeColor(bitmap.getPixel(x - ox, y - oy));
                }
                if (stickPixel) {
                    hits++;
                    run++;
                    if (run > longestRun) longestRun = run;
                } else {
                    run = 0;
                }
            }
            double score = hits + longestRun * 0.65;
            if (hits >= 10 && longestRun >= 5 && score > bestScore) {
                bestScore = score;
                bestAngle = (float) angle;
            }
        }
        return bestAngle;
    }

    private static boolean isCueLikeColor(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max < 48 || max == min) return false;
        float hue;
        if (max == r) hue = 60f * (((g - b) / (float) (max - min)) % 6f);
        else if (max == g) hue = 60f * (((b - r) / (float) (max - min)) + 2f);
        else hue = 60f * (((r - g) / (float) (max - min)) + 4f);
        if (hue < 0f) hue += 360f;
        float saturation = (max - min) / (float) max;
        // Covers common wood/gold cue shafts; deliberately conservative.
        return hue >= 8f && hue <= 48f && saturation >= 0.22f;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isFelt(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float saturation = max == 0 ? 0f : (max - min) / (float) max;
        if (saturation <= 0.22f || max / 255f <= 0.12f) return false;
        float hue;
        if (max == min) hue = 0f;
        else if (max == r) hue = 60f * (((g - b) / (float) (max - min)) % 6f);
        else if (max == g) hue = 60f * (((b - r) / (float) (max - min)) + 2f);
        else hue = 60f * (((r - g) / (float) (max - min)) + 4f);
        if (hue < 0f) hue += 360f;
        return (hue >= 68f && hue <= 175f) || (hue >= 180f && hue <= 255f);
    }

    private static boolean looksLikeBallCenter(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float saturation = max == 0 ? 0f : (max - min) / (float) max;
        float value = max / 255f;
        return value > 0.48f && (saturation < 0.88f || value > 0.82f);
    }
}
