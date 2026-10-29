@echo off
setlocal enabledelayedexpansion
title CineAgent Installer (Projector)
color 0A

echo ============================================
echo   CineAgent Projector Installer
echo   1.1.8 - normal build, works on all projectors
echo   (on E1 / E1 Pro the platform-signed build installs without these steps)
echo ============================================
echo.

REM ---- Use the adb bundled next to this script, not whatever's on PATH ----
set "ADB=%~dp0adb.exe"
if not exist "%ADB%" (
    echo [ERROR] adb.exe not found next to this installer.
    pause
    exit /b 1
)

REM ---- Locate the APK next to this script ----
set "APK=%~dp0CineAgent-1.1.8-debug-signed.apk"
if not exist "%APK%" (
    echo [ERROR] Could not find CineAgent-1.1.8-debug-signed.apk next to this script.
    echo Expected: !APK!
    pause
    exit /b 1
)

REM ---- Ask for projector IP ----
set /p PROJECTOR_IP="Enter the projector's IP address ONLY, no port (e.g. 192.168.20.145): "
if "%PROJECTOR_IP%"=="" (
    echo [ERROR] No IP entered.
    pause
    exit /b 1
)

echo.
echo [1/7] Connecting to %PROJECTOR_IP%:5555 ...
"%ADB%" connect %PROJECTOR_IP%:5555 >nul 2>nul
set "DEVICE=%PROJECTOR_IP%:5555"

REM Give adb a moment to register the device
timeout /t 2 >nul

"%ADB%" devices | findstr /C:"%DEVICE%" | findstr /C:"device" >nul
if not errorlevel 1 goto :CONNECTED

REM ---- Fallback: newer Android "Wireless debugging" needs pairing first ----
echo.
echo   This projector needs the newer "Wireless debugging" pairing flow
echo   instead of legacy port-5555 debugging.
echo   On the projector: Settings ^> Developer options ^> Wireless debugging
echo   ^> Pair device with pairing code - note the IP:port and 6-digit code
echo   shown there (they expire quickly, so enter them right away).
echo.
set /p DO_PAIR="Try pairing now? (Y/N): "
if /I not "%DO_PAIR%"=="Y" (
    echo [ERROR] Device %DEVICE% not found in "adb devices". Aborting.
    pause
    exit /b 1
)

set /p PAIR_ADDR="Enter the pairing IP:port shown on the projector (e.g. 192.168.23.184:41323): "
set /p PAIR_CODE="Enter the 6-digit pairing code: "

echo.
echo   Pairing with %PAIR_ADDR% ...
"%ADB%" pair %PAIR_ADDR% %PAIR_CODE%
if errorlevel 1 (
    echo [ERROR] Pairing failed. The code/port shown on the projector may have
    echo expired - re-check the screen and re-run this installer.
    pause
    exit /b 1
)

echo.
echo   Paired. Waiting for the projector to appear as a connected device ...
set "DEVICE="
set "RETRY_COUNT=0"
set "DEVLIST=%TEMP%\cineagent_devices_%RANDOM%.txt"

:FIND_DEVICE_LOOP
set /a RETRY_COUNT+=1
timeout /t 2 >nul

"%ADB%" devices > "%DEVLIST%" 2>nul

for /f "tokens=1,2" %%A in (%DEVLIST%) do (
    if "%%B"=="device" (
        echo %%A | findstr /C:"_adb-tls-connect._tcp" >nul && set "DEVICE=%%A"
        echo %%A | findstr /C:"%PROJECTOR_IP%" >nul && if not defined DEVICE set "DEVICE=%%A"
    )
)
if defined DEVICE goto :DEVICE_FOUND

if %RETRY_COUNT% LSS 6 goto :FIND_DEVICE_LOOP

:DEVICE_FOUND
del "%DEVLIST%" >nul 2>nul
if not defined DEVICE (
    echo [ERROR] Paired, but no connected device appeared after waiting. Try again.
    pause
    exit /b 1
)

echo   Connected as: !DEVICE!
set "DEVICE=!DEVICE!"

:CONNECTED

REM ---- Step 2: install APK ----
echo.
echo [2/7] Installing CineAgent APK ...
set "INSTALL_LOG=%TEMP%\cineagent_install_%RANDOM%.txt"
"%ADB%" -s %DEVICE% install -r "%APK%" > "%INSTALL_LOG%" 2>&1
type "%INSTALL_LOG%"
findstr /C:"Success" "%INSTALL_LOG%" >nul
if not errorlevel 1 goto :INSTALLED

