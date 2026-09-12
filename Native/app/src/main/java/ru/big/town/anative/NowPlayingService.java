package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ридер «сейчас играет»: читает активную медиа-сессию ЛЮБОГО плеера (Яндекс.Музыка и т.п.) через
 * {@link MediaSessionManager} и публикует метаданные (название/исполнитель/альбом/состояние/позиция/
 * длительность/обложка) для наших поверхностей.
 *
 * <p><b>Почему это работает, а штатный виджет — нет:</b> сторонние плееры публикуют стандартную
 * {@code MediaSession}, но стоковый агрегатор головы (PAL {@code com.qinggan.media}/{@code tai.pal})
 * показывает только «свои» плееры. {@code MediaSessionManager} же видит ВСЕ сессии системно — нужен
 * лишь привилегированный {@code MEDIA_CONTENT_CONTROL} (Native — priv-app, разрешение в whitelist).
 * См. память reference_media_nowplaying_pipeline.
 *
 * <p><b>Публикация (два канала):</b>
 * <ul>
 *   <li>статический снимок читает {@link NowPlayingProvider} (pull: {@code content://…/nowplaying});
 *       обложка — файл {@link #artFile(Context)}, отдаётся провайдером через openFile;</li>
 *   <li>broadcast {@link #ACTION_NOW_PLAYING} с текстовыми extras (push: живое обновление UI).</li>
 * </ul>
 *
 * <p>Фича не завязана на Frida/VD — работает в обоих флейворах (Native priv-app и в full, и в light).
 */
public class NowPlayingService extends Service {

    private static final String TAG = "$$$ NowPlayingService $$$";
    private static final String CHANNEL_ID = "now_playing_channel";
    private static final String NATIVE_PREFS = "NativePrefs";
    private static final String MANUAL_SOURCE_KEY = "voyahtune_media_source_package";

    public static final String ACTION_NOW_PLAYING         = "ru.big.town.anative.NOW_PLAYING";
    public static final String ACTION_NOW_PLAYING_SOURCES = "ru.big.town.anative.NOW_PLAYING_SOURCES";
    public static final String ACTION_REQUEST_NOW_PLAYING = "ru.big.town.anative.REQUEST_NOW_PLAYING";

    private static final String ART_FILE_NAME = "nowplaying_art.png";
    // All current consumers render the cover at 66–160 dp. Keeping a full-size artwork supplied
    // by a player (often 2–4K) only increases the Native heap, PNG IO and cross-process decode.
    private static final int MAX_ART_EDGE_PX = 512;

    // Legacy-маршрут для совместимости со старыми версиями steeringwheelkeys.js. Новый хук на каждое
    // initial DOWN синхронно вызывает NowPlayingProvider.media_command: там берётся СВЕЖИЙ список сессий,
    // выбирается конкретная цель и команда доставляется ровно одним путём. Старый Settings.Global ключ
    // продолжаем публиковать, чтобы обновление APK отдельно от Packaging не ломало кнопки:
    //   "native"   → отдать клавишу штатной маршрутизации прошивки (BT/AVRCP, штатный плеер и его прокси,
    //                нет сессии, старт до готовности, нет привилегии) — стоковое поведение;
    //   "dispatch" → сторонний плеер (Яндекс/Spotify/…) → хук сам шлёт медиа-эвент в активную сессию.
    // Дефолт при отсутствии ключа — passthrough (см. хук), т.е. заводское поведение.
    static final String MEDIA_ROUTE_KEY = "voyahtune_mediaRoute";
    private static final String ROUTE_NATIVE   = "native";
    private static final String ROUTE_DISPATCH = "dispatch";
    private static final AtomicLong INSTANCE_SEQUENCE = new AtomicLong();
    private static final AtomicLong ACTIVE_INSTANCE = new AtomicLong();
    private static final AtomicLong ROUTE_REVISION = new AtomicLong();
    private static final AtomicLong BROADCAST_REVISION = new AtomicLong();
    private static final Object INSTANCE_CALLBACK_LOCK = new Object();
    private static final Object ART_COMMIT_LOCK = new Object();
    private static final ThreadPoolExecutor ROUTE_EXECUTOR = newDeliveryExecutor("MediaRoute");
    private static final ThreadPoolExecutor BROADCAST_EXECUTOR =
            newDeliveryExecutor("NowPlayingBroadcast");
    private static final LatestValueDelivery<RouteWrite> ROUTE_WRITES =
            new LatestValueDelivery<>(ROUTE_EXECUTOR, NowPlayingService::writeRoute);
    private static final LatestValueDelivery<BroadcastWrite> BROADCASTS =
            new LatestValueDelivery<>(BROADCAST_EXECUTOR, NowPlayingService::sendSnapshotBroadcast);
    // Confined to ROUTE_EXECUTOR.
    private static long lastWrittenRouteGeneration;
    private static String lastWrittenRoute = "";

    private static ThreadPoolExecutor newDeliveryExecutor(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final class RouteWrite {
        final Context app;
        final long generation;
        final String route;
        final String pkg;
        final boolean clearGeneration;

        RouteWrite(Context app, long generation, String route, String pkg,
                   boolean clearGeneration) {
            this.app = app;
            this.generation = generation;
            this.route = route;
            this.pkg = pkg;
            this.clearGeneration = clearGeneration;
        }
    }

    private static final class BroadcastWrite {
        final Context app;
        final long generation;
        final Intent intent;

        BroadcastWrite(Context app, long generation, Intent intent) {
            this.app = app;
            this.generation = generation;
            this.intent = intent;
        }
    }

    // The provider's Binder thread reads this object while the worker thread replaces it. One
    // volatile reference gives every reader a coherent snapshot, unlike independent volatile
    // fields which could otherwise mix title from one track with position from the next one.
    static volatile Snapshot sSnapshot = Snapshot.empty();
    static volatile List<SourceSnapshot> sSources = Collections.emptyList();
    private static volatile NowPlayingService activeService;

    static final class Snapshot {
        final String title;
        final String artist;
        final String album;
        final String packageName;
        final String appLabel;
        final int state;
        final long position;
        final long duration;
        final boolean hasArt;
        final long updatedAt;

        Snapshot(String title, String artist, String album, String packageName, String appLabel,
                 int state, long position, long duration, boolean hasArt, long updatedAt) {
            this.title = nz(title);
            this.artist = nz(artist);
            this.album = nz(album);
            this.packageName = nz(packageName);
            this.appLabel = nz(appLabel);
            this.state = state;
            this.position = position;
            this.duration = duration;
            this.hasArt = hasArt;
            this.updatedAt = updatedAt;
        }

        static Snapshot empty() {
            return new Snapshot("", "", "", "", "", PlaybackState.STATE_NONE,
                    0L, 0L, false, System.currentTimeMillis());
        }
    }

    /** One real active MediaSession exposed to source pickers in launcher and RestoreMode. */
    static final class SourceSnapshot {
        final String packageName;
        final String appLabel;
        final String title;
        final String artist;
        final int state;
        final boolean selected;

        SourceSnapshot(String packageName, String appLabel, String title, String artist,
                       int state, boolean selected) {
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.title = title;
            this.artist = artist;
            this.state = state;
            this.selected = selected;
        }
    }

    /** Файл обложки (приватный для Native; наружу отдаётся через NowPlayingProvider.openFile). */
    static File artFile(Context ctx) {
        return new File(ctx.getFilesDir(), ART_FILE_NAME);
    }

    private volatile Handler handler;
    private HandlerThread workerThread;
    private volatile Handler callbackHandler;
    private HandlerThread callbackThread;
    private volatile MediaRefreshDelivery mediaRefreshes;
    private volatile boolean stopping;
    private long instanceGeneration;
    private long watcherSequence;
    private volatile long activeWatcherEpoch;
    private boolean receiverRegistered;
    private MediaSessionManager msm;
    private volatile MediaController current;        // пишет worker, читает лёгкий callback ingress
    private volatile List<MediaController> activeControllers = Collections.emptyList();
    // Empty means automatic priority selection. A source picker sets this package until its
    // MediaSession disappears, so a paused second player is not immediately replaced by another
    // active session on the next callback.
    private String manuallySelectedPackage = "";
    // PackageManager lookups are Binder calls and metadata/playback callbacks arrive often. Labels
    // only change on package update, so a small service-lifetime cache removes this hot-path work.
    private final Map<String, String> appLabelCache = new HashMap<>();
    private MediaController.Callback controllerCallback;
    private Bitmap lastWrittenArt;                   // тот же Bitmap не кодируем в PNG на каждый playback callback

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> offerMediaRefresh(MediaRefreshDelivery.Work.REBUILD, "sessions");

    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // UI открылось/подписалось — сразу отдать текущий снимок.
            offerMediaRefresh(MediaRefreshDelivery.Work.PUBLISH, "request");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = this;
        manuallySelectedPackage = getSharedPreferences(NATIVE_PREFS, MODE_PRIVATE)
                .getString(MANUAL_SOURCE_KEY, "");
        // Сначала fail-closed сбрасываем legacy-маршрут. Даже если foreground-уведомление не
        // поднимется, старое значение "dispatch" не должно остаться после неудачного запуска.
        synchronized (INSTANCE_CALLBACK_LOCK) {
            instanceGeneration = INSTANCE_SEQUENCE.incrementAndGet();
            ACTIVE_INSTANCE.set(instanceGeneration);
            MediaControlRouter.activateObserverGeneration(instanceGeneration);
        }
        resetSnapshotForNewInstance();
        enqueueRoute(ROUTE_NATIVE, "startup", false);
        enqueueSnapshotBroadcast(buildSnapshotIntent());
        // Foreground обязателен (стартуем через startForegroundService). Не удалось поднять — тихо
        // гасим ТОЛЬКО этот сервис (stopSelf), НО не роняем процесс: иначе утащим за собой применение
        // режима на старте (ApplyEngine в том же процессе).
        try {
            createNotificationChannel();
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Медиа-информация")
                    .setContentText("Отслеживание текущего трека")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .build();
            startForeground(6, n);
        } catch (Exception e) {
            Log.e(TAG, "onCreate startForeground: " + e.getMessage());
            stopSelf();
            return;
        }
        workerThread = new HandlerThread("NowPlaying", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        handler = new Handler(workerThread.getLooper());
        callbackThread = new HandlerThread("NowPlayingIngress", Process.THREAD_PRIORITY_BACKGROUND);
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());
        mediaRefreshes = new MediaRefreshDelivery(command -> {
            Handler worker = handler;
            if (stopping || worker == null || !worker.post(command)) {
                throw new RejectedExecutionException("NowPlaying worker unavailable");
            }
        }, this::runMediaRefresh);
        final Handler callbacks = callbackHandler;
        dispatchWorker("initialize", () -> {
            try {
                registerReceiver(requestReceiver,
                        new IntentFilter(ACTION_REQUEST_NOW_PLAYING), null, callbacks,
                        RECEIVER_EXPORTED);
                receiverRegistered = true;
            } catch (Exception e) {
                Log.w(TAG, "onCreate registerReceiver: " + e.getMessage());
            }
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            try {
                // null вместо NotificationListener-компонента разрешён при MEDIA_CONTENT_CONTROL.
                msm.addOnActiveSessionsChangedListener(sessionsListener, null, callbacks);
                onSessionsChanged(msm.getActiveSessions(null));  // первичный снимок
                Log.i(TAG, "onCreate: подписка на активные медиа-сессии установлена");
            } catch (SecurityException e) {
                Log.e(TAG, "onCreate: нет MEDIA_CONTENT_CONTROL (whitelist на enforce-ROM?) — ридер инертен: "
                        + e.getMessage());
            }
        });
    }

    private boolean dispatchWorker(String source, Runnable action) {
        Handler worker = handler;
        if (stopping || worker == null) return false;
        return worker.post(() -> {
            if (stopping) return;
            try {
                action.run();
            } catch (Throwable e) {
                Log.e(TAG, source + ": " + e.getMessage(), e);
            }
        });
    }

    private boolean isActiveInstance() {
        return !stopping && ACTIVE_INSTANCE.get() == instanceGeneration;
    }

    private boolean isActiveWatcher(long generation, long watcherEpoch) {
        return !stopping
                && ACTIVE_INSTANCE.get() == generation
                && activeWatcherEpoch == watcherEpoch;
    }

    private void offerMediaRefresh(MediaRefreshDelivery.Work work, String reason) {
        if (!isActiveInstance()) return;
        MediaRefreshDelivery refreshes = mediaRefreshes;
        if (refreshes != null) refreshes.offer(work, reason);
    }

    private void runMediaRefresh(MediaRefreshDelivery.Work work, String reason) {
        if (!isActiveInstance()) return;
        switch (work) {
            case REBUILD:
                onSessionsChanged(safeSessions());
                break;
            case SOURCES:
                publishSources(reason);
                publish(reason);
                break;
            case REPICK:
                repick(reason);
                break;
            case PUBLISH:
                publish(reason);
                break;
            default:
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Выбор активной сессии + подписка на её изменения
    // -------------------------------------------------------------------------

    /**
     * ВАЖНО: следим за playback ВСЕХ активных сессий, а не только выбранной.
     *
     * OnActiveSessionsChangedListener приходит на создание/уничтожение сессии и на setActive, но НЕ на
     * смену того, кто реально играет. Раньше мы подписывались только на выбранную сессию, поэтому
     * сценарий «играет Bluetooth → пользователь запускает Spotify» ломался: сессия Spotify появлялась
     * (колбэк был), но играть ещё не начинала, поэтому топ-сессией оставался BT. Когда Spotify начинал
     * играть, состав сессий не менялся — колбэка не было, current навсегда оставался на BT, а
     * voyahtune_mediaRoute залипал в "native". Кнопки руля уходили в штатный маршрут к мёртвой сессии:
     * первое нажатие ещё ставило паузу, дальше не работало ничего.
     */
    private final java.util.List<MediaController> watched = new java.util.ArrayList<>();
    private final java.util.List<MediaController.Callback> watchedCbs = new java.util.ArrayList<>();

    private void onSessionsChanged(List<MediaController> controllers) {
        if (!isActiveInstance()) return;
        Handler callbacks = callbackHandler;
        if (callbacks == null) return;
        final long generation = instanceGeneration;
        final long watcherEpoch;
        synchronized (INSTANCE_CALLBACK_LOCK) {
            if (!isActiveInstance()) return;
            watcherEpoch = ++watcherSequence;
            activeWatcherEpoch = watcherEpoch;
        }
        detachAll();
        activeControllers = controllers == null
                ? Collections.emptyList() : new ArrayList<>(controllers);
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (!isActiveInstance()) return;
                final MediaController watchedController = c;
                final MediaSession.Token watchedToken = watchedController.getSessionToken();
                MediaController.Callback cb = new MediaController.Callback() {
                    private final PlaybackActivityTracker activity = new PlaybackActivityTracker(
                            MediaControlRouter.isActiveState(safePlaybackState(watchedController)));

                    @Override public void onMetadataChanged(MediaMetadata metadata) {
                        if (!isActiveWatcher(generation, watcherEpoch)) return;
                        // Metadata cannot change controller priority. Non-current metadata is noise.
                        if (sameController(watchedController, current)) {
                            offerMediaRefresh(MediaRefreshDelivery.Work.PUBLISH, "metadata");
                        }
                    }
                    @Override public void onPlaybackStateChanged(PlaybackState state) {
                        if (!isActiveWatcher(generation, watcherEpoch)) return;
                        boolean active = MediaControlRouter.isActiveState(state);
                        PlaybackActivityTracker.Change change = activity.update(active);
                        if (change == PlaybackActivityTracker.Change.ENTERED_ACTIVE) {
                            notePlayingIfActive(watchedToken, generation, watcherEpoch);
                        }
                        if (!isActiveWatcher(generation, watcherEpoch)) return;
                        if (change != PlaybackActivityTracker.Change.SAME) {
                            offerMediaRefresh(MediaRefreshDelivery.Work.REPICK, "playback-edge");
                        } else if (sameController(watchedController, current)) {
                            // Same playback class cannot change selection, but the public snapshot
                            // still needs current state/position for the selected controller.
                            offerMediaRefresh(MediaRefreshDelivery.Work.PUBLISH, "playback");
                        }
                    }
                    @Override public void onSessionDestroyed() {
                        if (isActiveWatcher(generation, watcherEpoch)) {
                            offerMediaRefresh(MediaRefreshDelivery.Work.REBUILD,
                                    "session-destroyed");
                        }
                    }
                };
                try {
                    c.registerCallback(cb, callbacks);
                    watched.add(c);
                    watchedCbs.add(cb);
                } catch (Exception ignored) {}
            }
        }
        if (!isActiveInstance()) return;
        MediaController selected = findPackage(controllers, manuallySelectedPackage);
        if (selected == null) {
            manuallySelectedPackage = "";
            getSharedPreferences(NATIVE_PREFS, MODE_PRIVATE).edit()
                    .remove(MANUAL_SOURCE_KEY).apply();
            selected = MediaControlRouter.selectController(controllers, instanceGeneration);
        }
        if (!isActiveInstance()) return;
        current = selected;
        Log.i(TAG, "сессий: " + (controllers == null ? 0 : controllers.size())
                + ", топ: " + (current != null ? current.getPackageName() : "нет"));
        publishMediaRoute();
        publishSources("sessions-changed");
        publish("sessions-changed");
    }

    /**
     * Перевыбор топ-сессии по СВЕЖЕМУ списку. Дёргается из playback/metadata-колбэков любой сессии —
     * именно здесь ловится «BT замолчал, заиграл Spotify», чего listener состава сессий не видит.
     * publishMediaRoute внутри дедуплицирует запись, так что Settings.Global не долбится.
     */
    private void repick(String reason) {
        if (!isActiveInstance()) return;
        List<MediaController> controllers = safeSessions();
        if (!isActiveInstance()) return;
        activeControllers = controllers == null
                ? Collections.emptyList() : new ArrayList<>(controllers);
        MediaController pick = findPackage(controllers, manuallySelectedPackage);
        if (pick == null) {
            manuallySelectedPackage = "";
            getSharedPreferences(NATIVE_PREFS, MODE_PRIVATE).edit()
                    .remove(MANUAL_SOURCE_KEY).apply();
            pick = MediaControlRouter.selectController(controllers, instanceGeneration);
        }
        if (!isActiveInstance()) return;
        if (!sameController(pick, current)) {
            current = pick;
            Log.i(TAG, "топ-сессия сменилась (" + reason + "): "
                    + (current != null ? current.getPackageName() : "нет"));
        }
        publishMediaRoute();
        publishSources(reason);
        publish(reason);
    }

    private void notePlayingIfActive(MediaSession.Token token, long generation,
                                     long watcherEpoch) {
        synchronized (INSTANCE_CALLBACK_LOCK) {
            if (!isActiveWatcher(generation, watcherEpoch)) return;
            MediaControlRouter.notePlaying(token, generation);
        }
    }

    private void detachAll() {
        for (int i = 0; i < watched.size(); i++) {
            try { watched.get(i).unregisterCallback(watchedCbs.get(i)); } catch (Exception ignored) {}
        }
        watched.clear();
        watchedCbs.clear();
        controllerCallback = null;
    }

    /**
     * Публикует в {@link #MEDIA_ROUTE_KEY} решение о маршрутизации медиа-кнопок руля по ТЕКУЩЕЙ топ-сессии
     * (её же читает {@code dispatchMediaKeyEvent}). Пишем ТОЛЬКО на смену решения — иначе долбили бы
     * Settings.Global (playback-колбэки идут десятками в секунду). Нет сессии / OEM-пакет → штатная
     * маршрутизация; сторонний плеер → перехват.
     */
    private void publishMediaRoute() {
        String pkg = (current != null) ? nz(current.getPackageName()) : "";
        String route = (pkg.isEmpty() || isOemMediaPackage(pkg)) ? ROUTE_NATIVE : ROUTE_DISPATCH;
        enqueueRoute(route, pkg.isEmpty() ? "none" : pkg, false);
    }

    private void enqueueRoute(String route, String pkg, boolean clearGeneration) {
        if (instanceGeneration == 0L) return;
        Context app = getApplicationContext();
        // Terminal token is greater than every normal token of this service instance. Therefore
        // an operation which entered before onDestroy and returned from Binder later cannot replace
        // the queued fail-closed ROUTE_NATIVE write. The next instance still has a greater token.
        long deliveryToken = (instanceGeneration << 1) | (clearGeneration ? 1L : 0L);
        ROUTE_WRITES.offer(deliveryToken, ROUTE_REVISION.incrementAndGet(),
                new RouteWrite(app, instanceGeneration, route, pkg, clearGeneration));
    }

    private static void writeRoute(RouteWrite request) {
        if (request == null || ACTIVE_INSTANCE.get() != request.generation) return;
        try {
            if (lastWrittenRouteGeneration != request.generation
                    || !request.route.equals(lastWrittenRoute)) {
                boolean written = android.provider.Settings.Global.putString(
                        request.app.getContentResolver(), MEDIA_ROUTE_KEY, request.route);
                if (written) {
                    lastWrittenRouteGeneration = request.generation;
                    lastWrittenRoute = request.route;
                    Log.i(TAG, "mediaRoute → " + request.route
                            + " (pkg=" + request.pkg + ")");
                } else {
                    Log.w(TAG, "publishMediaRoute: Settings.Global rejected write");
                }
            }
        } catch (Exception e) {
            // Нет WRITE_SECURE_SETTINGS (enforce-ROM без whitelist) → ключ не появится → хук по умолчанию
            // passthrough (стоковое поведение). Безопасная деградация.
            Log.w(TAG, "publishMediaRoute: " + e.getMessage());
        } finally {
            if (request.clearGeneration) {
                ACTIVE_INSTANCE.compareAndSet(request.generation, 0L);
            }
        }
    }

    /**
     * OEM/системная медиа-сессия, которую корректно рулит штатная маршрутизация прошивки: Bluetooth/AVRCP
     * (телефон), штатный плеер {@code com.qinggan.media} и его прокси/зеркала. Всё остальное — сторонние
     * приложения (Яндекс.Музыка/Spotify/…), которыми штатная маршрутизация может не управлять, поэтому их
     * перехватываем и шлём сами.
     */
    private static boolean isOemMediaPackage(String pkg) {
        return pkg.startsWith("com.android.") || pkg.startsWith("com.qinggan.") || pkg.equals("android");
    }

    private List<MediaController> safeSessions() {
        try { return msm.getActiveSessions(null); } catch (Exception e) { return null; }
    }

    private static PlaybackState safePlaybackState(MediaController controller) {
        try { return controller == null ? null : controller.getPlaybackState(); }
        catch (Exception e) { return null; }
    }

    private boolean sameController(MediaController a, MediaController b) {
        if (a == null || b == null) return a == b;
        try { return a.getSessionToken().equals(b.getSessionToken()); } catch (Exception e) { return false; }
    }

    private static MediaController findPackage(List<MediaController> controllers, String pkg) {
        if (controllers == null || pkg == null || pkg.isEmpty()) return null;
        for (MediaController controller : controllers) {
            try {
                if (pkg.equals(controller.getPackageName())) return controller;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Снять подписки со всех отслеживаемых сессий (следим за всеми, а не только за выбранной). */
    private void detachCurrent() {
        detachAll();
    }

    // -------------------------------------------------------------------------
    // Публикация снимка (статик для провайдера + broadcast для UI)
    // -------------------------------------------------------------------------

    /** Rebuilds the source picker from actual active MediaSession controllers, never installed APKs. */
    private void publishSources(String reason) {
        if (!isActiveInstance()) return;
        List<SourceSnapshot> next = new ArrayList<>();
        List<MediaController> controllers = activeControllers;
        if (controllers != null) {
            for (MediaController controller : controllers) {
                try {
                    String pkg = nz(controller.getPackageName());
                    if (pkg.isEmpty()) continue;
                    MediaMetadata md = controller.getMetadata();
                    PlaybackState ps = controller.getPlaybackState();
                    // A controller without either metadata or state is an implementation detail,
                    // not a usable music source. Paused sessions with metadata remain visible.
                    if (md == null && ps == null) continue;
                    String title = md == null ? "" : firstNonEmpty(
                            md.getString(MediaMetadata.METADATA_KEY_TITLE),
                            md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                    String artist = md == null ? "" : firstNonEmpty(
                            md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                            md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                            md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                    int state = ps == null ? PlaybackState.STATE_NONE : ps.getState();
                    next.add(new SourceSnapshot(pkg, appLabel(pkg), title, artist, state,
                            sameController(controller, current)));
                } catch (Exception ignored) {}
            }
        }
        List<SourceSnapshot> previous = sSources;
        sSources = Collections.unmodifiableList(next);
        // Source title/artist are refreshed in the provider for the next picker opening, but do
        // not need a cross-process broadcast for every metadata tick. A notification is only
        // useful when the selectable source topology or selected package changed.
        if (sameSourceTopology(previous, next)) return;
        Intent update = new Intent(ACTION_NOW_PLAYING_SOURCES);
        update.putExtra("count", next.size());
        update.putExtra("updatedAt", System.currentTimeMillis());
        enqueueSnapshotBroadcast(update);
        Log.i(TAG, "publishSources(" + reason + "): " + next.size());
    }

    private static boolean sameSourceTopology(List<SourceSnapshot> before,
                                              List<SourceSnapshot> after) {
        if (before == after) return true;
        if (before == null || after == null || before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            SourceSnapshot a = before.get(i);
            SourceSnapshot b = after.get(i);
            if (!a.packageName.equals(b.packageName) || !a.appLabel.equals(b.appLabel)
                    || a.selected != b.selected) return false;
        }
        return true;
    }

    /** Selects one package from the current active-session set. */
    static boolean selectSource(String packageName) {
        NowPlayingService service = activeService;
        if (service == null || packageName == null || packageName.trim().isEmpty()) return false;
        String requested = packageName.trim();
        return service.dispatchWorker("select-source", () -> {
            List<MediaController> controllers = service.safeSessions();
            service.activeControllers = controllers == null
                    ? Collections.emptyList() : new ArrayList<>(controllers);
            MediaController selected = findPackage(controllers, requested);
            if (selected == null) {
                Log.w(TAG, "selectSource: active MediaSession not found for " + requested);
                return;
            }
            service.manuallySelectedPackage = requested;
            service.getSharedPreferences(NATIVE_PREFS, MODE_PRIVATE).edit()
                    .putString(MANUAL_SOURCE_KEY, requested).apply();
            service.current = selected;
            // Keep the OEM-native third-party alias and steering-wheel/provider commands on the
            // exact session the user selected. Without this, an active Bluetooth session can win
            // the next fresh MediaSession arbitration even though an app source row was selected.
            MediaControlRouter.pinSource(selected.getSessionToken());
            service.publishMediaRoute();
            service.publishSources("source-selected");
            service.publish("source-selected");
        });
    }

    private void publish(String reason) {
        if (!isActiveInstance()) return;
        try {
            String title = "", artist = "", album = "", pkg = "", appLabel = "";
            int state = PlaybackState.STATE_NONE;
            long position = 0L, duration = 0L;
            boolean hasArt = false;

            if (current != null) {
                pkg = nz(current.getPackageName());
                appLabel = appLabel(pkg);
                MediaMetadata md = current.getMetadata();
                if (md != null) {
                    title  = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_TITLE),
                                           md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                    artist = firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                                           md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                                           md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                    album  = nz(md.getString(MediaMetadata.METADATA_KEY_ALBUM));
                    duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    hasArt = writeArt(md);
                }
                PlaybackState ps = current.getPlaybackState();
                if (ps != null) { state = ps.getState(); position = ps.getPosition(); }
            }

            if (!isActiveInstance()) return;
            sSnapshot = new Snapshot(title, artist, album, pkg, appLabel, state, position,
                    duration, hasArt, System.currentTimeMillis());
            enqueueSnapshotBroadcast(buildSnapshotIntent());

            Log.i(TAG, "publish(" + reason + "): [" + pkg + "] " + title + " — " + artist
                    + " state=" + state + " art=" + hasArt);
        } catch (Exception e) {
            Log.w(TAG, "publish: " + e.getMessage());
        }
    }

    private void resetSnapshotForNewInstance() {
        sSnapshot = Snapshot.empty();
        sSources = Collections.emptyList();
    }

    private static Intent buildSnapshotIntent() {
        Snapshot snapshot = sSnapshot;
        Intent intent = new Intent(ACTION_NOW_PLAYING);
        intent.setPackage(null);
        intent.putExtra("title", snapshot.title);
        intent.putExtra("artist", snapshot.artist);
        intent.putExtra("album", snapshot.album);
        intent.putExtra("package", snapshot.packageName);
        intent.putExtra("appLabel", snapshot.appLabel);
        intent.putExtra("state", snapshot.state);
        intent.putExtra("position", snapshot.position);
        intent.putExtra("duration", snapshot.duration);
        intent.putExtra("hasArt", snapshot.hasArt);
        intent.putExtra("updatedAt", snapshot.updatedAt);
        return intent;
    }

    private void enqueueSnapshotBroadcast(Intent intent) {
        if (instanceGeneration == 0L || intent == null) return;
        Context app = getApplicationContext();
        BROADCASTS.offer(instanceGeneration, BROADCAST_REVISION.incrementAndGet(),
                new BroadcastWrite(app, instanceGeneration, intent));
    }

    private static void sendSnapshotBroadcast(BroadcastWrite request) {
        if (request == null || ACTIVE_INSTANCE.get() != request.generation) return;
        request.app.sendBroadcast(request.intent);
    }

    /** Сохраняет обложку в приватный файл (отдаётся наружу через NowPlayingProvider). @return есть ли обложка. */
    private boolean writeArt(MediaMetadata md) {
        if (!isActiveInstance()) return false;
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        File f = artFile(this);
        if (bmp == null) {
            lastWrittenArt = null;
            synchronized (ART_COMMIT_LOCK) {
                if (isActiveInstance() && f.exists()) f.delete();
            }
            return false;
        }
        // PlaybackState может приходить много раз в секунду с тем же объектом MediaMetadata/Bitmap.
        // PNG-compress + перезапись файла нужны только при реальной смене обложки.
        if (bmp == lastWrittenArt && f.exists() && f.length() > 0L) {
            return isActiveInstance();
        }
        File pending = new File(getFilesDir(), ART_FILE_NAME + "." + instanceGeneration + ".tmp");
        Bitmap encoded = scaleArtForTransport(bmp);
        try (FileOutputStream fos = new FileOutputStream(pending)) {
            boolean written = encoded.compress(Bitmap.CompressFormat.PNG, 100, fos);
            if (!written) {
                pending.delete();
                return false;
            }
            synchronized (ART_COMMIT_LOCK) {
                if (!isActiveInstance()) {
                    pending.delete();
                    return false;
                }
                Os.rename(pending.getAbsolutePath(), f.getAbsolutePath());
                lastWrittenArt = bmp;
                return true;
            }
        } catch (Exception e) {
            pending.delete();
            Log.w(TAG, "writeArt: " + e.getMessage());
            return false;
        } finally {
            // The scaled bitmap is ours. The original belongs to MediaMetadata and must not be
            // recycled here because the framework/player may still retain it.
            if (encoded != bmp && !encoded.isRecycled()) encoded.recycle();
        }
    }

    private static Bitmap scaleArtForTransport(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int edge = Math.max(width, height);
        if (edge <= MAX_ART_EDGE_PX || edge <= 0) return source;
        float scale = (float) MAX_ART_EDGE_PX / edge;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        try {
            return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        } catch (Exception ignored) {
            // A malformed/hardware Bitmap should never prevent the metadata update.
            return source;
        }
    }

    private String appLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = appLabelCache.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {}
        appLabelCache.put(pkg, label);
        return label;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy()");
        if (activeService == this) activeService = null;
        sSources = Collections.emptyList();
        synchronized (INSTANCE_CALLBACK_LOCK) {
            stopping = true;
            activeWatcherEpoch++;
            MediaControlRouter.deactivateObserverGeneration(instanceGeneration);
        }
        MediaRefreshDelivery refreshes = mediaRefreshes;
        mediaRefreshes = null;
        if (refreshes != null) refreshes.close();
        HandlerThread ingress = callbackThread;
        callbackHandler = null;
        callbackThread = null;
        // quit(), not quitSafely(): framework may already have queued a callback storm. Every
        // callback has been invalidated above, so draining that backlog has no value.
        if (ingress != null) ingress.quit();
        Handler worker = handler;
        HandlerThread thread = workerThread;
        enqueueRoute(ROUTE_NATIVE, "destroy", true);
        if (worker != null && thread != null) {
            boolean queued = worker.postAtFrontOfQueue(() -> {
                try {
                    if (receiverRegistered) {
                        try { unregisterReceiver(requestReceiver); } catch (Exception ignored) {}
                        receiverRegistered = false;
                    }
                    if (msm != null) {
                        try { msm.removeOnActiveSessionsChangedListener(sessionsListener); }
                        catch (Exception ignored) {}
                    }
                    detachCurrent();
                } finally {
                    worker.removeCallbacksAndMessages(null);
                    handler = null;
                    thread.quitSafely();
                }
            });
            if (!queued) thread.quitSafely();
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Медиа-информация",
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}
