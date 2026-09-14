@echo off
rem Shared CANBus permission-owner preflight. This file intentionally contains ASCII only.
setlocal EnableExtensions
set "CANBUS_INSTALL_FLAVOR=%~1"
if "%CANBUS_INSTALL_FLAVOR%"=="" set "CANBUS_INSTALL_FLAVOR=install"
set "CANBUS_PERMISSION=com.qinggan.permission.WRITE_CANBUS"
set "CANBUS_NATIVE_PACKAGE=ru.big.town.anative"
set "CANBUS_HL_PACKAGE=com.voyah.hl.service"
set "CANBUS_HL_SYSTEM_DIR=/system/priv-app/VoyahHlCTRL"
set "CANBUS_HL_REMOVAL_ATTEMPTED=0"

:canbus_preflight
echo === Preflight owner check for %CANBUS_PERMISSION% ===
adb.exe shell dumpsys package permissions >nul 2>nul
if errorlevel 1 (
    echo !!! PackageManager permissions are unavailable. Installation stopped before writing to /system.
    goto :canbus_fail
)
set "CANBUS_PERMISSION_PRESENT=0"
set "CANBUS_PERMISSION_OWNER="
adb.exe shell "dumpsys package permissions | grep -qF 'Permission [%CANBUS_PERMISSION%]'" >nul 2>nul
if not errorlevel 1 set "CANBUS_PERMISSION_PRESENT=1"
if "%CANBUS_PERMISSION_PRESENT%"=="1" for /f "tokens=2 delims==" %%i in ('adb.exe shell "dumpsys package permissions ^| grep -A 32 -F 'Permission [%CANBUS_PERMISSION%]' ^| grep -F 'sourcePackage='" 2^>nul') do if not defined CANBUS_PERMISSION_OWNER set "CANBUS_PERMISSION_OWNER=%%i"

set "CANBUS_HL_INSTALLED=0"
adb.exe shell "pm list packages --user 0 | grep -qx 'package:%CANBUS_HL_PACKAGE%'" >nul 2>nul
if not errorlevel 1 set "CANBUS_HL_INSTALLED=1"
set "CANBUS_HL_CONFLICT=0"
if "%CANBUS_PERMISSION_OWNER%"=="%CANBUS_HL_PACKAGE%" set "CANBUS_HL_CONFLICT=1"
if "%CANBUS_HL_INSTALLED%"=="1" set "CANBUS_HL_CONFLICT=1"
if not "%CANBUS_HL_CONFLICT%"=="1" goto :canbus_validate_owner
if "%CANBUS_HL_REMOVAL_ATTEMPTED%"=="1" (
    echo !!! %CANBUS_HL_PACKAGE% still owns WRITE_CANBUS or is installed for user 0.
    goto :canbus_fail
)
set "CANBUS_HL_REMOVAL_ATTEMPTED=1"
call :canbus_remove_hl_service
if errorlevel 1 goto :canbus_fail
goto :canbus_preflight

:canbus_validate_owner
if "%CANBUS_PERMISSION_PRESENT%"=="0" (
    echo   The permission is not declared yet. Native will create it.
    goto :canbus_ok
)
if "%CANBUS_PERMISSION_OWNER%"=="%CANBUS_NATIVE_PACKAGE%" (
    echo   The permission belongs to %CANBUS_NATIVE_PACKAGE%. This update is compatible.
    goto :canbus_ok
)
if "%CANBUS_PERMISSION_OWNER%"=="" (
    echo !!! The owner of %CANBUS_PERMISSION% is not reported. Installation stopped before writing to /system.
) else (
    echo !!! %CANBUS_PERMISSION% already belongs to %CANBUS_PERMISSION_OWNER%.
)
echo     Remove the incompatible package and repeat %CANBUS_INSTALL_FLAVOR% install. /system is still unchanged.
goto :canbus_fail

:canbus_remove_hl_service
echo === VoyahHlCTRL conflicts with WRITE_CANBUS: removing it ===
echo   %CANBUS_HL_PACKAGE% and %CANBUS_HL_SYSTEM_DIR% will be removed; its user data is not backed up.
if not exist "backup" mkdir "backup"
if not exist "backup" (
    echo !!! Could not prepare backup. Removal cancelled.
    exit /b 1
)
set "CANBUS_HL_DIRECTORY_STATE="
for /f "delims=" %%i in ('adb.exe shell "if [ -d '%CANBUS_HL_SYSTEM_DIR%' ]; then echo PRESENT; else echo ABSENT; fi" 2^>nul') do set "CANBUS_HL_DIRECTORY_STATE=%%i"
if "%CANBUS_HL_DIRECTORY_STATE%"=="PRESENT" call :canbus_backup_hl_directory
if errorlevel 1 exit /b 1
if not "%CANBUS_HL_DIRECTORY_STATE%"=="PRESENT" if not "%CANBUS_HL_DIRECTORY_STATE%"=="ABSENT" (
    echo !!! Could not determine the state of %CANBUS_HL_SYSTEM_DIR%. Removal cancelled.
    exit /b 1
)
if "%CANBUS_HL_DIRECTORY_STATE%"=="ABSENT" echo   VoyahHlCTRL system directory is absent - backup skipped.

