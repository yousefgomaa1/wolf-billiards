package com.wolfbilliards.app;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * First live-frame vision stage.
 * Finds the dominant felt region and circular high-contrast ball candidates.
 * This is deliberately conservative: it reports confidence/counts and does not
 * invent cue direction or claim a complete 8-ball object detector.
 */
final class WolfVisionAnalyzer {
    static final class Result {
        final int tableLeft, tableTop, tableRight, tableBottom;
        final int ballCount;
        final boolean tableFound;

        Result(int left, int top, int right, int bottom, int count, boolean found) {
            tableLeft = left; tableTop = top; tableRight = right; tableBottom = bottom;
            ballCount = count; tableFound = found;
        }

        String describe() {
            if (!tableFound) return "تحليل مباشر: جاري البحث عن الطاولة";
            return "الطاولة مكتشفة · كرات مرشحة: " + ballCount;
        }
    }

    private WolfVisionAnalyzer() {}

    static Result analyze(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return new Result(0, 0, 0, 0, 0, false);
        final int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width < 100 || height < 100) return new Result(0, 0, 0, 0, 0, false);

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

        if (feltPixels < (width * height) / 100 || right <= left || bottom <= top) {
            return new Result(0, 0, 0, 0, 0, false);
        }

        int padX = Math.max(2, (right - left) / 100);
        int padY = Math.max(2, (bottom - top) / 100);
        left = Math.max(0, left - padX);
        right = Math.min(width - 1, right + padX);
        top = Math.max(0, top - padY);
        bottom = Math.min(height - 1, bottom + padY);

        int tableWidth = right - left;
        int radius = Math.max(5, Math.min(18, tableWidth / 55));
        int step = Math.max(3, radius / 2);
        int candidates = 0;
        int lastX = -10000, lastY = -10000;

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
                    int sx = Math.max(0, Math.min(width - 1,
                        x + (int) Math.round(Math.cos(angle) * radius * 1.35)));
                    int sy = Math.max(0, Math.min(height - 1,
                        y + (int) Math.round(Math.sin(angle) * radius * 1.35)));
                    int pixel = bitmap.getPixel(sx, sy);
                    if (isFelt(pixel)) feltRing++;
                }
                if (feltRing >= 8) {
                    candidates++;
                    lastX = x; lastY = y;
                    if (candidates >= 16) break;
                }
            }
            if (candidates >= 16) break;
        }
        return new Result(left, top, right, bottom, candidates, true);
    }

    private static boolean isFelt(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[1] > 0.22f && hsv[2] > 0.12f &&
            ((hsv[0] >= 68f && hsv[0] <= 175f) || (hsv[0] >= 180f && hsv[0] <= 255f));
    }

    private static boolean looksLikeBallCenter(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[2] > 0.48f && (hsv[1] < 0.88f || hsv[2] > 0.82f);
    }
}
