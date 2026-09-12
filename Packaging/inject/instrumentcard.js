// Adds an optional "Сейчас играет" item to the driver's instrument-card process.
//
// The exact menu view hierarchy is different between H97C/H97X builds. The agent therefore uses
// a real focusable overlay attached to ScreenActivity's decor root, positioned over the left menu
// rail. It is fail-open: if the OEM activity or any optional Android widget API is unavailable, the
// stock instrument card continues untouched.
Java.perform(function () {
    var TAG = "vt_instrumentcard";
    var SETTINGS_KEY = "voyahtune_instrument_now_playing";
    var NOW_PLAYING = "ru.big.town.anative.NOW_PLAYING";
    var SOURCES = "ru.big.town.anative.NOW_PLAYING_SOURCES";
    var RELOAD = "ru.big.town.anative.DOCK_RELOAD";
    var Uri = Java.use("android.net.Uri");
    var mediaUri = Uri.parse("content://ru.big.town.anative.nowplaying");
    var artUri = Uri.parse("content://ru.big.town.anative.nowplaying/art");
    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var System = Java.use("java.lang.System");
    var Handler = Java.use("android.os.Handler");
    var Looper = Java.use("android.os.Looper");
    var BroadcastReceiver = Java.use("android.content.BroadcastReceiver");
    var IntentFilter = Java.use("android.content.IntentFilter");
    var ViewGroup = Java.use("android.view.ViewGroup");
    var FrameLayout = Java.use("android.widget.FrameLayout");
    var LinearLayout = Java.use("android.widget.LinearLayout");
    var ImageView = Java.use("android.widget.ImageView");
    var TextView = Java.use("android.widget.TextView");
    var Button = Java.use("android.widget.Button");
    var ProgressBar = Java.use("android.widget.ProgressBar");
    var BitmapFactory = Java.use("android.graphics.BitmapFactory");
    var app = ActivityThread.currentApplication();
    var screenHooked = false;
    var receiverRegistered = false;
    var activeActivity = null;
    var panels = {};
    var updatePending = false;
    var timer = null;
    var clickListener = null;

    function log(message) { try { Java.use("android.util.Log").i(TAG, message); } catch (e) {} }
    function warn(message) { try { Java.use("android.util.Log").w(TAG, message); } catch (e) {} }
    function text(value) {
        if (value === null || value === undefined) return "";
        var result = "" + value;
        return result === "null" || result === "undefined" ? "" : result;
    }
    function dp(value) {
        try { return Math.max(1, Math.round(value * app.getResources().getDisplayMetrics().density)); }
        catch (e) { return Math.max(1, Math.round(value)); }
    }
    function enabled() {
        try {
            var value = SettingsGlobal.getString(app.getContentResolver(), SETTINGS_KEY);
            return value === null || text(value) !== "0";
        } catch (e) { return true; }
    }
    function column(cursor, name) {
        try {
            var index = cursor.getColumnIndex(name);
            return index >= 0 ? text(cursor.getString(index)) : "";
        } catch (e) { return ""; }
    }
    function number(cursor, name) {
        try {
            var index = cursor.getColumnIndex(name);
            return index >= 0 ? Number(cursor.getLong(index)) : 0;
        } catch (e) { return 0; }
    }
    function snapshot() {
        var result = {title: "", artist: "", pkg: "", app: "", position: 0, duration: 0,
            hasArt: false, updatedAt: 0};
        var cursor = null;
        try {
            cursor = app.getContentResolver().query(mediaUri, null, null, null, null);
            if (cursor !== null && cursor.moveToFirst()) {
                result.title = column(cursor, "title");
                result.artist = column(cursor, "artist");
                result.pkg = column(cursor, "package");
                result.app = column(cursor, "appLabel");
                result.position = number(cursor, "position");
                result.duration = number(cursor, "duration");
                result.hasArt = number(cursor, "hasArt") === 1;
                result.updatedAt = number(cursor, "updatedAt");
            }
        } catch (e) { warn("snapshot query failed: " + e); }
        finally { try { if (cursor !== null) cursor.close(); } catch (ignored) {} }
        return result;
    }
    function formatTime(milliseconds) {
        var seconds = Math.max(0, Math.floor(Number(milliseconds || 0) / 1000));
        var minutes = Math.floor(seconds / 60);
        seconds = seconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }
    function progressOf(info) {
        if (!(info.duration > 0)) return 0;
        return Math.max(0, Math.min(1000, Math.round(info.position * 1000 / info.duration)));
    }
    function loadArt() {
        try {
            var stream = app.getContentResolver().openInputStream(artUri);
            if (stream === null) return null;
            var bitmap = BitmapFactory.decodeStream(stream);
            stream.close();
            return bitmap;
        } catch (e) { return null; }
    }
    function installPanel(activity) {
        if (activity === null) return;
        try {
            var activityKey = "" + Number(System.identityHashCode(activity));
            activeActivity = Java.retain(activity);
            Java.scheduleOnMainThread(function () {
                try {
                    var decor = activity.getWindow().getDecorView();
                    var root = Java.cast(decor, ViewGroup);
                    if (panels[activityKey]) return;

                    var panel = FrameLayout.$new(activity);
                    panel.setBackgroundColor(0xE9161A20);
                    panel.setPadding(dp(8), dp(6), dp(8), dp(6));
                    panel.setFocusable(true);
                    panel.setClickable(true);
                    var params = FrameLayout.LayoutParams.$new(dp(350), dp(126));
                    params.gravity.value = 3 | 16; // left|center_vertical
                    root.addView(panel, params);

                    var header = Button.$new(activity);
                    header.setText("Сейчас играет");
                    header.setTextSize(13);
                    header.setAllCaps(false);
                    header.setTextColor(0xFFFFFFFF);
                    var headerParams = FrameLayout.LayoutParams.$new(-1, dp(34));
                    headerParams.gravity.value = 3;
                    panel.addView(header, headerParams);

                    var details = LinearLayout.$new(activity);
                    details.setOrientation(LinearLayout.HORIZONTAL.value);
                    var detailsParams = FrameLayout.LayoutParams.$new(-1, dp(78));
                    detailsParams.topMargin.value = dp(38);
                    panel.addView(details, detailsParams);

                    var cover = ImageView.$new(activity);
                    cover.setScaleType(ImageView.ScaleType.CENTER_CROP.value);
                    var coverParams = LinearLayout.LayoutParams.$new(dp(60), dp(60));
                    coverParams.gravity.value = 16;
                    coverParams.rightMargin.value = dp(8);
                    details.addView(cover, coverParams);

                    var lines = LinearLayout.$new(activity);
                    lines.setOrientation(LinearLayout.VERTICAL.value);
                    var linesParams = LinearLayout.LayoutParams.$new(0, -1);
                    linesParams.weight.value = 1.0;
                    details.addView(lines, linesParams);

                    var title = TextView.$new(activity);
                    title.setTextColor(0xFFFFFFFF);
                    title.setTextSize(15);
                    title.setSingleLine(true);
                    lines.addView(title, LinearLayout.LayoutParams.$new(-1, dp(24)));

                    var artist = TextView.$new(activity);
                    artist.setTextColor(0xFFB7C0CC);
                    artist.setTextSize(12);
                    artist.setSingleLine(true);
                    lines.addView(artist, LinearLayout.LayoutParams.$new(-1, dp(20)));

                    var timeline = TextView.$new(activity);
                    timeline.setTextColor(0xFFB7C0CC);
                    timeline.setTextSize(10);
                    lines.addView(timeline, LinearLayout.LayoutParams.$new(-1, dp(18)));

                    var progress = null;
                    try {
                        progress = ProgressBar.$new(activity, null, 0x01010078);
                        progress.setMax(1000);
                        lines.addView(progress, LinearLayout.LayoutParams.$new(-1, dp(6)));
                    } catch (e) { warn("progress widget unavailable: " + e); }

                    if (clickListener === null) {
                        clickListener = Java.registerClass({
                            name: "ru.town.voyah.InstrumentNowPlayingClick",
                            implements: [Java.use("android.view.View$OnClickListener")],
                            methods: {
                                onClick: {
                                    returnType: "void",
                                    argumentTypes: ["android.view.View"],
                                    implementation: function (view) {
                                        try {
                                            var key = "" + Number(System.identityHashCode(view));
                                            // Header and panel share the same action: keep the item
                                            // focusable and refresh its details on selection.
                                            scheduleUpdate();
                                        } catch (e) {}
                                    }
                                }
                            }
                        });
                    }
                    header.setOnClickListener(clickListener.$new());
                    panel.setOnClickListener(clickListener.$new());
                    panels[activityKey] = {panel: Java.retain(panel), cover: Java.retain(cover),
                        title: Java.retain(title), artist: Java.retain(artist),
                        timeline: Java.retain(timeline), progress: progress, artKey: ""};
                    updatePanels();
                    log("now-playing item attached to ScreenActivity");
                } catch (e) { warn("panel attach failed: " + e); }
            });
        } catch (e) { warn("activity retain failed: " + e); }
    }
    function updatePanels() {
        var info = snapshot();
        var show = enabled() && !!info.pkg && !!info.title;
        Object.keys(panels).forEach(function (key) {
            var view = panels[key];
            try {
                view.panel.setVisibility(show ? 0 : 8);
                if (!show) return;
                view.title.setText(info.title);
                view.artist.setText(info.artist || info.app || info.pkg);
                view.timeline.setText(formatTime(info.position) + " / " + formatTime(info.duration));
                if (view.progress !== null) view.progress.setProgress(progressOf(info));
                var artKey = info.pkg + "|" + info.title;
                if (info.hasArt && artKey !== view.artKey) {
                    view.artKey = artKey;
                    var bitmap = loadArt();
                    if (bitmap !== null) view.cover.setImageBitmap(bitmap);
                } else if (!info.hasArt) {
                    view.artKey = "";
                    view.cover.setImageDrawable(null);
                }
            } catch (e) { warn("panel update failed: " + e); }
        });
    }
    function scheduleUpdate() {
        if (updatePending) return;
        updatePending = true;
        setTimeout(function () {
            updatePending = false;
            try { Java.scheduleOnMainThread(updatePanels); } catch (e) {}
        }, 100);
    }
    function startTimer() {
        if (timer !== null) return;
        timer = setInterval(function () {
            if (activeActivity !== null) scheduleUpdate();
        }, 1000);
    }
    function registerUpdates() {
        if (receiverRegistered || app === null) return;
        try {
            var Receiver = Java.registerClass({
                name: "ru.town.voyah.InstrumentNowPlayingReceiver",
                superClass: BroadcastReceiver,
                methods: {
                    onReceive: {
                        returnType: "void",
                        argumentTypes: ["android.content.Context", "android.content.Intent"],
                        implementation: function (context, intent) { scheduleUpdate(); }
                    }
                }
            });
            var receiver = Receiver.$new();
            var filter = IntentFilter.$new();
            filter.addAction(NOW_PLAYING);
            filter.addAction(SOURCES);
            filter.addAction(RELOAD);
            var sdk = Java.use("android.os.Build$VERSION").SDK_INT.value;
            if (sdk >= 33) {
                app.registerReceiver.overload("android.content.BroadcastReceiver",
                        "android.content.IntentFilter", "int").call(app, receiver, filter, 0x2);
            } else {
                app.registerReceiver.overload("android.content.BroadcastReceiver",
                        "android.content.IntentFilter").call(app, receiver, filter);
            }
            receiverRegistered = true;
        } catch (e) { warn("update receiver unavailable: " + e); }
    }
    function hookScreenActivity() {
        if (screenHooked) return true;
        var ScreenActivity = null;
        try { ScreenActivity = Java.use("com.qinggan.instrumentcard.ScreenActivity"); }
        catch (e) { return false; }
        try {
            ScreenActivity.onCreate.overloads.forEach(function (overload) {
                overload.implementation = function () {
                    var result = overload.apply(this, arguments);
                    try { installPanel(this); } catch (e) { warn("onCreate panel: " + e); }
                    return result;
                };
            });
            ScreenActivity.onResume.overloads.forEach(function (overload) {
                overload.implementation = function () {
                    var result = overload.apply(this, arguments);
                    try { installPanel(this); scheduleUpdate(); } catch (e) {}
                    return result;
                };
            });
            ScreenActivity.onDestroy.overloads.forEach(function (overload) {
                overload.implementation = function () {
                    try {
                        var key = "" + Number(System.identityHashCode(this));
                        delete panels[key];
                        if (activeActivity !== null && Number(System.identityHashCode(activeActivity)) === Number(key)) {
                            activeActivity = null;
                        }
                    } catch (e) {}
                    return overload.apply(this, arguments);
                };
            });
            screenHooked = true;
            try {
                Java.choose("com.qinggan.instrumentcard.ScreenActivity", {
                    onMatch: installPanel, onComplete: function () {}
                });
            } catch (e) {}
            log("ScreenActivity hooks installed");
            return true;
        } catch (e) { warn("ScreenActivity hooks failed: " + e); return false; }
    }

    if (app === null) {
        warn("currentApplication is null");
        return;
    }
    registerUpdates();
    hookScreenActivity();
    startTimer();
    function retryScreenHook() {
        if (screenHooked) return;
        try { Java.perform(function () { hookScreenActivity(); }); } catch (e) {}
        if (!screenHooked) setTimeout(retryScreenHook, 5000);
    }
    setTimeout(retryScreenHook, 1000);
});
