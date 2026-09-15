import assert from "node:assert/strict";
import {bridgeConnected, planMediaTransition} from "../inject/lib/media_transition.mjs";

assert.equal(bridgeConnected(true, true, "com.spotify.music"), true);
assert.equal(bridgeConnected(true, false, "com.spotify.music"), false);
assert.equal(bridgeConnected(true, true, ""), false);

let plan = planMediaTransition(true, "com.spotify.music", false, "", true);
assert.deepEqual(plan, {
    changed: true,
    clearPrevious: true,
    notifyNoMedia: false,
    refreshThirdParty: false,
    refreshBluetooth: true
});

plan = planMediaTransition(true, "com.spotify.music", true, "ru.yandex.music", false);
assert.equal(plan.clearPrevious, true);
assert.equal(plan.notifyNoMedia, false);
assert.equal(plan.refreshThirdParty, true);
assert.equal(plan.refreshBluetooth, false);

plan = planMediaTransition(false, "", false, "", false);
assert.equal(plan.changed, false);
assert.equal(plan.clearPrevious, false);

console.log("media transition policy: OK");
