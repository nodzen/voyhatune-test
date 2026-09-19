package ru.big.town.anative;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.lang.ref.WeakReference;

import androidx.core.content.ContextCompat;

/**
 * Хост одного приложения или сплита на VirtualDisplay: каждая видимая панель запускается на СВОЁМ
 * VirtualDisplay, картинка которого рендерится в SurfaceView. Однопанельный режим заменяет legacy
 * system_server freeform hot-hooks; двухпанельный даёт полноценный сплит. Оба режима поддерживают:
 *  - per-app DPI: плотность задаётся на каждый VirtualDisplay ({@code createVirtualDisplay(...,densityDpi,...)});
 *  - ресайз пропорций: во время жеста двигаем безопасное превью, на отпускании один раз меняем веса
 *    SurfaceView → {@code vd.resize(w,h,dpi)}. Activity стороннего приложения при этом может штатно
 *    пересоздаться из-за configuration change.
 *
 * ⚠️ Доставка ВВОДА (касаний) в VirtualDisplay требует INJECT_EVENTS — это ЧИСТЫЙ signature
 * пермишен, которого у Native фактически НЕТ (голова на release-keys, подпись dev-ключом; whitelist
 * его не выдаёт). Поэтому {@link #injectTouch} обёрнут в try/catch и на этой голове, скорее всего,
 * бросит SecurityException. Рабочая доставка ввода — отдельная задача (root+Frida-инъекция вызова
 * injectInputEvent в system_server ЛИБО роутинг ввода самим WM для
 * trusted-дисплея). Рендер, per-app DPI и живой ресайз работают независимо от ввода.
 *
 * Process-wide invariant: одновременно жив только один host. Новый запрос на другой физический
 * дисплей получает новую generation и завершает старый host; это делает быстрый handoff latest-wins.
 *
 * Extras: leftPkg, rightPkg (String), ratio (int 0..4), leftDpi, rightDpi (int, 0=дефолт дисплея).
 */
public class SplitHostActivity extends Activity {

    private static final String TAG = "$$$ SplitHostActivity $$$";

    /** Жест изменения пропорции включается отдельно для каждого split-пресета. */
    private static final boolean DIVIDER_RESIZE_GESTURE_ENABLED = true;

    private static final String ACTION_SCREEN_LIFT_CHANGED = "action.qg.layout.changed";
    /** Sent only to the app hosted by a pane after its VirtualDisplay accepted a new size. */
    private static final String ACTION_VD_RESIZED = "ru.big.town.anative.VD_RESIZED";
    private static final String VD_RESIZE_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    private static final String SCREEN_LIFT_SETTING = "voyahtune_screen_lift_type";
    private static final String SCREEN_LIFT_PROPERTY = "persist.qg.canbus.bcm_screenAutoLiftFdb";
    private static final int SCREEN_LIFT_DOWN = 1;
    private static final int SCREEN_LIFT_UP = 2;
    private static final int SCREEN_DOWN_HEIGHT_PX = 560;
    private static final int SCREEN_UP_HEIGHT_PX = 720;
    /**
     * H97C does not tolerate two VirtualDisplay configuration changes in the same frame.  Keep a
     * small gap between pane.resize() calls so a split release cannot make system_server process
     * two activity/configuration rebuilds concurrently.
     */
    private static final long VD_RESIZE_GAP_MS = 180L;

    public static final String EXTRA_LEFT     = "leftPkg";
    public static final String EXTRA_RIGHT    = "rightPkg";
    public static final String EXTRA_RATIO    = "ratio";
    public static final String EXTRA_LEFT_DPI = "leftDpi";
    public static final String EXTRA_RIGHT_DPI = "rightDpi";
    public static final String EXTRA_RESIZABLE  = "resizable";
    public static final String EXTRA_SPLIT      = "split";
    public static final String EXTRA_PRESET_IDX = "presetIdx";
    public static final String EXTRA_PRESET_ID  = "presetId";
    /** User-launched same-preset requests ask an existing host to reconcile missing pane tasks. */
    public static final String EXTRA_RECONCILE = "reconcilePanes";

    // Флаги VirtualDisplay. TRUSTED(1<<10) обязателен, чтобы на дисплей можно было запускать
    // чужие активити и (в перспективе) роутить ввод; требует ADD_TRUSTED_DISPLAY (privapp whitelist).
    // PUBLIC(1<<0) | OWN_CONTENT_ONLY(1<<3) | DESTROY_CONTENT_ON_REMOVAL(1<<8) | TRUSTED(1<<10) = 1289.
    private static final int VD_FLAGS_TRUSTED  = 1 | 8 | 256 | 1024;
    // Фолбэк без TRUSTED (если ADD_TRUSTED_DISPLAY не выдан, напр. на эмуляторе) — рендер будет,
    // запуск чужой активити может не пройти, но не роняем приложение.
    private static final int VD_FLAGS_FALLBACK = 1 | 8 | 256;
    private static volatile WeakReference<SplitHostActivity> activeHost =
            new WeakReference<>(null);

    private DisplayManager displayManager;
    private int defaultDpi = 213;
    private boolean touchWarned = false;
    private boolean touchInjectionResolved = false;
    private boolean touchInjectionAvailable = false;
    private Object inputManager;
    private Method setDisplayIdMethod;
    private Method injectInputEventMethod;
    private volatile long lastVdResizeAt = 0L;
    private boolean screenLiftReceiverRegistered;
    private int screenLiftType = SCREEN_LIFT_UP;

