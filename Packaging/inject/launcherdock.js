import {bridgeConnected, planMediaTransition} from "./lib/media_transition.mjs";

// launcherdock.js — переопределение кнопок «Звонок»/«Радио» и стабилизация доков обоих экранов Open Voyah.
// На ОД-прошивках это NavigationBarMain + NavigationBarSecond, на ПИ — общий NavigationBar с mScreenId.
//
// Механика: хук навигационного бара и списка приложений штатного лаунчера:
//   • КОНФИГ — живьём из Settings.Global: voyahtune_dock1/2
//     (= packageName кнопок «Звонок»/«Радио»; "none" = штатная кнопка). Опц. одноимённые *Dpi ключи.
//     Пишет их Native (SetModesReceiverDynamic.mirrorDock), читаем как action() в steeringwheelkeys.js.
//   • ИКОНКА слота — через view.setBackground(drawable), НЕ setImageDrawable: картинка слота живёт в
//     background у NoToggleRadioButton. Оригинал бэкапим один раз (getBackground), кастом строим из
//     pm.getApplicationIcon → Bitmap → 50x50 → BitmapDrawable + Java.retain. Всё на main-треде + invalidate.
//     Хук updateTheme переустанавливает иконки после каждой перекраски темы (иначе фон сбрасывается).
//   • КЛИК — на водительском onClick сравнивает view.getId() с mScreenUpItemView1/2 (штатно «Звонок»/«Радио»). При совпадении и
//     если pkg установлен — делегируем Native. Пассажирские Air/Seat остаются полностью штатными.
//     Native запускает обычную задачу целевого пакета на display 0, а vd_bypass ужимает её
//     WindowManager-рамку. VD — только для split-пресетов.
//   • ДОЛГИЙ ТАП по «Звонку»/«Радио» — если назначено действие (voyahtune_dockNLong), шлём Native broadcast
//     OPEN_DOCK_ACTION. Для старых конфигов сохраняется fallback через OPEN_DOCK_SPLIT и HasSplit.
//     Если действие не назначено — слушатель возвращает false (штатное долгое поведение лаунчера).
//   • ПОДСВЕТКА — updateSelectedApp: reverse-mapping (наш pkg слота → штатный pkg, закреплённый за слотом),
//     чтобы родной лаунчер чекнул правильную кнопку. Косметика, не блокер.
//   • RELOAD — приёмник ru.big.town.anative.DOCK_RELOAD перечитывает конфиг и перерисовывает иконки
//     (иконки рисуются проактивно; клик читает конфиг живьём, ему reload не нужен).
//   • ALL APPS — в списки обоих экранов добавляются все launchable user-apps, которых штатный
//     лаунчер не показывает; замороженные пакеты из blacklist убираются из списка через
//     voyahtune_frozen_apps. PackageManager scan кэшируется до PACKAGE_ADDED/REMOVED/CHANGED;
//     package-broadcast через штатный AllAppDataManager.reload() пересобирает оба списка и обновляет открытые UI
//     без polling. Клик идёт через OEM AppLauncher с mScreenId владельца All Apps (и проверочным fallback по view),
//     поэтому top activity остаётся целевым package на соответствующем физическом display.
//   • ВОЗВРАТ ИЗ FULLSCREEN/ПЕРЕНОСА — TOP_ACTIVITY_CHANGED повторно просит штатный LauncherModel
//     показать navigation bar нужного физического экрана. Во время OEM transfer короткий deadline-guard
//     не даёт onMoveStart удалить оба бара до того, как foreground-кэш обновится на destination.
Java.perform(function () {
    // Кнопка → штатный pkg, который родной лаунчер умеет подсвечивать (oversea, главный экран).
    // ВНИМАНИЕ: значения версионно-хрупкие, подтвердить на живой голове H97C.
    var STOCK_SLOT_PKG = { 1: "com.qinggan.bluetoothphone", 2: "com.qinggan.app.music" };
    var STOCK_SLOT_LABEL = { 1: "Звонок", 2: "Радио" };
    var NAV_MAIN   = "com.qinggan.launcher.navigation.NavigationBarMain"; // класс навбара в ОД-прошивках
    var NAV_SECOND = "com.qinggan.launcher.navigation.NavigationBarSecond";
    var RELOAD_ACT = "ru.big.town.anative.DOCK_RELOAD";
    var OUR_PKG    = "ru.big.town.anative";           // наш VD-хост (SplitHostActivity) для подсветки
    var RESTORE_PKG = "ru.big.town.restoremode";      // VoyahTune (UI) — открывается долгим тапом по «меню»

    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var SystemClock    = Java.use("android.os.SystemClock");
    var Intent         = Java.use("android.content.Intent");
    var Bitmap         = Java.use("android.graphics.Bitmap");
    var BitmapConfig   = Java.use("android.graphics.Bitmap$Config");
    var BitmapDrawable = Java.use("android.graphics.drawable.BitmapDrawable");
    var Canvas         = Java.use("android.graphics.Canvas");

    var TAG = "vt_launcherdock";
    var Log = Java.use("android.util.Log");
    // Live OD source of truth for the foreground package. updateSelectedApp() is posted to the
    // launcher UI queue and may still contain the previous app when a later show/dismiss arrives.
    // Keep both classes optional so PI/other firmware can fall back to the event cache.
    var LauncherAppUtils = null;
    try { LauncherAppUtils = Java.use("com.qinggan.launcher.base.utils.AppUtils"); }
    catch (e) { Log.w(TAG, "[dock] AppUtils unavailable; foreground cache fallback: " + e); }
    var AccountConstantUtil = null;
    try { AccountConstantUtil = Java.use("com.qinggan.account.AccountConstantUtil"); }
    catch (e) { Log.w(TAG, "[dock] AccountConstantUtil unavailable; using | separator: " + e); }

    // На ОД классы экранов раздельные, на ПИ общий класс различается полем mScreenId.
    var SHARED_NAV = false;
    var NAV_CLASSES = [];
    try {
        Java.use(NAV_MAIN);
        NAV_CLASSES.push({ name: NAV_MAIN, screen: 0 });
        try { Java.use(NAV_SECOND); }
        catch (e2) { Log.w(TAG, "OD passenger NavigationBarSecond unavailable: " + e2); }
        Log.i(TAG, "OD firmware");
    } catch (e) {
        NAV_MAIN   = "com.qinggan.mainlauncher.navigation.NavigationBar";  // класс навбара в ПИ-прошивках
        NAV_SECOND = null;
        SHARED_NAV = true;
        NAV_CLASSES.push({ name: NAV_MAIN, screen: -1 });
        Log.i(TAG, "PI firmware");
    }

    function cleanJavaString(value) {
        if (value === null || value === undefined) return "";
        var result = "" + value;
        return (result === "null" || result === "undefined") ? "" : result;
    }

    // Поля OEM private, а passenger OD имеет отдельную, не наследующую Main, модель. Доступ по имени
    // намеренно fail-open: отсутствие optional view на другой прошивке не должно сорвать весь dock pass.
    function dockField(instance, name) {
        try {
            var field = instance[name];
            if (field !== null && field !== undefined && field.value !== undefined) {
                return field.value;
            }
        } catch (direct) {}
        // Some live OD private fields (notably NavigationBarController.mNavigationBar) are absent
        // from Frida's direct wrapper even though sibling fields resolve. Reflection keeps the lift
        // replay fail-open without assuming public/package visibility.
        try {
            var c = instance.getClass();
            while (c !== null) {
                try {
                    var reflected = c.getDeclaredField(name);
                    reflected.setAccessible(true);
                    return reflected.get(instance);
                } catch (missing) {
                    try { c = c.getSuperclass(); } catch (end) { c = null; }
                }
            }
        } catch (ignored) {}
        return null;
    }

    function runtimeObject(value) {
        if (value === null || value === undefined) return null;
        try { return Java.cast(value, Java.use(cleanJavaString(value.getClass().getName()))); }
        catch (e) {
            try { Log.e(TAG, "[dock] runtime cast failed for " + value.getClass().getName() + ": " + e); }
            catch (ignored) {}
            return null;
        }
    }

    // Driver app slots and passenger Air/Seat have different ABIs. Keep the generic driver resolver
    // free of passenger fields so icon/click/long-tap overrides can never leak to display 1.
    function dockViews(instance) {
        return {
            up: dockField(instance, "mScreenUpView"),
            down: dockField(instance, "mScreenDownView"),
            group: dockField(instance, "mScreenUpRadioGroup"),
            home: dockField(instance, "mScreenUpHomeView"),
            allApps: dockField(instance, "mScreenUpAllAppView"),
            slot1: dockField(instance, "mScreenUpItemView1"),
            slot2: dockField(instance, "mScreenUpItemView2"),
            slot1Name: "mScreenUpItemView1",
            slot2Name: "mScreenUpItemView2",
            extra1: dockField(instance, "mScreenUpItemView3"),
            extra2: dockField(instance, "mScreenUpItemView4")
        };
    }

    // Номер экрана инстанса навбара: 0 = водительский (наш), 1 = пассажирский, -1 = определить не удалось.
    // Основной источник — поле mScreenId (им же пользуется сам лаунчер). Фолбэк — displayId вьюхи бара:
    // не зависит от приватных полей лаунчера и переживает переименования на другой прошивке.
    function screenIdOf(instance, fallbackScreen) {
        try {
            var v = dockField(instance, "mScreenId");
            if (v === 0 || v === 1) return v;
        } catch (e) {}
        try {
            var anyView = dockField(instance, "mScreenUpAllAppView")
                    || dockField(instance, "mScreenUpItemView1");
            if (anyView) {
                var d = anyView.getDisplay();
                if (d) return d.getDisplayId();
            }
        } catch (e) {}
        try {
            var className = "" + instance.getClass().getName();
            if (className.indexOf("NavigationBarSecond") >= 0) return 1;
            if (!SHARED_NAV && className.indexOf("NavigationBarMain") >= 0) return 0;
        } catch (e) {}
        return (fallbackScreen === 0 || fallbackScreen === 1) ? fallbackScreen : -1;
    }

    function managedScreenId(instance, fallbackScreen) {
        var id = screenIdOf(instance, fallbackScreen);
        if (id === 0 || id === 1) return id;
        if (!managedScreenId._warned) {
            managedScreenId._warned = true;
            try { Log.i(TAG, "screenId неизвестен на общем классе навбара — хуки пропущены"); } catch (e) {}
        }
        return -1;
    }

    // Кэш иконочного конфига водительского дока (для проактивной перерисовки).
    var cache = { dock1: "none", dock2: "none", fullscreen: {}, frozenApps: {} };
    // Бэкап штатных фонов слотов: originalBg["<screenId>:<viewName>"] = Drawable (один раз на экран+поле,
    // чтобы Drawable одного экрана никогда не попал во вьюху другого — см. updateIcons).
    var originalBg = {};
    // Удержанные Drawable (иначе GC уберёт background).
    var retained = [];
    // Иконка выбранного приложения не меняется между bounded startup-проходами. Повторный decode
    // Bitmap через Java bridge был заметен как микрофриз при запуске лаунчера.
    var appDrawableCache = {};
    var MAX_RETAINED_DRAWABLES = 64;
    // Последний нажатый слот (для корректной подсветки нашего VD-хоста в updateSelectedApp).
    var lastSlot = { 0: 0, 1: 0 };
    // viewId кнопки → номер кнопки (1=Звонок, 2=Радио). Заполняется в updateIcons, читается в долгом тапе
    // (устойчиво к нескольким инстансам навбара — ключ по id вью, а не по последнему инстансу).
    var slotByViewId = {};
    // Приложение переднего плана ПО ЭКРАНАМ. Один общий кэш позволял пассажирскому бару перетирать
    // foreground водительского, и решения о видимости дока принимались по чужому экрану.
    var fgByScreen = { 0: { pkg: "", act: "" }, 1: { pkg: "", act: "" } };
    // onMoveStart ставит UI-runnable асинхронно и последовательно вызывает dismiss обоих контроллеров.
    // Поэтому guard хранится по source display и не consume-ится первым dismiss. generation нужен для
    // корреляции start/stop в живых логах; безопасность обеспечивают source+package match и короткий TTL.
    var moveDockGuards = {
        0: { deadline: 0, generation: 0, pkg: "" },
        1: { deadline: 0, generation: 0, pkg: "" }
    };
    var moveDockGeneration = 0;
    var schedulePhysicalDockRecovery = null;
    var physicalDockRecoveryTimer = null;
    // installAllAppsHooks owns the actual list caches. The reload receiver is outside that
    // function, so keep an explicit no-op until the optional All Apps ABI is resolved instead of
    // reaching into block-local variables from the broadcast hot path.
    var invalidateAllAppsCaches = function () {};
    var reloadAllApps = function () {};

    function activeMoveDockGuard() {
        if (cfg("dockpin") === "0" || cfg("freeform") === "0") return null;
        var now = Number(SystemClock.elapsedRealtime());
        for (var sid = 0; sid <= 1; sid++) {
            var guard = moveDockGuards[sid];
            if (guard.deadline > now && guard.deadline - now <= 10000) {
                return { screen: sid, pkg: guard.pkg, remaining: guard.deadline - now };
            }
        }
        return null;
    }

    // Штатные пакеты, которым МОЖНО скрывать док: их окна оконный режим не ужимает (они честно
    // разворачиваются на весь экран), поэтому прятать док для них — правильное штатное поведение.
    // ВАЖНО: список должен соответствовать блэклисту ffBlacklisted в vd_bypass.js — если там
    // появится новый префикс, добавить и сюда, иначе док зависнет поверх полноэкранного окна.
    // ИСКЛЮЧЕНИЕ — ru.big.town: наши окна тоже полноэкранные, но часть из них сама резервирует полосу
    // под родной док, поэтому решение по ним принимает не этот список, а dockKept() по имени активити.
    var STOCK_PREFIX = ["com.android", "com.qinggan", "com.pateo", "com.baidu", "com.huawei",
                        "com.iflytek", "com.iland", "com.mega", "com.qti", "com.qualcomm",
                        "com.tencent", "com.nng.igo.primong", "com.bz.CA08"];
    function isStockPkg(pkg) {
        pkg = cleanJavaString(pkg);
        if (!pkg) return true;                                   // неизвестно → считаем штатным (не мешаем)
        if (pkg === "com.android.settings" || pkg === "com.android.documentsui") return false;
        for (var i = 0; i < STOCK_PREFIX.length; i++) if (pkg.indexOf(STOCK_PREFIX[i]) === 0) return true;
        return false;
    }

    function fullscreenPackageSet(csv) {
        var out = {};
        if (csv && csv !== "none") {
            var packages = csv.split(",");
            for (var i = 0; i < packages.length; i++) {
                var pkg = cleanJavaString(packages[i]);
                if (pkg) out[pkg] = true;
            }
        }
        return out;
    }

    // Резервный UI-гейт All Apps. Эти пакеты нельзя заморозить даже при повреждённой записи
    // Settings.Global: радио, Bluetooth-телефон и базовые автомобильные контуры должны оставаться
    // доступными и не затрагиваться чисткой.
    var NEVER_FREEZE_LAUNCHER = {
        "com.qinggan.app.music": true,
        "com.qinggan.app.radio": true,
        "com.pateo.rdsapp": true,
        "com.qinggan.tuner.service": true,
        "com.qinggan.media": true,
        "com.qinggan.audiopolicy.service": true,
        "com.qinggan.bluetoothphone": true,
        "com.qinggan.app.launcher": true,
        "com.qinggan.app.vehicle": true,
        "com.qinggan.app.vehiclesetting": true,
        "com.qinggan.app.setting": true,
        "com.qinggan.systemservice": true,
        "com.qinggan.systemui": true,
        "com.qinggan.canbus.service": true,
        "com.qinggan.carsignal.service": true,
        "com.qinggan.QGBus": true,
        "com.qinggan.powermanager": true,
        "com.qinggan.lastmemory.service": true,
        "com.qinggan.app.thirdscreen": true,
        "com.qinggan.app.islandapp": true
    };

    function frozenLauncherPackageSet(csv) {
        var out = {};
        if (csv && csv !== "none") {
            var packages = csv.split(",");
            for (var i = 0; i < packages.length; i++) {
                var pkg = cleanJavaString(packages[i]);
                if (pkg && !NEVER_FREEZE_LAUNCHER[pkg]) out[pkg] = true;
            }
        }
        return out;
    }

    function isFrozenLauncherPackage(pkg) {
        pkg = cleanJavaString(pkg);
        return !!pkg && NEVER_FREEZE_LAUNCHER[pkg] !== true && cache.frozenApps[pkg] === true;
    }

    function isUserFullscreen(pkg) {
        pkg = cleanJavaString(pkg);
        return !!pkg && cache.fullscreen[pkg] === true;
    }

    // Наши активити, которые САМИ отступают на полосу родного дока (их контент туда не залезает).
    // Под ними док обязан остаться — иначе получается пустая чёрная полоса. Остальные наши экраны
    // отступа не делают, им док прятать нужно, иначе он накроет их левый край.
    function ourInsetActivity(act) {
        act = cleanJavaString(act);
        return act.indexOf("SplitHostActivity") >= 0
            || act.indexOf("restoremode.MainActivity") >= 0
            || act.indexOf("AdvanceActivity") >= 0
            || act.indexOf("TripHistoryActivity") >= 0;
    }

    // ЕДИНОЕ условие «док должен остаться под этим окном». Одно на всех потребителей — раньше их было
    // три с разными предикатами, и они противоречили друг другу.
    function dockKept(pkg, act) {
        pkg = cleanJavaString(pkg);
        act = cleanJavaString(act);
        if (cfg("dockpin") === "0" || cfg("freeform") === "0") return false;
        if (!pkg) return false;                                  // неизвестно → не мешаем штатному
        if (isUserFullscreen(pkg)) return false;                  // пользователь явно выбрал полный экран
        if (pkg.indexOf("ru.big.town") === 0) return ourInsetActivity(act);
        return !isStockPkg(pkg);
    }

    // Native публикует guard одной строкой "elapsedDeadline|package" непосредственно перед
    // startActivity. Это закрывает окно гонки dismiss → updateSelectedApp при запуске со звёздочки:
    // foreground-кэш в этот момент ещё закономерно содержит Launcher/старое приложение.
    function pendingDockLaunch(screenId) {
        if (cfg("dockpin") === "0" || cfg("freeform") === "0") return null;
        try {
            var raw = cfg("dockLaunchGuard" + screenId);
            if (raw === "none") return null;
            var sep = raw.indexOf("|");
            if (sep <= 0 || sep >= raw.length - 1) return null;
            var deadline = parseInt(raw.substring(0, sep), 10);
            var now = Number(SystemClock.elapsedRealtime());
            var remaining = deadline - now;
            // Верхний предел делает persisted Settings-запись безопасной после reboot, когда
            // elapsedRealtime снова начинается с нуля. Штатный guard держится 5 секунд.
            if (isNaN(deadline) || remaining <= 0 || remaining > 10000) return null;
            var pkg = raw.substring(sep + 1);
            if (isUserFullscreen(pkg)) return null;
            // Guard не должен удержать док поверх полноэкранного штатного приложения, которому
            // штатный dismiss как раз нужен. Для наших двух inset-экранов activity заранее известна.
            var keep = (pkg === OUR_PKG || pkg === RESTORE_PKG)
                    || (pkg.indexOf("ru.big.town") !== 0 && !isStockPkg(pkg));
            if (!keep) return null;
            return { pkg: pkg, remaining: remaining };
        } catch (e) { return null; }
    }

    function ctx() {
        try { var app = ActivityThread.currentApplication(); if (app !== null) return app.getApplicationContext(); } catch (e) {}
        return ActivityThread.currentActivityThread().getSystemContext();
    }

    // Значение слота из Settings.Global; нет значения → "none".
    function cfg(key) {
        try {
            var v = SettingsGlobal.getString(ctx().getContentResolver(), "voyahtune_" + key);
            return (v === null || v === "") ? "none" : v.toString();
        } catch (e) { return "none"; }
    }

    function parseTopActivity(top) {
        top = cleanJavaString(top);
        if (!top) return { pkg: "", act: "" };
        var separator = "|";
        try {
            if (AccountConstantUtil !== null) {
                separator = cleanJavaString(AccountConstantUtil.SEPARATOR.value) || "|";
            }
        } catch (ignored) {}
        var separatorAt = top.indexOf(separator);
        // The inspected H97C launcher uses '|'. Preserve recovery if an optional account helper
        // reports a different/invalid value on another firmware variant.
        if (separatorAt < 0 && separator !== "|") separatorAt = top.indexOf("|");
        if (separatorAt < 0) return { pkg: top, act: "" };
        return {
            pkg: cleanJavaString(top.substring(0, separatorAt)),
            act: cleanJavaString(top.substring(separatorAt + separator.length))
        };
    }

    // Returns a live top when the OEM helper is available. A non-empty live answer is authoritative,
    // including when it says Launcher/Home: a stale fullscreen cache must not keep the dock hidden.
    function topActivityForScreen(screenId, context) {
        var cached = (screenId === 0 || screenId === 1)
                ? fgByScreen[screenId] : { pkg: "", act: "" };
        if (LauncherAppUtils === null || (screenId !== 0 && screenId !== 1)) {
            return { pkg: cached.pkg, act: cached.act, live: false };
        }
        try {
            var parsed = parseTopActivity(LauncherAppUtils.getTopAppInfo(
                    context || ctx(), screenId, 4));
            if (!parsed.pkg) return { pkg: cached.pkg, act: cached.act, live: false };
            fgByScreen[screenId].pkg = parsed.pkg;
            fgByScreen[screenId].act = parsed.act;
            return { pkg: parsed.pkg, act: parsed.act, live: true };
        } catch (e) {
            if (!topActivityForScreen._warned) {
                topActivityForScreen._warned = true;
                Log.w(TAG, "[dock] live top lookup failed; using event cache: " + e);
            }
            return { pkg: cached.pkg, act: cached.act, live: false };
        }
    }

    function refreshCache() {
        var nextDock1 = cfg("dock1");
        var nextDock2 = cfg("dock2");
        if (cache.dock1 !== nextDock1 && cache.dock1 !== "none") delete appDrawableCache[cache.dock1];
        if (cache.dock2 !== nextDock2 && cache.dock2 !== "none") delete appDrawableCache[cache.dock2];
        cache.dock1 = nextDock1;
        cache.dock2 = nextDock2;
        cache.fullscreen = fullscreenPackageSet(cfg("fullscreen_apps"));
        cache.frozenApps = frozenLauncherPackageSet(cfg("frozen_apps"));
        Log.i(TAG, "[dock] cache: driver=" + cache.dock1 + "/" + cache.dock2
                + " fullscreen=" + Object.keys(cache.fullscreen).join(",")
                + " frozenApps=" + Object.keys(cache.frozenApps).length);
    }

    function dockPackage(screenId, slot, live) {
        // Passenger Air/Seat are OEM vehicle controls, not user-remappable application slots.
        if (screenId !== 0) return "none";
        var key = "dock" + slot;
        if (live) return cfg(key);
        return slot === 1 ? cache.dock1 : cache.dock2;
    }

    // Проверка «pkg установлен и запускаем» — гейт перед перехватом клика.
    function isInstalled(pkg) {
        if (pkg === "none") return false;
        try {
            var li = ctx().getPackageManager().getLaunchIntentForPackage(pkg);
            return li !== null;
        } catch (e) { return false; }
    }

    function retainDrawable(obj) {
        try {
            var r = Java.retain(obj);
            retained.push(r);
            // updateTheme может вызываться много раз за жизнь launcher. Старые background уже давно
            // заменены; освобождаем их global refs с большим запасом для живых navbar instances.
            while (retained.length > MAX_RETAINED_DRAWABLES) {
                var old = retained.shift();
                try { old.$dispose(); } catch (ignored) {}
            }
            return r;
        } catch (e) { return obj; }
    }

    // Drawable иконки приложения: pm.getApplicationIcon → рисуем на Bitmap → масштаб 50x50 → BitmapDrawable.
    function getAppDrawable(pkg) {
        if (appDrawableCache[pkg]) return appDrawableCache[pkg];
        try {
            var pm = ctx().getPackageManager();
            var ai = pm.getApplicationInfo(pkg, 0);
            var icon = pm.getApplicationIcon(ai);
            var bmp = Bitmap.createBitmap(icon.getIntrinsicWidth(), icon.getIntrinsicHeight(), BitmapConfig.ARGB_8888.value);
            var canvas = Canvas.$new(bmp);
            icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            icon.draw(canvas);
            var scaled = Bitmap.createScaledBitmap(bmp, 50, 50, true);
            var d = BitmapDrawable.$new(ctx().getResources(), scaled);
            d.setGravity(17);              // Gravity.CENTER
            d.setBounds(0, 0, 50, 50);
            var retainedDrawable = retainDrawable(d);
            appDrawableCache[pkg] = retainedDrawable;
            return retainedDrawable;
        } catch (e) {
            Log.e(TAG, "[dock] getAppDrawable err " + pkg + ": " + e);
            return null;
        }
    }

    // Открыть VoyahTune (UI RestoreMode) — по долгому тапу «меню».
    function openVoyahTune() {
        try {
            var i = Intent.$new();
            i.setClassName(RESTORE_PKG, "ru.big.town.restoremode.MainActivity");
            i.addFlags(0x10000000);   // FLAG_ACTIVITY_NEW_TASK
            ctx().startActivity(i);
            try { Java.use("android.util.Log").i("voyahdock", "menu long-press -> VoyahTune"); } catch (ee) {}
            Log.i(TAG, "[dock] menu long-press -> VoyahTune");
        } catch (e) { Log.e(TAG, "[dock] openVoyahTune err: " + e); }
    }

    // Слушатель долгого тапа «меню» (кнопка «все приложения», последний элемент дока). Регистрируем
    // один раз лениво. onLongClick → VoyahTune + return true (гасим штатное долгое). Короткий тап не
    // трогаем — идёт штатно (открытие списка приложений).
    var menuLC = null;
    function getMenuLongClick() {
        if (menuLC !== null) return menuLC;
        try {
            var Listener = Java.registerClass({
                name: "ru.big.town.dock.MenuLongClick",
                implements: [Java.use("android.view.View$OnLongClickListener")],
                methods: {
                    onLongClick: {
                        returnType: "boolean",
                        argumentTypes: ["android.view.View"],
                        implementation: function (view) { openVoyahTune(); return true; }
                    }
                }
            });
            menuLC = Listener.$new();
        } catch (e) { Log.e(TAG, "[dock] menuLongClick reg err: " + e); }
        return menuLC;
    }

    // Долгий тап по кнопке «Звонок»/«Радио» → выполнить назначенное действие. Слушатель один на обе кнопки;
    // кнопку определяем по view.getId() через slotByViewId. Если action не назначен, оставляем старый split
    // fallback для конфигов до 3.8.4 и возвращаем false для полностью штатного слота.
    var slotLC = null;
    function getSlotLongClick() {
        if (slotLC !== null) return slotLC;
        try {
            var Listener = Java.registerClass({
                name: "ru.big.town.dock.SlotLongClick",
                implements: [Java.use("android.view.View$OnLongClickListener")],
                methods: {
                    onLongClick: {
                        returnType: "boolean",
                        argumentTypes: ["android.view.View"],
                        implementation: function (view) {
                            try {
                                var slot = slotByViewId["" + view.getId()] || 0;
                                var action = slot ? cfg("dock" + slot + "Long") : "none";
                                var has = slot ? cfg("dock" + slot + "HasSplit") : "?";
                                try { Java.use("android.util.Log").i("voyahdock", "button long-press id=" + view.getId() + " button=" + (STOCK_SLOT_LABEL[slot] || slot) + " action=" + action + " hasSplit=" + has); } catch (ee) {}
                                if (slot === 0) return false;
                                if (action && action !== "none") {
                                    openDockAction(slot, action);
                                    return true;
                                }
                                if (has === "1") {              // legacy split-only config
                                    openDockSplit(slot);
                                    return true;
                                }
                                return false;                     // штатное долгое поведение
                            } catch (e) {
                                try { Log.e(TAG, "slot long-press err: " + e); } catch (ee) {}
                                return false;
                            }
                        }
                    }
                }
            });
            slotLC = Listener.$new();
        } catch (e) { Log.e(TAG, "[dock] slotLongClick reg err: " + e); }
        return slotLC;
    }

    // Открыть назначенный слоту сплит — broadcast OPEN_DOCK_SPLIT в Native (тот резолвит детали и стартует).
    function openDockSplit(slot) {
        try {
            var i = Intent.$new("ru.big.town.anative.OPEN_DOCK_SPLIT");
            i.setClassName(OUR_PKG, "ru.big.town.anative.SetModesReceiverDynamic");
            i.putExtra.overload('java.lang.String', 'int').call(i, "slot", slot);
            i.addFlags(0x00000020);   // FLAG_INCLUDE_STOPPED_PACKAGES — добудиться, даже если Native стоплен
            ctx().sendBroadcast(i);
            Log.i(TAG, "[dock] OPEN_DOCK_SPLIT slot=" + slot);
            try { Java.use("android.util.Log").i("voyahdock", "OPEN_DOCK_SPLIT sent slot=" + slot); } catch (ee) {}
        } catch (e) {
            Log.e(TAG, "[dock] openDockSplit err: " + e);
            try { Log.e(TAG, "openDockSplit err: " + e); } catch (ee) {}
        }
    }

    // Универсальное действие назначенного слоту долгого нажатия. Native проверяет точное значение
    // против защищённого Settings.Global snapshot перед исполнением.
    function openDockAction(slot, action) {
        try {
            var i = Intent.$new("ru.big.town.anative.OPEN_DOCK_ACTION");
            i.setClassName(OUR_PKG, "ru.big.town.anative.SetModesReceiverDynamic");
            i.putExtra.overload('java.lang.String', 'int').call(i, "slot", slot);
            i.putExtra.overload('java.lang.String', 'java.lang.String').call(i, "action", "" + action);
            i.addFlags(0x00000020);
            ctx().sendBroadcast(i);
            Log.i(TAG, "[dock] OPEN_DOCK_ACTION slot=" + slot + " action=" + action);
        } catch (e) {
            Log.e(TAG, "[dock] openDockAction err: " + e);
        }
    }

    function setDockViewVisibility(view, visibility, label) {
        if (!view) return;
        try { view.setVisibility(visibility); }
        catch (e) { Log.e(TAG, "[dock] visibility " + label + " err: " + e); }
    }

    function setDockViewHeight(view, height, label) {
        if (!view) return;
        try {
            var lp = view.getLayoutParams();
            if (lp === null) return;
            lp.height.value = height;
            view.setLayoutParams(lp);
        } catch (e) { Log.e(TAG, "[dock] height " + label + " err: " + e); }
    }

    // OEM dismiss() only starts a 100-ms x=-width animation and removes the Window from its end
    // callback. Another launcher lifecycle event can end/reuse that animator before removal. Cancelling
    // it with the OEM listener still attached invokes onAnimationEnd(), so a following explicit remove
    // can remove the same root twice and destabilize Launcher. Silence/cancel the animator first, move
    // the Window off-screen synchronously, then use ordinary removeView(). Keeping the Window attached
    // would leave its navigation-bar inset active and constrain fullscreen apps to the old dock width.
    // WindowManagerGlobal clears the View parent as removal starts, so repeated hides are idempotent;
    // OEM show() can safely add the root again when Home becomes foreground.
    function forceHideDockController(controller, label) {
        if (controller === null) return false;
        try {
            var animator = runtimeObject(dockField(controller, "mMoveWindowAnimator"));
            if (animator !== null && animator.isStarted()) {
                animator.removeAllListeners();
                animator.removeAllUpdateListeners();
                animator.cancel();
            }
        } catch (e) { Log.w(TAG, "[dock] cancel dismiss animator " + label + ": " + e); }
        try {
            var root = runtimeObject(dockField(controller, "mRootView"));
            var windowManager = runtimeObject(dockField(controller, "mWindowManager"));
            var lp = runtimeObject(dockField(controller, "mLp"));
            if (root === null || lp === null) return false;
            lp.x.value = -Math.abs(Number(lp.width.value));
            var attached = root.getParent() !== null;
            if (attached) {
                if (windowManager === null) return false;
                windowManager.updateViewLayout(root, lp);
                windowManager.removeView(root);
            }
            Log.i("voyahdock", "force hidden/detached " + label + " attached=" + attached);
            return true;
        } catch (e) {
            Log.e(TAG, "[dock] force hide " + label + " failed: " + e);
            return false;
        }
    }

    function applyScreenLiftDock(instance, type, fallbackScreen) {
        var sid = managedScreenId(instance, fallbackScreen);
        if (sid !== 0) return; // passenger compact remains completely OEM-controlled (Home only)
        if (isUserFullscreen(topActivityForScreen(0, null).pkg)) {
            // Bounded boot/reload icon passes must never expose children of the detached fullscreen dock.
            var hiddenViews = dockViews(instance);
            setDockViewVisibility(hiddenViews.up, 8, "fullscreen screenUp");
            setDockViewVisibility(hiddenViews.down, 8, "fullscreen screenDown");
            return;
        }
        var compact = type === 1;
        var views = dockViews(instance);
        // На водительском OD temperature-content лежит поверх штатного slot3 (Air). В compact
        // скрываем оба слоя вместе со всеми штатными кнопками, оставляя Home и пользовательские 1/2.
        var driverTemperature = dockField(instance, "mScreenUpTemperatureContentView");
        // Visibility follows the persisted assignment, not early PackageManager readiness. During
        // cold boot getLaunchIntentForPackage() may still be null even though the app is installed;
        // hiding the slot on that transient answer made compact startup look as if the hook was absent.
        var compactSlot1 = dockPackage(0, 1, false) !== "none";
        var compactSlot2 = dockPackage(0, 2, false) !== "none";

        // OEM controller doScreenLift(1) перед нашим post-hook показывает отдельный one-button screenDown.
        // Возвращаем driver screenUp. WRAP_CONTENT + штатный layout_gravity=center центрирует по высоте
        // Home и только реально назначенные/установленные пользовательские app-слоты.
        setDockViewVisibility(views.up, 0, "screenUp");
        setDockViewVisibility(views.down, 8, "screenDown");
        setDockViewHeight(views.up, compact ? 560 : 720, "screenUp");
        setDockViewHeight(views.group, compact ? -2 : -1, "radioGroup");
        setDockViewVisibility(views.home, 0, "home");
        setDockViewVisibility(views.slot1, compact && !compactSlot1 ? 8 : 0, "slot1");
        setDockViewVisibility(views.slot2, compact && !compactSlot2 ? 8 : 0, "slot2");
        setDockViewVisibility(views.allApps, compact ? 8 : 0, "allApps");
        setDockViewVisibility(views.extra1, compact ? 8 : 0, "slot3");
        setDockViewVisibility(views.extra2, compact ? 8 : 0, "slot4");
        setDockViewVisibility(driverTemperature, compact ? 8 : 0, "driverTemperature");
        Log.i(TAG, "[dock] driver lift=" + type
                + " mode=" + (compact ? "compact(home"
                    + (compactSlot1 ? "+1" : "") + (compactSlot2 ? "+2" : "") + ")" : "normal"));
    }

    function currentScreenLiftType() {
        try {
            var SP = Java.use("android.os.SystemProperties");
            var type = SP.getInt("persist.qg.canbus.bcm_screenAutoLiftFdb", 2);
            if (type === 1 || type === 2) return type;
        } catch (e) {}
        var saved = parseInt(cfg("screen_lift_type"), 10);
        return saved === 1 ? 1 : 2;
    }

    // Перерисовка иконок слотов на инстансе навбара. Только слоты 1 и 2. Строго на main-треде.
    function updateIcons(instance, fallbackScreen, skipLayout) {
        try {
            var sid = managedScreenId(instance, fallbackScreen);
            if (sid !== 0) return; // no icon/listener/layout writes to the passenger OEM bar
            var views = dockViews(instance);
            var slots = [
                { name: views.slot1Name, view: views.slot1, pkg: dockPackage(0, 1, false) },
                { name: views.slot2Name, view: views.slot2, pkg: dockPackage(0, 2, false) }
            ];
            for (var slotIndex = 0; slotIndex < slots.length; slotIndex++) {
                var name = slots[slotIndex].name;
                var view = slots[slotIndex].view;
                if (!view) continue;
                var bgKey = "0:" + name;
                if (!originalBg[bgKey]) originalBg[bgKey] = view.getBackground(); // backup once
                var pkg = slots[slotIndex].pkg;
                if (pkg === "none") {
                    view.setBackground(originalBg[bgKey]);                       // restore OEM icon
                } else {
                    // getAppDrawable uses getApplicationInfo directly. It commonly becomes available
                    // earlier during cold boot than getLaunchIntentForPackage used by the click gate.
                    var d = getAppDrawable(pkg);
                    if (d) view.setBackground(d);
                }
                view.invalidate();
            }
            // Долгий тап по «меню» (все приложения, mScreenUpAllAppView) → VoyahTune. Короткий тап НЕ
            // трогаем — идёт штатно (открытие списка приложений). setOnLongClickListener идемпотентен,
            // навешиваем на каждом проходе updateIcons (init/theme/reload) — переживает перекраску темы.
            try {
                var av = dockField(instance, "mScreenUpAllAppView");
                if (av) {
                    var lc = getMenuLongClick();
                    if (lc) { av.setLongClickable(true); av.setOnLongClickListener(lc); }
                }
            } catch (e) { Log.e(TAG, "[dock] menu long-press attach err: " + e); }
            // Долгий тап по кнопкам «Звонок»/«Радио» → выполнить назначенное действие. Регистрируем viewId→slot и вешаем
            // слушатель (идемпотентно, переживает перекраску темы, как и меню-лонгтап выше).
            try {
                var sv1 = views.slot1;
                var sv2 = views.slot2;
                var slc = getSlotLongClick();
                if (slc && sv1) { slotByViewId["" + sv1.getId()] = 1; sv1.setLongClickable(true); sv1.setOnLongClickListener(slc); }
                if (slc && sv2) { slotByViewId["" + sv2.getId()] = 2; sv2.setLongClickable(true); sv2.setOnLongClickListener(slc); }
            } catch (e) { Log.e(TAG, "[dock] slot long-press attach err: " + e); }
            if (!skipLayout) applyScreenLiftDock(instance, currentScreenLiftType(), fallbackScreen);
        } catch (e) { Log.e(TAG, "[dock] updateIcons err: " + e); }
    }

    // Первичный проход + reload: перерисовать водительский dock (passenger остаётся OEM-controlled).
    function updateAllNavbars() {
        NAV_CLASSES.forEach(function (entry) {
            try {
                Java.choose(entry.name, {
                    onMatch: function (inst) {
                        // Java.choose wrappers are only guaranteed for the callback lifetime.
                        // The actual view mutation is posted to the launcher looper, so retain the
                        // controller until that runnable finishes instead of occasionally using a
                        // stale Frida handle during the bounded cold-boot passes.
                        var retainedNavbar = Java.retain(inst);
                        Java.scheduleOnMainThread(function () {
                            try { updateIcons(retainedNavbar, entry.screen); }
                            catch (e) { Log.e(TAG, "[dock] updateAll err: " + e); }
                            finally {
                                try { retainedNavbar.$dispose(); }
                                catch (ignored) {}
                            }
                        });
                    },
                    onComplete: function () {}
                });
            } catch (e) { Log.e(TAG, "[dock] choose " + entry.name + " err: " + e); }
        });
        // If Launcher itself restarted while a third-party task remained top, OEM firstShow() can
        // call INavigationBarController.show() without a new TOP_ACTIVITY_CHANGED broadcast. The
        // concrete controller hook is not reliable for that invoke-interface path, so every bounded
        // startup/lift/reload pass also reconciles the live LauncherModel after its UI queue settles.
        if (schedulePhysicalDockRecovery !== null) {
            try {
                Java.choose("com.qinggan.app.launcher.LauncherModel", {
                    onMatch: function (inst) {
                        schedulePhysicalDockRecovery(inst, "navbar pass");
                    },
                    onComplete: function () {}
                });
            } catch (e) { Log.e(TAG, "[dock] bounded model recovery err: " + e); }
        }
    }

    // Freeform-запуск приложения из слота дока делегируем Native: Native закроет активный VD-сплит и
    // запустит обычную задачу целевого пакета на выбранном физическом display.
    function launchFreeform(pkg, displayId) {
        try {
            var i = Intent.$new("ru.big.town.anative.OPEN_FREEFORM");
            i.setClassName(OUR_PKG, "ru.big.town.anative.SetModesReceiverDynamic");
            i.putExtra.overload('java.lang.String', 'java.lang.String').call(i, "pkg", "" + pkg);
            i.putExtra.overload('java.lang.String', 'int').call(i, "display", displayId);
            i.addFlags(0x00000020);   // FLAG_INCLUDE_STOPPED_PACKAGES — добудиться, даже если Native стоплен
            ctx().sendBroadcast(i);
            Log.i(TAG, "[dock] OPEN_FREEFORM -> " + pkg + " display=" + displayId);
        } catch (e) { Log.e(TAG, "[dock] launchFreeform err: " + e); }
    }

    // Fullscreen launch must normalize an already existing mode-5 task before resume. Native applies
    // Android 11 ActivityOptions windowingMode=FULLSCREEN and independently validates the persisted
    // allowlist, so this exported launcher bridge cannot start an arbitrary package.
    function launchFullscreen(pkg, displayId) {
        try {
            if (displayId !== 0 && displayId !== 1) return false;
            var i = Intent.$new("ru.big.town.anative.OPEN_FULLSCREEN");
            i.setClassName(OUR_PKG, "ru.big.town.anative.SetModesReceiverDynamic");
            i.putExtra.overload('java.lang.String', 'java.lang.String').call(i, "pkg", "" + pkg);
            i.putExtra.overload('java.lang.String', 'int').call(i, "display", displayId);
            i.addFlags(0x00000020);
            ctx().sendBroadcast(i);
            Log.i(TAG, "[dock] OPEN_FULLSCREEN -> " + pkg + " display=" + displayId);
            return true;
        } catch (e) {
            Log.e(TAG, "[dock] launchFullscreen err: " + e);
            return false;
        }
    }

    // Ordinary All Apps launches use the OEM AppLauncher directly, so Native does not see the
    // openFreeform bridge that records the last session. Keep the record in the same protected
    // receiver used by the other launcher paths.
    function rememberLastSession(pkg, displayId) {
        try {
            if (displayId !== 0 && displayId !== 1) return;
            var i = Intent.$new("ru.big.town.anative.RECORD_LAST_SESSION");
            i.setClassName(OUR_PKG, "ru.big.town.anative.SetModesReceiverDynamic");
            i.putExtra.overload('java.lang.String', 'java.lang.String').call(i, "pkg", "" + pkg);
            i.putExtra.overload('java.lang.String', 'int').call(i, "display", displayId);
            i.addFlags(0x00000020);
            ctx().sendBroadcast(i);
        } catch (e) { Log.w(TAG, "[allapps] remember last session failed: " + e); }
    }

    // Штатный All Apps фильтрует почти все сторонние APK. Вариант voboost решает это хуком
    // AllAppDataManager + AllAppAdapter. Здесь тот же контракт для обоих физических экранов;
    // запуск делегируется OEM AppLauncher с mScreenId владельца All Apps. У AllAppAdapter нет mScreenId,
    // поэтому вычислять экран при bind нельзя: null в JavaScript превращается в 0 и тап пассажира
    // ошибочно уходит водителю.
    // Никакого периодического PackageManager polling: снимок живёт до ближайшего package-broadcast.
    function installAllAppsHooks() {
        try {
            // H97C OD keeps the whole model/adapter family in com.qinggan.launcher.allapp. Other
            // launcher builds use the older launcher.base split. Resolve one complete family so
            // overload signatures never mix classes from different ABIs.
            var allAppsFamilies = [
                {
                    bean: "com.qinggan.launcher.allapp.AppBean",
                    data: "com.qinggan.launcher.allapp.AllAppDataManager",
                    adapter: "com.qinggan.launcher.allapp.AllAppAdapter",
                    bar: "com.qinggan.launcher.allapp.AllAppBarView"
                },
                {
                    bean: "com.qinggan.launcher.base.bean.AppBean",
                    data: "com.qinggan.launcher.base.allapp.AllAppDataManager",
                    adapter: "com.qinggan.launcher.base.adapter.AllAppAdapter",
                    bar: "com.qinggan.launcher.base.allapp.AllAppBarView"
                }
            ];
            var allAppsAbi = null;
            var AppBean = null;
            var Data = null;
            var Adapter = null;
            var AllAppBarView = null;
            for (var familyIndex = 0; familyIndex < allAppsFamilies.length; familyIndex++) {
                try {
                    var family = allAppsFamilies[familyIndex];
                    var familyBean = Java.use(family.bean);
                    var familyData = Java.use(family.data);
                    var familyAdapter = Java.use(family.adapter);
                    var familyBar = Java.use(family.bar);
                    allAppsAbi = family;
                    AppBean = familyBean;
                    Data = familyData;
                    Adapter = familyAdapter;
                    AllAppBarView = familyBar;
                    break;
                } catch (familyMissing) {}
            }
            if (allAppsAbi === null) throw new Error("no compatible All Apps class family");
            Log.i(TAG, "[allapps] ABI=" + allAppsAbi.bean);
            var AppLauncher = Java.use("com.qinggan.launcher.base.utils.AppLauncher");
            var JavaString = Java.use("java.lang.String");
            var JavaSystem = Java.use("java.lang.System");
            var pm = ctx().getPackageManager();
            var installedSnapshot = null;
            var iconCache = {};
            var labelCache = {};
            var packageRefreshTimer = null;
            var preparedAllAppsLists = {};
            // The map is only needed by the optional passenger home rail. The full-screen launcher
            // uses the native dynamic AppBean renderer and therefore has no scroll-time JS hook.
            var syntheticStartByList = {};
            var FLAG_SYSTEM = 0x00000001;
            var SYNTHETIC_PREFIX = "__voyahtune_allapps__:";
            var resourceTemplate = null;
            // H97C AppBean natively supports a dynamic label and Drawable. Populate those fields
            // once when the OEM list is made, instead of intercepting every RecyclerView bind.
            // The legacy family retains the old renderer as a compatibility fallback.
            var nativeDynamicApps = false;
            try {
                AppBean.$init.overload('int', 'java.lang.String', 'java.lang.String');
                AppBean.setDynamicDrawable.overload('android.graphics.drawable.Drawable');
                nativeDynamicApps = true;
            } catch (dynamicAppUnsupported) {}
            Log.i(TAG, "[allapps] renderer="
                    + (nativeDynamicApps ? "native-dynamic" : "legacy-post-bind"));

            function packageFromIntent(intent) {
                if (intent === null) return "";
                try {
                    var component = intent.getComponent();
                    if (component !== null) return cleanJavaString(component.getPackageName());
                } catch (ignored) {}
                try {
                    var explicitPackage = cleanJavaString(intent.getPackage());
                    if (explicitPackage) return explicitPackage;
                } catch (ignored) {}
                try {
                    var resolved = pm.resolveActivity(intent, 0);
                    if (resolved !== null && resolved.activityInfo.value !== null) {
                        return cleanJavaString(resolved.activityInfo.value.packageName.value);
                    }
                } catch (ignored) {}
                return "";
            }

            // Covers both synthetic third-party entries and stock OEM entries that happen to expose an
            // allowlisted package. Without this gate, All Apps bypasses Native ActivityOptions and can
            // simply raise a reused dock-width freeform task.
            try {
                var startAppIntent = AppLauncher.startApp.overload(
                        'android.content.Context', 'android.content.Intent', 'int');
                startAppIntent.implementation = function (context, intent, screenIdArg) {
                    var screenId = Number(screenIdArg);
                    var pkg = packageFromIntent(intent);
                    if (isUserFullscreen(pkg) && launchFullscreen(pkg, screenId)) return;
                    rememberLastSession(pkg, screenId);
                    return startAppIntent.call(this, context, intent, screenIdArg);
                };
                var startAppComponent = AppLauncher.startApp.overload(
                        'android.content.Context', 'java.lang.String', 'java.lang.String', 'int');
                startAppComponent.implementation = function (context, pkgArg, classArg, screenIdArg) {
                    var pkg = cleanJavaString(pkgArg);
                    var screenId = Number(screenIdArg);
                    if (isUserFullscreen(pkg) && launchFullscreen(pkg, screenId)) return;
                    rememberLastSession(pkg, screenId);
                    return startAppComponent.call(this,
                            context, pkgArg, classArg, screenIdArg);
                };
                Log.i(TAG, "[allapps] fullscreen ActivityOptions routing installed");
            } catch (e) { Log.e(TAG, "[allapps] fullscreen launch routing unavailable: " + e); }

            function launchAllApp(pkg, screenId) {
                try {
                    if (screenId !== 0 && screenId !== 1) {
                        Log.e(TAG, "[allapps] reject non-physical display=" + screenId + " for " + pkg);
                        return false;
                    }
                    if (isUserFullscreen(pkg)) return launchFullscreen(pkg, screenId);
                    var intent = pm.getLaunchIntentForPackage(pkg);
                    if (intent === null) return false;
                    intent.addFlags(0x10000000); // FLAG_ACTIVITY_NEW_TASK
                    AppLauncher.startApp(ctx(), intent, screenId);
                    Log.i(TAG, "[allapps] launch " + pkg + " display=" + screenId);
                    return true;
                } catch (e) {
                    Log.e(TAG, "[allapps] launch " + pkg + ": " + e);
                    return false;
                }
            }

            function fieldValue(obj, name) {
                try { return obj[name].value; } catch (direct) {}
                var c = obj.getClass();
                while (c !== null) {
                    try {
                        var f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        return f.get(obj);
                    } catch (ignored) {
                        try { c = c.getSuperclass(); } catch (end) { c = null; }
                    }
                }
                return null;
            }

            function snapshotInstalled() {
                if (installedSnapshot !== null) return installedSnapshot;
                var result = [];
                var installed = pm.getInstalledApplications(0);
                for (var i = 0; i < installed.size(); i++) {
                    try {
                        var ai = installed.get(i);
                        var pkg = "" + ai.packageName.value;
                        var flags = Number(ai.flags.value);
                        if ((flags & FLAG_SYSTEM) !== 0 || pkg === "com.qinggan.app.launcher") continue;
                        if (isFrozenLauncherPackage(pkg)) continue;
                        if (pm.getLaunchIntentForPackage(pkg) === null) continue;
                        result.push(pkg);
                    } catch (ignored) {}
                }
                installedSnapshot = result;
                Log.i(TAG, "[allapps] cached launchable user apps=" + result.length);
                return installedSnapshot;
            }

            // Only the legacy renderer needs OEM resource IDs before its post-bind replacement.
            function findAppTemplate(list) {
                if (list === null || list === undefined) return resourceTemplate;
                for (var i = 0; i < list.size(); i++) {
                    try {
                        var bean = Java.cast(list.get(i), AppBean);
                        if (Number(bean.getType()) === 1 && Number(bean.getIcon()) > 0
                                && Number(bean.getNameRes()) > 0) {
                            resourceTemplate = {
                                icon: Number(bean.getIcon()),
                                name: Number(bean.getNameRes())
                            };
                            return resourceTemplate;
                        }
                    } catch (ignored) {}
                }
                return resourceTemplate;
            }

            function addMissingApps(list) {
                var existing = {};
                for (var i = 0; i < list.size(); i++) {
                    try {
                        var current = Java.cast(list.get(i), AppBean);
                        existing["pkg:" + current.getPackageName()] = true;
                    } catch (ignored) {}
                }
                var apps = snapshotInstalled();
                var template = null;
                for (var j = 0; j < apps.length; j++) {
                    var pkg = apps[j];
                    if (existing["pkg:" + pkg]) continue;
                    try {
                        var bean;
                        if (nativeDynamicApps) {
                            // AppBean's dynamic constructor makes the OEM adapter read appName and
                            // dynamicDrawable itself. This work happens during list construction,
                            // never during a RecyclerView frame.
                            var label = "" + pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                            var icon = pm.getApplicationIcon(pkg);
                            bean = AppBean.$new(0, JavaString.$new(label), pkg);
                            bean.setDynamicDrawable(icon);
                        } else {
                            // The older launcher does not expose the dynamic AppBean contract; its
                            // post-bind fallback below needs valid OEM resource IDs to remain safe.
                            if (template === null) template = findAppTemplate(list);
                            if (template === null && getAll !== null && getAll !== undefined) {
                                template = findAppTemplate(getAll.call(Data, 0));
                            }
                            if (template === null) {
                                Log.e(TAG, "[allapps] no valid OEM app template; cannot safely add " + pkg);
                                return;
                            }
                            bean = AppBean.$new(template.icon, template.name, pkg);
                        }
                        bean.setSubType(SYNTHETIC_PREFIX + pkg);
                        list.add(bean);
                        existing["pkg:" + pkg] = true;
                    } catch (e) { Log.e(TAG, "[allapps] add " + pkg + ": " + e); }
                }
            }

            // Blacklist работает и для штатных OEM-записей, а не только для synthetic user-apps.
            // Идём с конца, чтобы удаление не сдвигало ещё не обработанные элементы.
            function filterFrozenApps(list) {
                var removed = 0;
                for (var i = list.size() - 1; i >= 0; i--) {
                    try {
                        var bean = Java.cast(list.get(i), AppBean);
                        if (isFrozenLauncherPackage(bean.getPackageName())) {
                            list.remove(i);
                            removed++;
                        }
                    } catch (ignored) {}
                }
                if (removed > 0) Log.i(TAG, "[allapps] frozen OEM/user entries=" + removed);
            }

            function adapterBeans(adapter) {
                try { return adapter.mAppBeans.value; } catch (direct) {}
                return fieldValue(adapter, "mAppBeans");
            }

            function listKey(list) {
                try { return "" + JavaSystem.identityHashCode(list); }
                catch (ignored) {
                    try { return "hash:" + list.hashCode(); } catch (ignoredAgain) { return "" + list; }
                }
            }

            invalidateAllAppsCaches = function () {
                installedSnapshot = null;
                syntheticStartByList = {};
                preparedAllAppsLists = {};
            };

            function beanAt(adapter, position) {
                var beans = adapterBeans(adapter);
                if (beans === null || position < 0 || position >= beans.size()) return null;
                return Java.cast(beans.get(position), AppBean);
            }

            function syntheticStartInList(list) {
                if (list === null || list === undefined) return -1;
                for (var i = 0; i < list.size(); i++) {
                    try {
                        if (syntheticPackage(Java.cast(list.get(i), AppBean)) !== null) return i;
                    } catch (ignored) {}
                }
                return list.size();
            }

            function syntheticPackage(bean) {
                if (bean === null) return null;
                try {
                    var pkg = "" + bean.getPackageName();
                    var subType = "" + bean.getSubType();
                    if (!pkg || subType !== SYNTHETIC_PREFIX + pkg) return null;
                    return pkg;
                } catch (ignored) {
                    return null;
                }
            }

            function loadIcon(pkg) {
                var icon = iconCache[pkg];
                if (!icon) {
                    icon = pm.getApplicationIcon(pkg);
                    try { icon = Java.retain(icon); } catch (ignored) {}
                    iconCache[pkg] = icon;
                }
                return icon;
            }

            function invalidateIconCache(packageName) {
                var keys = packageName ? [packageName] : Object.keys(iconCache);
                for (var i = 0; i < keys.length; i++) {
                    var cached = iconCache[keys[i]];
                    if (cached) {
                        try { cached.$dispose(); } catch (ignored) {}
                    }
                    delete iconCache[keys[i]];
                    delete labelCache[keys[i]];
                }
            }

            function loadLabel(pkg) {
                if (labelCache[pkg]) return labelCache[pkg];
                // PackageManager label зависит от locale. Инвалидируем его при PACKAGE_CHANGED,
                // поэтому между прокрутками не повторяем Binder-вызов на каждый bind.
                var label = "" + pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                labelCache[pkg] = label;
                return label;
            }

            function physicalScreenId(owner, view) {
                var rawScreenId = fieldValue(owner, "mScreenId");
                var screenId = (rawScreenId === null || rawScreenId === undefined)
                        ? -1 : Number(rawScreenId);
                if (screenId !== 0 && screenId !== 1 && view !== null) {
                    try {
                        var display = view.getDisplay();
                        screenId = display !== null ? Number(display.getDisplayId()) : -1;
                    } catch (ignored) {}
                }
                return (screenId === 0 || screenId === 1) ? screenId : -1;
            }

            function finishBoundItem(adapter, holder, position) {
                var bean = beanAt(adapter, position);
                var pkg = syntheticPackage(bean);
                if (pkg === null) return;
                var icon = loadIcon(pkg);
                var label = loadLabel(pkg);
                var iconView = fieldValue(holder, "iconView");
                var nameView = fieldValue(holder, "nameView");
                if (iconView !== null && icon) {
                    var concreteIconView = runtimeObject(iconView) || iconView;
                    try { concreteIconView.setImageDrawable(icon); }
                    catch (notImageView) { concreteIconView.setBackground(icon); }
                }
                if (nameView !== null) nameView.setText(JavaString.$new(label));
            }

            // Владельцем штатного listener является AllAppBarView, и именно у него хранится точный
            // mScreenId. Перехватываем только наши записи по tag, не меняя listener RecyclerView-holder:
            // так recycling обычных OEM-плиток не может унаследовать чужой package.
            var allAppClick = AllAppBarView.onClick.overload('android.view.View');
            allAppClick.implementation = function (view) {
                try {
                    var tagged = view !== null ? view.getTag() : null;
                    var bean = tagged !== null ? Java.cast(tagged, AppBean) : null;
                    var pkg = syntheticPackage(bean);
                    if (pkg !== null) {
                        var screenId = physicalScreenId(this, view);
                        if (screenId < 0) {
                            Log.e(TAG, "[allapps] owner has no physical screen for " + pkg);
                            return;
                        }
                        if (launchAllApp(pkg, screenId)) {
                            try { this.dismiss(); } catch (ignored) {}
                        }
                        return;
                    }
                } catch (e) { Log.e(TAG, "[allapps] owner click: " + e); }
                return allAppClick.call(this, view);
            };

            if (!nativeDynamicApps) {
                // Compatibility only: the old ABI has no native dynamic AppBean. H97C never
                // enters this block, so stock tiles do not cross Frida while the list is scrolled.
                var bind = Adapter.onBindViewHolder.overload(
                        allAppsAbi.adapter + '$AppViewHolder', 'int');
                bind.implementation = function (holder, position) {
                    bind.call(this, holder, position);
                    try {
                        finishBoundItem(this, holder, position);
                    } catch (e) { Log.e(TAG, "[allapps] bind: " + e); }
                };

                try {
                    var bindPayload = Adapter.onBindViewHolder.overload(
                            allAppsAbi.adapter + '$AppViewHolder',
                            'int', 'java.util.List');
                    bindPayload.implementation = function (holder, position, payloads) {
                        bindPayload.call(this, holder, position, payloads);
                        try {
                            finishBoundItem(this, holder, position);
                        } catch (e) { Log.e(TAG, "[allapps] payload bind: " + e); }
                    };
                } catch (e) { Log.e(TAG, "[allapps] payload bind hook unavailable: " + e); }
            }

            // На части OD launcher пассажирская home-лента читает тот же mSecondAllApps через отдельный
            // SecondAllAppAdapter. Если класс присутствует, его тоже надо декорировать и перехватить
            // owner-click; иначе глобально добавленные записи были бы placeholder-плитками без запуска.
            var SecondAdapter = null;
            try {
                    SecondAdapter = Java.use("com.qinggan.secondlauncher.adapter.SecondAllAppAdapter");
            } catch (absent) {
                Log.i(TAG, "[allapps] optional SecondAllAppAdapter is absent");
            }
            if (SecondAdapter !== null) {
                try {
                    var SecondFragment = Java.use("com.qinggan.secondlauncher.fragment.SecondMainFragment");
                    var secondBind = SecondAdapter.onBindViewHolder.overload(
                            'com.qinggan.secondlauncher.adapter.SecondAllAppAdapter$ViewHolder', 'int');
                    secondBind.implementation = function (holder, position) {
                        secondBind.call(this, holder, position);
                        try {
                            var list = fieldValue(this, "allAppList");
                            if (list === null || position < 0 || position >= list.size()) return;
                            var secondStart = syntheticStartByList[listKey(list)];
                            if (secondStart !== undefined && position < secondStart) return;
                            var bean = Java.cast(list.get(position), AppBean);
                            var pkg = syntheticPackage(bean);
                            if (pkg === null) return;
                            var iconView = fieldValue(holder, "iconView");
                            var nameView = fieldValue(holder, "nameView");
                            var icon = loadIcon(pkg);
                            if (iconView !== null && icon) iconView.setImageDrawable(icon);
                            if (nameView !== null) nameView.setText(JavaString.$new(loadLabel(pkg)));
                        } catch (e) { Log.e(TAG, "[allapps] passenger rail bind: " + e); }
                    };

                    var secondClick = SecondFragment.onItemClick.overload(
                            allAppsAbi.bean);
                    secondClick.implementation = function (bean) {
                        try {
                            var pkg = syntheticPackage(bean);
                            if (pkg !== null) {
                                launchAllApp(pkg, 1);
                                return;
                            }
                        } catch (e) { Log.e(TAG, "[allapps] passenger rail click: " + e); }
                        return secondClick.call(this, bean);
                    };
                    Log.i(TAG, "[allapps] passenger home rail hooks installed");
                } catch (e) {
                    // Passenger rail is optional. ABI drift here must not prevent the full-screen
                    // driver/passenger lists from receiving their getAllApps hook below.
                    Log.e(TAG, "[allapps] optional passenger rail hooks unavailable: " + e);
                }
            }

            // Ставим data hook последним: если обязательный renderer/click ABI выше разошёлся с
            // прошивкой, synthetic entries не успеют попасть в разделяемый OEM list.
            var getAll = Data.getAllApps.overload('int');
            getAll.implementation = function (screenId) {
                var list = getAll.call(Data, screenId);
                if ((screenId === 0 || screenId === 1) && list !== null) {
                    var preparedKey = screenId + ":" + listKey(list);
                    if (!preparedAllAppsLists[preparedKey]) {
                        filterFrozenApps(list);
                        addMissingApps(list);
                        preparedAllAppsLists[preparedKey] = true;
                    }
                    syntheticStartByList[listKey(list)] = syntheticStartInList(list);
                }
                return list;
            };

            try {
                // AllAppDataManager.reload() сам очищает/пересобирает mMainAllApps и mSecondAllApps,
                // затем зовёт onAppReload() у AllAppBarView и SecondMainFragment. Их повторные
                // getAllApps(0/1) проходят через хук выше, поэтому synthetic entries возвращаются до
                // notify/setAllAppList открытых адаптеров. Не мутируем OEM-списки параллельно с reload.
                var reloadData = Data.reload.overload();
                reloadAllApps = function () {
                    if (reloadData !== null && reloadData !== undefined) reloadData.call(Data);
                };

                function schedulePackageRefresh(action, packageName) {
                    // Инвалидация сразу: если UI запросит список до debounce, он уже получит
                    // свежий PackageManager snapshot. Штатный reload через 300 ms доведёт списки/UI до
                    // консистентного состояния. REMOVE+ADD при APK update схлопываются в один reload.
                    invalidateAllAppsCaches();
                    invalidateIconCache(packageName);
                    if (packageRefreshTimer !== null) clearTimeout(packageRefreshTimer);
                    packageRefreshTimer = setTimeout(function () {
                        packageRefreshTimer = null;
                        Java.scheduleOnMainThread(function () {
                            try {
                                reloadData.call(Data);
                                Log.i(TAG, "[allapps] package refresh action=" + action
                                        + " package=" + packageName);
                            } catch (e) { Log.e(TAG, "[allapps] package refresh failed: " + e); }
                        });
                    }, 300);
                }

                // Dynamic receiver нужен именно в процессе OEM launcher: manifest VoyahTune не может
                // обновить его in-memory RecyclerView. data-scheme "package" обязателен для package actions.
                var PackageReceiver = Java.registerClass({
                    name: "ru.big.town.dock.AllAppsPackageReceiver",
                    superClass: Java.use("android.content.BroadcastReceiver"),
                    methods: {
                        onReceive: {
                            returnType: "void",
                            argumentTypes: ["android.content.Context", "android.content.Intent"],
                            implementation: function (context, intent) {
                                try {
                                    var action = intent !== null ? "" + intent.getAction() : "";
                                    if (action !== "android.intent.action.PACKAGE_ADDED"
                                            && action !== "android.intent.action.PACKAGE_REMOVED"
                                            && action !== "android.intent.action.PACKAGE_CHANGED") return;
                                    var data = intent.getData();
                                    var packageName = data !== null ? "" + data.getSchemeSpecificPart() : "";
                                    schedulePackageRefresh(action, packageName);
                                } catch (e) { Log.e(TAG, "[allapps] package receiver: " + e); }
                            }
                        }
                    }
                });
                var packageFilter = Java.use("android.content.IntentFilter").$new();
                packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
                packageFilter.addAction("android.intent.action.PACKAGE_REMOVED");
                packageFilter.addAction("android.intent.action.PACKAGE_CHANGED");
                packageFilter.addDataScheme("package");
                var packageReceiver = PackageReceiver.$new();
                var packageSdk = Java.use("android.os.Build$VERSION").SDK_INT.value;
                if (packageSdk >= 33) {
                    ctx().registerReceiver.overload('android.content.BroadcastReceiver',
                        'android.content.IntentFilter', 'int').call(ctx(), packageReceiver, packageFilter, 0x2);
                } else {
                    ctx().registerReceiver.overload('android.content.BroadcastReceiver',
                        'android.content.IntentFilter').call(ctx(), packageReceiver, packageFilter);
                }
                Log.i(TAG, "[allapps] package receiver registered (sdk=" + packageSdk + ")");
            } catch (e) {
                // Старая/другая прошивка без reload не должна отключать базовое добавление
                // synthetic apps: getAllApps/bind/click хуки уже установлены и остаются рабочими.
                Log.e(TAG, "[allapps] event refresh unavailable: " + e);
            }
            Log.i(TAG, "[allapps] both physical display list hooks installed");
        } catch (e) {
            // Firmware variant without these launcher-base classes: dock remains fully functional.
            Log.e(TAG, "[allapps] hooks unavailable: " + e);
        }
    }

    try {
        var NavigationBarMain = Java.use(NAV_MAIN);
        var mainFallbackScreen = SHARED_NAV ? -1 : 0;

        // 1) ИКОНКА: переустановка после каждой перекраски темы (иначе штатная тема затрёт наш фон).
        var origUpdateTheme = NavigationBarMain.updateTheme;
        NavigationBarMain.updateTheme.implementation = function () {
            origUpdateTheme.call(this);
            try { updateIcons(this, mainFallbackScreen); } catch (e) {}
        };

        // 1b) ИНИЦИАЛИЗАЦИЯ СЛОТОВ: навбар строит up-view'ы в initScreenUpViews — сразу после него
        //     слоты существуют, применяем иконки. Страховка от гонки: если инъекция прошла ДО создания
        //     навбара (первичный Java.choose ничего не нашёл), иконка всё равно встанет здесь.
        try {
            var origInitUp = NavigationBarMain.initScreenUpViews;
            NavigationBarMain.initScreenUpViews.implementation = function () {
                origInitUp.call(this);
                try { updateIcons(this, mainFallbackScreen); } catch (e) {}
            };
        } catch (e) { Log.e(TAG, "[dock] initScreenUpViews hook skip: " + e); }

        // 2) ПОДСВЕТКА (косметика): reverse-mapping нашего pkg слота → штатный pkg, чтобы родной код чекнул
        //    правильную кнопку. Для нашего VD-хоста (SplitHostActivity) чекаем слот 2 напрямую.
        var origUpdateSelectedApp = NavigationBarMain.updateSelectedApp;
        NavigationBarMain.updateSelectedApp.implementation = function (packageName, activityName) {
            // Запоминаем приложение переднего плана ДЛЯ СВОЕГО ЭКРАНА (см. dockKept/dismiss).
            try {
                var sid = managedScreenId(this, mainFallbackScreen);
                if (sid === 0 || sid === 1) {
                    fgByScreen[sid].pkg = cleanJavaString(packageName);
                    fgByScreen[sid].act = cleanJavaString(activityName);
                }
            } catch (e) {}
            var sid = managedScreenId(this, mainFallbackScreen);
            if (sid !== 0) return origUpdateSelectedApp.call(this, packageName, activityName);
            try {
                // Наш VD-хост запущен по клику слота → чекнуть именно тот слот, что нажали (lastSlot).
                if (packageName === OUR_PKG && ("" + activityName).indexOf("SplitHostActivity") >= 0) {
                    var selectedViews = dockViews(this, sid);
                    var v = (lastSlot[sid] === 1) ? selectedViews.slot1
                          : (lastSlot[sid] === 2) ? selectedViews.slot2 : null;
                    if (v) { v.setChecked(true); return; }
                }
                // Реверс-маппинг: наш pkg слота → штатный pkg, чтобы родной код подсветил правильную кнопку.
                if (dockPackage(sid, 1, false) !== "none" && packageName === dockPackage(sid, 1, false)) packageName = STOCK_SLOT_PKG[1];
                else if (dockPackage(sid, 2, false) !== "none" && packageName === dockPackage(sid, 2, false)) packageName = STOCK_SLOT_PKG[2];
            } catch (e) {}
            return origUpdateSelectedApp.call(this, packageName, activityName);
        };

        // 3) КЛИК: слот определяем сравнением view.getId() с getId() закэшированных полей (НЕ по индексу).
        //    Совпал + pkg установлен → обычная задача на display этого дока; иначе штатный onClick.
        var mainOnClick = NavigationBarMain.onClick.overload('android.view.View');
        mainOnClick.implementation = function (view) {
            var sid = managedScreenId(this, mainFallbackScreen);
            if (sid !== 0) return mainOnClick.call(this, view);
            try {
                var viewId = view.getId();
                var clickViews = dockViews(this, sid);
                if (clickViews.slot1 !== null && viewId === clickViews.slot1.getId()) {
                    var p1 = dockPackage(sid, 1, true);
                    if (isInstalled(p1)) { lastSlot[sid] = 1; launchFreeform(p1, sid); return; }
                }
                if (clickViews.slot2 !== null && viewId === clickViews.slot2.getId()) {
                    var p2 = dockPackage(sid, 2, true);
                    if (isInstalled(p2)) { lastSlot[sid] = 2; launchFreeform(p2, sid); return; }
                }
            } catch (e) { Log.e(TAG, "[dock] onClick err: " + e); }
            return mainOnClick.call(this, view);
        };

        // На живом OD doScreenLift принадлежит единственному NavigationBarController, а не классам
        // Main/Second. После OEM-переключения меняем layout только у driver controller; passenger
        // остаётся на штатном one-button Home dock.
        try {
            var LiftController = Java.use("com.qinggan.launcher.navigation.NavigationBarController");
            var controllerLift = LiftController.doScreenLift.overload('int');
            controllerLift.implementation = function (type) {
                var result = controllerLift.call(this, type);
                try {
                    var sid = managedScreenId(this, -1);
                    if (sid === 0) {
                        if (isUserFullscreen(topActivityForScreen(0, null).pkg)) {
                            forceHideDockController(this, "screen-lift driver");
                            return result;
                        }
                        var navigationBar = runtimeObject(dockField(this, "mNavigationBar"));
                        if (navigationBar === null) return result;
                        updateIcons(navigationBar, sid, true);
                        applyScreenLiftDock(navigationBar, type, sid);
                    }
                } catch (e) { Log.e(TAG, "[dock] controller screen-lift layout err: " + e); }
                return result;
            };
            Log.i(TAG, "[dock] NavigationBarController doScreenLift hooked");

            // On a cold boot that starts already lowered, doScreenLift(1) may have run before the
            // agent was attached. show() is the next authoritative point at which the root dock is
            // attached/updated, so reconcile the current property there as well.
            var controllerShow = LiftController.show.overload();
            controllerShow.implementation = function () {
                var sid = managedScreenId(this, -1);
                if ((sid === 0 || sid === 1)
                        && isUserFullscreen(topActivityForScreen(sid, null).pkg)) {
                    forceHideDockController(this, "blocked show display=" + sid);
                    return;
                }
                var result = controllerShow.call(this);
                try {
                    if (sid === 0) {
                        var navigationBar = runtimeObject(dockField(this, "mNavigationBar"));
                        if (navigationBar !== null) {
                            updateIcons(navigationBar, sid, true);
                            applyScreenLiftDock(navigationBar, currentScreenLiftType(), sid);
                            Log.i(TAG, "[dock] controller show reconciled driver layout");
                        }
                    }
                } catch (e) { Log.e(TAG, "[dock] controller show reconcile err: " + e); }
                return result;
            };
            Log.i(TAG, "[dock] NavigationBarController show reconciliation hooked");
        } catch (e) { Log.e(TAG, "[dock] controller doScreenLift hook skip: " + e); }

        // 4) ДОК НЕ ДОЛЖЕН САМ УЕЗЖАТЬ ИЗ-ПОД НАШЕГО FREEFORM-ОКНА/VD-СПЛИТА.
        //    При переносе приложения между экранами система вызывает dismiss() у навбара, и док
        //    анимированно скрывается. Для стороннего приложения это тупик: наш оконный режим оставляет
        //    полосу дока свободной, окно её не перекрывает — но самого дока уже нет, и свернуть
        //    приложение или уйти на главный экран нечем.
        //
        //    Гасим dismiss для любого стороннего приложения, потому что глобальный WindowManager hook
        //    оставляет под ним полосу дока независимо от источника запуска. Аварийно отключить pinning:
        //      settings put global voyahtune_dockpin 0
        //
        //    Хукаем navigation class как PI-fallback и реальный общий OD controller.
        function pinDock(clsName, label, fallbackScreen) {
            try {
                var C = Java.use(clsName);
                var origDismiss = C.dismiss;
                C.dismiss.implementation = function () {
                    var sid = 0;
                    try { sid = managedScreenId(this, fallbackScreen); } catch (e) {}
                    if (sid !== 0 && sid !== 1) return origDismiss.call(this);
                    var fg = topActivityForScreen(sid, null);
                    var moving = activeMoveDockGuard();
                    var pending = pendingDockLaunch(sid);
                    // Разведочный лог ДО решения: без него «хук не встал» неотличимо от «условие не
                    // сработало». console.log после -e мёртв, поэтому только android.util.Log.
                    try { Java.use("android.util.Log").i("voyahdock",
                            "dismiss ENTER " + label + " screen=" + sid + " fg=" + fg.pkg + " act=" + fg.act
                            + (moving ? " moving=" + moving.pkg + "/" + Math.ceil(moving.remaining) + "ms" : "")
                            + (pending ? " pending=" + pending.pkg + "/" + Math.ceil(pending.remaining) + "ms" : "")); } catch (ee) {}
                    try {
                        // Fullscreen policy wins over stale transfer/launch guards. Do not enter the
                        // asynchronous OEM dismiss path: hide and detach the attached root now.
                        if (isUserFullscreen(fg.pkg)) {
                            if (forceHideDockController(this, "dismiss fullscreen " + label
                                    + " display=" + sid)) return;
                            return origDismiss.call(this); // firmware fallback without controller fields
                        }
                        if (moving !== null) {
                            try { Java.use("android.util.Log").i("voyahdock", "dismiss BLOCKED " + label
                                    + " active transfer " + moving.pkg); } catch (ee) {}
                            return;
                        }
                        if (pending !== null) {
                            try { Java.use("android.util.Log").i("voyahdock", "dismiss BLOCKED " + label
                                    + " pending launch " + pending.pkg); } catch (ee) {}
                            return;
                        }
                        if (dockKept(fg.pkg, fg.act)) {
                            try { Java.use("android.util.Log").i("voyahdock", "dismiss BLOCKED " + label); } catch (ee) {}
                            return;                      // док остаётся на месте
                        }
                    } catch (e) {}
                    return origDismiss.call(this);
                };
                Log.i(TAG, "[dock] dismiss pinned on " + label);
            } catch (e) { Log.e(TAG, "[dock] dismiss hook skip " + label + ": " + e); }
        }

        try {
            pinDock(NAV_MAIN, "main/shared bar", mainFallbackScreen);
        } catch (e) {
            Log.e(TAG, "pinDock main/shared bar error: " + e);
        }

        try {
            pinDock(NAV_MAIN.replace(/\.[^.]+$/, ".NavigationBarController"), "main/shared controller", mainFallbackScreen);
        } catch (e) {
            Log.e(TAG, "pinDock main/shared controller error: " + e);
        }

        // 4b) LauncherModel уже получает авторитетный TOP_ACTIVITY_CHANGED. Для обычного стороннего
        // viewport повторно показываем dock нужного display, а для пакета из пользовательского fullscreen-
        // списка ЯВНО скрываем его. Одного разрешения пройти в штатный dismiss() недостаточно: на OD при
        // обычном запуске third-party приложения dismiss вообще не вызывается.
        try {
            var TopLM = Java.use("com.qinggan.app.launcher.LauncherModel");
            var retainedLauncherModel = null;

            function modelDockController(model, displayId) {
                var controllerField = displayId === 0
                        ? "mMainScreenNavigationBar" : "mSecondScreenNavigationBar";
                return runtimeObject(dockField(model, controllerField));
            }

            // QGBus navigation visibility requests are queued independently of TOP_ACTIVITY_CHANGED.
            // A late visible=true was the repeat-launch resurrection path, and the live launcher calls
            // the controller through INavigationBarController (where a concrete show() hook alone is
            // not reliable). Normalize every model request while the authoritative top is fullscreen.
            function installFullscreenVisibilityGate(methodName, displayId) {
                var original = TopLM[methodName].overload(
                        'java.lang.String', 'java.lang.String', 'boolean');
                original.implementation = function (pkgArg, actArg, visible) {
                    var requestedPkg = cleanJavaString(pkgArg);
                    var foreground = topActivityForScreen(displayId, null);
                    // With no live helper answer, the request is fresher than updateSelectedApp cache.
                    var decisionPkg = foreground.live
                            ? foreground.pkg : (requestedPkg || foreground.pkg);
                    if (!isUserFullscreen(decisionPkg)) {
                        return original.call(this, pkgArg, actArg, visible);
                    }
                    fgByScreen[displayId].pkg = decisionPkg;
                    if (!foreground.live) fgByScreen[displayId].act = cleanJavaString(actArg);
                    // Calling OEM dismiss() again after x already reached -width makes the live
                    // controller removeView(root), reintroducing an async detach/show race. Keep the
                    // Window detached; use OEM false only as a cross-firmware fallback
                    // when the controller fields are unavailable. A later original(true) always
                    // invokes show() and restores x=0 on the confirmed H97C LauncherModel ABI.
                    var controller = modelDockController(this, displayId);
                    var label = "model gate " + methodName + " display=" + displayId;
                    if (forceHideDockController(controller, label)) {
                        Log.i("voyahdock", "model gate forced hidden display=" + displayId
                                + " pkg=" + decisionPkg + " requestedVisible=" + visible);
                        return;
                    }
                    return original.call(this, pkgArg, actArg, false);
                };
            }

            installFullscreenVisibilityGate("handleUpdateMainNavigationBar", 0);
            installFullscreenVisibilityGate("handleUpdateSecondNavigationBar", 1);
            Log.i(TAG, "[dock] LauncherModel fullscreen visibility gates installed");

            function reconcilePhysicalDock(model, context, displayId, reason) {
                if (displayId !== 0 && displayId !== 1) return;
                if (retainedLauncherModel === null) retainedLauncherModel = Java.retain(model);
                var foreground = topActivityForScreen(displayId, context);
                var pkg = foreground.pkg;
                var act = foreground.act;
                if (isUserFullscreen(pkg)) {
                    if (displayId === 0) model.handleUpdateMainNavigationBar(pkg, act, false);
                    else model.handleUpdateSecondNavigationBar(pkg, act, false);
                    Log.i("voyahdock", reason + " hid display=" + displayId + " dock for " + pkg);
                    return;
                }
                if (!dockKept(pkg, act)) return;
                if (displayId === 0) model.handleUpdateMainNavigationBar(pkg, act, true);
                else model.handleUpdateSecondNavigationBar(pkg, act, true);
                Log.i("voyahdock", reason + " restored display=" + displayId + " dock for " + pkg);
            }

            schedulePhysicalDockRecovery = function (model, reason) {
                try {
                    if (retainedLauncherModel === null) retainedLauncherModel = Java.retain(model);
                    if (physicalDockRecoveryTimer !== null) clearTimeout(physicalDockRecoveryTimer);
                    physicalDockRecoveryTimer = setTimeout(function () {
                        physicalDockRecoveryTimer = null;
                        Java.scheduleOnMainThread(function () {
                            try {
                                reconcilePhysicalDock(retainedLauncherModel, ctx(), 0, reason);
                                reconcilePhysicalDock(retainedLauncherModel, ctx(), 1, reason);
                            } catch (e) { Log.e(TAG, "[dock] delayed transfer recovery: " + e); }
                        });
                    }, 300);
                } catch (e) { Log.e(TAG, "[dock] schedule transfer recovery: " + e); }
            };

            var topReceive = TopLM.onReceive.overload('android.content.Context', 'android.content.Intent');
            topReceive.implementation = function (context, intent) {
                var result = topReceive.call(this, context, intent);
                try {
                    if (intent === null || ("" + intent.getAction()) !==
                            "android.intent.action.TOP_ACTIVITY_CHANGED") return result;
                    var displayId = intent.getIntExtra("displayId", -1);
                    reconcilePhysicalDock(this, context, displayId, "TOP_ACTIVITY_CHANGED");
                } catch (e) { Log.e(TAG, "[dock] TOP_ACTIVITY_CHANGED recovery: " + e); }
                return result;
            };
            Log.i(TAG, "[dock] dual-display TOP_ACTIVITY_CHANGED recovery installed");
        } catch (e) { Log.e(TAG, "[dock] TOP_ACTIVITY_CHANGED recovery unavailable: " + e); }

        // 5) ПЛАВАЮЩАЯ HOME — подавление ВОЗВРАЩЕНО.
        //    Снимать его было ошибкой. Обоснование при снятии («во freeform-окне кнопка и так не
        //    всплывает») оказалось ложным: наш оконный режим НЕ переводит окно в настоящий freeform —
        //    vd_bypass.js настоящий freeform (windowing mode 5) наоборот пропускает, а обычному
        //    полноэкранному окну лишь переписывает рамки уже ПОСЛЕ раскладки. Для лаунчера приложение
        //    остаётся «сторонним на весь экран», поэтому предикат истинен всегда — и кнопка вылезала
        //    постоянно, даже когда док на месте и она не нужна.
        //
        //    Аварийно вернуть штатное поведение: settings put global voyahtune_floathome 0
        if (SHARED_NAV == false) { // на ПИ не надо даваить плавающую кнопку
            try {
                var floatHomeOff = function () { return cfg("floathome") !== "0"; };
                var LM = Java.use("com.qinggan.app.launcher.LauncherModel");
                var launcherFloatApp = LM.isThirdShowFloatApp.overload('java.lang.String');
                launcherFloatApp.implementation = function (cn) {
                    return floatHomeOff() ? false : launcherFloatApp.call(this, cn);
                };
                Log.i(TAG, "[dock] floating home suppressed (LauncherModel)");
            } catch (e) { Log.e(TAG, "[dock] LauncherModel.isThirdShowFloatApp skip: " + e); }
            try {
                var TAU = Java.use("com.qinggan.launcher.base.drag.ThirdAppUtil");
                var thirdFloatApp = TAU.isThirdShowFloatApp.overload('java.lang.String');
                thirdFloatApp.implementation = function (cn) {
                    return cfg("floathome") !== "0" ? false : thirdFloatApp.call(this, cn);
                };
                Log.i(TAG, "[dock] floating home suppressed (ThirdAppUtil)");
            } catch (e) { Log.e(TAG, "[dock] ThirdAppUtil.isThirdShowFloatApp skip: " + e); }
        }

        // 6) OEM onMoveStart асинхронно гасит ОБА NavigationBarController. На destination foreground-кэш
        //    в этот момент ещё может содержать Launcher, поэтому обычный dockKept(fg) пропускает dismiss
        //    и пассажирский Window удаляется. Guard ставим до оригинала, только для стороннего viewport-
        //    приложения; оба dismiss видят его до matching onMoveStop/TTL. После stop дополнительно
        //    сверяем реальные top обоих display — это закрывает пропущенный/опоздавший TOP broadcast.
        try {
            var LM2 = Java.use("com.qinggan.app.launcher.LauncherModel");
            var Log2 = Java.use("android.util.Log");
            // Live H97C invokes NavigationBarController through its INavigationBarController field;
            // that invoke-interface path is not reliably intercepted by the concrete-class hook.
            // Replay the driver layout shortly after the authoritative LauncherModel event instead.
            try {
                var launcherScreenLift = LM2.doScreenLift.overload('int');
                launcherScreenLift.implementation = function (type) {
                    var result = launcherScreenLift.call(this, type);
                    setTimeout(updateAllNavbars, 50);
                    setTimeout(updateAllNavbars, 250);
                    Log.i(TAG, "[dock] LauncherModel lift replay scheduled type=" + type);
                    return result;
                };
                Log.i(TAG, "[dock] LauncherModel doScreenLift replay hooked");
            } catch (e) { Log.e(TAG, "[dock] LauncherModel doScreenLift replay skip: " + e); }
            LM2.onMoveStart.overloads.forEach(function (ov) {
                ov.implementation = function () {
                    try {
                        var a = [];
                        for (var i = 0; i < arguments.length; i++) a.push("" + arguments[i]);
                        Log2.i("voyahdock", "onMoveStart(" + a.join(", ") + ")");
                        var type = Number(arguments[2]);
                        var sourceDisplay = Number(arguments[3]);
                        var pkg = cleanJavaString(arguments[0]);
                        var act = cleanJavaString(arguments[1]);
                        if (type === 1 && (sourceDisplay === 0 || sourceDisplay === 1)
                                && dockKept(pkg, act)) {
                            var generation = ++moveDockGeneration;
                            moveDockGuards[sourceDisplay] = {
                                deadline: Number(SystemClock.elapsedRealtime()) + 5000,
                                generation: generation,
                                pkg: pkg
                            };
                            Log2.i("voyahdock", "move guard START source=" + sourceDisplay
                                    + " gen=" + generation + " pkg=" + pkg);
                        }
                    } catch (e) {}
                    return ov.apply(this, arguments);
                };
            });
            LM2.onMoveStop.overloads.forEach(function (ov) {
                ov.implementation = function () {
                    var stopType = -1;
                    var sourceDisplay = -1;
                    var stopPackage = "";
                    try {
                        stopType = Number(arguments[2]);
                        sourceDisplay = Number(arguments[3]);
                        stopPackage = cleanJavaString(arguments[0]);
                        var a = [];
                        for (var i = 0; i < arguments.length; i++) a.push("" + arguments[i]);
                        Log2.i("voyahdock", "onMoveStop(" + a.join(", ") + ")");
                    } catch (e) {}
                    var result = ov.apply(this, arguments);
                    try {
                        if (stopType === 1 && (sourceDisplay === 0 || sourceDisplay === 1)) {
                            var guard = moveDockGuards[sourceDisplay];
                            if (guard.pkg === stopPackage && guard.deadline > 0) {
                                // Короткий grace нужен, потому что OEM stop сам лишь ставит UI-runnable.
                                guard.deadline = Math.min(guard.deadline,
                                        Number(SystemClock.elapsedRealtime()) + 750);
                                Log2.i("voyahdock", "move guard STOP source=" + sourceDisplay
                                        + " gen=" + guard.generation + " pkg=" + guard.pkg);
                            }
                        }
                        if (stopType === 1 && schedulePhysicalDockRecovery !== null) {
                            schedulePhysicalDockRecovery(this, "onMoveStop");
                        }
                    } catch (e) { Log.e(TAG, "[dock] onMoveStop recovery: " + e); }
                    return result;
                };
            });
            Log.i(TAG, "[dock] transfer guard/recovery installed");
        } catch (e) { Log.e(TAG, "[dock] transfer guard/recovery skip: " + e); }

        // Приёмник reload: Native шлёт DOCK_RELOAD после записи voyahtune_dock* → перечитать + перерисовать.
        // ВАЖНО: BroadcastReceiver.onReceive — АБСТРАКТНЫЙ метод. Shorthand-форма registerClass
        // (methods:{onReceive:function(){}}) на этой прошивке НЕ переопределяла абстрактный слот в vtable →
        // AbstractMethodError при доставке брэдкаста → КРЭШ лаунчера (весь UI). Объявляем метод с ЯВНОЙ
        // сигнатурой (returnType/argumentTypes) — это гарантирует конкретный override поверх абстрактного.
        try {
            var Receiver = Java.registerClass({
                name: "ru.big.town.dock.DockReloadReceiver",
                superClass: Java.use("android.content.BroadcastReceiver"),
                methods: {
                    onReceive: {
                        returnType: "void",
                        argumentTypes: ["android.content.Context", "android.content.Intent"],
                        implementation: function (context, intent) {
                            // NB: console.log после eternalize уходит в никуда → лог через android.util.Log
                            // (виден в logcat -s voyahdock), чтобы подтверждать доставку брэдкаста на голове.
                            try { Java.use("android.util.Log").i("voyahdock", "onReceive DOCK_RELOAD"); } catch (e) {}
                            try {
                                refreshCache();
                                setTimeout(updateAllNavbars, 300);   // дать навбару стабилизироваться
                                if (intent !== null && intent.getBooleanExtra("reloadAllApps", false)) {
                                    // A freeze/unfreeze changes PackageManager enabled state without an
                                    // install/remove broadcast guaranteed on every OEM build. Invalidate
                                    // both caches explicitly so the next reload can add a restored app or
                                    // remove a newly frozen one immediately.
                                    invalidateAllAppsCaches();
                                    setTimeout(function () {
                                        Java.scheduleOnMainThread(function () {
                                            try {
                                                if (reloadAllApps !== null && reloadAllApps !== undefined) {
                                                    reloadAllApps();
                                                    Log.i(TAG, "[allapps] frozen-app blacklist reloaded");
                                                }
                                            } catch (e) { Log.e(TAG, "[allapps] frozen-app reload failed: " + e); }
                                        });
                                    }, 300);
                                }
                            } catch (e) { Log.e(TAG, "[dock] onReceive err: " + e); }
                        }
                    }
                }
            });
            var IntentFilter = Java.use("android.content.IntentFilter");
            var recv = Receiver.$new();
            var filt = IntentFilter.$new(RELOAD_ACT);
            // На API≥33 форма (receiver, filter) для чужого implicit-broadcast бросает SecurityException —
            // нужен флаг RECEIVER_EXPORTED (0x2). На нашей голове Android 11 (API 30) — обычная 2-арг форма.
            var sdk = Java.use("android.os.Build$VERSION").SDK_INT.value;
            if (sdk >= 33) {
                ctx().registerReceiver.overload('android.content.BroadcastReceiver',
                    'android.content.IntentFilter', 'int').call(ctx(), recv, filt, 0x2);
            } else {
                ctx().registerReceiver.overload('android.content.BroadcastReceiver',
                    'android.content.IntentFilter').call(ctx(), recv, filt);
            }
            Log.i(TAG, "[dock] reload receiver registered: " + RELOAD_ACT + " (sdk=" + sdk + ")");
        } catch (e) { Log.e(TAG, "[dock] receiver reg err: " + e); }

        // Feed real third-party MediaSession players into the OEM model as the existing
        // WECAR_FLOW source. The OEM keeps ownership of the card, source picker, cluster panel,
        // layout, and touch handling.
        // No custom view is attached to the Launcher window; all rendering stays in OEM views.
        function installMediaWidgetBridge() {
            var NOW_PLAYING = "ru.big.town.anative.NOW_PLAYING";
            var SOURCES = "ru.big.town.anative.NOW_PLAYING_SOURCES";
            var WAKE_REFRESH = "ru.big.town.anative.INSTRUMENT_WAKE_REFRESH";
            var ENUM_NAME = "com.qinggan.media.helper.MediaEnum";
            var INFO_NAME = "com.qinggan.media.helper.base.bean.QinMediaInfo";
            var MANAGER_NAME = "com.qinggan.app.mediaCentre.MediaManager";
            var WIDGET_MANAGER_NAME = "com.qinggan.app.mediaCentre.manager.WidgetControlMediaManager";
            var WIDGET_VIEW_INTER_NAME = "com.qinggan.app.mediaCentre.inter.WidgetViewInter";
            var SRC_BEAN_NAME = "com.pateo.voyah.mediaCard.bean.SrcMediaBean";
            var RES_ENUM_NAME = "com.pateo.voyah.mediaCard.home.enums.MediaResEnum";
            var ADAPTER_NAME = "com.pateo.voyah.mediaCard.home.activity.MediaSrcAdapter";
            var HOLDER_NAME = "com.pateo.voyah.mediaCard.home.activity.MediaSrcAdapter$MediaSrcHolder";
            var HOME_SOURCE_NAME = "com.pateo.voyah.mediaCard.home.activity.HomeSrcMediaActivity";
            var HOME_BASE_VIEW_NAME = "com.pateo.voyah.mediaCard.home.view.HomeBaseView";
            var BIG_MEDIA_CARD_NAME = "com.pateo.voyah.mediaCard.home.view.BigMediaCard";
            var BASE_MEDIA_VIEW_NAME = "com.qinggan.app.mediaCentre.view.BaseMediaView";
            var MEDIA_CONTROL_METHOD = "media_control";
            var CLEAR_SOURCE_METHOD = "clear_source";
            var UriMedia = Java.use("android.net.Uri");
            var mediaUri = UriMedia.parse("content://ru.big.town.anative.nowplaying");
            var mediaSourcesUri = UriMedia.parse("content://ru.big.town.anative.nowplaying/sources");
            var BroadcastReceiverMedia = Java.use("android.content.BroadcastReceiver");
            var IntentFilterMedia = Java.use("android.content.IntentFilter");
            var StringMedia = Java.use("java.lang.String");
            var NativeMediaTag = Java.retain(Java.use("java.lang.Object").$new());
            var ArrayListMedia = Java.use("java.util.ArrayList");
            var IdentityHashMapMedia = Java.use("java.util.IdentityHashMap");
            var BundleMedia = Java.use("android.os.Bundle");
            var mediaWidgetHooks = {};
            var widgetConfigCache = {};
            var widgetRewriteCache = {};
            var receiverRegistered = false;
            var managerHooked = false;
            var widgetUpdateHooked = false;
            var nativeCardInfoHooked = false;
            var artMediaDeoptimized = false;
            var nativeViewHooked = false;
            var sourceHooked = false;
            var homeHooked = false;
            var refreshPending = false;
            var bridgeAvailable = false;
            var bridgeSelected = false;
            var selectedMediaPackage = "";
            var emptyProviderReads = 0;
            var bridgeSources = [];
            var bridgeSourcesKey = "";
            // SrcMediaBean.hashCode() is value-based (the OEM MediaResEnum), so all of our
            // WE_CAR rows can have the same hash. Identity mapping keeps app rows distinct and
            // never mistakes the stock DAB/WeChat row for one inserted by VoyahTune.
            var bridgeBeanPackages = IdentityHashMapMedia.$new();
            var enabled = true;
            var latestSnapshot = null;
            var nativeInfo = null;
            var mediaInfoQueryLogs = 0;
            var lastNativeKey = "";
            var MediaEnum = null;
            var QinMediaInfo = null;
            var Manager = null;
            var SrcMediaBean = null;
            var MediaResEnum = null;
            var WidgetViewInter = null;
            var HomeBaseView = null;
            var WECAR = null;
            var WE_CAR = null;
            var NO_MEDIA = null;
            var mediaControl = null;
            var nativeMediaResourceHooked = false;

            function mediaString(value) { return cleanJavaString(value); }
            function isPlaybackActive(state) {
                // Android reports buffering/connecting/seek states while the stream is already
                // active. The OEM widget only checking STATE_PLAYING is the source of its transient
                // "нет воспроизведения" state during Spotify/BT handoff.
                var value = Number(state || 0);
                return value === 3 || value === 6 || value === 8 || value === 4 || value === 5
                        || value === 9 || value === 10 || value === 11;
            }
            function staticField(clazz, name) {
                // Read enum constants through reflection.  A Frida Java.Field `.value`
                // can become a JS string and then has no JNI handle for OEM calls.
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
                try { return Java.cast(clazz.valueOf(StringMedia.$new(name)), clazz); }
                catch (e2) { return null; }
            }
            function fieldValue(instance, name) {
                if (instance === null || instance === undefined) return null;
                try {
                    var direct = instance[name];
                    if (direct !== null && direct !== undefined) {
                        return direct.value !== undefined ? direct.value : direct;
                    }
                } catch (e) {}
                try {
                    var clazz = instance.getClass();
                    while (clazz !== null) {
                        try {
                            var field = clazz.getDeclaredField(name);
                            field.setAccessible(true);
                            return field.get(instance);
                        } catch (missing) {
                            try { clazz = clazz.getSuperclass(); } catch (end) { clazz = null; }
                        }
                    }
                } catch (e2) {}
                return null;
            }
            function freshMediaEnum(name) {
                if (MediaEnum === null) return null;
                var value = staticField(MediaEnum, name);
                if (value === null || value === undefined) return null;
                try { value.getClass(); return value; } catch (e) { return null; }
            }
            function freshMediaResEnum(name) {
                if (MediaResEnum === null) return null;
                var value = staticField(MediaResEnum, name);
                if (value === null || value === undefined) return null;
                try { value.getClass(); return value; } catch (e) { return null; }
            }
            function enumName(value) {
                if (value === null || value === undefined) return "";
                try { return mediaString(value.name()); } catch (e) {}
                try { return mediaString(value.toString()); } catch (e2) {}
                return "";
            }
            function isWecar(value) {
                if (value === null || value === undefined || WECAR === null) return false;
                if (enumName(value) === "WECAR_FLOW") return true;
                try { return value.equals(WECAR); } catch (e) { return false; }
            }
            function isBluetoothSnapshot(snapshot) {
                if (snapshot === null || snapshot === undefined) return false;
                var pkg = mediaString(snapshot.pkg);
                return pkg === "com.android.bluetooth" || pkg === "com.qinggan.media";
            }
            function readColumn(cursor, name, fallback) {
                try {
                    var index = cursor.getColumnIndex(name);
                    return index >= 0 ? mediaString(cursor.getString(index)) : fallback;
                } catch (e) { return fallback; }
            }
            function readNumber(cursor, name, fallback) {
                try {
                    var index = cursor.getColumnIndex(name);
                    return index >= 0 ? Number(cursor.getLong(index)) : fallback;
                } catch (e) { return fallback; }
            }
            function readMediaSnapshot() {
                var result = {title: "", artist: "", album: "", app: "", pkg: "", state: 0,
                    position: 0, duration: 0, hasArt: false, updatedAt: 0};
                var cursor = null;
                try {
                    cursor = ctx().getContentResolver().query(mediaUri, null, null, null, null);
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
                } catch (e) { Log.w(TAG, "[media] snapshot query failed: " + e); }
                finally { try { if (cursor !== null) cursor.close(); } catch (ignored) {} }
                return result;
            }
            function readMediaSources() {
                var result = [];
                var cursor = null;
                try {
                    cursor = ctx().getContentResolver().query(mediaSourcesUri, null, null, null, null);
                    if (cursor !== null) {
                        while (cursor.moveToNext()) {
                            var pkg = readColumn(cursor, "package", "");
                            if (pkg) result.push({
                                pkg: pkg,
                                label: readColumn(cursor, "appLabel", ""),
                                title: readColumn(cursor, "title", ""),
                                artist: readColumn(cursor, "artist", ""),
                                selected: readNumber(cursor, "selected", 0) === 1
                            });
                        }
                    }
                } catch (e) { Log.w(TAG, "[media] source query failed: " + e); }
                finally { try { if (cursor !== null) cursor.close(); } catch (ignored) {} }
                return result;
            }
            function refreshMediaConfig() {
                try {
                    var value = SettingsGlobal.getString(ctx().getContentResolver(),
                            "voyahtune_home_third_party_media");
                    enabled = value === null || mediaString(value) !== "0";
                } catch (e) { enabled = true; }
            }
            function isBridgeSourcePackage(pkg) {
                // Radio, Bluetooth and the OEM local players already have their own native
                // MediaEnum sources. The bridge is exclusively for application MediaSessions.
                if (!pkg || pkg === "ru.big.town.anative" || pkg === "android") return false;
                return pkg.indexOf("com.qinggan.") !== 0
                        && pkg.indexOf("com.pateo.") !== 0
                        && pkg.indexOf("tai.") !== 0
                        && pkg.indexOf("com.android.bluetooth") !== 0;
            }
            function bridgeSourcesFrom(sources, selectedSource) {
                var result = [];
                var selectedPresent = false;
                for (var i = 0; i < sources.length; i++) {
                    if (isBridgeSourcePackage(sources[i].pkg)) {
                        result.push(sources[i]);
                        if (selectedSource !== null && selectedSource !== undefined
                                && sources[i].pkg === selectedSource.pkg) selectedPresent = true;
                    }
                }
                // During service startup the snapshot broadcast can arrive before the source
                // cursor has been populated. Keep the selected app visible instead of letting the
                // OEM picker briefly fall back to its stock DAB/WeChat row.
                if (selectedSource !== null && selectedSource !== undefined
                        && isBridgeSourcePackage(selectedSource.pkg) && !selectedPresent) {
                    result.push(selectedSource);
                }
                return result;
            }
            function sourceTopologyKey(sources) {
                var key = "";
                for (var i = 0; i < sources.length; i++) {
                    key += sources[i].pkg + "\u0001" + (sources[i].label || "") + "\u0001"
                            + (sources[i].selected ? "1" : "0") + "\u0002";
                }
                return key;
            }
            function findSelectedBridgeSource(snapshot, sources) {
                // Snapshot and source topology cross process boundaries separately. The snapshot
                // is the fresher authority during an app↔Bluetooth handoff; otherwise an old
                // selected Spotify row can keep WECAR_FLOW active while the OEM is already on BT.
                if (snapshot.pkg && !isBridgeSourcePackage(snapshot.pkg)) return null;
                if (isBridgeSourcePackage(snapshot.pkg)) {
                    for (var current = 0; current < sources.length; current++) {
                        if (sources[current].pkg === snapshot.pkg
                                && isBridgeSourcePackage(sources[current].pkg)) return sources[current];
                    }
                    return {pkg: snapshot.pkg, label: snapshot.app, selected: true};
                }
                for (var i = 0; i < sources.length; i++) {
                    if (sources[i].selected && isBridgeSourcePackage(sources[i].pkg)) return sources[i];
                }
                return null;
            }
            function setInfoValue(info, name, signature, value) {
                try {
                    var method = info[name].overload(signature);
                    if (signature === "java.lang.String") value = StringMedia.$new(mediaString(value));
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
                setInfoValue(info, "setName", "java.lang.String",
                        snapshot.title || snapshot.app || pkg || "Media");
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
                    var extras = BundleMedia.$new();
                    extras.putString.overload("java.lang.String", "java.lang.String").call(
                            extras, StringMedia.$new("package"), StringMedia.$new(pkg));
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
                    Log.w(TAG, "[media] QinMediaInfo retain/cast failed: " + e);
                    return null;
                }
                // MediaManager posts listener work asynchronously. Keep the old global reference
                // alive for a few frames after replacing it, then release it so frequent playback
                // updates do not accumulate Frida global references.
                if (previous !== null && previous !== nativeInfo) {
                    setTimeout(function () { try { previous.$dispose(); } catch (ignored) {} }, 3000);
                }
                return nativeInfo;
            }
            function sendMediaControl(command) {
                // An OEM card can retain this proxy after the user has switched to Bluetooth/DAB.
                // Never let that stale card route its buttons into the last third-party session.
                if (!currentWecar()) return;
                var argument = command;
                if (command === "play") {
                    if (latestSnapshot !== null && isPlaybackActive(latestSnapshot.state)) return;
                    argument = "play_pause";
                } else if (command === "pause") {
                    argument = "pause_only";
                }
                try {
                    ctx().getContentResolver().call(mediaUri, MEDIA_CONTROL_METHOD, argument, null);
                } catch (e) { Log.w(TAG, "[media] native command " + command + " failed: " + e); }
            }
            function installControlProxy() {
                if (mediaControl !== null) return;
                try {
                    var Control = Java.use("com.qinggan.app.mediaCentre.inter.IMediaControl");
                    var SearchCallback = "android.support.v4.media.MediaBrowserCompat$SearchCallback";
                    var CustomActionCallback = "android.support.v4.media.MediaBrowserCompat$CustomActionCallback";
                    var ControlClass = Java.registerClass({
                        name: "ru.big.town.launcher.ThirdPartyMediaControl" + new Date().getTime(),
                        implements: [Control],
                        methods: {
                            addFav: {returnType: "void", argumentTypes: ["java.lang.String"], implementation: function () {}},
                            addQinMediaListener: {returnType: "void", argumentTypes: ["com.qinggan.app.mediaCentre.inter.QinMediaListener"], implementation: function () {}},
                            fastForward: {returnType: "void", argumentTypes: [], implementation: function () {}},
                            getMediaBrowserHelper: {returnType: "com.qinggan.media.helper.MediaBrowserHelper", argumentTypes: [], implementation: function () { return null; }},
                            getMediaType: {returnType: ENUM_NAME, argumentTypes: [], implementation: function () { return freshMediaEnum("WECAR_FLOW"); }},
                            isConnected: {returnType: "boolean", argumentTypes: [], implementation: function () { return currentWecar(); }},
                            isPlay: {returnType: "boolean", argumentTypes: [], implementation: function () { return currentWecar() && !!latestSnapshot && isPlaybackActive(latestSnapshot.state); }},
                            pause: {returnType: "void", argumentTypes: [], implementation: function () { sendMediaControl("pause"); }},
                            play: {returnType: "void", argumentTypes: [], implementation: function () { sendMediaControl("play"); }},
                            playNext: {returnType: "void", argumentTypes: [], implementation: function () { sendMediaControl("next"); }},
                            playPrevious: {returnType: "void", argumentTypes: [], implementation: function () { sendMediaControl("previous"); }},
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
                            stop: {returnType: "void", argumentTypes: [], implementation: function () { sendMediaControl("pause"); }},
                            unRegisterCallback: {returnType: "void", argumentTypes: ["com.qinggan.media.helper.MediaBrowserHelper$MediaListener"], implementation: function () {}}
                        }
                    });
                    var proxy = ControlClass.$new();
                    mediaControl = Java.cast(proxy, Control);
                    Log.i(TAG, "[media] native IMediaControl proxy registered");
                } catch (e) { Log.w(TAG, "[media] IMediaControl proxy unavailable: " + e); }
            }
            function ensureMediaClasses() {
                if (Manager !== null && MediaEnum !== null && QinMediaInfo !== null
                        && WECAR !== null && WECAR !== undefined && SrcMediaBean !== null
                        && WE_CAR !== null && WE_CAR !== undefined && HomeBaseView !== null) return true;
                try {
                    MediaEnum = Java.use(ENUM_NAME);
                    QinMediaInfo = Java.use(INFO_NAME);
                    Manager = Java.use(MANAGER_NAME);
                    WidgetViewInter = Java.use(WIDGET_VIEW_INTER_NAME);
                    HomeBaseView = Java.use(HOME_BASE_VIEW_NAME);
                    SrcMediaBean = Java.use(SRC_BEAN_NAME);
                    MediaResEnum = Java.use(RES_ENUM_NAME);
                    WECAR = staticField(MediaEnum, "WECAR_FLOW");
                    NO_MEDIA = staticField(MediaEnum, "NO");
                    WE_CAR = staticField(MediaResEnum, "WE_CAR");
                    return Manager !== null && WECAR !== null && WECAR !== undefined
                            && SrcMediaBean !== null && WE_CAR !== null && WE_CAR !== undefined;
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
            function setObjectField(instance, fieldName, value) {
                if (instance === null || instance === undefined) return false;
                try {
                    var clazz = instance.getClass();
                    while (clazz !== null) {
                        try {
                            var field = clazz.getDeclaredField(fieldName);
                            field.setAccessible(true);
                            field.set(instance, value);
                            return true;
                        } catch (missing) {
                            try { clazz = clazz.getSuperclass(); } catch (end) { clazz = null; }
                        }
                    }
                } catch (e) {}
                return false;
            }
            function nativeMediaPackage() {
                var pkg = latestSnapshot === null ? "" : mediaString(latestSnapshot.pkg);
                return pkg || mediaString(selectedMediaPackage);
            }
            function nativeMediaLabel() {
                var pkg = nativeMediaPackage();
                var snapshotPackage = latestSnapshot === null
                        ? "" : mediaString(latestSnapshot.pkg);
                // Snapshot and source selection cross the process boundary independently. Never
                // put the previous app's label above the newly selected package during that gap.
                var label = latestSnapshot !== null && snapshotPackage === pkg
                        ? mediaString(latestSnapshot.app) : "";
                if (!label) {
                    for (var i = 0; i < bridgeSources.length; i++) {
                        if (bridgeSources[i].pkg === pkg && bridgeSources[i].label) {
                            label = mediaString(bridgeSources[i].label);
                            break;
                        }
                    }
                }
                return label || nativeMediaPackage() || "Музыка";
            }
            function applyNativeMediaIdentity(view) {
                if (view === null || view === undefined) return;
                // MediaResEnum.WE_CAR carries the OEM WeChat string/icon. Keep the native layout,
                // but replace that identity with the selected MediaSession app on every source
                // transition, including a late OEM DAB callback.
                try {
                    var nameView = fieldValue(view, "mediaName");
                    if (nameView !== null) {
                        nameView.setText.overload("java.lang.CharSequence").call(
                                nameView, StringMedia.$new(nativeMediaLabel()));
                    }
                } catch (e) {}
                try {
                    var pkg = nativeMediaPackage();
                    var iconView = fieldValue(view, "mediaIcon");
                    if (pkg && iconView !== null) {
                        iconView.setImageDrawable(ctx().getPackageManager().getApplicationIcon(
                                StringMedia.$new(pkg)));
                    }
                } catch (e2) {}
            }
            function applyNativeMediaResource(view) {
                if (view === null || view === undefined || WE_CAR === null
                        || WE_CAR === undefined || HomeBaseView === null) return false;
                try {
                    HomeBaseView.changeMediaType.overload(RES_ENUM_NAME).call(view, WE_CAR);
                    applyNativeMediaIdentity(view);
                    return true;
                } catch (e) {
                    // The concrete card may be exposed through an interface wrapper while it is
                    // being attached. Keep the protected field coherent as a fallback; the next
                    // attached-card update will run changeMediaType() normally.
                    var changed = setObjectField(view, "mediaResEnum", WE_CAR);
                    if (changed) applyNativeMediaIdentity(view);
                    return changed;
                }
            }
            function installNativeMediaResourceHook() {
                if (nativeMediaResourceHooked || HomeBaseView === null || WE_CAR === null
                        || WE_CAR === undefined) return true;
                try {
                    var method = HomeBaseView.changeMediaType.overload(RES_ENUM_NAME);
                    method.implementation = function (mediaResEnum) {
                        // OEM DAB callbacks can arrive after our WECAR callback. They only replace
                        // the card source caption/icon; keep the selected third-party source
                        // visually coherent until the selection changes.
                        if (nativeInfo !== null && currentWecar()) mediaResEnum = WE_CAR;
                        var result = method.call(this, mediaResEnum);
                        if (nativeInfo !== null && currentWecar()) applyNativeMediaIdentity(this);
                        return result;
                    };
                    nativeMediaResourceHooked = true;
                    Log.i(TAG, "[media] native WECAR source label hook installed");
                    return true;
                } catch (e) {
                    Log.w(TAG, "[media] native source label hook unavailable: " + e);
                    return false;
                }
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
                            var actualManager = fieldValue(this, "mediaManager");
                            if (actualManager !== null) {
                                putManagerValue(actualManager, "mediaInfoMap", canonicalEnum, nativeInfo);
                                putManagerValue(actualManager, "mediaInfoMap", mediaEnum, nativeInfo);
                                var actualEnum = fieldValue(this, "curMediaEnum");
                                if (actualEnum !== null && actualEnum !== mediaEnum) {
                                    putManagerValue(actualManager, "mediaInfoMap", actualEnum, nativeInfo);
                                }
                            }
                            // The OEM method can have an inlined getMediaInfoByType() and still
                            // store null in its private mediaInfo field. In that build, updating
                            // the callback directly is the only reliable way to keep both stock
                            // card variants on the same QinMediaInfo object.
                            var callback = fieldValue(this, "widgetViewInter");
                            var callbackEnum = canonicalEnum;
                            if (callback !== null && WidgetViewInter !== null) {
                                try {
                                    applyNativeMediaResource(callback);
                                    setObjectField(this, "mediaInfo", nativeInfo);
                                    WidgetViewInter.updateView.overload(ENUM_NAME, INFO_NAME).call(
                                            callback, callbackEnum, nativeInfo);
                                    setObjectField(this, "lastMediaEnum", callbackEnum);
                                    if (mediaInfoQueryLogs < 40) {
                                        mediaInfoQueryLogs++;
                                        Log.i(TAG, "[media] direct native card update normalized from "
                                                + enumName(mediaEnum));
                                    }
                                    return;
                                } catch (directError) {
                                    Log.w(TAG, "[media] direct native card update failed: " + directError);
                                }
                            }
                            if (mediaInfoQueryLogs < 20) {
                                mediaInfoQueryLogs++;
                                Log.i(TAG, "[media] WidgetControlMediaManager map refreshed for WECAR_FLOW");
                            }
                            // If the callback object is absent, still prevent a DAB/BT enum from
                            // selecting the OEM error renderer.
                            return method.apply(this, [canonicalEnum]);
                        }
                        return method.apply(this, arguments);
                    };
                    widgetUpdateHooked = true;
                    return true;
                } catch (e) {
                    Log.w(TAG, "[media] widget update hook unavailable: " + e);
                    return false;
                }
            }
            function installNativeCardInfoHook() {
                if (nativeCardInfoHooked) return true;
                try {
                    // BigMediaCard overrides updateView(). Its widget manager can pass a null
                    // QinMediaInfo to that override even while getMediaInfoByType() returns the
                    // bridge object. Repair the argument before BigMediaCard runs its own error
                    // handling and delegates to HomeBaseView/BaseMediaView.
                    var BigMediaCard = Java.use(BIG_MEDIA_CARD_NAME);
                    var method = BigMediaCard.updateView.overload(ENUM_NAME, INFO_NAME);
                    method.implementation = function () {
                        var mediaEnum = arguments[0];
                        var mediaInfo = arguments[1];
                        if (nativeInfo !== null && currentWecar()) {
                            var canonicalEnum = freshMediaEnum("WECAR_FLOW");
                            if (canonicalEnum === null) return method.apply(this, arguments);
                            applyNativeMediaResource(this);
                            if (mediaInfoQueryLogs < 40) {
                                mediaInfoQueryLogs++;
                                Log.i(TAG, "[media] BigMediaCard normalized from "
                                        + enumName(mediaEnum) + " info="
                                        + (mediaInfo === null ? "null" : "present"));
                            }
                            return method.apply(this, [canonicalEnum, nativeInfo]);
                        }
                        return method.apply(this, arguments);
                    };
                    nativeCardInfoHooked = true;
                    return true;
                } catch (e) {
                    Log.w(TAG, "[media] native card info hook unavailable: " + e);
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
                            } catch (e) { Log.w(TAG, "[media] manager " + name + " failed: " + e); }
                            return overload.apply(this, arguments);
                        };
                    });
                } catch (e) { Log.w(TAG, "[media] manager hook " + name + " unavailable: " + e); }
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
            function deoptimizeMediaPaths() {
                if (artMediaDeoptimized) return;
                try {
                    if (typeof Java.deoptimizeEverything === "function") {
                        Java.deoptimizeEverything();
                        artMediaDeoptimized = true;
                        Log.i(TAG, "[media] ART media paths deoptimized for OEM callbacks");
                    }
                } catch (e) {
                    Log.w(TAG, "[media] ART media deoptimization unavailable: " + e);
                }
            }
            function addWecarToNativeViewList(list) {
                // HomeBaseView filters WECAR_FLOW out when the optional OEM WeChat Music
                // feature is disabled. Third-party MediaSession apps use that contract; let the
                // OEM card accept it only for the selected application source.
                if (!currentWecar() || list === null || list === undefined) return;
                var mediaEnum = freshMediaEnum("WECAR_FLOW");
                if (mediaEnum === null) return;
                // Some launcher builds inline getMediaInfoByType() and read
                // MediaManager.mediaInfoMap directly. Keep that map coherent too; otherwise the
                // second stock card can still see null even though the hooked getter returns the
                // bridge object.
                var mediaManager = managerInstance();
                if (mediaManager !== null && nativeInfo !== null) {
                    putManagerValue(mediaManager, "mediaInfoMap", mediaEnum, nativeInfo);
                }
                try {
                    if (!list.contains(mediaEnum)) list.add(mediaEnum);
                } catch (e) { Log.w(TAG, "[media] native view media list update failed: " + e); }
            }
            function hookNativeViewList(className) {
                try {
                    var NativeView = Java.use(className);
                    var getMediaEnums = NativeView.getMediaEnums.overload();
                    getMediaEnums.implementation = function () {
                        var list = getMediaEnums.call(this);
                        if (list === null && currentWecar()) {
                            list = ArrayListMedia.$new();
                            var mediaEnum = freshMediaEnum("WECAR_FLOW");
                            if (mediaEnum !== null) list.add(mediaEnum);
                        }
                        addWecarToNativeViewList(list);
                        return list;
                    };
                    return true;
                } catch (e) {
                    Log.w(TAG, "[media] native view hook unavailable " + className + ": " + e);
                    return false;
                }
            }
            function installNativeViewSupport() {
                if (nativeViewHooked || !ensureMediaClasses()) return nativeViewHooked;
                // HomeBaseView owns the central card; BaseMediaView covers the OEM validation
                // path used by any other native media card in this process.
                var homeViewHooked = hookNativeViewList(HOME_BASE_VIEW_NAME);
                var baseViewHooked = hookNativeViewList(BASE_MEDIA_VIEW_NAME);
                nativeViewHooked = homeViewHooked || baseViewHooked;
                if (nativeViewHooked) Log.i(TAG, "[media] native WECAR view support installed");
                return nativeViewHooked;
            }
            function installManagerHooks() {
                if (!ensureMediaClasses()) return false;
                // The launcher can load MediaManager before HomeBaseView. Keep view support
                // retryable so a late-created card still receives the WECAR_FLOW model.
                installNativeViewSupport();
                if (managerHooked) return true;
                var manager = managerInstance();
                if (manager === null) return false;
                deoptimizeMediaPaths();
                installControlProxy();
                installWidgetUpdateHook();
                installNativeCardInfoHook();
                installNativeMediaResourceHook();
                hookManagerMethod("getCurMediaType", function () {
                    var mediaEnum = freshMediaEnum("WECAR_FLOW");
                    return (currentWecar() || nativeInfo !== null) && mediaEnum !== null
                            ? {handled: true, value: mediaEnum} : null;
                });
                hookManagerMethod("getCurMediaInfo", function () {
                    return nativeInfo !== null ? {handled: true, value: nativeInfo} : null;
                });
                hookManagerMethod("getMediaInfoByType", function (self, args) {
                    // The second stock card may pass a proxy/tag enum whose identity differs from
                    // the enum delivered to our callback. The OEM also calls this getter while
                    // rebuilding a card and may pass a stale/non-WECAR enum; filtering that
                    // argument makes the second card read null and fall back to DAB. nativeInfo
                    // exists only while a bridge source is selected and is cleared by
                    // clearNativeSelection(), so answer every concurrent query while it exists.
                    if (nativeInfo === null) return null;
                    if (mediaInfoQueryLogs < 20) {
                        mediaInfoQueryLogs++;
                        Log.i(TAG, "[media] getMediaInfoByType intercepted: arg="
                                + enumName(args[0]) + " currentWecar=" + currentWecar());
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
                            ? {handled: true, value: latestSnapshot === null ? 0
                                : Number(latestSnapshot.state || 0)} : null;
                });
                ["play", "pause", "playNext", "playPrevious", "playOrPause"].forEach(function (name) {
                    hookManagerMethod(name, function (self, args) {
                        if (!isWecar(args[0]) || !currentWecar()) return null;
                        var command = name === "playNext" ? "next"
                                : name === "playPrevious" ? "previous"
                                : name === "pause" ? "pause"
                                : name === "play" ? "play" : "play_pause";
                        sendMediaControl(command);
                        return {handled: true, value: undefined};
                    });
                });
                if (mediaControl !== null) {
                    try { manager.addMediaControl(mediaControl); } catch (e) {}
                }
                managerHooked = true;
                Log.i(TAG, "[media] native MediaManager hooks installed");
                return true;
            }
            function setNativeCurrent(manager, info) {
                var mediaEnum = freshMediaEnum("WECAR_FLOW");
                if (mediaEnum === null) return false;
                // Some OEM card variants read the private map directly and never call the hooked
                // getter. Populate it before setCurMedia posts any asynchronous listener work.
                putManagerValue(manager, "mediaInfoMap", mediaEnum, info);
                var currentSet = false;
                try {
                    manager.setCurMedia.overload(ENUM_NAME, INFO_NAME, "java.lang.Object").call(
                            manager, mediaEnum, info, NativeMediaTag);
                    currentSet = true;
                } catch (e) { Log.w(TAG, "[media] setCurMedia failed: " + e); }
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
                // MediaManager posts its type notification asynchronously. Retain the object and
                // populate the info map before any queued listener can reread it, otherwise the
                // OEM card briefly receives null and falls back to DAB/NO.
                info = retainNativeInfo(info);
                if (info === null) return;
                if (!setNativeCurrent(manager, info)) return;
                var mediaEnum = freshMediaEnum("WECAR_FLOW");
                if (mediaEnum === null) return;
                try {
                    manager.onMediaInfoChange.overload(ENUM_NAME, INFO_NAME, "boolean", "java.lang.Object").call(
                            manager, mediaEnum, info, true, NativeMediaTag);
                } catch (e) {
                    Log.w(TAG, "[media] native info callback failed: " + e);
                    return;
                }
                try {
                    manager.onMediaStateChange.overload(ENUM_NAME, "boolean", INFO_NAME,
                            "boolean", "java.lang.Object").call(
                            manager, mediaEnum, isPlaybackActive(snapshot.state), info, true, NativeMediaTag);
                } catch (e2) {
                    Log.w(TAG, "[media] native state callback failed: " + e2);
                }
                // OEM DAB/BT callbacks can arrive after our info/state callbacks. Re-announce the
                // canonical type only after the map and nativeInfo are populated; the manager hook
                // above also rewrites any late OEM enum back to this same WECAR object.
                try {
                    manager.onMediaTypeChange.overload(ENUM_NAME, "java.lang.Object").call(
                            manager, mediaEnum, NativeMediaTag);
                } catch (e3) {
                    Log.w(TAG, "[media] native type callback failed: " + e3);
                }
                lastNativeKey = key;
                Log.i(TAG, "[media] MediaSession " + snapshot.pkg + " -> native WECAR_FLOW: "
                        + snapshot.title);
            }
            function scheduleNativeRefresh() {
                // MediaManager can receive the broadcast before the card has rebuilt its source
                // list. Re-send the same snapshot after two UI frames so no manual picker toggle is
                // required to make BT/Spotify metadata visible.
                [250, 900].forEach(function (delay) {
                    setTimeout(function () {
                        try { Java.scheduleOnMainThread(function () { pushNativeSnapshot(true); }); }
                        catch (e) {}
                    }, delay);
                });
            }
            function clearNativeSelection(notifyNoMedia) {
                Log.i(TAG, "[media] native WECAR selection cleared");
                var previous = nativeInfo;
                nativeInfo = null;
                if (previous !== null) {
                    setTimeout(function () { try { previous.$dispose(); } catch (ignored) {} }, 3000);
                }
                lastNativeKey = "";
                // App→app and app→Bluetooth have a replacement ready in the same refresh. Sending
                // NO between them makes the OEM card race back to DAB and retain "no songs".
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
                    manager.onMediaTypeChange.overload(ENUM_NAME, "java.lang.Object").call(
                            manager, mediaEnum, NativeMediaTag);
                    Log.i(TAG, "[media] native BT_MUSIC selection refreshed");
                } catch (e) { Log.w(TAG, "[media] native BT_MUSIC refresh failed: " + e); }
            }
            function scheduleNativeBluetoothRefresh() {
                // The BT controller and both stock cards attach on separate UI turns. Repeat only
                // the native type notification after leaving WECAR so each one rereads BT metadata.
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
            function beanKey(bean) {
                if (bean === null || bean === undefined) return "";
                try {
                    var pkg = bridgeBeanPackages.get(bean);
                    return pkg === null || pkg === undefined ? "" : mediaString(pkg);
                } catch (e) { return ""; }
            }
            function isBridgeBean(bean) {
                if (bean === null || bean === undefined) return false;
                try { return bridgeBeanPackages.containsKey(bean); }
                catch (e) { return false; }
            }
            function clearBridgeBeans() {
                try { bridgeBeanPackages.clear(); } catch (e) {}
            }
            function sourceForBean(bean) {
                var pkg = beanKey(bean);
                if (!pkg) return null;
                for (var i = 0; i < bridgeSources.length; i++) {
                    if (bridgeSources[i].pkg === pkg) return bridgeSources[i];
                }
                return null;
            }
            function makeBridgeBean(source) {
                try {
                    var mediaRes = freshMediaResEnum("WE_CAR");
                    if (mediaRes === null) return null;
                    var bean = SrcMediaBean.$new(mediaRes);
                    bridgeBeanPackages.put(bean, StringMedia.$new(source.pkg));
                    return bean;
                } catch (e) {
                    Log.w(TAG, "[media] source bean creation failed: " + e);
                    return null;
                }
            }
            function appendBridgeBeans(list) {
                if (list === null || list === undefined) return;
                for (var i = 0; i < bridgeSources.length; i++) {
                    var bean = makeBridgeBean(bridgeSources[i]);
                    if (bean !== null) list.add(bean);
                }
            }
            function rebuildBridgeBeans(adapter) {
                if (adapter === null || adapter === undefined) return;
                var list = fieldValue(adapter, "mediaBeans");
                if (list === null) return;
                try {
                    var stock = ArrayListMedia.$new();
                    for (var i = 0; i < list.size(); i++) {
                        var bean = list.get(i);
                        if (!isBridgeBean(bean)) {
                            stock.add(bean);
                        }
                    }
                    clearBridgeBeans();
                    list.clear();
                    list.addAll(stock);
                    appendBridgeBeans(list);
                    adapter.notifyDataSetChanged();
                } catch (e) { Log.w(TAG, "[media] source rows refresh failed: " + e); }
            }
            function refreshSourcePickers() {
                try {
                    Java.choose(HOME_SOURCE_NAME, {
                        onMatch: function (activity) {
                            try {
                                var retained = Java.retain(activity);
                                Java.scheduleOnMainThread(function () {
                                    try { rebuildBridgeBeans(fieldValue(retained, "mediaSrcAdapter")); }
                                    catch (e) {}
                                    finally { try { retained.$dispose(); } catch (ignored) {} }
                                });
                            } catch (e) {}
                        },
                        onComplete: function () {}
                    });
                } catch (e) {}
            }
            function installSourceHooks() {
                if (sourceHooked || !ensureMediaClasses()) return false;
                try {
                    var Adapter = Java.use(ADAPTER_NAME);
                    var Holder = Java.use(HOLDER_NAME);
                    var fillData = Adapter.fillData.overload("java.util.List");
                    fillData.implementation = function (list) {
                        if (list === null) return fillData.call(this, list);
                        // The OEM adapter owns its stock rows. We append one existing-WECAR row
                        // per actual app MediaSession and keep an object-identity map for clicks.
                        var copy = ArrayListMedia.$new();
                        for (var i = 0; i < list.size(); i++) {
                            var bean = list.get(i);
                            if (!isBridgeBean(bean)) copy.add(bean);
                        }
                        clearBridgeBeans();
                        appendBridgeBeans(copy);
                        return fillData.call(this, copy);
                    };
                    var bindView = Holder.bindView.overload("int");
                    bindView.implementation = function (position) {
                        var result = bindView.call(this, position);
                        try {
                            var source = sourceForBean(fieldValue(this, "srcMediaBean"));
                            if (source !== null) {
                                var binding = fieldValue(this, "binding");
                                var name = fieldValue(binding, "tvName");
                                if (name !== null) name.setText.overload("java.lang.CharSequence").call(
                                        name, StringMedia.$new(source.label || source.pkg));
                                var root = fieldValue(binding, "clRoot");
                                if (root !== null) root.setActivated(!!source.selected);
                                try {
                                    var icon = fieldValue(binding, "ivMain");
                                    if (icon !== null) icon.setImageDrawable(ctx().getPackageManager()
                                            .getApplicationIcon(source.pkg));
                                } catch (ignoredIcon) {}
                            }
                        } catch (e) {}
                        return result;
                    };
                    sourceHooked = true;
                    Log.i(TAG, "[media] native source picker hooks installed");
                    return true;
                } catch (e) {
                    Log.w(TAG, "[media] source picker hooks unavailable: " + e);
                    return false;
                }
            }
            function selectBridgeSource(source) {
                if (source === null || !source.pkg) return;
                try {
                    ctx().getContentResolver().call(mediaUri, "select_source", source.pkg, null);
                    Log.i(TAG, "[media] MediaSession source selected: " + source.pkg);
                } catch (e) { Log.w(TAG, "[media] source selection failed: " + e); }
            }
            function clearBridgeSourceSelection() {
                try {
                    ctx().getContentResolver().call(mediaUri, CLEAR_SOURCE_METHOD, null, null);
                    Log.i(TAG, "[media] OEM source selected; MediaSession pin cleared");
                } catch (e) { Log.w(TAG, "[media] source clear failed: " + e); }
            }
            function installHomeSourceHooks() {
                if (homeHooked) return true;
                try {
                    var HomeSource = Java.use(HOME_SOURCE_NAME);
                    var play = HomeSource.play.overload(SRC_BEAN_NAME);
                    play.implementation = function (bean) {
                        var source = sourceForBean(bean);
                        if (source !== null) {
                            selectBridgeSource(source);
                            return;
                        }
                        var result = play.call(this, bean);
                        // Stock BT/DAB/USB rows are not in bridgeBeanPackages. Their click must
                        // release the persisted Spotify/Yandex pin or the provider keeps publishing
                        // the old app after the OEM player has already switched sources.
                        clearBridgeSourceSelection();
                        return result;
                    };
                    HomeSource.onResume.overloads.forEach(function (overload) {
                        overload.implementation = function () {
                            var result = overload.apply(this, arguments);
                            try { rebuildBridgeBeans(fieldValue(this, "mediaSrcAdapter")); } catch (e) {}
                            return result;
                        };
                    });
                    homeHooked = true;
                    return true;
                } catch (e) {
                    Log.w(TAG, "[media] HomeSrcMediaActivity hooks unavailable: " + e);
                    return false;
                }
            }
            function refreshMediaState(force) {
                refreshMediaConfig();
                var snapshot = readMediaSnapshot();
                var sources = readMediaSources();
                // Snapshot and source rows are written independently. Do not turn a valid card into
                // DAB/NO on one empty cross-process read; only accept the reset after persistence.
                if (!snapshot.pkg && sources.length === 0 && bridgeSelected && selectedMediaPackage) {
                    emptyProviderReads++;
                    if (emptyProviderReads < 4) {
                        Log.i(TAG, "[media] provider transiently empty; keeping " + selectedMediaPackage);
                        scheduleMediaRefresh();
                        return;
                    }
                } else {
                    emptyProviderReads = 0;
                }
                // A cross-process query may briefly observe the provider between its snapshot and
                // source-list writes. Retain the last non-empty topology for that one frame; the
                // next broadcast replaces it with the authoritative list.
                if (sources.length === 0 && bridgeSources.length > 0 && snapshot.pkg) {
                    var fallbackPackage = snapshot.pkg;
                    sources = bridgeSources.map(function (source) {
                        return {pkg: source.pkg, label: source.label, title: source.title,
                            artist: source.artist,
                            selected: source.pkg === fallbackPackage};
                    });
                }
                var selectedSource = findSelectedBridgeSource(snapshot, sources);
                var nextSources = enabled ? bridgeSourcesFrom(sources, selectedSource) : [];
                var wasAvailable = bridgeAvailable;
                var wasSelected = bridgeSelected;
                var previousSelectedPackage = selectedMediaPackage;
                var nextSourcesKey = sourceTopologyKey(nextSources);
                var sourcesChanged = nextSourcesKey !== bridgeSourcesKey;
                latestSnapshot = snapshot;
                bridgeSources = nextSources;
                bridgeSourcesKey = nextSourcesKey;
                bridgeAvailable = enabled && (bridgeSources.length > 0 || selectedSource !== null);
                bridgeSelected = bridgeAvailable && selectedSource !== null;
                selectedMediaPackage = bridgeSelected ? selectedSource.pkg : "";
                var transition = planMediaTransition(wasSelected, previousSelectedPackage,
                        bridgeSelected, selectedMediaPackage, isBluetoothSnapshot(snapshot));
                var selectionChanged = transition.changed;
                if (transition.clearPrevious) {
                    clearNativeSelection(transition.notifyNoMedia);
                }
                installManagerHooks();
                installSourceHooks();
                installHomeSourceHooks();
                if (bridgeSelected) {
                    pushNativeSnapshot(!!force);
                    if (transition.refreshThirdParty || force) scheduleNativeRefresh();
                } else {
                    refreshNativeBluetooth(snapshot);
                    if (transition.refreshBluetooth || force) {
                        scheduleNativeBluetoothRefresh();
                    }
                }
                if (wasAvailable !== bridgeAvailable || wasSelected !== bridgeSelected
                        || previousSelectedPackage !== selectedMediaPackage || sourcesChanged) {
                    refreshSourcePickers();
                }
            }
            function scheduleMediaRefresh() {
                if (refreshPending) return;
                refreshPending = true;
                setTimeout(function () {
                    refreshPending = false;
                    try { Java.scheduleOnMainThread(refreshMediaState); } catch (e) {}
                }, 100);
            }
            function scheduleWakeRefresh() {
                // A process can survive a long suspend while its OEM card/view has lost the
                // connection to MediaManager. Rebuild on several UI turns; this is bounded and
                // does not restart the launcher or instrument process.
                [0, 700, 2200].forEach(function (delay) {
                    setTimeout(function () {
                        try {
                            Java.scheduleOnMainThread(function () { refreshMediaState(true); });
                        } catch (e) {}
                    }, delay);
                });
            }
            function configuredWidgetCsv(key) {
                if (Object.prototype.hasOwnProperty.call(widgetConfigCache, key)) {
                    return widgetConfigCache[key];
                }
                var region = key.replace("_widget_list", "");
                try {
                    var raw = SettingsGlobal.getString(ctx().getContentResolver(),
                            "voyahtune_home_widgets_" + region);
                    var result = (raw === null || raw === "")
                            ? null : (raw.toString() === "none" ? "" : raw.toString());
                    widgetConfigCache[key] = result;
                    return result;
                } catch (e) {
                    widgetConfigCache[key] = null;
                    return null;
                }
            }
            function invalidateWidgetRewriteCache() {
                widgetConfigCache = {};
                widgetRewriteCache = {};
            }
            function rewriteWidgetJson(raw, key) {
                var csv = configuredWidgetCsv(key);
                if (csv === null || !raw) return raw;
                if (csv === "") return "[]";
                var cached = widgetRewriteCache[key];
                if (cached !== undefined && cached.raw === raw && cached.csv === csv) return cached.value;
                try {
                    var list = JSON.parse(raw);
                    if (!Array.isArray(list)) return raw;
                    var byOperation = {};
                    for (var i = 0; i < list.length; i++) {
                        if (list[i] && list[i].operation) byOperation["" + list[i].operation] = list[i];
                    }
                    var result = [];
                    var wanted = csv.split(",");
                    for (var j = 0; j < wanted.length; j++) {
                        if (byOperation[wanted[j]]) result.push(byOperation[wanted[j]]);
                    }
                    var rewritten = JSON.stringify(result);
                    widgetRewriteCache[key] = {raw: raw, csv: csv, value: rewritten};
                    return rewritten;
                } catch (e) {
                    Log.w(TAG, "[widgets] JSON rewrite failed for " + key + ": " + e);
                    return raw;
                }
            }
            function installHomeWidgetHooks() {
                var classes = [];
                try { classes = Java.enumerateLoadedClassesSync(); } catch (e) { return; }
                for (var i = 0; i < classes.length; i++) {
                    var className = "" + classes[i];
                    if (className.indexOf("VehicleHiBoardDataManager") < 0) continue;
                    if (mediaWidgetHooks[className]) continue;
                    try {
                        var manager = Java.use(className);
                        if (!manager.getSPWidgetInfoList) continue;
                        manager.getSPWidgetInfoList.overloads.forEach(function (overload) {
                            overload.implementation = function () {
                                var result = overload.apply(this, arguments);
                                var key = "";
                                for (var a = 0; a < arguments.length; a++) {
                                    var candidate = mediaString(arguments[a]);
                                    if (candidate.indexOf("_widget_list") >= 0) { key = candidate; break; }
                                }
                                if (!key || result === null) return result;
                                try {
                                    if ("" + result.getClass().getName() === "java.lang.String") {
                                        return StringMedia.$new(rewriteWidgetJson("" + result, key));
                                    }
                                } catch (ignored) {}
                                return result;
                            };
                        });
                        mediaWidgetHooks[className] = true;
                        Log.i(TAG, "[widgets] hook installed " + className);
                    } catch (e) { Log.w(TAG, "[widgets] hook failed " + className + ": " + e); }
                }
            }
            function registerMediaReceiver() {
                if (receiverRegistered) return;
                try {
                    var Receiver = Java.registerClass({
                        name: "ru.town.voyah.LauncherNativeMediaReceiver",
                        superClass: BroadcastReceiverMedia,
                        methods: {
                            onReceive: {
                                returnType: "void",
                                argumentTypes: ["android.content.Context", "android.content.Intent"],
                                implementation: function (context, intent) {
                                    var action = intent === null ? "" : mediaString(intent.getAction());
                                    if (action === RELOAD_ACT) {
                                        invalidateWidgetRewriteCache();
                                        installHomeWidgetHooks();
                                    }
                                    if (action === WAKE_REFRESH) scheduleWakeRefresh();
                                    else scheduleMediaRefresh();
                                }
                            }
                        }
                    });
                    var receiver = Receiver.$new();
                    var filter = IntentFilterMedia.$new();
                    filter.addAction(NOW_PLAYING);
                    filter.addAction(SOURCES);
                    filter.addAction(RELOAD_ACT);
                    filter.addAction(WAKE_REFRESH);
                    var sdk = Java.use("android.os.Build$VERSION").SDK_INT.value;
                    if (sdk >= 33) {
                        ctx().registerReceiver.overload("android.content.BroadcastReceiver",
                                "android.content.IntentFilter", "int").call(ctx(), receiver, filter, 0x2);
                    } else {
                        ctx().registerReceiver.overload("android.content.BroadcastReceiver",
                                "android.content.IntentFilter").call(ctx(), receiver, filter);
                    }
                    receiverRegistered = true;
                    Log.i(TAG, "[media] native MediaSession receiver registered");
                } catch (e) { Log.w(TAG, "[media] receiver unavailable: " + e); }
            }

            refreshMediaConfig();
            installManagerHooks();
            installSourceHooks();
            installHomeSourceHooks();
            registerMediaReceiver();
            refreshMediaState();
            installHomeWidgetHooks();
            setTimeout(installHomeWidgetHooks, 1200);
            setTimeout(installHomeWidgetHooks, 3000);
        }

        installMediaWidgetBridge();

        // Первичная загрузка конфига + отрисовка иконок на уже живых навбарах. Повторы — на случай,
        // если навбар создаётся чуть позже инъекции (на буте load.bin инжектит рано).
        refreshCache();
        installAllAppsHooks();
        setImmediate(updateAllNavbars);
        setTimeout(updateAllNavbars, 800);
        setTimeout(updateAllNavbars, 2500);
        setTimeout(updateAllNavbars, 5000);
        // PackageManager and OEM navbar construction finish at different moments on a true cold boot.
        // These are bounded one-shot reconciliations, not polling; normal boots still update immediately.
        setTimeout(updateAllNavbars, 8000);

        Log.i(TAG, "[dock] NavigationBarMain hooks installed (updateTheme/updateSelectedApp/onClick)");
    } catch (e) {
        // Класс не найден (скрипт заинжектили не в лаунчер, либо CN/другая прошивка) — тихо выходим.
        Log.e(TAG, "[dock] NavigationBarMain not found (not launcher/oversea?): " + e);
    }
});
