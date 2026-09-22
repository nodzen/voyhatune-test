import {bridgeConnected, planMediaTransition} from "./lib/media_transition.mjs";
import {
    buildOemMediaModel,
    isBridgeMediaPackage,
    normalizeMediaSnapshot,
    selectBridgeSource
} from "./lib/media_core.mjs";

// Exposes an active third-party MediaSession through the instrument-card's native media model.
// No view is added to ScreenActivity: the OEM PanelView/MusicBaseView remains the only renderer.
Java.perform(function () {
    var TAG = "vt_instrumentcard";
    var SETTINGS_KEY = "voyahtune_instrument_now_playing";
    var NOW_PLAYING = "ru.big.town.anative.NOW_PLAYING";
    var SOURCES = "ru.big.town.anative.NOW_PLAYING_SOURCES";
    var RELOAD = "ru.big.town.anative.DOCK_RELOAD";
    var WAKE_REFRESH = "ru.big.town.anative.INSTRUMENT_WAKE_REFRESH";
    var ENUM_NAME = "com.qinggan.media.helper.MediaEnum";
    var INFO_NAME = "com.qinggan.media.helper.base.bean.QinMediaInfo";
    var MANAGER_NAME = "com.qinggan.app.mediaCentre.MediaManager";
    var WIDGET_MANAGER_NAME = "com.qinggan.app.mediaCentre.manager.WidgetControlMediaManager";
    var MUSIC_VIEW_NAME = "com.qinggan.cardview.panel.media.MusicBaseView";
    var BASE_MEDIA_VIEW_NAME = "com.qinggan.app.mediaCentre.view.BaseMediaView";
    var RES_ENUM_NAME = "com.pateo.voyah.mediaCard.home.enums.MediaResEnum";
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
    var ArrayList = Java.use("java.util.ArrayList");
    var AndroidLog = Java.use("android.util.Log");
    var app = null;
    var receiverRegistered = false;
    var managerHooked = false;
    var widgetUpdateHooked = false;
    var nativeViewHooked = false;
    var nativeMediaResourceHooked = false;
    var refreshPending = false;
    var bridgeAvailable = false;
    var bridgeSelected = false;
    var selectedMediaPackage = "";
    var emptyProviderReads = 0;
    var enabled = true;
    var latestSnapshot = null;
    var nativeInfo = null;
    var mediaInfoQueryLogs = 0;
    var lastNativeKey = "";
    var MediaEnum = null;
    var QinMediaInfo = null;
    var Manager = null;
    var WECAR = null;
    var NO_MEDIA = null;
    var MediaResEnum = null;
    var WE_CAR = null;
    var mediaControl = null;

    function log(message) { try { AndroidLog.i(TAG, message); } catch (e) {} }
    function warn(message) { try { AndroidLog.w(TAG, message); } catch (e) {} }
    function text(value) {
        if (value === null || value === undefined) return "";
        var result = "" + value;
        return result === "null" || result === "undefined" ? "" : result;
    }
    function isPlaybackActive(state) {
        // BUFFERING/CONNECTING and the seek states are audible/active on Android even though the
        // OEM card does not use STATE_PLAYING exclusively. Treating them as stopped is what makes
        // the card briefly show "нет воспроизведения" while the player is already producing audio.
        var value = Number(state || 0);
        return value === 3 || value === 6 || value === 8 || value === 4 || value === 5
                || value === 9 || value === 10 || value === 11;
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
    function isBluetoothSnapshot(snapshot) {
        if (snapshot === null || snapshot === undefined) return false;
        var pkg = text(snapshot.pkg);
        // On this firmware the Bluetooth AVRCP metadata is exposed by either the framework
        // Bluetooth package or com.qinggan.media. The latter is the OEM proxy used in the live log.
        return pkg === "com.android.bluetooth" || pkg === "com.qinggan.media";
    }
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
        return normalizeMediaSnapshot(result);
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
        return isBridgeMediaPackage(pkg);
    }
    function findSelectedBridgeSource(snapshot, sources) {
        return selectBridgeSource(snapshot, sources);
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
        var model = buildOemMediaModel(snapshot, selectedMediaPackage);
        setInfoValue(info, "setName", "java.lang.String", model.name);
        setInfoValue(info, "setArtist", "java.lang.String", model.artist);
        setInfoValue(info, "setAlbumName", "java.lang.String", model.album);
        setInfoValue(info, "setDuration", "long", model.duration);
        setInfoValue(info, "setMediaId", "java.lang.String", model.mediaId);
        setInfoValue(info, "setMediaType", "java.lang.String", model.mediaType);
        setInfoValue(info, "setHostId", "java.lang.String", model.hostId);
        setInfoValue(info, "setPath", "java.lang.String", model.path);
        setInfoValue(info, "setCoverUrl", "java.lang.String", model.coverUrl);
        setInfoValue(info, "setFav", "boolean", false);
        try {
            var extras = Bundle.$new();
            extras.putString.overload("java.lang.String", "java.lang.String").call(
                    extras, JavaString.$new("package"), JavaString.$new(model.packageName));
            setInfoValue(info, "setExtBundle", "android.os.Bundle", extras);
        } catch (e3) {}
        return info;
    }
    function retainNativeInfo(info) {
        var previous = nativeInfo;
        try {
            var retained = Java.retain(info);
            nativeInfo = Java.cast(retained, QinMediaInfo);
        } catch (e) {
            nativeInfo = null;
            warn("QinMediaInfo retain/cast failed: " + e);
            return null;
        }
        // MediaManager posts listener work asynchronously. Keep the old global reference alive
        // for a few frames after replacing it, then release it so frequent playback updates do not
        // accumulate Frida global references.
        if (previous !== null && previous !== nativeInfo) {
            setTimeout(function () { try { previous.$dispose(); } catch (ignored) {} }, 3000);
        }
        return nativeInfo;
    }
    function sendControl(command) {
        // The cluster can retain this proxy after its visible source has moved back to BT/DAB.
        // Ignore those stale callbacks instead of controlling the last third-party session.
        if (!currentWecar()) return;
        var current = currentApp();
        if (current === null) return;
        var argument = command;
        if (command === "play") {
            if (latestSnapshot !== null && isPlaybackActive(latestSnapshot.state)) return;
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
                    isConnected: {returnType: "boolean", argumentTypes: [], implementation: function () { return currentWecar(); }},
                    isPlay: {returnType: "boolean", argumentTypes: [], implementation: function () { return currentWecar() && !!latestSnapshot && isPlaybackActive(latestSnapshot.state); }},
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
            var proxy = ControlClass.$new();
            mediaControl = Java.cast(proxy, Control);
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
    function putManagerValue(manager, fieldName, key, value) {
        try {
            var clazz = manager.getClass();
            while (clazz !== null) {
                try {
                    var field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    var map = field.get(manager);
                    if (map !== null) {
                        map.put(key, value);
                        return true;
                    }
                    return false;
                } catch (missing) {
                    try { clazz = clazz.getSuperclass(); } catch (end) { clazz = null; }
                }
            }
        } catch (e) {}
        return false;
    }
    function readObjectField(instance, fieldName) {
        if (instance === null || instance === undefined) return null;
        try {
            var clazz = instance.getClass();
            while (clazz !== null) {
                try {
                    var field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(instance);
                } catch (missing) {
                    try { clazz = clazz.getSuperclass(); } catch (end) { clazz = null; }
                }
            }
        } catch (e) {}
        return null;
    }
    function nativeMediaPackage() {
        var pkg = latestSnapshot === null ? "" : text(latestSnapshot.pkg);
        return pkg || text(selectedMediaPackage);
    }
    function nativeMediaLabel() {
        var pkg = nativeMediaPackage();
        var snapshotPackage = latestSnapshot === null ? "" : text(latestSnapshot.pkg);
        var label = latestSnapshot !== null && snapshotPackage === pkg
                ? text(latestSnapshot.app) : "";
        return label || pkg || "Музыка";
    }
    function applyNativeMediaIdentity(view) {
        if (view === null || view === undefined) return;
        // MediaResEnum.WE_CAR is backed by the OEM Tencent/WeChat resource. Preserve the stock
        // card layout, but replace its resource identity with the selected MediaSession app.
        try {
            var nameView = readObjectField(view, "mediaName");
            if (nameView !== null) {
                nameView.setText.overload("java.lang.CharSequence").call(
                        nameView, JavaString.$new(nativeMediaLabel()));
            }
        } catch (e) {}
        try {
            var pkg = nativeMediaPackage();
            var iconView = readObjectField(view, "mediaIcon");
            var current = currentApp();
            if (pkg && iconView !== null && current !== null) {
                iconView.setImageDrawable(current.getPackageManager().getApplicationIcon(
                        JavaString.$new(pkg)));
            }
        } catch (e2) {}
    }
    function freshMediaResEnum(name) {
        if (MediaResEnum === null) return null;
        var value = staticField(MediaResEnum, name);
        if (value === null || value === undefined) return null;
        try { value.getClass(); return value; } catch (e) { return null; }
    }
    function ensureMediaResourceClasses() {
        if (MediaResEnum !== null && WE_CAR !== null && WE_CAR !== undefined) return true;
        try {
            MediaResEnum = Java.use(RES_ENUM_NAME);
            WE_CAR = freshMediaResEnum("WE_CAR");
            return WE_CAR !== null && WE_CAR !== undefined;
        } catch (e) {
            warn("native media resource class unavailable: " + e);
            return false;
        }
    }
    function hookNativeMediaResourceClass(className) {
        try {
            var NativeView = Java.use(className);
            var method = NativeView.changeMediaType.overload(RES_ENUM_NAME);
            method.implementation = function (mediaResEnum) {
                if (nativeInfo !== null && currentWecar()) mediaResEnum = WE_CAR;
                var result = method.call(this, mediaResEnum);
                if (nativeInfo !== null && currentWecar()) applyNativeMediaIdentity(this);
                return result;
            };
            log("native media resource identity hook installed " + className);
            return true;
        } catch (e) {
            warn("native media resource hook unavailable " + className + ": " + e);
            return false;
        }
    }
    function installNativeMediaResourceHook() {
        if (nativeMediaResourceHooked || !ensureMediaResourceClasses()) {
            return nativeMediaResourceHooked;
        }
        // The cluster card owns this method on the current firmware. Keep BaseMediaView as a
        // fallback for a build where MusicBaseView only inherits the OEM implementation.
        nativeMediaResourceHooked = hookNativeMediaResourceClass(MUSIC_VIEW_NAME);
        if (!nativeMediaResourceHooked) {
            nativeMediaResourceHooked = hookNativeMediaResourceClass(BASE_MEDIA_VIEW_NAME);
        }
        return nativeMediaResourceHooked;
    }
    function installWidgetUpdateHook() {
        if (widgetUpdateHooked) return true;
        try {
            var WidgetManager = Java.use(WIDGET_MANAGER_NAME);
            var method = WidgetManager.updateView.overload(ENUM_NAME);
            method.implementation = function () {
                var mediaEnum = arguments[0];
                if (nativeInfo !== null && currentWecar()) {
                    var canonicalEnum = freshMediaEnum("WECAR_FLOW");
                    if (canonicalEnum === null) return method.apply(this, arguments);
                    var actualManager = readObjectField(this, "mediaManager");
                    if (actualManager !== null) {
                        putManagerValue(actualManager, "mediaInfoMap", canonicalEnum, nativeInfo);
                        putManagerValue(actualManager, "mediaInfoMap", mediaEnum, nativeInfo);
                        var actualEnum = readObjectField(this, "curMediaEnum");
                        if (actualEnum !== null && actualEnum !== mediaEnum) {
                            putManagerValue(actualManager, "mediaInfoMap", actualEnum, nativeInfo);
                        }
                    }
                    if (mediaInfoQueryLogs < 20) {
                        mediaInfoQueryLogs++;
                        log("WidgetControlMediaManager map refreshed for " + enumName(mediaEnum));
                    }
                    applyNativeMediaIdentity(readObjectField(this, "widgetViewInter"));
                    return method.apply(this, [canonicalEnum]);
                }
                return method.apply(this, arguments);
            };
            widgetUpdateHooked = true;
            return true;
        } catch (e) {
            warn("widget update hook unavailable: " + e);
            return false;
        }
    }
    function hookManagerMethod(name, handler) {
        try {
            var method = Manager[name];
            method.overloads.forEach(function (overload) {
                overload.implementation = function () {
                    try {
                        var result = handler(this, arguments);
                        if (result !== null && result !== undefined && result.handled) return result.value;
                        if (result !== null && result !== undefined && result.args) {
                            return overload.apply(this, result.args);
                        }
                    } catch (e) { warn(name + " bridge failed: " + e); }
                    return overload.apply(this, arguments);
                };
            });
        } catch (e) { warn("manager hook " + name + " unavailable: " + e); }
    }
    function bridgeManagerArguments(name, args) {
        if (nativeInfo === null || !currentWecar()) return null;
        var mediaEnum = freshMediaEnum("WECAR_FLOW");
        if (mediaEnum === null) return null;
        var replacement = [];
        for (var i = 0; i < args.length; i++) replacement.push(args[i]);
        if (name === "onMediaTypeChange" && replacement.length >= 1) {
            replacement[0] = mediaEnum;
        } else if (name === "onMediaInfoChange" && replacement.length >= 2) {
            replacement[0] = mediaEnum;
            replacement[1] = nativeInfo;
        } else if (name === "onMediaStateChange" && replacement.length >= 1) {
            replacement[0] = mediaEnum;
            if (replacement.length >= 3) replacement[2] = nativeInfo;
        } else if (name === "setCurMedia" && replacement.length >= 2) {
            replacement[0] = mediaEnum;
            replacement[1] = nativeInfo;
        } else {
            return null;
        }
        return {args: replacement};
    }
    function currentWecar() {
        return bridgeConnected(bridgeAvailable, bridgeSelected, selectedMediaPackage);
    }
    function addWecarToNativeViewList(list) {
        // The firmware exposes WECAR_FLOW only when its own WeChat Music option is enabled.
        // Third-party MediaSession apps use that same native media contract, so make it visible
        // to the OEM renderer only while one of those sources is selected.
        if (!currentWecar() || list === null || list === undefined) return;
        var mediaEnum = freshMediaEnum("WECAR_FLOW");
        if (mediaEnum === null) return;
        // Some launcher builds inline getMediaInfoByType() and read MediaManager.mediaInfoMap
        // directly. Keep that map coherent too; otherwise the second stock card can still see
        // null even though the hooked getter returns the bridge object.
        var actualManager = managerInstance();
        if (actualManager !== null && nativeInfo !== null) {
            putManagerValue(actualManager, "mediaInfoMap", mediaEnum, nativeInfo);
        }
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
                if (list === null && currentWecar()) {
                    list = ArrayList.$new();
                    var mediaEnum = freshMediaEnum("WECAR_FLOW");
                    if (mediaEnum !== null) list.add(mediaEnum);
                }
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
        if (!ensureMediaClasses()) return false;
        // MediaManager may appear before the card view classes. Retry this independently on every
        // refresh; otherwise the cluster card permanently falls back to DAB/NO after early boot.
        installNativeViewSupport();
        if (managerHooked) return true;
        var manager = managerInstance();
        if (manager === null) return false;
        installControlProxy();
        installWidgetUpdateHook();
        installNativeMediaResourceHook();
        hookManagerMethod("getCurMediaType", function () {
            var mediaEnum = currentMediaEnum();
            return (currentWecar() || nativeInfo !== null) && mediaEnum !== null
                    ? {handled: true, value: mediaEnum} : null;
        });
        hookManagerMethod("getCurMediaInfo", function () {
            return nativeInfo !== null ? {handled: true, value: nativeInfo} : null;
        });
        hookManagerMethod("getMediaInfoByType", function (self, args) {
            // Some OEM widgets pass a proxy/tag enum that is not equal() to the enum instance
            // received by our callback, even though their current type is WECAR_FLOW. Once a
            // bridge object is live, returning it here is the only stable source for all stock
            // card variants. The OEM also calls this getter while rebuilding a card and may pass
            // a stale/non-WECAR enum; filtering that argument makes the second card read null and
            // fall back to DAB. nativeInfo exists only while a bridge source is selected and is
            // cleared by clearNativeSelection(), so it is safe to answer every concurrent query.
            if (nativeInfo === null) return null;
            if (mediaInfoQueryLogs < 20) {
                mediaInfoQueryLogs++;
                log("getMediaInfoByType intercepted: arg=" + enumName(args[0])
                        + " currentWecar=" + currentWecar());
            }
            return {handled: true, value: nativeInfo};
        });
        ["setCurMedia", "onMediaTypeChange", "onMediaInfoChange",
            "onMediaStateChange"].forEach(function (name) {
            hookManagerMethod(name, function (self, args) {
                return bridgeManagerArguments(name, args);
            });
        });
        hookManagerMethod("getMediaControl", function (self, args) {
            return isWecar(args[0]) && currentWecar() && mediaControl !== null
                    ? {handled: true, value: mediaControl} : null;
        });
        hookManagerMethod("isPlay", function (self, args) {
            return isWecar(args[0]) && currentWecar()
                    ? {handled: true, value: !!latestSnapshot && isPlaybackActive(latestSnapshot.state)} : null;
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
        putManagerValue(manager, "mediaInfoMap", mediaEnum, info);
        var currentSet = false;
        try {
            manager.setCurMedia.overload(ENUM_NAME, INFO_NAME, "java.lang.Object").call(
                    manager, mediaEnum, info, NativeMediaTag);
            currentSet = true;
        } catch (e) { warn("setCurMedia failed: " + e); }
        return currentSet;
    }
    function pushNativeSnapshot(force) {
        if (!managerHooked || !currentWecar() || latestSnapshot === null
                || latestSnapshot.pkg !== selectedMediaPackage) return;
        var snapshot = latestSnapshot;
        var key = snapshot.pkg + "|" + snapshot.title + "|" + snapshot.artist + "|"
                + snapshot.album + "|" + snapshot.duration + "|" + snapshot.hasArt + "|"
                + snapshot.updatedAt + "|" + snapshot.state;
        if (!force && key === lastNativeKey) return;
        var manager = managerInstance();
        if (manager === null) return;
        var info = buildNativeInfo(snapshot);
        if (info === null) return;
        // The OEM posts onMediaTypeChange() to its main handler. Publish our retained object before
        // any callback is queued; otherwise getMediaInfoByType() can observe nativeInfo == null
        // during the first type refresh and the card falls back to DAB/NO.
        info = retainNativeInfo(info);
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
                    manager, mediaEnum, isPlaybackActive(snapshot.state), info, true, NativeMediaTag);
        } catch (e2) { warn("native state callback failed: " + e2); }
        // Re-announce the canonical type only after mediaInfoMap/nativeInfo are populated. The
        // manager hook also rewrites any late OEM DAB/BT event back to this same object.
        try {
            manager.onMediaTypeChange.overload(ENUM_NAME, "java.lang.Object").call(
                    manager, mediaEnum, NativeMediaTag);
        } catch (e3) { warn("native type callback failed: " + e3); }
        lastNativeKey = key;
        log("MediaSession " + snapshot.pkg + " -> native WECAR_FLOW: " + snapshot.title);
    }
    function scheduleNativeRefresh() {
        // The OEM card and the cluster are not always attached to MediaManager at the same frame as
        // the source broadcast. Replaying the same coherent snapshot after those two frames mirrors
        // the refresh produced by opening the stock media picker, without adding a custom renderer.
        [250, 900].forEach(function (delay) {
            setTimeout(function () {
                try { Java.scheduleOnMainThread(function () { pushNativeSnapshot(true); }); }
                catch (e) {}
            }, delay);
        });
    }
    function clearNativeSelection(notifyNoMedia) {
        log("native WECAR selection cleared");
        var previous = nativeInfo;
        nativeInfo = null;
        if (previous !== null) {
            setTimeout(function () { try { previous.$dispose(); } catch (ignored) {} }, 3000);
        }
        lastNativeKey = "";
        // A ready app/BT replacement must not pass through NO: the cluster maps that transient
        // state to DAB and can keep the stale "no songs" error until its next full rebuild.
        if (notifyNoMedia === false) return;
        if (!ensureMediaClasses()) return;
        var manager = managerInstance();
        if (manager === null) return;
        var mediaEnum = freshMediaEnum("NO");
        if (mediaEnum === null) return;
        try {
            manager.onMediaTypeChange.overload(ENUM_NAME, "java.lang.Object").call(
                    manager, mediaEnum, NativeMediaTag);
        } catch (e) {}
    }
    function refreshNativeBluetooth(snapshot) {
        if (!isBluetoothSnapshot(snapshot) || !ensureMediaClasses()) return;
        var manager = managerInstance();
        var mediaEnum = freshMediaEnum("BT_MUSIC");
        if (manager === null || mediaEnum === null) return;
        try {
            // The OEM Bluetooth controller already owns the BT_MUSIC info. Re-issuing only the
            // native type change makes the instrument-card/cluster reread that info after we have
            // invalidated a previous WECAR_FLOW selection.
            manager.onMediaTypeChange.overload(ENUM_NAME, "java.lang.Object").call(
                    manager, mediaEnum, NativeMediaTag);
            log("native BT_MUSIC selection refreshed");
        } catch (e) { warn("native BT_MUSIC refresh failed: " + e); }
    }
    function scheduleNativeBluetoothRefresh() {
        // Cluster and center-screen cards do not subscribe on the same frame. Re-announce BT after
        // leaving WECAR, but only while the provider still reports Bluetooth as the active source.
        [250, 900].forEach(function (delay) {
            setTimeout(function () {
                try {
                    Java.scheduleOnMainThread(function () {
                        if (!currentWecar() && latestSnapshot !== null
                                && isBluetoothSnapshot(latestSnapshot)) {
                            refreshNativeBluetooth(latestSnapshot);
                        }
                    });
                } catch (e) {}
            }, delay);
        });
    }
    function refreshState(force) {
        refreshConfig();
        var snapshot = readSnapshot();
        var sources = readSources();
        // Snapshot and source rows are written independently. A short read where both are empty
        // must not clear a valid WECAR card: the OEM immediately falls back to DAB/NO and keeps only
        // the already decoded cover. Require several consecutive empty reads before accepting it.
        if (!snapshot.pkg && sources.length === 0 && bridgeSelected && selectedMediaPackage) {
            emptyProviderReads++;
            if (emptyProviderReads < 4) {
                log("media provider transiently empty; keeping " + selectedMediaPackage);
                scheduleRefresh();
                return;
            }
        } else {
            emptyProviderReads = 0;
        }
        var selectedSource = findSelectedBridgeSource(snapshot, sources);
        var found = false;
        for (var i = 0; i < sources.length; i++) {
            if (isBridgeSourcePackage(sources[i].pkg)) { found = true; break; }
        }
        var wasSelected = bridgeSelected;
        var previousSelectedPackage = selectedMediaPackage;
        latestSnapshot = snapshot;
        bridgeAvailable = enabled && (found || selectedSource !== null);
        bridgeSelected = bridgeAvailable && selectedSource !== null;
        selectedMediaPackage = bridgeSelected ? selectedSource.pkg : "";
        var transition = planMediaTransition(wasSelected, previousSelectedPackage,
                bridgeSelected, selectedMediaPackage, isBluetoothSnapshot(snapshot));
        var selectionChanged = transition.changed;
        if (transition.clearPrevious) {
            clearNativeSelection(transition.notifyNoMedia);
        }
        installManagerHooks();
        if (bridgeSelected) {
            pushNativeSnapshot(!!force);
            if (transition.refreshThirdParty || force) scheduleNativeRefresh();
        } else {
            // BT_MUSIC is owned by the OEM Bluetooth controller. Calling MediaManager's
            // onMediaTypeChange from this process while MusicBaseView is still constructing
            // crashes this firmware in BaseMediaView.initProgress (SIGSEGV). Let the stock
            // controller publish its own BT event; we only clear our WECAR state above.
            if (transition.refreshBluetooth || force) {
                log("stock BT_MUSIC refresh retained; no manual MediaManager callback");
            }
        }
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
    function scheduleWakeRefresh() {
        // Long suspend may leave ScreenActivity alive with a stale card attachment. Re-publish the
        // coherent native state after several wake frames instead of force-stopping the OEM process.
        [0, 700, 2200].forEach(function (delay) {
            setTimeout(function () {
                try { Java.scheduleOnMainThread(function () { refreshState(true); }); }
                catch (e) {}
            }, delay);
        });
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
                        implementation: function (context, intent) {
                            var action = intent === null ? "" : text(intent.getAction());
                            if (action === WAKE_REFRESH) {
                                scheduleWakeRefresh();
                            } else {
                                scheduleRefresh();
                            }
                        }
                    }
                }
            });
            var receiver = Receiver.$new();
            var filter = IntentFilter.$new();
            filter.addAction(NOW_PLAYING);
            filter.addAction(SOURCES);
            filter.addAction(RELOAD);
            filter.addAction(WAKE_REFRESH);
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
        registerUpdates();
        if (!managerHooked) {
            setTimeout(function () { try { Java.perform(ensureReady); } catch (e) {} }, 1200);
        }
    }
    log("native bridge started (no ScreenActivity overlay)");
    ensureReady();
});
