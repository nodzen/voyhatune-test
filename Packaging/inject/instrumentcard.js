// Exposes an active third-party MediaSession through the instrument-card's native media model.
// No view is added to ScreenActivity: the OEM PanelView/MusicBaseView remains the only renderer.
Java.perform(function () {
    var TAG = "vt_instrumentcard";
    var SETTINGS_KEY = "voyahtune_instrument_now_playing";
    var NOW_PLAYING = "ru.big.town.anative.NOW_PLAYING";
    var SOURCES = "ru.big.town.anative.NOW_PLAYING_SOURCES";
    var RELOAD = "ru.big.town.anative.DOCK_RELOAD";
    var ENUM_NAME = "com.qinggan.media.helper.MediaEnum";
    var INFO_NAME = "com.qinggan.media.helper.base.bean.QinMediaInfo";
    var MANAGER_NAME = "com.qinggan.app.mediaCentre.MediaManager";
    var MUSIC_VIEW_NAME = "com.qinggan.cardview.panel.media.MusicBaseView";
    var BASE_MEDIA_VIEW_NAME = "com.qinggan.app.mediaCentre.view.BaseMediaView";
    var MEDIA_CONTROL_METHOD = "media_control";
    var Uri = Java.use("android.net.Uri");
    var mediaUri = Uri.parse("content://ru.big.town.anative.nowplaying");
    var mediaSourcesUri = Uri.parse("content://ru.big.town.anative.nowplaying/sources");
    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var BroadcastReceiver = Java.use("android.content.BroadcastReceiver");
    var IntentFilter = Java.use("android.content.IntentFilter");
    var JavaString = Java.use("java.lang.String");
    var NativeMediaTag = Java.retain(Java.use("java.lang.Object").$new());
    var Bundle = Java.use("android.os.Bundle");
    var AndroidLog = Java.use("android.util.Log");
    var app = null;
    var receiverRegistered = false;
    var managerHooked = false;
    var nativeViewHooked = false;
    var refreshPending = false;
    var bridgeAvailable = false;
    var bridgeSelected = false;
    var selectedMediaPackage = "";
    var enabled = true;
    var latestSnapshot = null;
    var nativeInfo = null;
    var lastNativeKey = "";
    var MediaEnum = null;
    var QinMediaInfo = null;
    var Manager = null;
    var WECAR = null;
    var NO_MEDIA = null;
    var mediaControl = null;

    function log(message) { try { AndroidLog.i(TAG, message); } catch (e) {} }
    function warn(message) { try { AndroidLog.w(TAG, message); } catch (e) {} }
    function text(value) {
        if (value === null || value === undefined) return "";
        var result = "" + value;
        return result === "null" || result === "undefined" ? "" : result;
    }
    function currentApp() {
        if (app !== null) return app;
        try { app = ActivityThread.currentApplication(); } catch (e) {}
        return app;
    }
    function staticField(clazz, name) {
        // Frida exposes enum constants as Java.Field wrappers on the class proxy.  Their
        // `.value` property may be converted to a JS string, which cannot cross a Java
        // method boundary.  Reflection returns the actual enum object/handle.
        try {
            var field = clazz.class.getDeclaredField(name);
            field.setAccessible(true);
            var reflected = field.get(null);
            if (reflected !== null && reflected !== undefined) {
                try { return Java.cast(reflected, clazz); }
                catch (castError) { return reflected; }
            }
        } catch (e) {}
        try {
            var value = clazz[name];
            if (value !== null && value !== undefined) {
                if (value.value !== undefined && typeof value.value !== "string") {
                    try { return Java.cast(value.value, clazz); }
                    catch (castError2) { return value.value; }
                }
            }
        } catch (e) {}
        try { return Java.cast(clazz.valueOf(JavaString.$new(name)), clazz); }
        catch (e2) { return null; }
    }
    function freshMediaEnum(name) {
        if (MediaEnum === null) return null;
        var value = staticField(MediaEnum, name);
        if (value === null || value === undefined) return null;
        try { value.getClass(); return value; } catch (e) { return null; }
    }
    function currentMediaEnum() { return freshMediaEnum("WECAR_FLOW"); }
    function enumName(value) {
        if (value === null || value === undefined) return "";
        try { return text(value.name()); } catch (e) {}
        try { return text(value.toString()); } catch (e2) {}
        return "";
    }
    function isWecar(value) {
        if (value === null || value === undefined || WECAR === null) return false;
        if (enumName(value) === "WECAR_FLOW") return true;
        try { return value.equals(WECAR); } catch (e) { return false; }
    }
    function readColumn(cursor, name, fallback) {
        try {
            var index = cursor.getColumnIndex(name);
            return index >= 0 ? text(cursor.getString(index)) : fallback;
        } catch (e) { return fallback; }
    }
    function readNumber(cursor, name, fallback) {
        try {
            var index = cursor.getColumnIndex(name);
            return index >= 0 ? Number(cursor.getLong(index)) : fallback;
        } catch (e) { return fallback; }
    }
    function readSnapshot() {
        var result = {title: "", artist: "", album: "", app: "", pkg: "", state: 0,
            position: 0, duration: 0, hasArt: false, updatedAt: 0};
        var current = currentApp();
        if (current === null) return result;
        var cursor = null;
        try {
            cursor = current.getContentResolver().query(mediaUri, null, null, null, null);
            if (cursor !== null && cursor.moveToFirst()) {
                result.title = readColumn(cursor, "title", "");
                result.artist = readColumn(cursor, "artist", "");
                result.album = readColumn(cursor, "album", "");
                result.app = readColumn(cursor, "appLabel", "");
                result.pkg = readColumn(cursor, "package", "");
                result.state = readNumber(cursor, "state", 0);
                result.position = readNumber(cursor, "position", 0);
                result.duration = readNumber(cursor, "duration", 0);
                result.hasArt = readNumber(cursor, "hasArt", 0) === 1;
                result.updatedAt = readNumber(cursor, "updatedAt", 0);
            }
        } catch (e) { warn("snapshot query failed: " + e); }
        finally { try { if (cursor !== null) cursor.close(); } catch (ignored) {} }
        return result;
    }
    function readSources() {
        var result = [];
        var current = currentApp();
        if (current === null) return result;
        var cursor = null;
        try {
            cursor = current.getContentResolver().query(mediaSourcesUri, null, null, null, null);
            if (cursor !== null) {
                while (cursor.moveToNext()) {
                    var pkg = readColumn(cursor, "package", "");
                    if (pkg) result.push({
                        pkg: pkg,
                        selected: readNumber(cursor, "selected", 0) === 1
                    });
                }
            }
        } catch (e) { warn("source query failed: " + e); }
        finally { try { if (cursor !== null) cursor.close(); } catch (ignored) {} }
        return result;
    }
    function refreshConfig() {
        var current = currentApp();
        if (current === null) return;
        try {
            var value = SettingsGlobal.getString(current.getContentResolver(), SETTINGS_KEY);
            enabled = value === null || text(value) !== "0";
        } catch (e) { enabled = true; }
    }
    function isBridgeSourcePackage(pkg) {
        // Keep the car's own Radio/BT/USB implementations on their OEM media paths. Every other
        // live MediaSession is an application source and can use the WECAR_FLOW bridge.
        if (!pkg || pkg === "ru.big.town.anative" || pkg === "android") return false;
        return pkg.indexOf("com.qinggan.") !== 0
                && pkg.indexOf("com.pateo.") !== 0
                && pkg.indexOf("tai.") !== 0
                && pkg.indexOf("com.android.bluetooth") !== 0;
    }
    function findSelectedBridgeSource(snapshot, sources) {
        for (var i = 0; i < sources.length; i++) {
            if (sources[i].selected && isBridgeSourcePackage(sources[i].pkg)) return sources[i];
        }
        // The provider's snapshot is published from the selected controller. Use it as a
        // short-lived fallback while the source-topology broadcast is still in flight.
        return isBridgeSourcePackage(snapshot.pkg) ? {pkg: snapshot.pkg, selected: true} : null;
    }
    function setInfoValue(info, name, signature, value) {
        try {
            var method = info[name].overload(signature);
            if (signature === "java.lang.String") value = JavaString.$new(text(value));
            method.call(info, value);
        } catch (e) {}
    }
    function buildNativeInfo(snapshot) {
        var info = null;
        var mediaEnum = freshMediaEnum("WECAR_FLOW");
        if (mediaEnum === null) return null;
        try {
            info = QinMediaInfo.$new(mediaEnum);
        } catch (e) {
            try { info = QinMediaInfo.$new(); } catch (e2) { return null; }
        }
        var pkg = snapshot.pkg || selectedMediaPackage;
        setInfoValue(info, "setName", "java.lang.String", snapshot.title || snapshot.app || pkg || "Media");
        setInfoValue(info, "setArtist", "java.lang.String", snapshot.artist);
        setInfoValue(info, "setAlbumName", "java.lang.String", snapshot.album);
        setInfoValue(info, "setDuration", "long", Math.max(0, Number(snapshot.duration || 0)));
        setInfoValue(info, "setMediaId", "java.lang.String",
                pkg + "|" + snapshot.title + "|" + snapshot.artist);
        setInfoValue(info, "setMediaType", "java.lang.String", "WECAR_FLOW");
        setInfoValue(info, "setHostId", "java.lang.String", pkg);
        setInfoValue(info, "setPath", "java.lang.String", pkg);
        setInfoValue(info, "setCoverUrl", "java.lang.String",
                snapshot.hasArt ? "content://ru.big.town.anative.nowplaying/art?rev="
                        + Number(snapshot.updatedAt || 0) : "");
        setInfoValue(info, "setFav", "boolean", false);
        try {
            var extras = Bundle.$new();
            extras.putString.overload("java.lang.String", "java.lang.String").call(
                    extras, JavaString.$new("package"), JavaString.$new(pkg));
            setInfoValue(info, "setExtBundle", "android.os.Bundle", extras);
        } catch (e3) {}
        return info;
    }
    function sendControl(command) {
        var current = currentApp();
        if (current === null) return;
        var argument = command;
        if (command === "play") {
            if (latestSnapshot !== null && Number(latestSnapshot.state) === 3) return;
            argument = "play_pause";
        } else if (command === "pause") {
            argument = "pause_only";
        }
        try {
            current.getContentResolver().call(mediaUri, MEDIA_CONTROL_METHOD, argument, null);
        } catch (e) { warn("native media command " + command + " failed: " + e); }
    }
    function installControlProxy() {
        if (mediaControl !== null) return;
        try {
            var Control = Java.use("com.qinggan.app.mediaCentre.inter.IMediaControl");
            var SearchCallback = "android.support.v4.media.MediaBrowserCompat$SearchCallback";
            var CustomActionCallback = "android.support.v4.media.MediaBrowserCompat$CustomActionCallback";
            var ControlClass = Java.registerClass({
                name: "ru.big.town.instrument.ThirdPartyMediaControl" + new Date().getTime(),
                implements: [Control],
                methods: {
                    addFav: {returnType: "void", argumentTypes: ["java.lang.String"], implementation: function () {}},
                    addQinMediaListener: {returnType: "void", argumentTypes: ["com.qinggan.app.mediaCentre.inter.QinMediaListener"], implementation: function () {}},
                    fastForward: {returnType: "void", argumentTypes: [], implementation: function () {}},
                    getMediaBrowserHelper: {returnType: "com.qinggan.media.helper.MediaBrowserHelper", argumentTypes: [], implementation: function () { return null; }},
                    getMediaType: {returnType: ENUM_NAME, argumentTypes: [], implementation: function () { return currentMediaEnum(); }},
                    isConnected: {returnType: "boolean", argumentTypes: [], implementation: function () { return bridgeAvailable; }},
                    isPlay: {returnType: "boolean", argumentTypes: [], implementation: function () { return !!latestSnapshot && Number(latestSnapshot.state) === 3; }},
                    pause: {returnType: "void", argumentTypes: [], implementation: function () { sendControl("pause"); }},
                    play: {returnType: "void", argumentTypes: [], implementation: function () { sendControl("play"); }},
                    playNext: {returnType: "void", argumentTypes: [], implementation: function () { sendControl("next"); }},
                    playPrevious: {returnType: "void", argumentTypes: [], implementation: function () { sendControl("previous"); }},
                    registerCallback: {returnType: "void", argumentTypes: ["com.qinggan.media.helper.MediaBrowserHelper$MediaListener"], implementation: function () {}},
                    removeFav: {returnType: "void", argumentTypes: ["java.lang.String"], implementation: function () {}},
                    search: {returnType: "void", argumentTypes: ["java.lang.String", "android.os.Bundle", SearchCallback], implementation: function () {}},
                    seekToProgress: {returnType: "void", argumentTypes: ["int"], implementation: function () {}},
                    sendCommand: {returnType: "void", argumentTypes: ["java.lang.String", "android.os.Bundle", "android.os.ResultReceiver"], implementation: function () {}},
                    sendCustomAction: [
                        {returnType: "void", argumentTypes: ["java.lang.String", "android.os.Bundle"], implementation: function () {}},
                        {returnType: "void", argumentTypes: ["java.lang.String", "android.os.Bundle", CustomActionCallback], implementation: function () {}}
                    ],
                    skipToPosition: {returnType: "void", argumentTypes: ["long"], implementation: function () {}},
                    stop: {returnType: "void", argumentTypes: [], implementation: function () { sendControl("pause"); }},
                    unRegisterCallback: {returnType: "void", argumentTypes: ["com.qinggan.media.helper.MediaBrowserHelper$MediaListener"], implementation: function () {}}
                }
            });
            mediaControl = ControlClass.$new();
            log("native IMediaControl proxy registered");
        } catch (e) { warn("IMediaControl proxy unavailable: " + e); }
    }
    function ensureMediaClasses() {
        if (Manager !== null && MediaEnum !== null && QinMediaInfo !== null
                && WECAR !== null && WECAR !== undefined) return true;
        try {
            MediaEnum = Java.use(ENUM_NAME);
            QinMediaInfo = Java.use(INFO_NAME);
            Manager = Java.use(MANAGER_NAME);
            WECAR = staticField(MediaEnum, "WECAR_FLOW");
            NO_MEDIA = staticField(MediaEnum, "NO");
            return Manager !== null && WECAR !== null && WECAR !== undefined;
        } catch (e) { return false; }
    }
    function managerInstance() {
        try { return Manager.getInstance(); } catch (e) { return null; }
    }
    function hookManagerMethod(name, handler) {
        try {
            var method = Manager[name];
            method.overloads.forEach(function (overload) {
                overload.implementation = function () {
                    try {
                        var result = handler(this, arguments);
                        if (result !== null && result !== undefined && result.handled) return result.value;
                    } catch (e) { warn(name + " bridge failed: " + e); }
                    return overload.apply(this, arguments);
                };
            });
        } catch (e) { warn("manager hook " + name + " unavailable: " + e); }
    }
    function currentWecar() {
        return bridgeAvailable && bridgeSelected && selectedMediaPackage !== "";
    }
    function addWecarToNativeViewList(list) {
        // The firmware exposes WECAR_FLOW only when its own WeChat Music option is enabled.
        // Third-party MediaSession apps use that same native media contract, so make it visible
        // to the OEM renderer only while one of those sources is selected.
        if (!currentWecar() || list === null || list === undefined) return;
        var mediaEnum = freshMediaEnum("WECAR_FLOW");
        if (mediaEnum === null) return;
        try {
            if (!list.contains(mediaEnum)) list.add(mediaEnum);
        } catch (e) { warn("native view media list update failed: " + e); }
    }
    function hookNativeViewList(className) {
        try {
            var NativeView = Java.use(className);
            var getMediaEnums = NativeView.getMediaEnums.overload();
            getMediaEnums.implementation = function () {
                var list = getMediaEnums.call(this);
                addWecarToNativeViewList(list);
                return list;
            };
            return true;
        } catch (e) {
            warn("native view hook unavailable " + className + ": " + e);
            return false;
        }
    }
    function installNativeViewSupport() {
        if (nativeViewHooked || !ensureMediaClasses()) return nativeViewHooked;
        // MusicBaseView is the actual cluster card. The base hook is a fallback for
        // other OEM media cards that inherit the generic validation path.
        var musicViewHooked = hookNativeViewList(MUSIC_VIEW_NAME);
        var baseViewHooked = hookNativeViewList(BASE_MEDIA_VIEW_NAME);
        nativeViewHooked = musicViewHooked || baseViewHooked;
        if (nativeViewHooked) log("native WECAR view support installed");
        return nativeViewHooked;
    }
    function installManagerHooks() {
        if (managerHooked || !ensureMediaClasses()) return false;
        var manager = managerInstance();
        if (manager === null) return false;
        installControlProxy();
        hookManagerMethod("getCurMediaType", function () {
            var mediaEnum = currentMediaEnum();
            return currentWecar() && mediaEnum !== null ? {handled: true, value: mediaEnum} : null;
        });
        hookManagerMethod("getCurMediaInfo", function () {
            return currentWecar() && nativeInfo !== null ? {handled: true, value: nativeInfo} : null;
        });
        hookManagerMethod("getMediaInfoByType", function (self, args) {
            return isWecar(args[0]) && currentWecar() && nativeInfo !== null
                    ? {handled: true, value: nativeInfo} : null;
        });
        hookManagerMethod("getMediaControl", function (self, args) {
            return isWecar(args[0]) && mediaControl !== null ? {handled: true, value: mediaControl} : null;
        });
        hookManagerMethod("isPlay", function (self, args) {
            return isWecar(args[0]) && currentWecar()
                    ? {handled: true, value: !!latestSnapshot && Number(latestSnapshot.state) === 3} : null;
        });
        hookManagerMethod("getPlayState", function (self, args) {
            return isWecar(args[0]) && currentWecar()
                    ? {handled: true, value: latestSnapshot === null ? 0 : Number(latestSnapshot.state || 0)} : null;
        });
        ["play", "pause", "playNext", "playPrevious", "playOrPause"].forEach(function (name) {
            hookManagerMethod(name, function (self, args) {
                if (!isWecar(args[0]) || !currentWecar()) return null;
                var command = name === "playNext" ? "next" : name === "playPrevious" ? "previous"
                        : name === "pause" ? "pause" : name === "play" ? "play" : "play_pause";
                sendControl(command);
                return {handled: true, value: undefined};
            });
        });
        if (mediaControl !== null) {
            try { manager.addMediaControl(mediaControl); } catch (e) {}
        }
        managerHooked = true;
        log("native MediaManager hooks installed");
        return true;
    }
    function setNativeCurrent(manager, info) {
        var mediaEnum = freshMediaEnum("WECAR_FLOW");
        if (mediaEnum === null) return false;
        try {
            manager.setCurMedia.overload(ENUM_NAME, INFO_NAME, "java.lang.Object").call(
                    manager, mediaEnum, info, NativeMediaTag);
            return true;
        } catch (e) { warn("setCurMedia failed: " + e); }
        try {
            manager.onMediaTypeChange.overload(ENUM_NAME, "java.lang.Object").call(
                    manager, mediaEnum, NativeMediaTag);
            return true;
        } catch (e2) { warn("onMediaTypeChange failed: " + e2); }
        return false;
    }
    function pushNativeSnapshot() {
        if (!managerHooked || !currentWecar() || latestSnapshot === null
                || latestSnapshot.pkg !== selectedMediaPackage) return;
        var snapshot = latestSnapshot;
        var key = snapshot.pkg + "|" + snapshot.title + "|" + snapshot.artist + "|"
                + snapshot.album + "|" + snapshot.duration + "|" + snapshot.hasArt + "|"
                + snapshot.updatedAt + "|" + snapshot.state;
        if (key === lastNativeKey) return;
        var manager = managerInstance();
        if (manager === null) return;
        var info = buildNativeInfo(snapshot);
        if (info === null) return;
        if (!setNativeCurrent(manager, info)) return;
        var mediaEnum = freshMediaEnum("WECAR_FLOW");
        if (mediaEnum === null) return;
        try {
            manager.onMediaInfoChange.overload(ENUM_NAME, INFO_NAME, "boolean", "java.lang.Object").call(
                    manager, mediaEnum, info, true, NativeMediaTag);
        } catch (e) { warn("native info callback failed: " + e); return; }
        try {
            manager.onMediaStateChange.overload(ENUM_NAME, "boolean", INFO_NAME,
                    "boolean", "java.lang.Object").call(
                    manager, mediaEnum, Number(snapshot.state) === 3, info, true, NativeMediaTag);
        } catch (e2) { warn("native state callback failed: " + e2); return; }
        nativeInfo = info;
        lastNativeKey = key;
        log("MediaSession " + snapshot.pkg + " -> native WECAR_FLOW: " + snapshot.title);
    }
    function clearNativeSelection() {
        if (nativeInfo === null || !ensureMediaClasses()) return;
        var manager = managerInstance();
        nativeInfo = null;
        lastNativeKey = "";
        if (manager === null) return;
        var mediaEnum = freshMediaEnum("NO");
        if (mediaEnum === null) return;
        try {
            manager.onMediaTypeChange.overload(ENUM_NAME, "java.lang.Object").call(
                    manager, mediaEnum, NativeMediaTag);
        } catch (e) {}
    }
    function refreshState() {
        refreshConfig();
        var snapshot = readSnapshot();
        var sources = readSources();
        var selectedSource = findSelectedBridgeSource(snapshot, sources);
        var found = false;
        for (var i = 0; i < sources.length; i++) {
            if (isBridgeSourcePackage(sources[i].pkg)) { found = true; break; }
        }
        var wasSelected = bridgeSelected;
        latestSnapshot = snapshot;
        bridgeAvailable = enabled && (found || selectedSource !== null);
        bridgeSelected = bridgeAvailable && selectedSource !== null;
        selectedMediaPackage = bridgeSelected ? selectedSource.pkg : "";
        if (!bridgeSelected && wasSelected) clearNativeSelection();
        installManagerHooks();
        installNativeViewSupport();
        if (bridgeSelected) pushNativeSnapshot();
    }
    function scheduleRefresh() {
        if (refreshPending) return;
        refreshPending = true;
        setTimeout(function () {
            refreshPending = false;
            try {
                Java.scheduleOnMainThread(function () {
                    refreshState();
                });
            } catch (e) {}
        }, 100);
    }
    function registerUpdates() {
        if (receiverRegistered || currentApp() === null) return;
        try {
            var Receiver = Java.registerClass({
                name: "ru.big.town.instrument.NativeMediaReceiver",
                superClass: BroadcastReceiver,
                methods: {
                    onReceive: {
                        returnType: "void",
                        argumentTypes: ["android.content.Context", "android.content.Intent"],
                        implementation: function () { scheduleRefresh(); }
                    }
                }
            });
            var receiver = Receiver.$new();
            var filter = IntentFilter.$new();
            filter.addAction(NOW_PLAYING);
            filter.addAction(SOURCES);
            filter.addAction(RELOAD);
            var current = currentApp();
            var sdk = Java.use("android.os.Build$VERSION").SDK_INT.value;
            if (sdk >= 33) {
                current.registerReceiver.overload("android.content.BroadcastReceiver",
                        "android.content.IntentFilter", "int").call(current, receiver, filter, 0x2);
            } else {
                current.registerReceiver.overload("android.content.BroadcastReceiver",
                        "android.content.IntentFilter").call(current, receiver, filter);
            }
            receiverRegistered = true;
            log("native media receiver registered");
        } catch (e) { warn("receiver unavailable: " + e); }
    }
    function ensureReady() {
        if (currentApp() === null) {
            setTimeout(function () { try { Java.perform(ensureReady); } catch (e) {} }, 1000);
            return;
        }
        refreshState();
        installManagerHooks();
        installNativeViewSupport();
        registerUpdates();
        if (!managerHooked) {
            setTimeout(function () { try { Java.perform(ensureReady); } catch (e) {} }, 1200);
        }
    }
    log("native bridge started (no ScreenActivity overlay)");
    ensureReady();
});
