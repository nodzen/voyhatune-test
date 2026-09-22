package ru.big.town.anative;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single process-wide launch lane for physical, split, widget and cluster targets.
 * Package/task Binder work stays off main and every public request is latest-wins.
 */
final class AppLaunchCoordinator {
    private static final String TAG = "$$$ AppLaunch $$$";
    private static final int TASK_RETIRE_MAX_ATTEMPTS = 20;
    private static final long TASK_RETIRE_RETRY_MS = 100L;
    private static volatile AppLaunchCoordinator instance;

    static AppLaunchCoordinator get(Context context) {
        AppLaunchCoordinator value = instance;
        if (value != null) return value;
        synchronized (AppLaunchCoordinator.class) {
            value = instance;
            if (value == null) instance = value = new AppLaunchCoordinator(context.getApplicationContext());
            return value;
        }
    }

    static final class Result {
        final boolean started;
        final String reason;
        Result(boolean started, String reason) { this.started = started; this.reason = reason; }
    }

    interface Callback { void complete(Result result); }

    private final Context app;
    private final ActivityManager activityManager;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LaunchGeneration generations = new LaunchGeneration();
    private final ConcurrentHashMap<String, ComponentName> componentCache = new ConcurrentHashMap<>();
    private Field displayIdField;
    private boolean displayIdResolved;
    private Object activityTaskManager;
    private Method removeTask;

