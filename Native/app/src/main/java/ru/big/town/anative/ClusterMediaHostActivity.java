package ru.big.town.anative;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Non-interactive one-TextureView host occupying the OEM cluster media display. */
public final class ClusterMediaHostActivity extends Activity implements VirtualDisplayLease.Owner {
    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_SOURCE = "source";
    private static final String TAG = "$$$ ClusterHost $$$";
    // PUBLIC | OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL | TRUSTED on Android 11.
    private static final int VD_FLAGS = 1 | 8 | 256 | 1024;
    private static final int VD_FLAGS_FALLBACK = 1 | 8 | 256;
    private static WeakReference<ClusterMediaHostActivity> active = new WeakReference<>(null);
    private final List<Runnable> closeCallbacks = new ArrayList<>();

    private TextureView texture;
    private VirtualDisplay virtualDisplay;
    private Surface surface;
    private ClusterSurfaceGeometry.Bounds geometry;
    private String packageName;
    private long createdAt;
    private boolean firstFrame;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        createdAt = SystemClock.elapsedRealtime();
        String allowlist = android.provider.Settings.Global.getString(
                getContentResolver(), "voyahtune_cluster_allowed_packages");
        int expectedDisplay = AppLaunchCoordinator.get(this).findClusterDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        if (getDisplay() != null) getDisplay().getRealMetrics(metrics);
        geometry = ClusterSurfaceGeometry.forDisplay(metrics.widthPixels, metrics.heightPixels);
        if (!ClusterLaunchPolicy.allows(allowlist, packageName)
                || expectedDisplay < 0 || getDisplay() == null
                || getDisplay().getDisplayId() != expectedDisplay || geometry == null) {
            Log.w(TAG, "refused package/display/geometry package=" + packageName
                    + " expected=" + expectedDisplay + " size=" + metrics.widthPixels
                    + "x" + metrics.heightPixels);
            finishAndRemoveTask();
            return;
        }
        synchronized (ClusterMediaHostActivity.class) { active = new WeakReference<>(this); }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        texture = new TextureView(this);
        FrameLayout.LayoutParams textureParams = new FrameLayout.LayoutParams(
                geometry.width, geometry.height, Gravity.TOP | Gravity.LEFT);
        textureParams.leftMargin = geometry.left;
        textureParams.topMargin = geometry.top;
        root.addView(texture, textureParams);
        setContentView(root);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                VirtualDisplayLease.acquire(ClusterMediaHostActivity.this,
                        () -> createDisplay(st, width, height));
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                if (virtualDisplay != null && width > 0 && height > 0) {
                    virtualDisplay.resize(geometry.width, geometry.height, geometry.dpi);
                }
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releaseDisplay();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
                if (!firstFrame) {
                    firstFrame = true;
                    VoyahLog.i(TAG, "first-frame-" + packageName,
                            "package=" + packageName + " firstFrameMs="
                                    + (SystemClock.elapsedRealtime() - createdAt));
                }
            }
        });
    }

    @SuppressLint("WrongConstant") // Includes privileged Android 11 TRUSTED display flag (1024).
    private void createDisplay(SurfaceTexture texture, int width, int height) {
        if (isFinishing() || isDestroyed() || geometry == null || width <= 0 || height <= 0) return;
        releaseDisplay();
        surface = new Surface(texture);
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (manager == null) {
            finishAndRemoveTask();
            return;
        }
        try {
            virtualDisplay = manager.createVirtualDisplay("VoyahTune-Cluster-Media",
                    geometry.width, geometry.height, geometry.dpi, surface, VD_FLAGS);
        } catch (RuntimeException trustedFailure) {
            Log.w(TAG, "trusted VD unavailable, fallback: " + trustedFailure.getMessage());
            try {
                virtualDisplay = manager.createVirtualDisplay("VoyahTune-Cluster-Media",
                        geometry.width, geometry.height, geometry.dpi, surface, VD_FLAGS_FALLBACK);
            } catch (RuntimeException fallbackFailure) {
                Log.w(TAG, "cluster VD unavailable: " + fallbackFailure.getMessage());
                virtualDisplay = null;
            }
        }
        if (virtualDisplay == null) {
            finishAndRemoveTask();
            return;
        }
        int target = virtualDisplay.getDisplay().getDisplayId();
        AppLaunchCoordinator.get(this).openOnVirtualDisplay(packageName, target,
                getIntent().getStringExtra(EXTRA_SOURCE), result -> {
                    if (!result.started) {
                        Log.w(TAG, "application launch refused: " + result.reason);
                        finishAndRemoveTask();
                    }
                });
    }

    private void releaseDisplay() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    @Override protected void onDestroy() {
        releaseDisplay();
        VirtualDisplayLease.release(this);
        List<Runnable> callbacks;
        synchronized (ClusterMediaHostActivity.class) {
            if (active.get() == this) active = new WeakReference<>(null);
            callbacks = new ArrayList<>(this.closeCallbacks);
            this.closeCallbacks.clear();
        }
        super.onDestroy();
        for (Runnable callback : callbacks) if (callback != null) callback.run();
    }

    @Override public void releaseForSuccessor(Runnable completion) {
        closeActiveHost(completion);
    }

    /** Completion runs after onDestroy released the VD; there is no fixed handoff delay. */
    static void closeActiveHost(Runnable completion) {
        ClusterMediaHostActivity host;
        synchronized (ClusterMediaHostActivity.class) {
            host = active.get();
            if (host != null && !host.isFinishing() && !host.isDestroyed()) {
                if (completion != null) host.closeCallbacks.add(completion);
            } else {
                host = null;
            }
        }
        if (host == null) {
            if (completion != null) completion.run();
        } else {
            host.runOnUiThread(host::finishAndRemoveTask);
        }
    }
}
