import assert from "node:assert/strict";
import {
    buildOemMediaModel,
    isBridgeMediaPackage,
    normalizeMediaSnapshot,
    selectBridgeSource
} from "../inject/lib/media_core.mjs";

assert.deepEqual(normalizeMediaSnapshot(null), {
    title: "", artist: "", album: "", app: "", pkg: "", state: 0,
    position: 0, duration: 0, hasArt: false, updatedAt: 0
});
assert.equal(normalizeMediaSnapshot({duration: -4, hasArt: "1"}).duration, 0);
assert.equal(normalizeMediaSnapshot({duration: -4, hasArt: "1"}).hasArt, true);

assert.equal(isBridgeMediaPackage("com.spotify.music"), true);
assert.equal(isBridgeMediaPackage("com.qinggan.media"), false);
assert.equal(isBridgeMediaPackage("com.android.bluetooth"), false);

const sources = [
    {pkg: "com.spotify.music", selected: true},
    {pkg: "ru.yandex.music", selected: false}
];
assert.equal(selectBridgeSource({pkg: "com.android.bluetooth"}, sources), null);
assert.equal(selectBridgeSource({pkg: "ru.yandex.music", app: "Музыка"}, sources).pkg,
    "ru.yandex.music");
assert.equal(selectBridgeSource({}, sources).pkg, "com.spotify.music");

assert.deepEqual(buildOemMediaModel({
    title: "Track", artist: "Artist", album: "Album", pkg: "com.spotify.music",
    duration: 123, hasArt: true, updatedAt: 456
}, ""), {
    name: "Track", artist: "Artist", album: "Album", duration: 123,
    mediaId: "com.spotify.music|Track|Artist", mediaType: "WECAR_FLOW",
    hostId: "com.spotify.music", path: "com.spotify.music",
    coverUrl: "content://ru.big.town.anative.nowplaying/art?rev=456",
    packageName: "com.spotify.music"
});

console.log("PASS: shared media snapshot/source/OEM model policies");
