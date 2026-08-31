#!/bin/sh
# Standalone macOS/Linux installer for the optional static Yandex DNS RRO.
# Uses the same configure_yandex_dns prompt and safety checks as the main install.sh.

set -u
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
cd "$SCRIPT_DIR" || exit 1

if [ ! -f ./dns-overlay.sh ]; then
    echo "!!! Не найден ./dns-overlay.sh — установка прервана."
    exit 1
fi
. ./dns-overlay.sh || {
    echo "!!! Не удалось загрузить ./dns-overlay.sh — установка прервана."
    exit 1
}

for ydns_required in ydns_prepare_helper configure_yandex_dns; do
    if ! command -v "$ydns_required" >/dev/null 2>&1; then
        echo "!!! dns-overlay.sh не содержит $ydns_required — установка прервана."
        exit 1
    fi
done
if ! ydns_prepare_helper; then
    echo "!!! DNS-overlay package is incomplete or has an invalid checksum."
    exit 1
fi

"$YDNS_ADB" root >/dev/null 2>&1 || {
    echo "!!! ADB не получил root-доступ к устройству."
    exit 1
}
"$YDNS_ADB" wait-for-device || {
    echo "!!! Устройство не найдено через ADB."
    exit 1
}
"$YDNS_ADB" root >/dev/null 2>&1 || {
    echo "!!! ADB не получил root-доступ к устройству."
    exit 1
}

if ! configure_yandex_dns; then
    echo "!!! Настройка DNS-overlay завершилась ошибкой."
    exit 1
fi

if [ "${YDNS_CHANGED:-0}" = "1" ]; then
    if ! "$YDNS_ADB" reboot; then
        echo "!!! DNS-overlay установлен, но ADB не смог перезагрузить устройство."
        exit 1
    fi
    echo "Yandex DNS установлен. Устройство перезагружается."
else
    echo "Yandex DNS не изменён. Перезагрузка не требуется."
fi
