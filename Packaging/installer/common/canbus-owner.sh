#!/bin/sh
# Shared CANBus permission-owner preflight for both release flavours.  Keep this
# file POSIX-sh compatible: the macOS bundle runs it with /bin/sh.

CANBUS_PERMISSION_NAME="com.qinggan.permission.WRITE_CANBUS"
CANBUS_NATIVE_PACKAGE="ru.big.town.anative"
CANBUS_HL_PACKAGE="com.voyah.hl.service"
CANBUS_HL_SYSTEM_DIR="/system/priv-app/VoyahHlCTRL"

canbus_read_permission_owner() {
    CANBUS_PERMISSION_DUMP=$(adb shell dumpsys package permissions 2>/dev/null) || {
        echo "!!! PackageManager permissions недоступны — установка прервана до записи в /system."
        return 1
    }

    CANBUS_PERMISSION_PRESENT=0
    CANBUS_PERMISSION_OWNER=""
    case "$CANBUS_PERMISSION_DUMP" in
        *"Permission [$CANBUS_PERMISSION_NAME]"*)
            CANBUS_PERMISSION_PRESENT=1
            CANBUS_PERMISSION_OWNER=$(printf '%s\n' "$CANBUS_PERMISSION_DUMP" | awk '
                /Permission \[com\.qinggan\.permission\.WRITE_CANBUS\]/ { in_block=1; next }
                in_block && /Permission \[/ { exit }
                in_block && /sourcePackage=/ {
                    sub(/^.*sourcePackage=/, ""); gsub(/[[:space:]]/, ""); print; exit
                }')
            ;;
    esac
}

canbus_read_hl_service_state() {
    CANBUS_USER_PACKAGES=$(adb shell "pm list packages --user 0" 2>/dev/null) || {
        echo "!!! Не удалось проверить пакеты пользователя 0 — установка прервана."
        return 1
    }
    CANBUS_HL_INSTALLED=0
    if printf '%s\n' "$CANBUS_USER_PACKAGES" | tr -d '\r' | grep -qx "package:$CANBUS_HL_PACKAGE"; then
        CANBUS_HL_INSTALLED=1
    fi
}

canbus_wait_for_boot() {
    adb wait-for-device || return 1
    CANBUS_BOOT_WAIT=0
    while [ "$CANBUS_BOOT_WAIT" -lt 60 ]; do
        [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 5
        CANBUS_BOOT_WAIT=$((CANBUS_BOOT_WAIT + 1))
    done
    if [ "$CANBUS_BOOT_WAIT" -ge 60 ]; then
        echo "!!! Автомобиль не завершил загрузку после удаления VoyahHlCTRL. Установка остановлена."
        return 1
    fi
    adb root >/dev/null 2>&1 || return 1
    adb wait-for-device || return 1
    adb root >/dev/null 2>&1 || return 1
}

canbus_system_is_writable() {
    adb remount >/dev/null 2>&1
    adb shell 'mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null' >/dev/null 2>&1
    [ "$(adb shell 'touch /system/.ovw_rwtest 2>/dev/null && rm -f /system/.ovw_rwtest && echo RW || echo RO' | tr -d '\r')" = "RW" ]
}

canbus_prepare_writable_system() {
    echo "=== Готовим /system к записи (verity → overlay) ==="
    adb disable-verity 2>&1 | sed 's/^/  /'
    if ! canbus_system_is_writable; then
        echo "  /system ещё read-only → перезагрузка ОДИН раз (применяем disable-verity)..."
        adb reboot || return 1
        if ! canbus_wait_for_boot; then
            echo "!!! Не удалось дождаться root-доступа после подготовки /system."
            return 1
        fi
    fi
    if ! canbus_system_is_writable; then
        echo "!!! /system ОСТАЁТСЯ read-only — установка прервана (в /system ничего не тронуто)."
        echo "    Причины: заблокирован загрузчик (disable-verity не срабатывает) / прошивка с EROFS"
        echo "    (несжимаемая read-only ФС) / verity не снимается на этой сборке."
        echo "    Проверьте вручную: adb disable-verity ; adb reboot ; adb root ; adb remount ; adb shell mount | grep system"
        return 1
    fi
    echo "  /system записываем — продолжаем."
}

canbus_backup_hl_system_directory() {
    CANBUS_HL_BACKUP="$BACKUP_DIR/VoyahHlCTRL"
    CANBUS_HL_BACKUP_NEW="$CANBUS_HL_BACKUP.new"
    CANBUS_HL_DIRECTORY_STATE=$(adb shell "if [ -d '$CANBUS_HL_SYSTEM_DIR' ]; then echo PRESENT; else echo ABSENT; fi" 2>/dev/null) || return 1
    CANBUS_HL_DIRECTORY_STATE=$(printf '%s' "$CANBUS_HL_DIRECTORY_STATE" | tr -d '\r')

    case "$CANBUS_HL_DIRECTORY_STATE" in
        ABSENT)
            echo "  Системная папка VoyahHlCTRL отсутствует — резервная копия не нужна."
            return 0
            ;;
        PRESENT)
            ;;
        *)
            echo "!!! Не удалось определить состояние $CANBUS_HL_SYSTEM_DIR. Удаление отменено."
            return 1
            ;;
    esac

    if [ -e "$CANBUS_HL_BACKUP" ]; then
        if [ ! -d "$CANBUS_HL_BACKUP" ]; then
            echo "!!! $CANBUS_HL_BACKUP уже существует, но это не папка. Удаление отменено."
            return 1
        fi
        echo "  Резервная копия $CANBUS_HL_BACKUP уже есть — сохраняем исходную копию."
        return 0
    fi

    rm -rf "$CANBUS_HL_BACKUP_NEW"
    if ! adb pull "$CANBUS_HL_SYSTEM_DIR" "$CANBUS_HL_BACKUP_NEW" >/dev/null 2>&1 \
            || [ ! -d "$CANBUS_HL_BACKUP_NEW" ] \
            || ! mv "$CANBUS_HL_BACKUP_NEW" "$CANBUS_HL_BACKUP"; then
        rm -rf "$CANBUS_HL_BACKUP_NEW"
        echo "!!! Не удалось сохранить $CANBUS_HL_SYSTEM_DIR. Удаление отменено."
        return 1
    fi
    echo "  Копия VoyahHlCTRL сохранена в $CANBUS_HL_BACKUP."
}

