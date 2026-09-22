package ru.big.town.anative;

/** Geometry verified on the H97C OEM Cluster-Media-Display. Unknown panels stay untouched. */
final class ClusterSurfaceGeometry {
    static final class Bounds {
        final int left;
        final int top;
        final int width;
        final int height;
        final int dpi;

        Bounds(int left, int top, int width, int height, int dpi) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
        }
    }

    private ClusterSurfaceGeometry() {}

    static Bounds forDisplay(int width, int height) {
        if (width == 1920 && height == 720) {
            return new Bounds(66, 200, 574, 464, 160);
        }
        return null;
    }
}
