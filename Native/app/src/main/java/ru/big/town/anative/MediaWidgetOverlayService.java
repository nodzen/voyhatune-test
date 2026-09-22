package ru.big.town.anative;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/** Native overlay for cards occupying the measured OEM BigMediaCard rectangle. */
public final class MediaWidgetOverlayService extends Service implements VirtualDisplayLease.Owner {
    static final String ACTION_GEOMETRY = "ru.big.town.anative.WIDGET_GEOMETRY";
    static final String ACTION_RELOAD = "ru.big.town.anative.WIDGET_RELOAD";
    static final String ACTION_SWIPE = "ru.big.town.anative.WIDGET_SWIPE";
    private static final String CHANNEL = "media_widget_overlay";
    private static final String TAG = "$$$ MediaWidgets $$$";
    private static final int VD_FLAGS = 1 | 8 | 256 | 1024;
    private static final int VD_FLAGS_FALLBACK = 1 | 8 | 256;
    private static WeakReference<MediaWidgetOverlayService> active = new WeakReference<>(null);

    private WindowManager windows;
    private FrameLayout overlay;
    private WindowManager.LayoutParams params;
    private List<WidgetCardPolicy.Card> cards;
    private int cardIndex;
    private boolean geometryVisible;
    private int x, y, width, height;
    private VirtualDisplay virtualDisplay;
    private Surface appSurface;
    private TextureView appTexture;
    private float downX;
    private long tripAccumulated;
    private long tripDriveStarted;
    private boolean tripInDrive;
    private int batteryTemperature = Integer.MIN_VALUE;
    private int batteryStatus = Integer.MIN_VALUE;
    private int powerHoldStatus;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView liveText;
    private final Runnable tripTicker = new Runnable() {
        @Override public void run() {
            if (liveText != null && currentKind(WidgetCardPolicy.TRIP)) liveText.setText(tripText());
            main.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TripStatsService.ACTION_TRIP_UPDATE.equals(action)) {
                tripAccumulated = intent.getLongExtra(TripStatsService.EXTRA_ACCUM_MS, 0L);
                tripDriveStarted = intent.getLongExtra(TripStatsService.EXTRA_DRIVE_START, 0L);
                tripInDrive = intent.getBooleanExtra(TripStatsService.EXTRA_IN_DRIVE, false);
                if (liveText != null && currentKind(WidgetCardPolicy.TRIP)) {
                    liveText.setText(tripText());
                }
            } else if (BatteryHeatService.ACTION_BATTERY_HEAT_UPDATE.equals(action)) {
                batteryTemperature = intent.getIntExtra("ambientTemp", Integer.MIN_VALUE);
                batteryStatus = intent.getIntExtra("controlStatus", Integer.MIN_VALUE);
                if (overlay != null && currentKind(WidgetCardPolicy.CAR)) renderCurrent();
            } else if (SetModesService.ACTION_POWER_HOLD_STATUS_UPDATE.equals(action)) {
                powerHoldStatus = intent.getIntExtra(SetModesService.EXTRA_POWER_HOLD_STATUS,
                        0);
                if (overlay != null && currentKind(WidgetCardPolicy.CAR)) renderCurrent();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        synchronized (MediaWidgetOverlayService.class) { active = new WeakReference<>(this); }
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(7303, new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("VoyahTune widgets")
                .setContentText("Custom media cards are active")
                .setOngoing(true).build());
        IntentFilter filter = new IntentFilter();
        filter.addAction(TripStatsService.ACTION_TRIP_UPDATE);
        filter.addAction(BatteryHeatService.ACTION_BATTERY_HEAT_UPDATE);
        filter.addAction(SetModesService.ACTION_POWER_HOLD_STATUS_UPDATE);
        registerReceiver(stateReceiver, filter);
        main.post(tripTicker);
        requestState();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_GEOMETRY.equals(intent.getAction())) {
                applyGeometry(intent);
            } else if (ACTION_SWIPE.equals(intent.getAction())) {
                switchCard(intent.getIntExtra("direction", 1));
            }
        }
        reloadCards();
        renderCurrent();
        return START_STICKY;
    }

    private void applyGeometry(Intent intent) {
        int nx = intent.getIntExtra("x", -1);
        int ny = intent.getIntExtra("y", -1);
        int nw = intent.getIntExtra("width", 0);
        int nh = intent.getIntExtra("height", 0);
        boolean visible = intent.getBooleanExtra("visible", false);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (!visible || nx < 0 || ny < 0 || nw < 160 || nh < 160
                || nx + nw > dm.widthPixels || ny + nh > dm.heightPixels) {
            geometryVisible = false;
            removeOverlay();
            return;
        }
        geometryVisible = true;
        x = nx; y = ny; width = nw; height = nh;
    }

    private void reloadCards() {
        cards = WidgetCardPolicy.parse(android.provider.Settings.Global.getString(
                getContentResolver(), "voyahtune_custom_widget_cards"));
        if (cardIndex >= cards.size()) cardIndex = 0;
    }

    private void switchCard(int direction) {
        if (cards == null || cards.isEmpty()) reloadCards();
        cardIndex = WidgetCardPolicy.nextIndex(cardIndex, direction, cards.size());
        renderCurrent();
    }

    private boolean currentKind(String kind) {
        return cards != null && !cards.isEmpty() && kind.equals(cards.get(cardIndex).kind);
    }

    private void renderCurrent() {
        if (!geometryVisible || cards == null || cards.isEmpty()
                || currentKind(WidgetCardPolicy.MUSIC)) {
            removeOverlay(); // OEM card regains its window and all touch/media events.
            return;
        }
        if (!ensureOverlay()) return;
        overlay.removeAllViews();
        releaseAppVirtualDisplay();
        WidgetCardPolicy.Card card = cards.get(cardIndex);
        if (WidgetCardPolicy.TRIP.equals(card.kind)) renderTrip();
        else if (WidgetCardPolicy.CAR.equals(card.kind)) renderCar();
        else if ("app".equals(card.kind)) renderApp(card);
    }

    private boolean ensureOverlay() {
        if (overlay == null) {
            overlay = new FrameLayout(this);
            overlay.setBackgroundColor(0xff161a22);
            overlay.setOnTouchListener((view, event) -> handleOverlayTouch(event));
            params = new WindowManager.LayoutParams(width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.OPAQUE);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = x;
            params.y = y;
            try {
                windows.addView(overlay, params);
            } catch (RuntimeException denied) {
                VoyahLog.w(TAG, "overlay-window", "media overlay unavailable: "
                        + denied.getClass().getSimpleName());
                overlay = null;
                params = null;
                return false;
            }
        } else if (params.width != width || params.height != height || params.x != x || params.y != y) {
            params.width = width; params.height = height; params.x = x; params.y = y;
            try {
                windows.updateViewLayout(overlay, params);
            } catch (RuntimeException detached) {
                removeOverlay();
                return false;
            }
        }
        return true;
    }

    private void renderTrip() {
        LinearLayout content = cardLayout("Поездка");
        liveText = bodyText(tripText());
        content.addView(liveText);
        Button history = button("История поездок");
        history.setOnClickListener(v -> {
            Intent open = new Intent().setClassName("ru.big.town.restoremode",
                    "ru.big.town.restoremode.TripHistoryActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(open); } catch (RuntimeException ignored) {}
        });
        content.addView(history);
        overlay.addView(content);
    }

    private String tripText() {
        long elapsed = tripAccumulated;
        if (tripInDrive && tripDriveStarted > 0L) elapsed += SystemClock.elapsedRealtime() - tripDriveStarted;
        long seconds = Math.max(0L, elapsed / 1000L);
        return String.format(Locale.ROOT, "%s\n%02d:%02d:%02d",
                tripInDrive ? "Движение" : "Остановка", seconds / 3600,
                (seconds / 60) % 60, seconds % 60);
    }

    private void renderCar() {
        LinearLayout content = cardLayout("Автомобиль");
        liveText = bodyText("Power Hold: " + powerHoldLabel() + "\nПрогрев ВВБ: "
                + batteryLabel() + (batteryTemperature == Integer.MIN_VALUE ? "" :
                "\nНа улице: " + batteryTemperature + " °C"));
        content.addView(liveText);
        Button hold = button("Power Hold");
        hold.setOnClickListener(v -> sendServiceMessage(SetModesService.MSG_LEAVE_CAR));
        content.addView(hold);
        Button heat = button("Запустить прогрев");
        heat.setOnClickListener(v -> sendBroadcast(new Intent(
                BatteryHeatService.ACTION_BATTERY_HEAT_ACTIVATE).setPackage(getPackageName())));
        content.addView(heat);
        overlay.addView(content);
    }

    private void renderApp(WidgetCardPolicy.Card card) {
        final long requestedAt = SystemClock.elapsedRealtime();
        final boolean[] firstFrame = {false};
        appTexture = new TextureView(this);
        appTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                VirtualDisplayLease.acquire(MediaWidgetOverlayService.this,
                        () -> createAppDisplay(card, st, w, h));
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                if (virtualDisplay != null) virtualDisplay.resize(w, h, appDpi(card));
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releaseAppVirtualDisplay();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
                if (firstFrame[0]) return;
                firstFrame[0] = true;
                VoyahLog.i(TAG, "first-frame-" + card.packageName,
                        "source=widget package=" + card.packageName + " firstFrameMs="
                                + (SystemClock.elapsedRealtime() - requestedAt));
            }
        });
        overlay.addView(appTexture, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void createAppDisplay(WidgetCardPolicy.Card card, SurfaceTexture texture, int w, int h) {
        if (!geometryVisible || !currentKind("app") || w <= 0 || h <= 0) return;
        appSurface = new Surface(texture);
        DisplayManager displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displays == null) {
            releaseAppVirtualDisplay();
            return;
        }
        try {
            virtualDisplay = displays.createVirtualDisplay("VoyahTune-Media-Widget", w, h,
                    appDpi(card), appSurface, VD_FLAGS);
        } catch (RuntimeException trustedFailure) {
            VoyahLog.w(TAG, "trusted-vd", "trusted widget VD unavailable: "
                    + trustedFailure.getClass().getSimpleName());
            try {
                virtualDisplay = displays.createVirtualDisplay("VoyahTune-Media-Widget", w, h,
                        appDpi(card), appSurface, VD_FLAGS_FALLBACK);
            } catch (RuntimeException fallbackFailure) {
                VoyahLog.w(TAG, "fallback-vd", "widget VD unavailable: "
                        + fallbackFailure.getClass().getSimpleName());
                virtualDisplay = null;
            }
        }
        if (virtualDisplay == null) {
            releaseAppVirtualDisplay();
            return;
        }
        AppLaunchCoordinator.get(this).openOnVirtualDisplay(card.packageName,
                virtualDisplay.getDisplay().getDisplayId(), "widget", result -> {
                    if (!result.started && liveText != null) liveText.setText("Приложение недоступно");
                });
    }

    private int appDpi(WidgetCardPolicy.Card card) {
        return card.dpi >= 100 ? card.dpi : getResources().getDisplayMetrics().densityDpi;
    }

    private boolean handleOverlayTouch(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) downX = event.getX();
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            if (Math.abs(dx) > Math.max(48f, width * .15f)) {
                switchCard(dx < 0 ? 1 : -1);
                return true;
            }
        }
        if (currentKind("app") && virtualDisplay != null) injectTouch(event);
        return true;
    }

    private void injectTouch(MotionEvent source) {
        MotionEvent copy = MotionEvent.obtain(source);
        try {
            copy.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            Method setDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
            setDisplayId.invoke(copy, virtualDisplay.getDisplay().getDisplayId());
            Class<?> inputManager = Class.forName("android.hardware.input.InputManager");
            Object instance = inputManager.getMethod("getInstance").invoke(null);
            inputManager.getMethod("injectInputEvent", android.view.InputEvent.class, int.class)
                    .invoke(instance, copy, 0);
        } catch (Throwable error) {
            VoyahLog.w(TAG, "inject", "widget touch injection unavailable: "
                    + error.getClass().getSimpleName());
        } finally {
            copy.recycle();
        }
    }

    private LinearLayout cardLayout(String title) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(18f * getResources().getDisplayMetrics().density);
        content.setPadding(p, p, p, p);
        TextView heading = bodyText(title);
        heading.setTextSize(26f);
        heading.setTextColor(0xffffffff);
        content.addView(heading);
        return content;
    }

    private TextView bodyText(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(0xffd5dae3);
        view.setTextSize(21f);
        view.setPadding(0, 10, 0, 10);
        return view;
    }

    private Button button(String label) {
        Button value = new Button(this);
        value.setText(label);
        value.setTextColor(Color.WHITE);
        return value;
    }

    private String powerHoldLabel() {
        if (powerHoldStatus == PowerHoldStatusPolicy.Status.ACTIVE.ipcCode) return "включён";
        if (powerHoldStatus == PowerHoldStatusPolicy.Status.INACTIVE.ipcCode) return "выключен";
        return "—";
    }

    private String batteryLabel() {
        if (batteryStatus == 1) return "нагрев";
        if (batteryStatus == 2) return "инициализация";
        if (batteryStatus == Integer.MIN_VALUE) return "—";
        return "ожидание";
    }

    private void sendServiceMessage(int what) {
        Intent service = new Intent(this, SetModesService.class).putExtra("widgetMessage", what);
        startForegroundService(service);
    }

    private void requestState() {
        sendBroadcast(new Intent(TripStatsService.ACTION_REQUEST_TRIP_UPDATE).setPackage(getPackageName()));
        sendBroadcast(new Intent(BatteryHeatService.ACTION_REQUEST_BATTERY_HEAT).setPackage(getPackageName()));
        sendBroadcast(new Intent(SetModesService.ACTION_REQUEST_POWER_HOLD_STATUS).setPackage(getPackageName()));
    }

    private void removeOverlay() {
        releaseAppVirtualDisplay();
        liveText = null;
        if (overlay != null) {
            try { windows.removeViewImmediate(overlay); } catch (RuntimeException ignored) {}
            overlay = null;
            params = null;
        }
    }

    private void releaseAppVirtualDisplay() {
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (appSurface != null) { appSurface.release(); appSurface = null; }
        appTexture = null;
        VirtualDisplayLease.release(this);
    }

    static void releaseAppDisplay(Runnable completion) {
        MediaWidgetOverlayService service;
        synchronized (MediaWidgetOverlayService.class) { service = active.get(); }
        if (service == null) { if (completion != null) completion.run(); return; }
        service.main.post(() -> {
            service.releaseAppVirtualDisplay();
            if (completion != null) completion.run();
        });
    }

    @Override public void releaseForSuccessor(Runnable completion) {
        releaseAppVirtualDisplay();
        if (completion != null) completion.run();
    }

    @Override public void onDestroy() {
        main.removeCallbacks(tripTicker);
        try { unregisterReceiver(stateReceiver); } catch (RuntimeException ignored) {}
        removeOverlay();
        synchronized (MediaWidgetOverlayService.class) {
            if (active.get() == this) active = new WeakReference<>(null);
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Media widgets",
                    NotificationManager.IMPORTANCE_MIN);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
