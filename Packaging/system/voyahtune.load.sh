#!/system/bin/sh
# voyahtune.load.sh — тело Frida-оркестратора, запускается voyahtune_load из voyahtune.load.rc.
# Извлечено из старого init.logcat.sh (монки-патча штатного логирования): здесь остаётся только
# рут-обвязка, logcat своим порядком поднимает штатный /system/etc/init.logcat.sh (не тронут).
LOG_TAG="vt_load_sh"
logi () { /system/bin/log -t $LOG_TAG -p i "$@"; }

# /data/local/bin — доступно рано при загрузке; /sdcard монтируется позже, там load.bin держать нельзя.
# Восстанавливаем права до инъекции: агент Frida работает под UID целевого процесса, поэтому
# читаемый файл бесполезен без прохода по родительским каталогам. Режим 2700, унаследованный от
# прошивки или стороннего инструмента, ломает клавиатурные агенты при конфиге 0644.
# chmod НЕ рекурсивный: конфиги агентов остаются 0644, root-состояние воркеров — приватным.
# Пять октальных цифр явно снимают унаследованные setgid/sticky биты с каталогов.
# ВАЖНО: фатальна только установка прав. chmod/chown и так возвращают ненулевой код при сбое, а
# сверка ниже — только диагностика: toybox рендерит %a как 751 или 0751 в зависимости от сборки,
# и строгое сравнение из-за одной разницы в форматировании не должно мешать загрузчику стартовать.
prepare_data_directories() {
    mkdir -p /data/local/bin /data/local/tmp &&
    chown 0:0 /data/local /data/local/bin &&
    chown 2000:2000 /data/local/tmp &&
    chmod 00751 /data/local &&
    chmod 00755 /data/local/bin &&
    chmod 00771 /data/local/tmp
}
if ! prepare_data_directories; then
    logi "cannot set /data/local, bin and tmp permissions"
    exit 1
fi

check_data_dir() {
    CD_GOT=$(stat -c %a:%u:%g "$1" 2>/dev/null)
    [ "$CD_GOT" = "$2" ] || logi "unexpected mode $1: got [$CD_GOT] want [$2]"
}
check_data_dir /data/local 751:0:0
check_data_dir /data/local/bin 755:0:0
check_data_dir /data/local/tmp 771:2000:2000

logi "starting load.bin watchdog"
exec /system/bin/sh /data/local/bin/load.bin >> /data/local/tmp/voyahtune_load.txt 2>&1