    private AppLaunchCoordinator(Context app) {
        this.app = app;
        activityManager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        HandlerThread thread = new HandlerThread("VoyahTuneLaunch", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent.getData() != null) componentCache.remove(intent.getData().getSchemeSpecificPart());
            }
        }, packages);
    }

    int findClusterDisplay() {
        DisplayManager manager = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        List<ClusterDisplayPolicy.Candidate> candidates = new ArrayList<>();
        if (manager != null) {
            for (Display display : manager.getDisplays()) {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                display.getRealMetrics(metrics);
                candidates.add(new ClusterDisplayPolicy.Candidate(display.getDisplayId(), display.getName(),
                        (display.getFlags() & Display.FLAG_PRESENTATION) != 0,
                        metrics.widthPixels, metrics.heightPixels));
            }
        }
        int visibleDisplay = ClusterDisplayPolicy.choose(candidates);
        if (visibleDisplay >= 0) return visibleDisplay;

        // The OEM media display is private on some H97C builds and therefore omitted from
        // DisplayManager. Its live ScreenActivity task still identifies the purpose and dynamic ID.
        List<ClusterDisplayPolicy.TaskCandidate> tasks = new ArrayList<>();
        if (activityManager != null) {
            try {
                List<ActivityManager.RunningTaskInfo> running = activityManager.getRunningTasks(100);
                if (running != null) {
                    for (ActivityManager.RunningTaskInfo task : running) {
                        ComponentName component = task.baseActivity != null
                                ? task.baseActivity : task.topActivity;
                        Integer displayId = readDisplayId(task);
                        if (component != null && displayId != null) {
                            tasks.add(new ClusterDisplayPolicy.TaskCandidate(displayId,
                                    component.getPackageName(), component.getClassName()));
                        }
                    }
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "cluster task discovery: " + error.getClass().getSimpleName());
            }
        }
        return ClusterDisplayPolicy.chooseOemTaskFallback(tasks);
    }

    void openOnCluster(String packageName, String source, Callback callback) {
        long generation = generations.next();
        if (!ClusterLaunchPolicy.allows(global("voyahtune_cluster_allowed_packages"), packageName)) {
            deliver(generation, callback, new Result(false, "not_allowlisted"));
            return;
        }
        int clusterDisplay = findClusterDisplay();
        if (clusterDisplay < 0) {
            deliver(generation, callback, new Result(false, "cluster_display_unavailable"));
            return;
        }
        ClusterMediaHostActivity.closeActiveHost(() -> worker.post(() -> {
            ComponentName component = resolveComponent(packageName);
            if (!generations.accepts(generation)) return;
            if (component == null) {
                deliver(generation, callback, new Result(false, "no_launch_component"));
                return;
            }
            main.post(() -> {
                if (!generations.accepts(generation)) return;
                try {
                    Intent host = new Intent(app, ClusterMediaHostActivity.class)
                            .putExtra(ClusterMediaHostActivity.EXTRA_PACKAGE, packageName)
                            .putExtra(ClusterMediaHostActivity.EXTRA_SOURCE, source)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(clusterDisplay);
                    app.startActivity(host, options.toBundle());
                    deliver(generation, callback, new Result(true, "host_started"));
                } catch (RuntimeException e) {
                    deliver(generation, callback, new Result(false, "host_start_failed"));
                    Log.w(TAG, "cluster host start: " + e.getMessage());
                }
            });
        }));
    }

    void openPhysical(String packageName, int displayId, boolean fullscreen, Callback callback) {
        long generation = generations.next();
        Runnable afterHosts = () -> prepareAndLaunch(generation, packageName, displayId,
                fullscreen, true, "physical", callback);
        SplitHostActivity.closeActiveHost(() ->
                ClusterMediaHostActivity.closeActiveHost(() ->
                        MediaWidgetOverlayService.releaseAppDisplay(afterHosts)));
    }

    void openOnVirtualDisplay(String packageName, int displayId, String source, Callback callback) {
        long generation = generations.next();
        prepareAndLaunch(generation, packageName, displayId, false, true, source, callback);
    }

    private void prepareAndLaunch(long generation, String packageName, int displayId,
                                  boolean fullscreen, boolean retireCrossDisplay, String source,
                                  Callback callback) {
        worker.post(() -> {
            long preparedAt = SystemClock.elapsedRealtime();
            ComponentName component = resolveComponent(packageName);
            if (component == null) {
                deliver(generation, callback, new Result(false, "no_launch_component"));
                return;
            }
            List<Integer> retiring = retireCrossDisplay
                    ? retireTasks(packageName, displayId, generation) : new ArrayList<>();
            if (retiring == null) {
                deliver(generation, callback, new Result(false, "task_retirement_failed"));
                return;
            }
            if (!generations.accepts(generation)) return;
            awaitTaskRetirement(generation, packageName, displayId, fullscreen, source,
                    callback, component, preparedAt, retiring, 0);
        });
    }

    /** removeTask is asynchronous on the OEM Android 11 build; launching before disappearance can
     * resurrect the old-display task and leave the new TextureView black. */
    private void awaitTaskRetirement(long generation, String packageName, int displayId,
                                     boolean fullscreen, String source, Callback callback,
                                     ComponentName component, long preparedAt,
                                     List<Integer> retiring, int attempt) {
        if (!generations.accepts(generation)) return;
        try {
            boolean remains = false;
            if (!retiring.isEmpty() && activityManager != null) {
                List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1000);
                if (tasks == null) throw new IllegalStateException("task_list_unavailable");
                for (ActivityManager.RunningTaskInfo task : tasks) {
                    if (retiring.contains(task.taskId)) {
                        remains = true;
                        break;
                    }
                }
            }
            if (remains) {
                if (attempt >= TASK_RETIRE_MAX_ATTEMPTS) {
                    deliver(generation, callback, new Result(false, "task_retirement_timeout"));
                    return;
                }
                worker.postDelayed(() -> awaitTaskRetirement(generation, packageName, displayId,
                        fullscreen, source, callback, component, preparedAt, retiring, attempt + 1),
                        TASK_RETIRE_RETRY_MS);
                return;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "await task retirement: " + error.getClass().getSimpleName());
            deliver(generation, callback, new Result(false, "task_retirement_check_failed"));
            return;
        }
        postPreparedLaunch(generation, packageName, displayId, fullscreen, source,
                callback, component, preparedAt);
    }

    private void postPreparedLaunch(long generation, String packageName, int displayId,
                                    boolean fullscreen, String source, Callback callback,
                                    ComponentName component, long preparedAt) {
        long prepareMs = SystemClock.elapsedRealtime() - preparedAt;
        main.post(() -> {
            if (!generations.accepts(generation)) return;
            try {
                Intent launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                android.os.Bundle bundle = options.toBundle();
                if (fullscreen) bundle.putInt("android.activity.windowingMode", 1);
                long startedAt = SystemClock.elapsedRealtime();
                app.startActivity(launch, bundle);
                VoyahLog.i(TAG, packageName + ':' + displayId,
                        "source=" + source + " package=" + packageName + " display=" + displayId
                                + " prepareMs=" + prepareMs + " startActivityMs="
                                + (SystemClock.elapsedRealtime() - startedAt));
                deliver(generation, callback, new Result(true, "started"));
            } catch (RuntimeException e) {
                Log.w(TAG, "start " + packageName + " display=" + displayId + ": " + e.getMessage());
                deliver(generation, callback, new Result(false, "start_failed"));
            }
        });
    }

    private ComponentName resolveComponent(String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        ComponentName cached = componentCache.get(packageName);
        if (cached != null) return cached;
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(packageName);
        ComponentName result = launch == null ? null : launch.getComponent();
        if (result != null) componentCache.put(packageName, result);
        return result;
    }

    @SuppressWarnings("deprecation")
    private List<Integer> retireTasks(String packageName, int targetDisplay, long generation) {
        List<Integer> retiring = new ArrayList<>();
        if (activityManager == null) return retiring;
        try {
            List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1000);
            if (tasks == null) return retiring;
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (!generations.accepts(generation)) return retiring;
                ComponentName top = task.topActivity;
                ComponentName base = task.baseActivity;
                SplitHostTaskSnapshot.TaskRecord record = new SplitHostTaskSnapshot.TaskRecord(
                        task.taskId, top == null ? null : top.getPackageName(),
                        base == null ? null : base.getPackageName(), readDisplayId(task));
                if (TaskRetirementPolicy.shouldRetire(packageName, targetDisplay, record)) {
                    retiring.add(task.taskId);
                }
            }
            if (retiring.isEmpty()) return retiring;
            if (!ensureRemoveTask()) return null;
            for (int taskId : retiring) {
                if (!generations.accepts(generation)) return retiring;
                Object removed = removeTask.invoke(activityTaskManager, taskId);
                if (Boolean.FALSE.equals(removed)) return null;
            }
            return retiring;
        } catch (Throwable error) {
            Log.w(TAG, "task retirement: " + error.getClass().getSimpleName());
            activityTaskManager = null;
            removeTask = null;
            return null;
        }
    }

    private Integer readDisplayId(ActivityManager.RunningTaskInfo task) {
        if (!displayIdResolved) {
            displayIdResolved = true;
            try { displayIdField = task.getClass().getField("displayId"); }
            catch (Throwable ignored) { displayIdField = null; }
        }
        try { return displayIdField == null ? null : displayIdField.getInt(task); }
        catch (Throwable ignored) { return null; }
    }

    private boolean ensureRemoveTask() {
        if (activityTaskManager != null && removeTask != null) return true;
        try {
            activityTaskManager = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
            removeTask = activityTaskManager.getClass().getMethod("removeTask", int.class);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private String global(String key) {
        try { return android.provider.Settings.Global.getString(app.getContentResolver(), key); }
        catch (RuntimeException ignored) { return null; }
    }

    private void deliver(long generation, Callback callback, Result result) {
        if (callback == null || !generations.accepts(generation)) return;
        if (Looper.myLooper() == Looper.getMainLooper()) callback.complete(result);
        else main.post(() -> { if (generations.accepts(generation)) callback.complete(result); });
    }
}
