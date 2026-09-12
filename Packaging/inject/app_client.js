// Client-side per-application repairs for packages selected in VoyahTune.
//
// The OEM launcher may start an application with WindowManager.LayoutParams.width fixed to the
// old 1780 px work area. system_server can already give that window a 1920 px frame and Surface,
// but ViewRootImpl still measures the application's DecorView with EXACTLY(1780), leaving a black
// strip where the dock used to be. Server-side frame or Surface scaling cannot repair that client
// measurement without distorting the UI.
//
// This agent runs only in exact allowlisted application processes. New layout calls give
// ViewRootImpl a COPY of base-activity LayoutParams whose width is MATCH_PARENT. A late attach must
// also update an already measured DecorView, so its original width is remembered and restored when
// the package leaves the list. Dialogs, starting windows and VirtualDisplay windows are left alone.
//
// Yandex MapKit owns a second, renderer-side density value. A physical Task density override scales
// Android resources but does not necessarily update MapWindow, so the native Vulkan/GL map can stay
// at the 160-dpi scale. For the three known Yandex/Yango map applications this agent mirrors the
// selected per-app DPI to MapWindow.scaleFactor (physical pixels per independent point). The Surface
// keeps its native physical resolution; no VirtualDisplay or compositor upscaling is involved.

Java.perform(function () {
    var TAG = "vt_app_client";
    var READY_MARKER = "[app-client] hook ready v1";
    var MAPKIT_READY_MARKER = "[mapkit-dpi] hook ready v1";
    var SETTING = "voyahtune_fullscreen_apps";
    var DPI_SETTING_PREFIX = "voyahtune_dpi_";
    var RELOAD_ACTION = "ru.big.town.anative.WIN_RELOAD";
    var VD_RESIZED_ACTION = "ru.big.town.anative.VD_RESIZED";
    var RELOAD_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    var TYPE_BASE_APPLICATION = 1;
    var MATCH_PARENT = -1;
    var DEFAULT_DENSITY_DPI = 160;
    var MAP_WINDOW_INTERFACE = "com.yandex.mapkit.map.MapWindow";
    var MAP_VIEW = "com.yandex.mapkit.mapview.MapView";
    var MAPKIT_BINDING = "com.yandex.mapkit.internal.MapKitBinding";

    var Log = Java.use("android.util.Log");
    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var LayoutParams = Java.use("android.view.WindowManager$LayoutParams");
    var View = Java.use("android.view.View");
    var ViewGroup = Java.use("android.view.ViewGroup");
    var ViewRootImpl = Java.use("android.view.ViewRootImpl");
    var WindowManagerGlobal = Java.use("android.view.WindowManagerGlobal");
    var BroadcastReceiver = Java.use("android.content.BroadcastReceiver");
    var IntentFilter = Java.use("android.content.IntentFilter");
    var Handler = Java.use("android.os.Handler");
    var Looper = Java.use("android.os.Looper");
    var System = Java.use("java.lang.System");
    var Thread = Java.use("java.lang.Thread");

    var application = ActivityThread.currentApplication();
    // pidof may observe a cold process a few milliseconds before ActivityThread publishes its
    // Application. Wait only on Frida's attached worker thread; the app main looper is not blocked.
    for (var bootstrapAttempt = 0; application === null && bootstrapAttempt < 30;
            bootstrapAttempt++) {
        Thread.sleep(100);
        application = ActivityThread.currentApplication();
    }
    if (application === null) {
        console.log("[app-client] hook failed v1: currentApplication is null");
        return;
    }

    var packageName = "" + application.getPackageName();
    var enabled = false;
    var mapkitPackage = packageName === "ru.yandex.yandexnavi"
        || packageName === "ru.yandex.yandexmaps"
        || packageName === "com.yango.maps.android";
    var mapkitDpi = 0;
    var reloadReceiver = null;
    var mainHandler = Handler.$new(Looper.getMainLooper());
    var originalWidths = {};
    // WindowManager may echo the old fixed width on every layout pass. Remember the request so
    // that the expensive WindowManagerGlobal replay is only queued once per distinct width; the
    // current setLayoutParams call is still normalized on every pass.
    var normalizedRequestWidths = {};
    var replayApplying = false;
    var replayScheduled = false;
    var MapWindow = null;
    var MapViewClass = null;
    var mapViewGetter = null;
    var mapkitBindingHookInstalled = false;
    var mapkitHooksInstalled = false;
    var mapkitReadyAnnounced = false;
    var mapkitReplayScheduled = false;
    var mapkitReplayPending = false;
    var mapkitReplayPendingReason = "";
    var mapkitReplayTimer = null;
    var mapkitResizeReplayGeneration = 0;
    var mapkitResizeReplaySettledTimer = null;
    var mapkitPolicyGeneration = 0;
    var mapViewAppliedGeneration = Object.create(null);
    var mapkitApplying = 0;
    var mapWindowBaselines = Object.create(null);
    var hookedMapWindowClasses = Object.create(null);

    function packageIsAllowlisted(csv) {
        if (csv === null) return false;
        var packages = ("" + csv).split(",");
        for (var i = 0; i < packages.length; i++) {
            if (packages[i].trim() === packageName) return true;
        }
        return false;
    }

    function readEnabled() {
        try {
            return packageIsAllowlisted(SettingsGlobal.getString(
                application.getContentResolver(), SETTING));
        } catch (e) {
            Log.e(TAG, "fullscreen setting read failed for " + packageName + ": " + e);
            return false;
        }
    }

    function readMapkitDpi() {
        if (!mapkitPackage) return 0;
        try {
            var raw = SettingsGlobal.getString(application.getContentResolver(),
                DPI_SETTING_PREFIX + packageName);
            if (raw === null) return 0;
            var dpi = parseInt(("" + raw).trim(), 10);
            return dpi >= 100 && dpi <= 640 ? dpi : 0;
        } catch (e) {
            Log.e(TAG, "MapKit DPI setting read failed for " + packageName + ": " + e);
            return 0;
        }
    }

    function mapkitScaleForDpi(dpi) {
        return dpi / DEFAULT_DENSITY_DPI;
    }

    function mapWindowKey(windowObject) {
        var className = "unknown";
        try { className = "" + windowObject.$className; } catch (ignoredClass) {}
        return className + ":" + Number(System.identityHashCode(windowObject));
    }

    function rememberMapWindowBaseline(windowObject, typedWindow) {
        var key = mapWindowKey(windowObject);
        if (typeof mapWindowBaselines[key] !== "number") {
            var current = Number(typedWindow.getScaleFactor());
            if (isFinite(current) && current > 0) mapWindowBaselines[key] = current;
        }
        return typeof mapWindowBaselines[key] === "number"
            ? mapWindowBaselines[key] : 1.0;
    }

    function hookMapWindowClass(windowObject) {
        var className = "" + windowObject.$className;
        if (hookedMapWindowClasses[className]) return true;
        try {
            var WindowClass = Java.use(className);
            var originalSetScale = WindowClass.setScaleFactor.overload("float");
            originalSetScale.implementation = function (requestedScale) {
                var requested = Number(requestedScale);
                var key = mapWindowKey(this);
                if (mapkitApplying > 0) {
                    return originalSetScale.call(this, requestedScale);
                }
                if (!(mapkitDpi > 0)) {
                    if (isFinite(requested) && requested > 0) {
                        mapWindowBaselines[key] = requested;
                    }
                    return originalSetScale.call(this, requestedScale);
                }
                if (typeof mapWindowBaselines[key] !== "number") {
                    try {
                        var current = Number(Java.cast(this, MapWindow).getScaleFactor());
                        if (isFinite(current) && current > 0) mapWindowBaselines[key] = current;
                    } catch (ignoredBaseline) {}
                }
                return originalSetScale.call(this, mapkitScaleForDpi(mapkitDpi));
            };
            hookedMapWindowClasses[className] = true;
            Log.i(TAG, "MapKit setScaleFactor guard installed class=" + className);
            return true;
        } catch (e) {
            Log.w(TAG, "MapKit setScaleFactor guard unavailable class=" + className + ": " + e);
            return false;
        }
    }

    function applyMapWindow(windowObject, reason) {
        if (!mapkitPackage || windowObject === null || MapWindow === null) return false;
        try {
            var typedWindow = Java.cast(windowObject, MapWindow);
            if (!typedWindow.isValid()) return false;
            hookMapWindowClass(windowObject);
            var baseline = rememberMapWindowBaseline(windowObject, typedWindow);
            var target = mapkitDpi > 0 ? mapkitScaleForDpi(mapkitDpi) : baseline;
            var current = Number(typedWindow.getScaleFactor());
            if (Math.abs(current - target) < 0.0001) return false;
            mapkitApplying++;
            try {
                typedWindow.setScaleFactor(target);
            } finally {
                mapkitApplying--;
            }
            var mapSize = "unknown";
            try {
                mapSize = Number(typedWindow.width()) + "x" + Number(typedWindow.height());
            } catch (ignoredSize) {}
            Log.i(TAG, "MapKit scale " + reason + " package=" + packageName
                + " dpi=" + mapkitDpi + " old=" + current + " target=" + target
                + " size=" + mapSize);
            return true;
        } catch (e) {
            Log.e(TAG, "MapKit scale apply failed " + reason + ": " + e);
            return false;
        }
    }

    function mapViewKey(mapView) {
        try { return "view:" + Number(System.identityHashCode(mapView)); }
        catch (ignored) { return null; }
    }

    function applyMapWindowForMapView(mapView, windowObject, reason, force) {
        var key = mapViewKey(mapView);
        var generation = mapkitPolicyGeneration + ":" + mapkitResizeReplayGeneration;
        if (!force && key !== null && mapViewAppliedGeneration[key] === generation) return false;
        var changed = applyMapWindow(windowObject, reason);
        if (key !== null) mapViewAppliedGeneration[key] = generation;
        return changed;
    }

    function hookMapView() {
        if (mapViewGetter !== null) return true;
        try {
            MapViewClass = Java.use(MAP_VIEW);
            mapViewGetter = MapViewClass.getMapWindow.overload();
            mapViewGetter.implementation = function () {
                var windowObject = mapViewGetter.call(this);
                // MapKit can ask for the same window from its gesture/render path many times per
                // frame. Re-apply only after a policy or VirtualDisplay generation changes.
                applyMapWindowForMapView(this, windowObject, "MapView.getMapWindow", false);
                return windowObject;
            };
            Log.i(TAG, "MapKit MapView factory hook installed");
            return true;
        } catch (e) {
            MapViewClass = null;
            mapViewGetter = null;
            Log.w(TAG, "MapKit MapView hook unavailable: " + e);
            return false;
        }
    }

    function hookMapKitBinding() {
        if (mapkitBindingHookInstalled) return true;
        try {
            var MapKitBinding = Java.use(MAPKIT_BINDING);
            if (typeof MapKitBinding.createMapWindow === "undefined") return false;
            var overloads = MapKitBinding.createMapWindow.overloads;
            for (var i = 0; i < overloads.length; i++) {
                (function (originalCreate) {
                    originalCreate.implementation = function () {
                        var windowObject = originalCreate.apply(this, arguments);
                        applyMapWindow(windowObject, "MapKitBinding.createMapWindow");
                        return windowObject;
                    };
                })(overloads[i]);
            }
            mapkitBindingHookInstalled = overloads.length > 0;
            Log.i(TAG, "MapKit binding factory hooks=" + overloads.length);
            return mapkitBindingHookInstalled;
        } catch (e) {
            Log.w(TAG, "MapKit binding factory hook unavailable: " + e);
            return false;
        }
    }

    function ensureMapkitHooks(reason) {
        if (!mapkitPackage) return true;
        if (mapkitHooksInstalled) return true;
        try {
            Java.classFactory.loader = application.getClassLoader();
            MapWindow = Java.use(MAP_WINDOW_INTERFACE);
            // Install both when available. The factory catches new windows; MapView also gives us
            // a stable path to already-created windows after a late Frida attach.
            var mapViewReady = hookMapView();
            var bindingReady = hookMapKitBinding();
            mapkitHooksInstalled = mapViewReady || bindingReady;
        } catch (e) {
            Log.e(TAG, "MapKit hook installation failed " + reason + ": " + e);
            mapkitHooksInstalled = false;
        }
        if (mapkitHooksInstalled && !mapkitReadyAnnounced) {
            mapkitReadyAnnounced = true;
            Log.i(TAG, MAPKIT_READY_MARKER + " package=" + packageName);
            console.log(MAPKIT_READY_MARKER + " package=" + packageName);
        }
        return mapkitHooksInstalled;
    }

    function replayMapWindows(reason) {
        if (!mapkitPackage || !mapkitHooksInstalled) return;
        if (mapkitReplayScheduled) {
            // A resize broadcast can arrive while a previous replay is walking the view tree. Keep
            // the latest reason and run one more pass after the current one instead of dropping it.
            mapkitReplayPending = true;
            mapkitReplayPendingReason = reason;
            return;
        }
        mapkitReplayScheduled = true;
        Java.scheduleOnMainThread(function () {
            try {
                var matched = 0;
                function visit(view) {
                    if (view === null) return;
                    try {
                        if (MapViewClass !== null && MapViewClass.class.isInstance(view)) {
                            var mapView = Java.cast(view, MapViewClass);
                            if (applyMapWindowForMapView(mapView, mapViewGetter.call(mapView), reason,
                                    true)) matched++;
                            // VD.resize updates the display configuration asynchronously. Ask the
                            // already-attached MapView to measure and redraw against the new bounds.
                            mapView.requestLayout();
                            mapView.invalidate();
                        }
                        if (!ViewGroup.class.isInstance(view)) return;
                        var group = Java.cast(view, ViewGroup);
                        for (var childIndex = 0; childIndex < group.getChildCount(); childIndex++) {
                            visit(group.getChildAt(childIndex));
                        }
                    } catch (viewError) {
                        Log.w(TAG, "MapKit view-tree replay skipped: " + viewError);
                    }
                }
                try {
                    var views = WindowManagerGlobal.getInstance().getWindowViews();
                    for (var i = 0; i < views.size(); i++) {
                        visit(Java.cast(views.get(i), View));
                    }
                } catch (rootError) {
                    Log.e(TAG, "MapKit root replay failed: " + rootError);
                }
                Log.i(TAG, "MapKit replay " + reason + " package=" + packageName
                    + " dpi=" + mapkitDpi + " changed=" + matched);
            } finally {
                mapkitReplayScheduled = false;
                if (mapkitReplayPending) {
                    var pendingReason = mapkitReplayPendingReason;
                    mapkitReplayPending = false;
                    mapkitReplayPendingReason = "";
                    replayMapWindows(pendingReason);
                }
            }
        });
    }

    function scheduleMapkitReplay(reason, delayMs) {
        if (!mapkitPackage || !mapkitHooksInstalled) return;
        if (mapkitReplayTimer !== null) {
            clearTimeout(mapkitReplayTimer);
            mapkitReplayTimer = null;
        }
        mapkitReplayTimer = setTimeout(function () {
            mapkitReplayTimer = null;
            replayMapWindows(reason);
        }, delayMs);
    }

    function scheduleMapkitResizeReplays() {
        if (!mapkitPackage || !mapkitHooksInstalled) return;
        mapkitResizeReplayGeneration++;
        var generation = mapkitResizeReplayGeneration;
        // The first pass catches the normal configuration update. The second pass handles MapKit
        // builds that publish their renderer size one or two choreographer frames later.
        scheduleMapkitReplay("VD_RESIZED", 180);
        if (mapkitResizeReplaySettledTimer !== null) {
            clearTimeout(mapkitResizeReplaySettledTimer);
            mapkitResizeReplaySettledTimer = null;
        }
        mapkitResizeReplaySettledTimer = setTimeout(function () {
            mapkitResizeReplaySettledTimer = null;
            if (generation !== mapkitResizeReplayGeneration) return;
            replayMapWindows("VD_RESIZED_SETTLED");
        }, 650);
    }

    function displayIdOf(root) {
        try { return Number(root.getDisplayId()); }
        catch (e) { return -1; }
    }

    function isBaseWindowOnPhysicalDisplay(root, attrs) {
        if (attrs === null) return false;
        var displayId = displayIdOf(root);
        return Number(attrs.type.value) === TYPE_BASE_APPLICATION
            && (displayId === 0 || displayId === 1);
    }

    function rootKey(root) {
        return "root:" + Number(System.identityHashCode(root));
    }

    function shouldReplayNormalizedRoot(root, attrs) {
        var requestedWidth = Number(attrs.width.value);
        if (requestedWidth === MATCH_PARENT) return false;
        var key = rootKey(root);
        if (normalizedRequestWidths[key] === requestedWidth) return false;
        normalizedRequestWidths[key] = requestedWidth;
        return true;
    }

    function rememberOriginalWidth(root, attrs) {
        if (replayApplying) return;
        var key = rootKey(root);
        // Keep the pre-hook baseline. A framework update may later echo the MATCH_PARENT params
        // installed by our late replay; overwriting here would make disable unable to restore 1780.
        if (typeof originalWidths[key] !== "number") {
            originalWidths[key] = Number(attrs.width.value);
        }
    }

    function normalizedCopy(root, attrs) {
        if (!enabled || !isBaseWindowOnPhysicalDisplay(root, attrs)) {
            return attrs;
        }
        rememberOriginalWidth(root, attrs);
        if (Number(attrs.width.value) === MATCH_PARENT) return attrs;
        try {
            var copy = LayoutParams.$new();
            copy.copyFrom(attrs);
            copy.width.value = MATCH_PARENT;
            return copy;
        } catch (e) {
            Log.e(TAG, "LayoutParams clone failed for " + packageName + ": " + e);
            return attrs;
        }
    }

    var setView = ViewRootImpl.setView.overload(
        "android.view.View",
        "android.view.WindowManager$LayoutParams",
        "android.view.View",
        "int");
    var setLayoutParams = ViewRootImpl.setLayoutParams.overload(
        "android.view.WindowManager$LayoutParams", "boolean");

    function replayAttachedRoots(reason) {
        if (replayScheduled) return;
        replayScheduled = true;
        Java.scheduleOnMainThread(function () {
            replayScheduled = false;
            var changed = 0;
            try {
                var windowManager = WindowManagerGlobal.getInstance();
                var views = windowManager.getWindowViews();
                for (var i = 0; i < views.size(); i++) {
                    try {
                        // ArrayList.get() is typed as Object in Frida; cast before calling hidden
                        // View methods such as getViewRootImpl().
                        var view = Java.cast(views.get(i), View);
                        var rawAttrs = view.getLayoutParams();
                        if (rawAttrs === null) continue;
                        var attrs = Java.cast(rawAttrs, LayoutParams);
                        var root = view.getViewRootImpl();
                        if (root === null || !isBaseWindowOnPhysicalDisplay(root, attrs)) continue;
                        var key = rootKey(root);
                        var currentWidth = Number(attrs.width.value);
                        var hasOriginalWidth = typeof originalWidths[key] === "number";
                        if (enabled && !hasOriginalWidth) {
                            originalWidths[key] = currentWidth;
                            hasOriginalWidth = true;
                        }
                        var targetWidth = enabled ? MATCH_PARENT
                            : (hasOriginalWidth
                                ? originalWidths[key] : currentWidth);
                        // On disable, replay even when DecorView already has the original width.
                        // A queued enable replay may have normalized ViewRootImpl only; sending the
                        // original params through WindowManagerGlobal restores both copies.
                        var mustRestoreRoot = !enabled && hasOriginalWidth;
                        if (currentWidth === targetWidth && !mustRestoreRoot) {
                            if (!enabled) delete originalWidths[key];
                            continue;
                        }
                        var copy = LayoutParams.$new();
                        copy.copyFrom(attrs);
                        copy.width.value = targetWidth;
                        // updateViewLayout updates both DecorView's LayoutParams and ViewRootImpl.
                        // A direct ViewRootImpl call updates WMS but does not force an existing
                        // DecorView with fixed root params to remeasure on this Android 11 build.
                        replayApplying = true;
                        try {
                            windowManager.updateViewLayout(view, copy);
                        } finally {
                            replayApplying = false;
                        }
                        if (!enabled) delete originalWidths[key];
                        changed++;
                    } catch (windowError) {
                        Log.w(TAG, "root replay skipped: " + windowError);
                    }
                }
                Log.i(TAG, "root replay " + reason + " package=" + packageName
                    + " enabled=" + enabled + " roots=" + changed);
            } catch (e) {
                Log.e(TAG, "root replay failed " + reason + ": " + e);
            }
        });
    }

    function refreshPolicy(reason) {
        var previous = enabled;
        var previousMapkitDpi = mapkitDpi;
        enabled = readEnabled();
        mapkitDpi = readMapkitDpi();
        if (previous !== enabled) normalizedRequestWidths = {};
        if (previousMapkitDpi !== mapkitDpi) mapkitPolicyGeneration++;
        Log.i(TAG, "policy " + reason + " package=" + packageName
            + " enabled=" + enabled + " changed=" + (previous !== enabled)
            + " mapkitDpi=" + mapkitDpi
            + " mapkitChanged=" + (previousMapkitDpi !== mapkitDpi));
        if (ensureMapkitHooks(reason)) replayMapWindows(reason);
        replayAttachedRoots(reason);
    }

    try {
        var Receiver = Java.registerClass({
            name: "ru.big.town.voyahtune.AppClientReloadReceiver",
            superClass: BroadcastReceiver,
            methods: {
                // BroadcastReceiver.onReceive is abstract. This OEM ART needs an explicit method
                // signature; Frida's shorthand function form leaves the vtable slot abstract and
                // crashes the target with AbstractMethodError on the first broadcast.
                onReceive: {
                    returnType: "void",
                    argumentTypes: ["android.content.Context", "android.content.Intent"],
                    implementation: function (context, intent) {
                        try {
                            var action = intent === null ? "" : "" + intent.getAction();
                            if (action === RELOAD_ACTION) {
                                refreshPolicy("WIN_RELOAD");
                            } else if (action === VD_RESIZED_ACTION) {
                                Log.i(TAG, "VD resize received package=" + packageName
                                    + " generation=" + intent.getLongExtra("generation", 0));
                                scheduleMapkitResizeReplays();
                            }
                        } catch (e) {
                            Log.e(TAG, "WIN_RELOAD failed: " + e);
                        }
                    }
                }
            }
        });
        reloadReceiver = Java.retain(Receiver.$new());
        var reloadFilter = IntentFilter.$new(RELOAD_ACTION);
        reloadFilter.addAction(VD_RESIZED_ACTION);
        application.registerReceiver.overload(
            "android.content.BroadcastReceiver",
            "android.content.IntentFilter",
            "java.lang.String",
            "android.os.Handler"
        ).call(application, reloadReceiver, reloadFilter,
            RELOAD_PERMISSION, mainHandler);
    } catch (e) {
        Log.e(TAG, "WIN_RELOAD receiver registration failed: " + e);
        console.log("[app-client] hook failed v1: receiver registration: " + e);
        return;
    }

    try {
        setView.implementation = function (view, attrs, panelParentView, userId) {
            var shouldReplay = enabled && isBaseWindowOnPhysicalDisplay(this, attrs)
                && shouldReplayNormalizedRoot(this, attrs);
            var result = setView.call(this, view, normalizedCopy(this, attrs),
                panelParentView, userId);
            // WindowManagerGlobal assigns the app-owned attrs to DecorView before setView(). Replay
            // on the next main-loop turn so the attached View also receives MATCH_PARENT.
            if (shouldReplay) replayAttachedRoots("setView");
            return result;
        };
        setLayoutParams.implementation = function (attrs, newView) {
            var shouldReplay = enabled && !replayApplying
                && isBaseWindowOnPhysicalDisplay(this, attrs)
                && shouldReplayNormalizedRoot(this, attrs);
            var result = setLayoutParams.call(this, normalizedCopy(this, attrs), newView);
            // WindowManagerGlobal has already copied app attrs onto DecorView before this hook.
            // Repair that client-owned copy on the next UI-loop turn as well.
            if (shouldReplay) replayAttachedRoots("setLayoutParams");
            return result;
        };
    } catch (e) {
        try { setView.implementation = null; } catch (ignoredSetView) {}
        try { setLayoutParams.implementation = null; } catch (ignoredSetLayout) {}
        try { application.unregisterReceiver(reloadReceiver); } catch (ignoredReceiver) {}
        Log.e(TAG, "ViewRootImpl hook installation failed: " + e);
        console.log("[app-client] hook failed v1: ViewRootImpl: " + e);
        return;
    }

    refreshPolicy("attach");
    Log.i(TAG, READY_MARKER + " package=" + packageName + " enabled=" + enabled
        + " mapkitDpi=" + mapkitDpi + " mapkitHooks=" + mapkitHooksInstalled);
    console.log(READY_MARKER + " package=" + packageName + " enabled=" + enabled
        + " mapkitDpi=" + mapkitDpi + " mapkitHooks=" + mapkitHooksInstalled);
});
