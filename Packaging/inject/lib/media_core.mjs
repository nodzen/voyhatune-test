const EMPTY_SNAPSHOT = Object.freeze({
    title: "", artist: "", album: "", app: "", pkg: "", state: 0,
    position: 0, duration: 0, hasArt: false, updatedAt: 0
});

function cleanText(value) {
    if (value === null || value === undefined) return "";
    const text = String(value);
    return text === "null" || text === "undefined" ? "" : text;
}

function finiteNumber(value) {
    const number = Number(value);
    return Number.isFinite(number) ? number : 0;
}

/** Pure, bounded snapshot shared by both OEM adapters. */
export function normalizeMediaSnapshot(raw) {
    const source = raw || EMPTY_SNAPSHOT;
    return {
        title: cleanText(source.title),
        artist: cleanText(source.artist),
        album: cleanText(source.album),
        app: cleanText(source.app),
        pkg: cleanText(source.pkg),
        state: finiteNumber(source.state),
        position: Math.max(0, finiteNumber(source.position)),
        duration: Math.max(0, finiteNumber(source.duration)),
        hasArt: source.hasArt === true || source.hasArt === 1 || source.hasArt === "1",
        updatedAt: Math.max(0, finiteNumber(source.updatedAt))
    };
}

export function isBridgeMediaPackage(packageName) {
    const pkg = cleanText(packageName);
    if (!pkg || pkg === "ru.big.town.anative" || pkg === "android") return false;
    return !pkg.startsWith("com.qinggan.")
        && !pkg.startsWith("com.pateo.")
        && !pkg.startsWith("tai.")
        && !pkg.startsWith("com.android.bluetooth");
}

/** Snapshot identity wins over a briefly stale selected row during OEM/app handoff. */
export function selectBridgeSource(snapshotValue, sourceValues) {
    const snapshot = normalizeMediaSnapshot(snapshotValue);
    const sources = Array.isArray(sourceValues) ? sourceValues : [];
    if (snapshot.pkg && !isBridgeMediaPackage(snapshot.pkg)) return null;
    if (isBridgeMediaPackage(snapshot.pkg)) {
        for (const source of sources) {
            if (source && cleanText(source.pkg) === snapshot.pkg
                    && isBridgeMediaPackage(source.pkg)) return source;
        }
        return {pkg: snapshot.pkg, label: snapshot.app, selected: true};
    }
    for (const source of sources) {
        if (source && source.selected && isBridgeMediaPackage(source.pkg)) return source;
    }
    return null;
}

/** Java-free description consumed by the QinMediaInfo-specific adapters. */
export function buildOemMediaModel(snapshotValue, selectedPackage) {
    const snapshot = normalizeMediaSnapshot(snapshotValue);
    const pkg = snapshot.pkg || cleanText(selectedPackage);
    return {
        name: snapshot.title || snapshot.app || pkg || "Media",
        artist: snapshot.artist,
        album: snapshot.album,
        duration: snapshot.duration,
        mediaId: pkg + "|" + snapshot.title + "|" + snapshot.artist,
        mediaType: "WECAR_FLOW",
        hostId: pkg,
        path: pkg,
        coverUrl: snapshot.hasArt
            ? "content://ru.big.town.anative.nowplaying/art?rev=" + snapshot.updatedAt : "",
        packageName: pkg
    };
}
