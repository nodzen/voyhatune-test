/** Pure source-transition policy shared by launcher and instrument-card Frida adapters. */
export function bridgeConnected(available, selected, packageName) {
    return !!available && !!selected && typeof packageName === "string" && packageName.length > 0;
}

export function planMediaTransition(wasSelected, previousPackage,
                                    bridgeSelected, selectedPackage, bluetoothSnapshot) {
    var previous = previousPackage || "";
    var next = selectedPackage || "";
    var changed = !!wasSelected !== !!bridgeSelected || previous !== next;
    return {
        changed: changed,
        clearPrevious: !!wasSelected && changed,
        // A ready app or Bluetooth replacement must never pass through OEM NO/DAB.
        notifyNoMedia: !bridgeSelected && !bluetoothSnapshot,
        refreshThirdParty: !!bridgeSelected && changed,
        refreshBluetooth: !!wasSelected && changed && !bridgeSelected && !!bluetoothSnapshot
    };
}