call :canbus_prepare_writable_system
if errorlevel 1 exit /b 1
adb.exe shell "am force-stop %CANBUS_HL_PACKAGE% 2>/dev/null || true" >nul 2>nul
adb.exe shell "pm uninstall --user 0 %CANBUS_HL_PACKAGE% 2>/dev/null || true" >nul 2>nul
adb.exe shell "rm -rf %CANBUS_HL_SYSTEM_DIR% && rm -rf /data/system/package_cache/*" >nul 2>nul
if errorlevel 1 (
    echo !!! Could not remove VoyahHlCTRL system files. Installation stopped.
    exit /b 1
)
echo   VoyahHlCTRL removed. Rebooting the vehicle to release WRITE_CANBUS...
adb.exe reboot
if errorlevel 1 (
    echo !!! ADB could not reboot the device after removing VoyahHlCTRL.
    exit /b 1
)
call :canbus_wait_for_boot
exit /b %ERRORLEVEL%

:canbus_backup_hl_directory
if exist "backup\VoyahHlCTRL\NUL" (
    echo   backup\VoyahHlCTRL already exists - keeping the original copy.
    exit /b 0
)
if exist "backup\VoyahHlCTRL" (
    echo !!! backup\VoyahHlCTRL exists but is not a directory. Removal cancelled.
    exit /b 1
)
rmdir /s /q "backup\VoyahHlCTRL.new" >nul 2>nul
adb.exe pull "%CANBUS_HL_SYSTEM_DIR%" "backup\VoyahHlCTRL.new" >nul 2>nul
if errorlevel 1 goto :canbus_backup_hl_failed
if not exist "backup\VoyahHlCTRL.new\NUL" goto :canbus_backup_hl_failed
move /y "backup\VoyahHlCTRL.new" "backup\VoyahHlCTRL" >nul 2>nul
if errorlevel 1 goto :canbus_backup_hl_failed
echo   Copy of VoyahHlCTRL saved to backup\VoyahHlCTRL.
exit /b 0

:canbus_backup_hl_failed
rmdir /s /q "backup\VoyahHlCTRL.new" >nul 2>nul
echo !!! Could not save %CANBUS_HL_SYSTEM_DIR%. Removal cancelled.
exit /b 1

:canbus_prepare_writable_system
echo === Preparing writable /system ^(verity, overlay^) ===
adb.exe disable-verity
call :canbus_ensure_rw
if "%CANBUS_RWSTATE%"=="RW" goto :canbus_sys_rw_ok
echo   /system is read-only. Rebooting once to apply disable-verity...
adb.exe reboot
if errorlevel 1 exit /b 1
call :canbus_wait_for_boot
if errorlevel 1 exit /b 1
call :canbus_ensure_rw
if "%CANBUS_RWSTATE%"=="RW" goto :canbus_sys_rw_ok
echo !!! /system remains read-only. Installation stopped without changing /system.
exit /b 1

:canbus_sys_rw_ok
echo   /system is writable. Continuing.
exit /b 0

:canbus_wait_for_boot
adb.exe wait-for-device
set /a CANBUS_BOOT_WAIT=0
:canbus_wait_boot_loop
adb.exe shell getprop sys.boot_completed 2>nul | findstr /b "1" >nul
if not errorlevel 1 goto :canbus_booted
set /a CANBUS_BOOT_WAIT+=1
if %CANBUS_BOOT_WAIT% GEQ 60 (
    echo !!! The device did not finish booting. Installation stopped.
    exit /b 1
)
timeout /t 5 /nobreak >nul
goto :canbus_wait_boot_loop

:canbus_booted
adb.exe root >nul 2>nul
if errorlevel 1 exit /b 1
adb.exe wait-for-device
adb.exe root >nul 2>nul
exit /b %ERRORLEVEL%

:canbus_ensure_rw
set "CANBUS_RWSTATE=RO"
adb.exe remount >nul 2>nul
adb.exe shell "mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null" >nul 2>nul
echo rwtest> "%TEMP%\_ovw_canbus_rwtest.tmp"
adb.exe push "%TEMP%\_ovw_canbus_rwtest.tmp" /system/.ovw_canbus_rwtest >nul 2>nul
del "%TEMP%\_ovw_canbus_rwtest.tmp" >nul 2>nul
if errorlevel 1 exit /b 0
adb.exe shell "rm -f /system/.ovw_canbus_rwtest" >nul 2>nul
set "CANBUS_RWSTATE=RW"
exit /b 0

:canbus_ok
endlocal & exit /b 0

:canbus_fail
endlocal & exit /b 1