canbus_remove_hl_service() {
    echo "=== Найден VoyahHlCTRL: удаляем конфликт WRITE_CANBUS ==="
    echo "  Удаляются com.voyah.hl.service и $CANBUS_HL_SYSTEM_DIR; пользовательские данные VoyahHlCTRL не сохраняются."
    BACKUP_DIR="backup"
    if ! mkdir -p "$BACKUP_DIR"; then
        echo "!!! Не удалось подготовить $BACKUP_DIR. Удаление отменено."
        return 1
    fi
    canbus_backup_hl_system_directory || return 1

    # Preparing /system may reboot the device. Check again afterwards: an OTA or a manual
    # cleanup can resolve the conflict while the device is restarting.
    canbus_prepare_writable_system || return 1
    canbus_read_permission_owner || return 1
    canbus_read_hl_service_state || return 1
    if [ "$CANBUS_PERMISSION_OWNER" != "$CANBUS_HL_PACKAGE" ] && [ "$CANBUS_HL_INSTALLED" -eq 0 ]; then
        echo "  После подготовки /system VoyahHlCTRL уже отсутствует — удаление не требуется."
        return 0
    fi

    adb shell "am force-stop '$CANBUS_HL_PACKAGE' >/dev/null 2>&1 || true"
    adb shell "pm uninstall --user 0 '$CANBUS_HL_PACKAGE' >/dev/null 2>&1 || true"
    if ! adb shell "rm -rf '$CANBUS_HL_SYSTEM_DIR' && rm -rf /data/system/package_cache/*"; then
        echo "!!! Не удалось удалить системные файлы VoyahHlCTRL. Установка остановлена."
        return 1
    fi
    echo "  VoyahHlCTRL удалён. Перезагружаем автомобиль для освобождения WRITE_CANBUS..."
    adb reboot || return 1
    canbus_wait_for_boot || return 1

    canbus_read_permission_owner || return 1
    canbus_read_hl_service_state || return 1
    if [ "$CANBUS_PERMISSION_OWNER" = "$CANBUS_HL_PACKAGE" ] || [ "$CANBUS_HL_INSTALLED" -eq 1 ]; then
        echo "!!! VoyahHlCTRL всё ещё владеет WRITE_CANBUS или установлен для пользователя 0. Установка остановлена."
        return 1
    fi
}

canbus_owner_preflight() {
    CANBUS_INSTALL_FLAVOR=${1:-install}
    echo "=== Preflight владельца $CANBUS_PERMISSION_NAME ==="
    canbus_read_permission_owner || return 1
    canbus_read_hl_service_state || return 1

    if [ "$CANBUS_PERMISSION_OWNER" = "$CANBUS_HL_PACKAGE" ] || [ "$CANBUS_HL_INSTALLED" -eq 1 ]; then
        canbus_remove_hl_service || return 1
        canbus_read_permission_owner || return 1
        canbus_read_hl_service_state || return 1
        if [ "$CANBUS_PERMISSION_OWNER" = "$CANBUS_HL_PACKAGE" ] || [ "$CANBUS_HL_INSTALLED" -eq 1 ]; then
            echo "!!! $CANBUS_HL_PACKAGE всё ещё конфликтует с $CANBUS_PERMISSION_NAME. Установка остановлена."
            return 1
        fi
    fi

    if [ "$CANBUS_PERMISSION_PRESENT" -eq 0 ]; then
        echo "  Permission ещё не объявлен — его создаст Native."
        return 0
    fi
    if [ "$CANBUS_PERMISSION_OWNER" = "$CANBUS_NATIVE_PACKAGE" ]; then
        echo "  Permission уже принадлежит $CANBUS_NATIVE_PACKAGE — совместимое обновление."
        return 0
    fi
    if [ -n "$CANBUS_PERMISSION_OWNER" ]; then
        echo "!!! $CANBUS_PERMISSION_NAME уже принадлежит $CANBUS_PERMISSION_OWNER."
    else
        echo "!!! Владелец $CANBUS_PERMISSION_NAME не определён однозначно."
    fi
    echo "    Удалите несовместимый пакет и повторите $CANBUS_INSTALL_FLAVOR install; /system ещё не изменялся."
    return 1
}
