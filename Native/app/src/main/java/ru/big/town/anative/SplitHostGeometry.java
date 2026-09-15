package ru.big.town.anative;

/** Pure split geometry shared by initial presets and interactive divider clamping. */
final class SplitHostGeometry {
    private static final float FALLBACK_MIN = 0.05f;
    private static final float FALLBACK_MAX = 0.95f;

    private SplitHostGeometry() {}

    static float presetFraction(int ratio) {
        switch (ratio) {
            case 0: return 3f / 7f;
            case 2: return 4f / 7f;
            case 3: return 5f / 7f;
            case 4: return 2f / 7f;
            default: return 0.5f;
        }
    }

    static float clampFraction(float value, int usablePixels, int leftDpi, int rightDpi,
                               float minimumPaneDp) {
        float fraction = Float.isFinite(value) ? value : 0.5f;
        if (usablePixels <= 0) return clamp(fraction, FALLBACK_MIN, FALLBACK_MAX);
        float leftMin = minimumPaneDp * Math.max(1, leftDpi) / 160f / usablePixels;
        float rightMin = minimumPaneDp * Math.max(1, rightDpi) / 160f / usablePixels;
        float sum = leftMin + rightMin;
        if (sum > 0.90f) {
            float scale = 0.90f / sum;
            leftMin *= scale;
            rightMin *= scale;
        }
        return clamp(fraction, leftMin, 1f - rightMin);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