    private final BroadcastReceiver screenLiftReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SCREEN_LIFT_CHANGED.equals(intent.getAction())) return;
            int type = intent.getIntExtra("type", SCREEN_LIFT_UP);
            int actualType = readScreenLiftProperty(type);
            if (actualType != type) {
                Log.w(TAG, "screen lift broadcast ignored: type=" + type
                        + " property=" + actualType);
                return;
            }
            applyScreenLiftSize(type);
        }
    };

    /** Одна «панель» сплита: контейнер + SurfaceView + свой VirtualDisplay + запускаемое приложение. */
    private static final class Pane {
        final String side;
        View container;     // FrameLayout с весом и скруглением
        SurfaceView view;
        VirtualDisplay vd;
        String pkg;
        int dpi;            // 0 = дефолт дисплея (приходит из per-app настройки RestoreMode)
        int w, h;
        int vdWidth, vdHeight, vdDpi; // last dimensions successfully sent to VirtualDisplay
        int pendingWidth, pendingHeight, pendingDpi;
        int resizeAttempts;
        Runnable resizeRunnable;
        long resizeVersion; // успешные vd.resize; нужен для снятия маски после реального layout обеих панелей
        boolean launched;
        boolean launchInFlight;
        long launchedAt;    // когда стартовали приложение — надзирателю нужно дать ему подняться
        int restarts;       // сколько раз надзиратель уже перезапускал панель за эту сессию сплита
        Pane(String side) { this.side = side; }
    }

    // --- Надзиратель панелей ---------------------------------------------------------------------
    // Приложение может умереть УЖЕ ПОСЛЕ успешного запуска (краш в чужом VirtualDisplay). Раньше это
    // не замечал никто: pane.launched оставался true, панель просто чернела, и лечил только перезапуск
    // сплита руками. Надзиратель периодически проверяет, жива ли задача приложения НА СВОЁМ дисплее,
    // и поднимает её заново.
    private static final long WATCH_PERIOD_MS  = 2500;  // период опроса
    private static final long WATCH_GRACE_MS   = 8000;  // столько не трогаем панель после запуска (старт приложения)
    private static final int  WATCH_MAX_RESTARTS = 3;   // предохранитель от бесконечного цикла перезапусков
    private final Handler watchHandler = new Handler(Looper.getMainLooper());
    /** Binder-side VirtualDisplay.resize must not occupy the activity/main looper. */
    private HandlerThread resizeThread;
    private Handler resizeHandler;
    private SplitHostTaskLane taskLane;
    private SplitHostGenerationGate workGate;
    private boolean watchActive;
    private volatile boolean hostDestroyed;
    /** Survives an onNewIntent delivered before onResume; cleared only by an accepted result. */
    private boolean paneHealthCheckPending;
    private final Runnable watchTick = new Runnable() {
        @Override public void run() {
            if (!watchActive || hostDestroyed) return;
            try {
                requestSupervisionSnapshot();
            } catch (Exception e) {
                Log.w(TAG, "supervise: " + e.getMessage());
            }
            if (watchActive && !hostDestroyed) watchHandler.postDelayed(this, WATCH_PERIOD_MS);
        }
    };

    // Для фиксированного пресета делитель только визуальный; resizable-пресет коммитит размер один раз
    // на отпускании. Двойной тап в обоих режимах меняет окна местами.
    private final Pane left  = new Pane("L");
    private final Pane right = new Pane("R");

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // LIGHT-сборка: VD-сплит-хост отключён (нет Frida/trusted-display) — сразу закрываемся.
        if (!BuildConfig.IS_FULL) { finish(); return; }
        resizeThread = new HandlerThread("VoyahTune-VdResize");
        resizeThread.start();
        resizeHandler = new Handler(resizeThread.getLooper());
        taskLane = SplitHostTaskLane.get(getApplicationContext());
        workGate = new SplitHostGenerationGate(taskLane.registerHost(this));
        activeHost = new WeakReference<>(this);
        // Поверх всего, не гаснуть, landscape. Edge-to-edge — чтобы получить реальные window insets
        // и самим задать отступы (иначе система инсетит контент и мы бы отступали повторно).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_split_host);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        applyScreenLiftSize(readScreenLiftType());
        registerScreenLiftReceiver();
        applyWindowInsets();

        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        defaultDpi = getResources().getDisplayMetrics().densityDpi;

        Intent in = getIntent();
        left.pkg   = in.getStringExtra(EXTRA_LEFT);
        right.pkg  = in.getStringExtra(EXTRA_RIGHT);
        left.dpi   = in.getIntExtra(EXTRA_LEFT_DPI, 0);
        right.dpi  = in.getIntExtra(EXTRA_RIGHT_DPI, 0);
        int ratio  = in.getIntExtra(EXTRA_RATIO, 1);
        resizable  = DIVIDER_RESIZE_GESTURE_ENABLED
                && in.getBooleanExtra(EXTRA_RESIZABLE, false);
        presetIdx  = in.getIntExtra(EXTRA_PRESET_IDX, -1);
        presetId   = in.getStringExtra(EXTRA_PRESET_ID);
        float startSplit = in.getFloatExtra(EXTRA_SPLIT, 0f);

        left.container  = findViewById(R.id.splitPaneLeft);
        right.container = findViewById(R.id.splitPaneRight);
        left.view       = findViewById(R.id.splitSurfaceLeft);
        right.view      = findViewById(R.id.splitSurfaceRight);

        // Одиночный режим (ярлык): правый пакет пуст → одно окно на всю ширину, без разделителя.
        boolean single = (right.pkg == null || right.pkg.isEmpty());

        if (single) {
            findViewById(R.id.splitDivider).setVisibility(View.GONE);
            right.container.setVisibility(View.GONE);
            setWeight(left.container, 1f);
        } else if (resizable && startSplit > 0.05f && startSplit < 0.95f) {
            applyFraction(startSplit);      // пропорция, выставленная рукой в прошлый раз
        } else {
            applyRatioWeights(ratio);
        }

        setupSurface(left);
        applyRoundedCorners(left.container);
        if (!single) {
            setupSurface(right);
            setupDivider();
            applyRoundedCorners(right.container);
        }

        Log.i(TAG, "onCreate single=" + single + " left=" + left.pkg + " right=" + right.pkg
                + " ratio=" + ratio + " lDpi=" + left.dpi + " rDpi=" + right.dpi
                + " defaultDpi=" + defaultDpi + " resizable=" + resizable
                + " split=" + startSplit + " presetId=" + presetId
                + " liftType=" + screenLiftType + " hostHeight=" + currentHostHeight());
    }

    /**
     * The OEM display always reports 1920x720. In the lowered state only the upper 560 pixels are
     * physically visible, so size the host content explicitly. SurfaceView then emits
     * surfaceChanged and the existing VD resize path updates an already running split as well.
     */
    private void applyScreenLiftSize(int type) {
        screenLiftType = type == SCREEN_LIFT_DOWN ? SCREEN_LIFT_DOWN : SCREEN_LIFT_UP;
        final int height = currentHostHeight();
        View root = findViewById(R.id.splitHostRoot);
        if (root == null) return;
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            root.setLayoutParams(lp);
        }
        root.requestLayout();
        Log.i(TAG, "screen lift type=" + screenLiftType + " hostHeight=" + height
                + " (active VDs resize from SurfaceView.surfaceChanged)");
    }

    private int currentHostHeight() {
        return screenLiftType == SCREEN_LIFT_DOWN ? SCREEN_DOWN_HEIGHT_PX : SCREEN_UP_HEIGHT_PX;
    }

    private int readScreenLiftType() {
        int property = readScreenLiftProperty(0);
        if (property == SCREEN_LIFT_DOWN || property == SCREEN_LIFT_UP) return property;
        try {
            return Settings.Global.getInt(getContentResolver(), SCREEN_LIFT_SETTING, SCREEN_LIFT_UP);
        } catch (RuntimeException e) {
            return SCREEN_LIFT_UP;
        }
    }

    private int readScreenLiftProperty(int fallback) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method getInt = properties.getDeclaredMethod("getInt", String.class, int.class);
            int value = (Integer) getInt.invoke(null, SCREEN_LIFT_PROPERTY, fallback);
            if (value == SCREEN_LIFT_DOWN || value == SCREEN_LIFT_UP) return value;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "screen lift property unavailable: " + e.getMessage());
        }
        return fallback;
    }

    private void registerScreenLiftReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_SCREEN_LIFT_CHANGED);
        try {
            ContextCompat.registerReceiver(this, screenLiftReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
            screenLiftReceiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "screen lift receiver unavailable: " + e.getMessage());
        }
    }

    /**
     * Хост уже открыт (launchMode=singleTop) и пришёл НОВЫЙ запрос — напр. клик по иконке дока
     * (одиночный запуск) поверх ранее открытого сплита. Без этого singleTop получил бы onNewIntent,
     * а старый сплит остался бы на экране. Обновляем интент и пересобираем хост с чистого листа:
     * старые VirtualDisplay/панели освобождаются в onDestroy → onCreate перечитывает новые extras.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (applyIncomingIntentInPlace(intent)) return;
        // Fence the old package/display before recreate posts a new Activity instance.
        retireAsyncHostWork(false);
        setIntent(intent);
        recreate();
    }

    /**
     * RestoreMode frequently re-sends the current split while the host is already alive (screen-lift
     * restore, a repeated preset click, or a saved ratio update). Recreating the Activity releases
     * both VirtualDisplays and leaves two black surfaces for the removeTask/startActivity handoff.
     * Keep the host when the pane identities are unchanged and only apply the new geometry in place.
     */
    private boolean applyIncomingIntentInPlace(Intent incoming) {
        if (incoming == null || hostDestroyed) return false;
        String incomingLeft = incoming.getStringExtra(EXTRA_LEFT);
        String incomingRight = incoming.getStringExtra(EXTRA_RIGHT);
        int incomingLeftDpi = incoming.getIntExtra(EXTRA_LEFT_DPI, 0);
        int incomingRightDpi = incoming.getIntExtra(EXTRA_RIGHT_DPI, 0);
        boolean incomingResizable = DIVIDER_RESIZE_GESTURE_ENABLED
                && incoming.getBooleanExtra(EXTRA_RESIZABLE, false);
        boolean currentSingle = right.pkg == null || right.pkg.isEmpty();
        boolean incomingSingle = incomingRight == null || incomingRight.isEmpty();

        if (!sameText(left.pkg, incomingLeft) || !sameText(right.pkg, incomingRight)
                || left.dpi != incomingLeftDpi || right.dpi != incomingRightDpi
                || currentSingle != incomingSingle || resizable != incomingResizable) {
            return false;
        }

        setIntent(incoming);
        presetIdx = incoming.getIntExtra(EXTRA_PRESET_IDX, -1);
        presetId = incoming.getStringExtra(EXTRA_PRESET_ID);
        if (presetId == null) presetId = "";

        if (resizeState != ResizeState.IDLE) cancelResizeGesture();
        int ratio = incoming.getIntExtra(EXTRA_RATIO, 1);
        float requestedSplit = incoming.getFloatExtra(EXTRA_SPLIT, 0f);
        if (incomingSingle) {
            setWeight(left.container, 1f);
            right.container.setVisibility(View.GONE);
            Log.i(TAG, "onNewIntent: same single host kept in place");
            return true;
        }

        if (resizable && isValidSplit(requestedSplit)) {
            final float committed = clampFraction(requestedSplit);
            applyFraction(committed);
            lastDragFraction = committed;
            prepareGeometryResize();
            Log.i(TAG, "onNewIntent: same split kept in place, fraction=" + committed);
        } else {
            applyRatioWeights(ratio);
            prepareGeometryResize();
            Log.i(TAG, "onNewIntent: same split kept in place, ratio=" + ratio);
        }
        if (incoming.getBooleanExtra(EXTRA_RECONCILE, false)) {
            requestPaneHealthCheck();
        }
        return true;
    }

    private static boolean sameText(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static boolean isValidSplit(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value)
                && value > 0.05f && value < 0.95f;
    }

    private void prepareGeometryResize() {
        long now = System.currentTimeMillis();
        left.launchedAt = now;
        right.launchedAt = now;
        resizeUntil = now + WATCH_GRACE_MS;
    }

    /**
     * Отступы области окон по window insets + левый док лончера. Левый системный навбар/док головы
     * НЕ сообщает свой размер в insets (висит поверх), поэтому гарантируем минимум ≈144dp слева
     * (как в старом freeform-сплите: контент головы начинается с x≈142px). Статус-бар и прочие
     * системные панели берём из реальных insets (+ фолбэк status_bar_height, если пришёл 0).
     */
    private void applyWindowInsets() {
        final float density = getResources().getDisplayMetrics().density;
        final int gap = Math.round(density * 6f);          // небольшой внутренний зазор
        final int nativeDock = Math.round(density * 145f); // родной док головы висит поверх слева (в insets не приходит)
        View root = findViewById(R.id.splitHostRoot);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets sb =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            int top = sb.top;
            if (top == 0) {
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) top = getResources().getDimensionPixelSize(id);
            }
            // Инсет на КОРЕНЬ (FrameLayout) → и панели, и оверлей-маска отступают одинаково и совпадают.
            v.setPadding(nativeDock + sb.left + gap, top + gap, sb.right + gap, sb.bottom + gap);
            return insets;
        });
    }


    // Вес левого окна по соотношению (0=3:4,1=1:1,2=4:3,3=5:2,4=2:5) — как во freeform-движке.
    private void applyRatioWeights(int ratio) {
        float fraction = SplitHostGeometry.presetFraction(ratio);
        setWeight(left.container, fraction);
        setWeight(right.container, 1f - fraction);
    }

    private void setWeight(View v, float w) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.weight = w;
        v.setLayoutParams(lp);
    }

    /** Разложить панели по доле левого окна (0..1). Веса суммируем в 1 — так проще считать драг. */
    private void applyFraction(float f) {
        float bounded = clampFraction(f);
        setWeight(left.container, bounded);
        setWeight(right.container, 1f - bounded);
    }

    /** Текущая доля левого окна по фактической ширине панелей. */
    private float currentFraction() {
        int lw = left.container.getWidth(), rw = right.container.getWidth();
        return (lw + rw > 0) ? (float) lw / (lw + rw) : 0.5f;
    }

    // -------------------------------------------------------------------------
    // Surface → VirtualDisplay → запуск приложения
    // -------------------------------------------------------------------------

    private void setupSurface(final Pane pane) {
        pane.view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) { }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                int dpi = effectiveDpi(pane);
                boolean sizeChanged = pane.vdWidth != width
                        || pane.vdHeight != height
                        || pane.vdDpi != dpi;
                pane.w = width;
                pane.h = height;
                if (pane.vd == null) {
                    createVirtualDisplay(pane, holder.getSurface());
                    launchApp(pane);
                } else if (sizeChanged) {
                    // SurfaceView may publish two or three intermediate sizes during one layout
                    // traversal (especially when the screen is lifted). Coalesce them so the hosted
                    // Activity receives one stable configuration instead of a resize race.
                    schedulePaneResize(pane, width, height, dpi);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                releasePane(pane);
            }
        });

        // Пересылка касаний в соответствующий VirtualDisplay (см. предупреждение в шапке класса).
        pane.view.setOnTouchListener((v, ev) -> {
            if (pane.vd != null) injectTouch(pane, ev);
            return true;
        });
    }

    private void schedulePaneResize(final Pane pane, int width, int height, int dpi) {
        schedulePaneResize(pane, width, height, dpi, true);
    }

    private void schedulePaneResize(final Pane pane, int width, int height, int dpi,
                                    boolean resetAttempts) {
        pane.pendingWidth = width;
        pane.pendingHeight = height;
        pane.pendingDpi = dpi;
        if (resetAttempts) pane.resizeAttempts = 0;
        Handler queue = resizeQueue();
        if (pane.resizeRunnable != null) queue.removeCallbacks(pane.resizeRunnable);
        // Use a self-referencing holder so a pane can yield to the other pane without creating a
        // second independent resize chain. The latest pending dimensions are read only when the
        // serialized operation actually reaches the front of the queue.
        final Runnable[] scheduled = new Runnable[1];
        scheduled[0] = () -> {
            pane.resizeRunnable = null;
            if (hostDestroyed) return;
            long wait = lastVdResizeAt + VD_RESIZE_GAP_MS - SystemClock.uptimeMillis();
            if (wait > 0L) {
                pane.resizeRunnable = scheduled[0];
                resizeQueue().postDelayed(scheduled[0], wait);
                return;
            }
            int targetWidth;
            int targetHeight;
            int targetDpi;
            boolean resized = false;
            boolean retry = false;
            synchronized (pane) {
                if (hostDestroyed || pane.vd == null) return;
                targetWidth = pane.pendingWidth;
                targetHeight = pane.pendingHeight;
                targetDpi = pane.pendingDpi;
                if (targetWidth <= 0 || targetHeight <= 0
                        || (pane.vdWidth == targetWidth && pane.vdHeight == targetHeight
                        && pane.vdDpi == targetDpi)) return;
                try {
                    // This is a synchronous Binder call on some H97C builds. Keep it off the
                    // activity looper; releasePane uses the same lock for a clean handoff.
                    VirtualDisplay targetVd = pane.vd;
                    pane.vd.resize(targetWidth, targetHeight, targetDpi);
                    if (hostDestroyed || pane.vd != targetVd) return;
                    lastVdResizeAt = SystemClock.uptimeMillis();
                    pane.vdWidth = targetWidth;
                    pane.vdHeight = targetHeight;
                    pane.vdDpi = targetDpi;
                    pane.resizeVersion++;
                    pane.resizeAttempts = 0;
                    resized = true;
                } catch (Exception e) {
                    pane.resizeAttempts++;
                    retry = pane.resizeAttempts < 4;
                    Log.w(TAG, "resize " + pane.side + " failed (attempt "
                            + pane.resizeAttempts + "): " + e.getMessage());
                }
            }
            if (resized) {
                notifyPaneResized(pane);
                Log.i(TAG, "resize settled " + pane.side + " " + targetWidth + "x"
                        + targetHeight + " dpi=" + targetDpi);
            } else if (retry && !hostDestroyed) {
                schedulePaneResizeRetry(pane);
            }
        };
        pane.resizeRunnable = scheduled[0];
        queue.postDelayed(scheduled[0], 50L);
    }

    private void schedulePaneResizeRetry(final Pane pane) {
        pane.resizeRunnable = () -> {
            pane.resizeRunnable = null;
            if (hostDestroyed || pane.vd == null) return;
            schedulePaneResize(pane, pane.pendingWidth, pane.pendingHeight, pane.pendingDpi, false);
        };
        resizeQueue().postDelayed(pane.resizeRunnable, 120L);
    }

    private Handler resizeQueue() {
        return resizeHandler != null ? resizeHandler : watchHandler;
    }

    private void createVirtualDisplay(Pane pane, Surface surface) {
        int dpi = effectiveDpi(pane);
        String name = "voyah-split-" + pane.side;
        try {
            pane.vd = displayManager.createVirtualDisplay(name, pane.w, pane.h, dpi, surface, VD_FLAGS_TRUSTED);
            Log.i(TAG, "VD " + pane.side + " (trusted) id="
                    + (pane.vd != null ? pane.vd.getDisplay().getDisplayId() : -1)
                    + " " + pane.w + "x" + pane.h + " dpi=" + dpi);
        } catch (Exception e) {
            Log.w(TAG, "VD " + pane.side + " trusted failed (" + e.getMessage() + ") → fallback");
            try {
                pane.vd = displayManager.createVirtualDisplay(name, pane.w, pane.h, dpi, surface, VD_FLAGS_FALLBACK);
                Log.i(TAG, "VD " + pane.side + " (fallback) id="
                        + (pane.vd != null ? pane.vd.getDisplay().getDisplayId() : -1));
            } catch (Exception e2) {
                Log.e(TAG, "VD " + pane.side + " fallback failed: " + e2.getMessage());
            }
        }
        if (pane.vd != null) {
            pane.vdWidth = pane.w;
            pane.vdHeight = pane.h;
            pane.vdDpi = dpi;
        }
    }

    private void launchApp(Pane pane) {
        if (pane.vd == null || pane.launched || pane.launchInFlight
                || pane.pkg == null || pane.pkg.isEmpty() || taskLane == null || workGate == null) {
            return;
        }
        Integer displayId = paneDisplayId(pane);
        if (displayId == null) return;
        int paneIndex = paneIndex(pane);
        long paneGeneration = workGate.nextPaneGeneration(paneIndex);
        if (paneGeneration == SplitHostGenerationGate.REJECTED) return;

        // ВАЖНО (фикс «пустая панель + уехавшее приложение»): приложение-одиночка (launchMode
        // singleTask/singleInstance или общий taskAffinity), УЖЕ открытое на другом дисплее (freeform на
        // display 0/1 или в другом сплите), при setLaunchDisplayId НЕ дублируется, а ПЕРЕЕЗЖАЕТ на наш VD —
        // WM бросает "Failed to find a stack behind stack", панель остаётся пустой. Поэтому освобождаем
        // приложение с исходного дисплея — но НЕ force-stop процесса (иначе музыка глохнет), а завершаем
        // только его ЗАДАЧУ (removeTask): активити умирает, процесс + foreground-плейбек живут → музыка
        // продолжает играть, а окно стартует ЗАНОВО на нашем VD. Teardown асинхронный → запуск с задержкой.
        pane.launched = true;   // помечаем сразу — повторные проходы (surface recreate) не запустят дважды
        pane.launchInFlight = true;
        pane.launchedAt = System.currentTimeMillis();   // отсчёт «времени на подъём» для надзирателя
        taskLane.requestPaneLaunch(this, new SplitHostTaskLane.PaneTicket(
                workGate.hostGeneration(), paneIndex, paneGeneration, pane.pkg, displayId));
    }

    /** Worker resolved launch intent and removed old tasks; only the UI launch remains on main. */
    void onPaneLaunchPrepared(SplitHostTaskLane.PaneLaunchRequest request, Intent launchIntent) {
        if (!isPaneTicketCurrent(request.pane)) return;
        Pane pane = paneForIndex(request.pane.paneIndex);
        if (launchIntent == null) {
            pane.launchInFlight = false;
            pane.launched = false;
            Log.w(TAG, "нет launch intent для " + request.pane.packageName);
            return;
        }
        Intent prepared = new Intent(launchIntent);
        if (!watchHandler.postDelayed(() -> startPreparedPane(request, prepared), 450)) {
            pane.launchInFlight = false;
            pane.launched = false;
            Log.w(TAG, "main Handler rejected delayed launch for " + request.pane.packageName);
        }
    }

    private void startPreparedPane(SplitHostTaskLane.PaneLaunchRequest request, Intent launchIntent) {
        if (!isPaneTicketCurrent(request.pane)) return;
        Pane pane = paneForIndex(request.pane.paneIndex);
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(request.pane.displayId);
            startActivity(launchIntent, options.toBundle());
            pane.launchInFlight = false;
            pane.launchedAt = System.currentTimeMillis();
            Log.i(TAG, "launched " + request.pane.packageName + " on display "
                    + request.pane.displayId + " (after removeTask)");
        } catch (Exception e) {
            pane.launchInFlight = false;
            pane.launched = false;
            Log.e(TAG, "launchApp " + request.pane.packageName + " failed: " + e.getMessage());
        }
    }

    /** Build one immutable two-pane request; Settings/task enumeration happen on the process lane. */
    private void requestSupervisionSnapshot() {
        if (taskLane == null || workGate == null) return;
        // Во время ресайза приложение получает смену конфигурации и может пересоздаться — в этот
        // момент его задачи на дисплее нет. Без этой паузы надзиратель принял бы это за падение и
        // перезапустил приложение прямо под рукой пользователя.
        if (dragging) return;
        // An explicit retry is stronger than the post-resize grace period and must not wait eight
        // seconds before rebuilding a pane which the user can already see is empty.
        if (paneHealthCheckPending) {
            requestPaneHealthCheck();
            return;
        }
        if (System.currentTimeMillis() < resizeUntil) return;
        ensurePaneRunning(left);
        ensurePaneRunning(right);
        long now = System.currentTimeMillis();
        List<SplitHostTaskLane.PaneTicket> panes = new ArrayList<>(2);
        addSupervisionTicket(panes, left, now);
        addSupervisionTicket(panes, right, now);
        if (panes.isEmpty()) return;
        taskLane.requestSupervision(this, workGate.hostGeneration(),
                workGate.currentSupervisionGeneration(), panes);
    }

    /** Reconcile a repeated user launch without tearing down a healthy split. */
    private void requestPaneHealthCheck() {
        paneHealthCheckPending = true;
        // A background singleTop Activity receives onNewIntent before onResume. Queueing with the
        // old supervision generation would guarantee that the worker result is discarded.
        if (hostDestroyed || !watchActive || taskLane == null || workGate == null) return;
        List<SplitHostTaskLane.PaneTicket> panes = new ArrayList<>(2);
        addHealthTicket(panes, left);
        addHealthTicket(panes, right);
        if (panes.isEmpty()) return;
        taskLane.requestPaneHealthCheck(this, workGate.hostGeneration(),
                workGate.currentSupervisionGeneration(), panes);
        Log.i(TAG, "same split launch: queued pane health check");
    }

    private void addHealthTicket(List<SplitHostTaskLane.PaneTicket> tickets, Pane pane) {
        if (pane.vd == null || pane.launchInFlight || pane.pkg == null || pane.pkg.isEmpty()) return;
        Integer displayId = paneDisplayId(pane);
        if (displayId == null) return;
        int index = paneIndex(pane);
        tickets.add(new SplitHostTaskLane.PaneTicket(workGate.hostGeneration(), index,
                workGate.currentPaneGeneration(index), pane.pkg, displayId));
    }

    /** Rebuilds a lost VD as soon as its SurfaceView is valid; watchdog must not wait for a restart. */
    private void ensurePaneRunning(Pane pane) {
        if (hostDestroyed || pane.vd != null || pane.view == null
                || pane.pkg == null || pane.pkg.isEmpty() || pane.w <= 0 || pane.h <= 0) return;
        Surface surface = pane.view.getHolder().getSurface();
        if (surface == null || !surface.isValid()) return;
        Log.w(TAG, "pane " + pane.side + " lost VirtualDisplay — rebuilding it");
        // A lost VD is also a lost launch target. releasePane normally clears this flag through
        // SurfaceView.surfaceDestroyed, but OEM teardown can drop the display without delivering
        // that callback; leave launched=true here and launchApp would silently skip the rebuild.
        pane.launched = false;
        pane.launchInFlight = false;
        createVirtualDisplay(pane, surface);
        launchApp(pane);
    }

    private void addSupervisionTicket(List<SplitHostTaskLane.PaneTicket> tickets,
                                      Pane pane, long now) {
        if (pane.vd == null || !pane.launched || pane.launchInFlight) return;
        if (now - pane.launchedAt < WATCH_GRACE_MS) return;
        Integer displayId = paneDisplayId(pane);
        if (displayId == null || pane.pkg == null || pane.pkg.isEmpty()) return;
        int index = paneIndex(pane);
        tickets.add(new SplitHostTaskLane.PaneTicket(workGate.hostGeneration(), index,
                workGate.currentPaneGeneration(index), pane.pkg, displayId));
    }

    /** One worker snapshot is applied to both panes only if its lifecycle generation is current. */
    void onSupervisionSnapshot(SplitHostTaskLane.SupervisionRequest request, boolean enabled,
                               SplitHostTaskSnapshot snapshot) {
        if (workGate == null || !workGate.acceptsSupervision(
                request.hostGeneration, request.supervisionGeneration)) return;
        if (request.immediate) paneHealthCheckPending = false;
        if (!enabled || dragging || (!request.immediate
                && System.currentTimeMillis() < resizeUntil)) return;
        for (SplitHostTaskLane.PaneTicket ticket : request.panes) {
            if (!isPaneTicketCurrent(ticket)) continue;
            Pane pane = paneForIndex(ticket.paneIndex);
            if (pane.launchInFlight || (!request.immediate
                    && System.currentTimeMillis() - pane.launchedAt < WATCH_GRACE_MS)) {
                continue;
            }
            boolean alive = snapshot.isAlive(ticket.packageName, ticket.displayId);
            if (request.immediate && !alive) {
                // A user retry is allowed to recover a pane even if the watchdog had already
                // reached its normal restart budget.
                pane.restarts = 0;
            }
            supervisePane(pane, alive);
        }
    }

    private void supervisePane(Pane pane, boolean alive) {
        if (alive) { pane.restarts = 0; return; }
        if (pane.restarts >= WATCH_MAX_RESTARTS) {
            // Приложение стабильно не живёт на VirtualDisplay — дальнейшие попытки только мигали бы
            // экраном. Останавливаемся и оставляем след в логе, чтобы причину можно было найти.
            if (pane.restarts == WATCH_MAX_RESTARTS) {
                pane.restarts++;
                Log.w(TAG, "надзиратель: " + pane.pkg + " (" + pane.side + ") не удержался после "
                        + WATCH_MAX_RESTARTS + " попыток — перезапуск прекращён");
            }
            return;
        }
        pane.restarts++;
        Log.i(TAG, "надзиратель: " + pane.pkg + " (" + pane.side + ") пропал со своего дисплея"
                + " → перезапуск " + pane.restarts + "/" + WATCH_MAX_RESTARTS);
        pane.launched = false;      // launchApp выходит по этому флагу — снимаем, иначе перезапуска не будет
        launchApp(pane);
    }

    private boolean isPaneTicketCurrent(SplitHostTaskLane.PaneTicket ticket) {
        if (workGate == null || hostDestroyed || !workGate.acceptsPane(
                ticket.hostGeneration, ticket.paneIndex, ticket.paneGeneration)) return false;
        Pane pane = paneForIndex(ticket.paneIndex);
        if (pane.vd == null || !ticket.packageName.equals(pane.pkg)) return false;
        Integer displayId = paneDisplayId(pane);
        return displayId != null && displayId == ticket.displayId;
    }

    private Integer paneDisplayId(Pane pane) {
        try {
            return pane.vd != null && pane.vd.getDisplay() != null
                    ? pane.vd.getDisplay().getDisplayId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int paneIndex(Pane pane) {
        return pane == left ? SplitHostGenerationGate.LEFT : SplitHostGenerationGate.RIGHT;
    }

    private Pane paneForIndex(int paneIndex) {
        return paneIndex == SplitHostGenerationGate.LEFT ? left : right;
    }

    private int effectiveDpi(Pane pane) {
        return pane.dpi > 0 ? pane.dpi : defaultDpi;
    }

    /**
     * MapKit and a few OEM layout implementations publish their new viewport one or two frames
     * after VirtualDisplay.resize(). Tell the hosted process which pane changed so its client hook
     * can replay the map layout after that asynchronous configuration update.
     */
    private void notifyPaneResized(Pane pane) {
        if (pane.pkg == null || pane.pkg.isEmpty()) return;
        try {
            Intent intent = new Intent(ACTION_VD_RESIZED);
            intent.setPackage(pane.pkg);
            intent.putExtra("width", pane.vdWidth);
            intent.putExtra("height", pane.vdHeight);
            intent.putExtra("dpi", pane.vdDpi);
            intent.putExtra("generation", pane.resizeVersion);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            sendBroadcast(intent, VD_RESIZE_PERMISSION);
            Log.i(TAG, "VD resize notified " + pane.side + " package=" + pane.pkg
                    + " generation=" + pane.resizeVersion + " "
                    + pane.vdWidth + "x" + pane.vdHeight + " dpi=" + pane.vdDpi);
        } catch (Exception e) {
            Log.w(TAG, "VD resize notify " + pane.side + " failed: " + e.getMessage());
        }
    }

    private void releasePane(Pane pane) {
        if (workGate != null) workGate.invalidatePane(paneIndex(pane));
        pane.launchInFlight = false;
        pane.launched = false;
        // Счётчик попыток — свойство ПОПЫТКИ, а не панели: пересоздание (своп, новый сплит) начинает
        // с чистого листа. Иначе исчерпанный лимит переезжал бы на другое приложение и надзиратель
        // молча отказывался бы его поднимать.
        pane.restarts = 0;
        Handler queue = resizeQueue();
        if (pane.resizeRunnable != null) {
            queue.removeCallbacks(pane.resizeRunnable);
        }
        VirtualDisplay oldVd;
        synchronized (pane) {
            pane.resizeRunnable = null;
            oldVd = pane.vd;
            pane.vd = null;
            pane.pendingWidth = 0;
            pane.pendingHeight = 0;
            pane.pendingDpi = 0;
            pane.resizeAttempts = 0;
            pane.vdWidth = 0;
            pane.vdHeight = 0;
            pane.vdDpi = 0;
        }
        if (oldVd != null) {
            try { oldVd.release(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Ввод (инъекция в VirtualDisplay) — hidden API, нужен INJECT_EVENTS (см. шапку класса)
    // -------------------------------------------------------------------------

    private void resolveTouchInjection() {
        if (touchInjectionResolved) return;
        touchInjectionResolved = true;
        try {
            setDisplayIdMethod = MotionEvent.class.getMethod("setDisplayId", int.class);
            inputManager = getSystemService("input");
            if (inputManager == null) throw new IllegalStateException("InputManager unavailable");
            injectInputEventMethod = inputManager.getClass().getMethod(
                    "injectInputEvent", InputEvent.class, int.class);
            touchInjectionAvailable = true;
        } catch (Throwable e) {
            touchInjectionAvailable = false;
            Log.w(TAG, "injectTouch недоступен (методы не найдены): " + e.getMessage()
                    + " — ввод в VD требует root+Frida-в-system_server или роутинга WM");
        }
    }

    private void injectTouch(Pane pane, MotionEvent ev) {
        resolveTouchInjection();
        if (!touchInjectionAvailable || pane.vd == null || pane.vd.getDisplay() == null) return;
        MotionEvent copy = null;
        try {
            int displayId = pane.vd.getDisplay().getDisplayId();
            copy = MotionEvent.obtain(ev);
            setDisplayIdMethod.invoke(copy, displayId);
            // InputManager.injectInputEvent(..., 0) = INJECT_INPUT_EVENT_MODE_ASYNC.
            injectInputEventMethod.invoke(inputManager, copy, 0);
        } catch (Throwable e) {
            // A SecurityException is permanent for this process. Stop reflecting on every MOVE;
            // the surface still consumes the event and the rest of the host remains responsive.
            touchInjectionAvailable = false;
            if (!touchWarned) {
                touchWarned = true;
                Throwable cause = e.getCause() == null ? e : e.getCause();
                Log.w(TAG, "injectTouch недоступен (нет INJECT_EVENTS у Native): "
                        + cause.getMessage()
                        + " — ввод в VD требует root+Frida-в-system_server или роутинга WM");
            }
        } finally {
            if (copy != null) copy.recycle();
        }
    }

    // -------------------------------------------------------------------------
    // Разделитель (живой ресайз пропорций)
    // -------------------------------------------------------------------------

    private void setupDivider() {
        final View divider = findViewById(R.id.splitDivider);

        // Двойной тап по handle-бару — поменять окна местами (работает всегда).
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                cancelResizeGesture();
                swapApps();
                doubleTapConsumed = true;
                return true;
            }
        });

        final View grip = findViewById(R.id.splitHandleGrip);

        // Диагностика: без неё «делитель мёртв» неотличимо от «resizable не доехал до хоста».
        Log.i(TAG, "setupDivider: resizable=" + resizable + " presetIdx=" + presetIdx
                + " presetId=" + presetId);

        if (!resizable) {   // пропорция зафиксирована пресетом — делитель только визуальный + свап
            divider.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    gripPressed(grip, true);      // видимый щуп: рукоятка реагирует на касание
                    Log.i(TAG, "делитель нажат, но пропорция зафиксирована (resizable=false)");
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    gripPressed(grip, false);
                }
                gd.onTouchEvent(e);
                return true;
            });
            return;
        }

        divider.setOnTouchListener(new View.OnTouchListener() {
            float startX, startFraction;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                gd.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (doubleTapConsumed) { doubleTapConsumed = false; return true; }
                        startX = e.getRawX();
                        startFraction = currentFraction();
                        lastDragFraction = startFraction;
                        gripPressed(grip, true);          // видимый щуп — рукоятка «загорается»
                        Log.i(TAG, "драг начат: fraction=" + startFraction);
                        // beginResize (снимки/оверлей) НЕ должен ронять жест: если PixelCopy/overlay
                        // бросит, делитель всё равно обязан ездить. Поэтому маска — в try, а движение
                        // делителя ниже от неё не зависит.
                        try { beginResize(); } catch (Throwable t) { Log.w(TAG, "beginResize: " + t); }
                        moveDivider(startFraction);       // сразу поставить в текущую позицию
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (resizeState != ResizeState.DRAGGING) return true;
                        float f = fractionForDx(startFraction, e.getRawX() - startX);
                        lastDragFraction = f;
                        moveDivider(f);                   // делитель едет НЕЗАВИСИМО от маски
                        try { previewFraction(f); } catch (Throwable t) { Log.w(TAG, "preview: " + t); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        gripPressed(grip, false);
                        if (resizeState == ResizeState.DRAGGING) {
                            lastDragFraction = fractionForDx(startFraction, e.getRawX() - startX);
                            endResize(lastDragFraction);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        gripPressed(grip, false);
                        cancelResizeGesture();
                        return true;
                }
                return false;
            }
        });
    }

    /** Сдвиг делителя за пальцем через translationX (не вызывает layout → не будит surfaceChanged). */
    private void moveDivider(float f) {
        View panes = findViewById(R.id.splitPanes);
        View divider = findViewById(R.id.splitDivider);
        if (panes == null || divider == null) return;
        int usable = panes.getWidth() - divider.getWidth();
        if (usable <= 0) return;
        divider.setTranslationX(Math.round(usable * f) - left.container.getWidth());
    }

    /** Видимая индикация нажатия рукоятки (неразрушающе — масштабом, drawable не трогаем). Это и щуп
     *  «касание дошло до делителя»: если при нажатии рукоятка не увеличивается — касания сюда не доходят. */
    private void gripPressed(View grip, boolean pressed) {
        if (grip == null) return;
        float s = pressed ? 1.6f : 1f;
        grip.setScaleX(s);
        grip.setScaleY(s);
    }

    // -------------------------------------------------------------------------
    // Живой ресайз пропорции: во время драга РЕАЛЬНЫЕ панели не трогаем
    // -------------------------------------------------------------------------
    //
    // Почему так. Смена веса панели → layout → surfaceChanged → vd.resize(). А vd.resize() для
    // приложения на этом дисплее — СМЕНА КОНФИГУРАЦИИ: приложение, не объявившее configChanges,
    // пересоздаётся целиком. При драге это десятки пересозданий в секунду — приложение не успевает
    // сойтись ни к какому состоянию (его «плющит», размер не совпадает с окном, и оно не выправляется).
    // Плюс vd.resize() асинхронный: поверхность уже нового размера, а последний кадр приложения —
    // старого, и композитор его растягивает.
    //
    // Поэтому во время драга панели СТОЯТ НА МЕСТЕ, а поверх лежит оверлей с размытым снимком обоих
    // окон — тянется именно он. На отпускании один раз выставляем вес → ровно один surfaceChanged →
    // ровно один vd.resize. Оверлей убираем не сразу, а дав приложению отрисовать новый кадр, иначе
    // пользователь увидит тот самый растянутый кадр, ради сокрытия которого всё и затевалось.

    private boolean resizable = false;
    private int presetIdx = -1;
    private String presetId = "";
    private enum ResizeState { IDLE, DRAGGING, SETTLING }
    private ResizeState resizeState = ResizeState.IDLE;
    private boolean dragging = false; // совместимость с watchdog; true только в DRAGGING
    private boolean doubleTapConsumed = false;
    private float lastDragFraction = 0.5f;
    private int resizeGeneration = 0;
    private long resizeUntil = 0L;          // до этого момента надзиратель панелей молчит
    private android.widget.FrameLayout maskOverlay;
    private android.widget.ImageView maskLeft, maskRight;
    private View maskDivider, maskGrip;

    private static final float MIN_PANE_DP = 260f;   // уже этого приложения начинают падать честно
    private static final long  MASK_HOLD_MS = 450;   // сколько ждём новый кадр перед снятием маски

    /**
     * Доля левого окна для смещения пальца, с ограничением минимальной ЛОГИЧЕСКОЙ ширины обеих VD.
     * Пиксельный минимум у панелей разный: 260dp при 320dpi — это 520px, а не 260px физического
     * экрана. Старый clamp использовал density хоста и позволял загнать VD с пользовательским DPI
     * в телефонный/compat-размер, где Activity начинала letterbox'иться маленьким прямоугольником.
     */
    private float fractionForDx(float startFraction, float dx) {
        View panes = findViewById(R.id.splitPanes);
        View divider = findViewById(R.id.splitDivider);
        int usable = panes.getWidth() - divider.getWidth();
        if (usable <= 0) return startFraction;
        return clampFraction(startFraction + dx / usable);
    }

    private float clampFraction(float value) {
        View panes = findViewById(R.id.splitPanes);
        View divider = findViewById(R.id.splitDivider);
        int usable = panes == null || divider == null ? 0 : panes.getWidth() - divider.getWidth();
        return SplitHostGeometry.clampFraction(value, usable,
                effectiveDpi(left), effectiveDpi(right), MIN_PANE_DP);
    }

    /** Снять размытые снимки обеих панелей и показать оверлей вместо живых окон. */
    private void beginResize() {
        resizeGeneration++;                 // инвалидировать callbacks прошлого жеста
        resizeState = ResizeState.DRAGGING;
        dragging = true;
        resizeUntil = System.currentTimeMillis() + 60_000;  // надзиратель молчит, пока тянем
        ensureMaskOverlay();
        resetMaskPreviewTransforms();
        gripPressed(maskGrip, true);
        if (maskOverlay != null) maskOverlay.animate().cancel();
        if (maskLeft != null) maskLeft.setImageDrawable(null);
        if (maskRight != null) maskRight.setImageDrawable(null);
        captureBlurred(left,  maskLeft, resizeGeneration);
        captureBlurred(right, maskRight, resizeGeneration);
        maskOverlay.setVisibility(View.VISIBLE);
        maskOverlay.setAlpha(1f);
        previewFraction(currentFraction());
    }

    /**
     * Превью пропорции. Настоящие панели и поверхности НЕ трогаем (иначе полетят vd.resize) — двигаем
     * картинки оверлея И САМ ДЕЛИТЕЛЬ.
     *
     * Делитель обязателен: без него рукоятка остаётся там, куда её поставили неизменные веса, и ресайз
     * выглядит намертво залипшим — палец едет, а на экране ничего не происходит. Сдвигаем через
     * translationX: это не вызывает layout, а значит не будит surfaceChanged.
     */
    private void previewFraction(float f) {
        if (maskOverlay == null) return;
        View panes = findViewById(R.id.splitPanes);
        View divider = findViewById(R.id.splitDivider);
        int usable = panes.getWidth() - divider.getWidth();
        if (usable <= 0) return;
        int lw = Math.round(usable * f);
        // Preview обязан сам рисовать непрозрачный divider. Настоящий divider находится под этим
        // оверлеем; прозрачный зазор между maskLeft/maskRight показывал бы старые SurfaceView.
        if (maskBaseLeftWidth <= 0 || maskBaseRightWidth <= 0) {
            initializeMaskPreviewGeometry(usable, divider.getWidth());
        }
        // Do not touch LayoutParams here. A divider MOVE can arrive every few milliseconds;
        // relayouting the FrameLayout would stall the UI and can cause SurfaceView churn. The
        // preview children have a fixed base layout and are moved/scaled by RenderThread-only
        // properties instead.
        float leftScale = maskBaseLeftWidth > 0 ? (float) lw / maskBaseLeftWidth : 1f;
        float rightScale = maskBaseRightWidth > 0
                ? (float) (usable - lw) / maskBaseRightWidth : 1f;
        float dx = lw - maskBaseLeftWidth;
        maskLeft.setScaleX(leftScale);
        maskRight.setScaleX(rightScale);
        maskRight.setTranslationX(dx);
        maskDivider.setTranslationX(dx);
    }

    private int maskBaseLeftWidth;
    private int maskBaseRightWidth;

    private void initializeMaskPreviewGeometry(int usable, int dividerWidth) {
        if (maskLeft == null || maskRight == null || maskDivider == null) return;
        maskBaseLeftWidth = left.container.getWidth();
        maskBaseRightWidth = right.container.getWidth();
        if (maskBaseLeftWidth <= 0 || maskBaseRightWidth <= 0) return;
        setLp(maskLeft, maskBaseLeftWidth, 0);
        setLp(maskRight, maskBaseRightWidth, maskBaseLeftWidth + dividerWidth);
        setLp(maskDivider, dividerWidth, maskBaseLeftWidth);
        maskLeft.setPivotX(0f);
        maskLeft.setPivotY(0f);
        maskRight.setPivotX(0f);
        maskRight.setPivotY(0f);
        maskDivider.setPivotX(0f);
        maskDivider.setPivotY(0f);
    }

    private void resetMaskPreviewTransforms() {
        if (maskLeft != null) {
            maskLeft.setScaleX(1f);
            maskLeft.setTranslationX(0f);
        }
        if (maskRight != null) {
            maskRight.setScaleX(1f);
            maskRight.setTranslationX(0f);
        }
        if (maskDivider != null) maskDivider.setTranslationX(0f);
        maskBaseLeftWidth = 0;
        maskBaseRightWidth = 0;
    }

    private void setLp(View v, int w, int leftMargin) {
        if (v == null) return;
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) v.getLayoutParams();
        lp.width = w;
        lp.leftMargin = leftMargin;
        v.setLayoutParams(lp);
    }

    /**
     * Отпустили делитель: один раз меняем пропорцию, ждём, пока приложения отрисуются в новом
     * размере, и уводим маску кроссфейдом. Если приложение не пережило конфигурацию, его поднимет
     * общий watchdog после полноценного grace-периода.
     */
    private void endResize(final float f) {
        if (resizeState != ResizeState.DRAGGING) return;
        dragging = false;
        resizeState = ResizeState.SETTLING;
        gripPressed(maskGrip, false);
        final int generation = resizeGeneration;
        final long leftVersion = left.resizeVersion;
        final long rightVersion = right.resizeVersion;
        // Сдвиг делителя был визуальным (translationX) — снимаем его, дальше позицию задаёт вес.
        View divider = findViewById(R.id.splitDivider);
        if (divider != null) divider.setTranslationX(0f);
        final float committed = clampFraction(f);
        applyFraction(committed);                            // ЕДИНСТВЕННАЯ смена веса за весь жест
        // Activity приложения может пересоздаваться после display configuration change. Даём тот же
        // grace, что при первоначальном запуске, и не пытаемся объявить её мёртвой через 630 ms.
        long now = System.currentTimeMillis();
        left.launchedAt = now;
        right.launchedAt = now;
        resizeUntil = now + WATCH_GRACE_MS;
        waitForSurfaceResize(generation, leftVersion, rightVersion, 0);
    }

    /** Ждём, пока оба SurfaceView действительно вызовут vd.resize; затем даём приложениям кадр и гасим маску. */
    private void waitForSurfaceResize(int generation, long leftBefore, long rightBefore, int attempt) {
        View root = findViewById(R.id.splitHostRoot);
        if (root == null) return;
        boolean resized = paneResizeSettled(left) && paneResizeSettled(right);
        if (!resized && attempt < 20) {
            root.postDelayed(() -> waitForSurfaceResize(generation, leftBefore, rightBefore, attempt + 1), 50);
            return;
        }
        root.postDelayed(() -> finishResizeVisual(generation), MASK_HOLD_MS);
    }

    private void finishResizeVisual(int generation) {
        if (generation != resizeGeneration || resizeState != ResizeState.SETTLING) return;
        Runnable done = () -> {
            if (generation != resizeGeneration) return;
            if (left.container.getWidth() > 0 && right.container.getWidth() > 0) {
                float actual = clampFraction(currentFraction());
                lastDragFraction = actual;
                saveFraction(actual);
            }
            if (maskOverlay != null) maskOverlay.setVisibility(View.GONE);
            resetMaskPreviewTransforms();
            resizeState = ResizeState.IDLE;
        };
        if (maskOverlay == null) { done.run(); return; }
        maskOverlay.animate().cancel();
        maskOverlay.animate().alpha(0f).setDuration(180).withEndAction(done).start();
    }

    private boolean paneResizeSettled(Pane pane) {
        if (pane.pkg == null || pane.pkg.isEmpty()) return true;
        return pane.vd != null && pane.vdWidth == pane.w && pane.vdHeight == pane.h
                && pane.vdDpi == effectiveDpi(pane);
    }

    /** CANCEL и двойной тап не должны менять пропорцию или сохранять случайную rawX. */
    private void cancelResizeGesture() {
        resizeGeneration++;
        dragging = false;
        resizeState = ResizeState.IDLE;
        gripPressed(maskGrip, false);
        View divider = findViewById(R.id.splitDivider);
        if (divider != null) divider.setTranslationX(0f);
        if (maskOverlay != null) {
            maskOverlay.animate().cancel();
            maskOverlay.setVisibility(View.GONE);
        }
        resetMaskPreviewTransforms();
        resizeUntil = 0L;
    }

    /** Вернуть выставленную пропорцию в пресет RestoreMode (единственный источник истины). */
    private void saveFraction(float f) {
        if (presetIdx < 0 && (presetId == null || presetId.isEmpty())) return;
        LastSessionStore.recordSplit(this, left.pkg, right.pkg, getIntent().getIntExtra(EXTRA_RATIO, 1),
                left.dpi, right.dpi, resizable, f, presetIdx, presetId);
        try {
            Intent i = new Intent("ru.big.town.restoremode.SPLIT_RATIO_SAVE");
            i.setClassName("ru.big.town.restoremode",
                    "ru.big.town.restoremode.SplitRatioSaveReceiver");
            i.putExtra("presetIdx", presetIdx);
            i.putExtra("presetId", presetId == null ? "" : presetId);
            i.putExtra("split", f);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            sendBroadcast(i);
        } catch (Exception e) { Log.w(TAG, "saveFraction: " + e.getMessage()); }
    }

    /**
     * Оверлей поверх панелей: две картинки + собственный непрозрачный divider с grip.
     *
     * SurfaceView живёт в отдельном Surface-слое. Поэтому нельзя оставлять между картинками
     * прозрачную щель и рассчитывать, что перемещённый divider из нижней view-иерархии её закроет:
     * через щель композитор показывает старые буферы приложений. Оверлей полностью непрозрачен ещё
     * до завершения асинхронного PixelCopy, а divider рисуется в том же верхнем слое, что и preview.
     */
    private void ensureMaskOverlay() {
        if (maskOverlay != null) return;
        maskOverlay = new android.widget.FrameLayout(this);
        maskOverlay.setBackgroundColor(android.graphics.Color.BLACK);
        maskOverlay.setClickable(false);
        maskLeft  = newMaskImage();
        maskRight = newMaskImage();
        android.widget.FrameLayout divider = new android.widget.FrameLayout(this);
        divider.setBackgroundColor(android.graphics.Color.BLACK);
        divider.setLayoutParams(new android.widget.FrameLayout.LayoutParams(0,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        maskDivider = divider;

        maskGrip = new View(this);
        float density = getResources().getDisplayMetrics().density;
        android.widget.FrameLayout.LayoutParams gripLp = new android.widget.FrameLayout.LayoutParams(
                Math.round(7f * density), Math.round(80f * density));
        gripLp.gravity = android.view.Gravity.CENTER;
        maskGrip.setLayoutParams(gripLp);
        maskGrip.setBackgroundResource(R.drawable.split_handle_grip);
        divider.addView(maskGrip);

        maskOverlay.addView(maskLeft);
        maskOverlay.addView(maskRight);
        maskOverlay.addView(maskDivider); // последним: divider/grip всегда поверх снимков
        // Кладём в КОРЕНЬ (FrameLayout), а не в splitPanes: тот горизонтальный LinearLayout, и оверлей
        // стал бы в нём ещё одной колонкой, отобрав ширину у самих панелей.
        // SurfaceView здесь в обычном z-порядке (setZOrderOnTop не вызывается нигде), поэтому обычная
        // вьюха поверх него в иерархии перекрывает поверхность штатно.
        ((ViewGroup) findViewById(R.id.splitHostRoot)).addView(maskOverlay,
                new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        maskOverlay.setVisibility(View.GONE);
    }

    private android.widget.ImageView newMaskImage() {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);   // тянется вместе с окном
        iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(0,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        applyRoundedCorners(iv);
        return iv;
    }

    /**
     * Снимок панели → сразу в МАЛЕНЬКИЙ bitmap (PixelCopy сам масштабирует) → в ImageView.
     *
     * Маленький кадр с включённой фильтрацией тянется билинейно. Предыдущая версия дополнительно
     * делала несколько проходов box-blur прямо в callback PixelCopy на main thread; на H97C это
     * заметно задерживало обработку MOVE/UP и могло совпасть с началом VD-resize.
     *
     * RenderEffect.createBlurEffect тут недоступен — это API 31, а голова на API 30.
     */
    private void captureBlurred(final Pane pane, final android.widget.ImageView target, final int generation) {
        if (pane.view == null || pane.view.getWidth() <= 0) return;
        final int w = Math.max(16, pane.view.getWidth() / 8);
        final int h = Math.max(16, pane.view.getHeight() / 8);
        try {
            final Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            PixelCopy.request(pane.view, small, res -> {
                if (res != PixelCopy.SUCCESS) {
                    small.recycle();
                    Log.w(TAG, "PixelCopy " + pane.side + " = " + res);
                    return;
                }
                if (generation != resizeGeneration || resizeState != ResizeState.DRAGGING) {
                    small.recycle();
                    return;
                }
                target.setImageBitmap(small);
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Exception e) {
            Log.w(TAG, "captureBlurred " + pane.side + ": " + e.getMessage());
        }
    }

    /** Скруглённые углы окна (SurfaceView через outline-клип). */
    private void applyRoundedCorners(View v) {
        if (v == null) return;
        final float r = getResources().getDisplayMetrics().density * 18f;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    /**
     * Меняет приложения (и их DPI) местами: левое ↔ правое. Пересоздаём оба VirtualDisplay на
     * тех же surface — приложения на старых дисплеях уничтожаются (DESTROY_CONTENT_ON_REMOVAL),
     * запускаем поменянные. Без выхода из хоста.
     */
    private void swapApps() {
        String tp = left.pkg; left.pkg = right.pkg; right.pkg = tp;
        int td = left.dpi; left.dpi = right.dpi; right.dpi = td; // DPI едет за приложением
        Log.i(TAG, "swapApps → left=" + left.pkg + " right=" + right.pkg);
        recreatePane(left);
        recreatePane(right);
    }

    private void recreatePane(Pane pane) {
        releasePane(pane);
        Surface s = pane.view.getHolder().getSurface();
        if (s != null && s.isValid() && pane.w > 0 && pane.h > 0) {
            createVirtualDisplay(pane, s);
            launchApp(pane);
        }
    }

    /** The app supports one process-wide VD host; a newer display handoff retires this instance. */
    void onSupersededByHost(long newHostGeneration) {
        if (hostDestroyed || workGate == null
                || workGate.hostGeneration() >= newHostGeneration) return;
        retireAsyncHostWork(true);
        Log.i(TAG, "host generation " + workGate.hostGeneration()
                + " superseded by " + newHostGeneration + " — finishing old host");
        finish();
    }

    private void retireAsyncHostWork(boolean releaseLease) {
        hostDestroyed = true;
        watchActive = false;
        watchHandler.removeCallbacksAndMessages(null);
        if (workGate != null) {
            workGate.close();
            if (taskLane != null) {
                if (releaseLease) taskLane.cancelHost(workGate.hostGeneration());
                else taskLane.cancelHostWork(workGate.hostGeneration());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hostDestroyed) return;
        // Стережём панели только пока сплит на переднем плане: свёрнутый сплит приложения не показывает,
        // и «пропажа» там ожидаема — перезапускать нечего.
        watchActive = true;
        if (workGate != null) workGate.resumeSupervision();
        if (paneHealthCheckPending) requestPaneHealthCheck();
        watchHandler.removeCallbacks(watchTick);
        watchHandler.postDelayed(watchTick, WATCH_PERIOD_MS);
    }

    @Override
    protected void onPause() {
        watchActive = false;
        watchHandler.removeCallbacks(watchTick);
        if (workGate != null) {
            workGate.pauseSupervision();
            if (taskLane != null) taskLane.cancelSupervision(workGate.hostGeneration());
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        WeakReference<SplitHostActivity> current = activeHost;
        if (current.get() == this) activeHost = new WeakReference<>(null);
        retireAsyncHostWork(true);
        cancelResizeGesture();
        releasePane(left);
        releasePane(right);
        stopResizeThread();
        if (screenLiftReceiverRegistered) {
            try {
                unregisterReceiver(screenLiftReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            screenLiftReceiverRegistered = false;
        }
        super.onDestroy();
    }

    private void stopResizeThread() {
        Handler queue = resizeHandler;
        resizeHandler = null;
        if (queue != null) queue.removeCallbacksAndMessages(null);
        HandlerThread thread = resizeThread;
        resizeThread = null;
        if (thread != null) thread.quitSafely();
    }

    /**
     * Finishes the Native VD host before a single application is launched as an ordinary physical
     * task. Destroy-content-on-removal retires its VD activities; vd_bypass then clamps the new task.
     */
    static boolean closeActiveHost() {
        SplitHostActivity host = activeHost.get();
        if (host == null || host.isFinishing() || host.isDestroyed()) return false;
        activeHost = new WeakReference<>(null);
        host.runOnUiThread(host::finishAndRemoveTask);
        Log.i(TAG, "active VD host closed before physical window launch");
        return true;
    }

    /**
     * Открыть одно приложение в VD-панели на физическом экране. Геометрия задаётся layout хоста,
     * DPI — самим VirtualDisplay, поэтому WindowManager system_server не требует hot-path hooks.
     */
    public static void launchSingle(android.content.Context ctx, String pkg, int dpi, int displayId) {
        launchSingle(ctx, pkg, dpi, displayId, true);
    }

    static void launchSingle(android.content.Context ctx, String pkg, int dpi, int displayId,
                             boolean remember) {
        if (ctx == null || pkg == null || pkg.isEmpty()) {
            Log.w(TAG, "launchSingle: пустой пакет — пропуск");
            return;
        }
        if (displayId != 0 && displayId != 1) displayId = 0;
        if (remember) LastSessionStore.recordSingleHost(ctx, pkg, dpi, displayId);
        SplitHostTaskLane.get(ctx).requestSingleHost(pkg, Math.max(0, dpi), displayId);
    }

    /** Called on main only after the process lane has resolved the target package. */
    static void startSingleHost(android.content.Context ctx, String pkg, int dpi, int displayId) {
        try {
            Intent i = new Intent(ctx, SplitHostActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra(EXTRA_LEFT, pkg);
            i.putExtra(EXTRA_RIGHT, "");
            i.putExtra(EXTRA_RATIO, 1);
            i.putExtra(EXTRA_LEFT_DPI, Math.max(0, dpi));
            i.putExtra(EXTRA_RIGHT_DPI, 0);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            ctx.startActivity(i, options.toBundle());
            Log.i(TAG, "launchSingle host started pkg=" + pkg + " dpi=" + dpi
                    + " display=" + displayId);
        } catch (Exception e) {
            Log.e(TAG, "launchSingle failed: " + e.getMessage());
        }
    }

    /** Запустить сплит на VirtualDisplay из статического контекста (напр. из {@link SetModesReceiverDynamic}
     *  по долгому нажатию на слот дока). Дублирует {@code SetModesService.launchVirtualSplit}: включает
     *  freeform-настройки (resizable) и стартует хост с extras. Пустой left/right → no-op. */
    public static void launchSplit(android.content.Context ctx, String left, String right, int ratio, int leftDpi, int rightDpi) {
        launchSplit(ctx, left, right, ratio, leftDpi, rightDpi, false, 0f, -1, "");
    }

    /** @param resizable разрешить менять пропорцию перетаскиванием делителя
     *  @param split     стартовая доля левого окна 0..1 (0 = вычислить из ratio)
     *  @param presetIdx индекс пресета в RestoreMode — по нему туда вернётся новая пропорция */
    public static void launchSplit(android.content.Context ctx, String left, String right, int ratio,
                                   int leftDpi, int rightDpi, boolean resizable, float split, int presetIdx) {
        launchSplit(ctx, left, right, ratio, leftDpi, rightDpi, resizable, split, presetIdx, "");
    }

    public static void launchSplit(android.content.Context ctx, String left, String right, int ratio,
                                   int leftDpi, int rightDpi, boolean resizable, float split,
                                   int presetIdx, String presetId) {
        launchSplit(ctx, left, right, ratio, leftDpi, rightDpi, resizable, split,
                presetIdx, presetId, true);
    }

    static void launchSplit(android.content.Context ctx, String left, String right, int ratio,
                            int leftDpi, int rightDpi, boolean resizable, float split,
                            int presetIdx, String presetId, boolean remember) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            Log.w(TAG, "launchSplit: пустой пакет — пропуск");
            return;
        }
        if (remember) {
            LastSessionStore.recordSplit(ctx, left, right, ratio, leftDpi, rightDpi,
                    resizable, split, presetIdx, presetId);
        }
        try {
            android.provider.Settings.Global.putInt(ctx.getContentResolver(), "enable_freeform_support", 1);
            android.provider.Settings.Global.putInt(ctx.getContentResolver(), "force_resizable_activities", 1);
        } catch (Exception e) {
            Log.w(TAG, "launchSplit freeform settings: " + e.getMessage());
        }
        try {
            Intent i = new Intent(ctx, SplitHostActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra(EXTRA_LEFT, left);
            i.putExtra(EXTRA_RIGHT, right);
            i.putExtra(EXTRA_RATIO, ratio);
            i.putExtra(EXTRA_LEFT_DPI, leftDpi);
            i.putExtra(EXTRA_RIGHT_DPI, rightDpi);
            i.putExtra(EXTRA_RESIZABLE, resizable);
            i.putExtra(EXTRA_SPLIT, split);
            i.putExtra(EXTRA_PRESET_IDX, presetIdx);
            i.putExtra(EXTRA_PRESET_ID, presetId);
            i.putExtra(EXTRA_RECONCILE, remember);
            DockLaunchGuard.arm(ctx, 0, "ru.big.town.anative");
            ctx.startActivity(i);
            Log.i(TAG, "launchSplit host started " + left + "/" + right);
        } catch (Exception e) {
            Log.e(TAG, "launchSplit failed: " + e.getMessage());
        }
    }

}