REM A CineAgent built on another PC is signed with a different key; Android refuses to update
REM it in place. The only way forward is to uninstall it first (its permissions are re-granted
REM by the steps below).
findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%INSTALL_LOG%" >nul
if errorlevel 1 (
    del "%INSTALL_LOG%" >nul 2>nul
    echo [ERROR] APK install failed.
    pause
    exit /b 1
)
echo.
echo   The CineAgent already on this projector was signed on a different PC,
echo   so it cannot be updated in place. It must be uninstalled first.
echo   (Its permissions and accessibility service are set up again below.)
set /p DO_UNINSTALL="Uninstall the old CineAgent and install this one? (Y/N): "
if /I not "%DO_UNINSTALL%"=="Y" (
    del "%INSTALL_LOG%" >nul 2>nul
    echo Aborted. Nothing was changed.
    pause
    exit /b 1
)
"%ADB%" -s %DEVICE% uninstall com.boat.cineagent
"%ADB%" -s %DEVICE% install "%APK%"
if errorlevel 1 (
    del "%INSTALL_LOG%" >nul 2>nul
    echo [ERROR] APK install failed.
    pause
    exit /b 1
)

:INSTALLED
del "%INSTALL_LOG%" >nul 2>nul

REM ---- Step 3: launch once ----
echo.
echo [3/7] Launching CineAgent once to start the foreground service ...
"%ADB%" -s %DEVICE% shell am start -n com.boat.cineagent/.MainActivity
timeout /t 2 >nul

REM ---- Step 4: appops + notification listener ----
echo.
echo [4/7] Granting required permissions ...
"%ADB%" -s %DEVICE% shell appops set com.boat.cineagent GET_USAGE_STATS allow
"%ADB%" -s %DEVICE% shell appops set com.boat.cineagent SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION allow
"%ADB%" -s %DEVICE% shell cmd notification allow_listener com.boat.cineagent/com.boat.cineagent.media.MediaNotificationListenerService

REM This projector's firmware resets the accessibility service list on every reboot (confirmed
REM live), so the app re-adds itself on every boot via Settings.Secure - but that self-heal needs
REM WRITE_SECURE_SETTINGS, which only ADB can grant. One-time grant; persists across reboots.
"%ADB%" -s %DEVICE% shell pm grant com.boat.cineagent android.permission.WRITE_SECURE_SETTINGS

REM ---- Step 5: accessibility service (append, never overwrite) ----
echo.
echo [5/7] Enabling accessibility service ...
set "EXISTING="
for /f "usebackq delims=" %%A in (`"%ADB%" -s %DEVICE% shell settings get secure enabled_accessibility_services`) do set "EXISTING=%%A"

set "CINEAGENT_SVC=com.boat.cineagent/com.boat.cineagent.accessibility.CineAgentAccessibilityService"

if /I "!EXISTING!"=="" (
    set "NEWLIST=%CINEAGENT_SVC%"
) else if /I "!EXISTING!"=="null" (
    set "NEWLIST=%CINEAGENT_SVC%"
) else (
    echo !EXISTING! | findstr /C:"%CINEAGENT_SVC%" >nul
    if not errorlevel 1 (
        echo   Accessibility service already present, skipping append.
        set "NEWLIST=!EXISTING!"
    ) else (
        set "NEWLIST=!EXISTING!:%CINEAGENT_SVC%"
    )
)

"%ADB%" -s %DEVICE% shell settings put secure enabled_accessibility_services "!NEWLIST!"
"%ADB%" -s %DEVICE% shell settings put secure accessibility_enabled 1

REM ---- Step 6: device name (blank keeps the current one) ----
echo.
set "CURNAME="
for /f "usebackq delims=" %%A in (`"%ADB%" -s %DEVICE% shell settings get global device_name`) do set "CURNAME=%%A"
echo   Current device name: !CURNAME!
set "DEVNAME="
set /p DEVNAME="Enter a new device name to show in discovery, or press Enter to keep it: "
if not "!DEVNAME!"=="" "%ADB%" -s %DEVICE% shell settings put global device_name \"!DEVNAME!\"
if "!DEVNAME!"=="" set "DEVNAME=!CURNAME!"

REM ---- Step 7: confirm and return home ----
echo.
echo [6/7] Verifying CineAgent is running ...
echo.
echo --- Foreground service status ---
"%ADB%" -s %DEVICE% shell dumpsys activity services com.boat.cineagent | findstr /I "isForeground"

echo.
echo [7/7] Returning to Home screen ...
"%ADB%" -s %DEVICE% shell input keyevent KEYCODE_HOME

echo.
echo ============================================
echo   CineAgent installation complete on %DEVICE%
echo   Device name: !DEVNAME!
echo ============================================
echo.
echo NOTE: If this projector is low on RAM (roughly under 1GB total),
echo check with the firmware owner whether CineAgent needs to be
echo allowlisted in the vendor's low-RAM background-kill controller.
echo.
pause
endlocal
