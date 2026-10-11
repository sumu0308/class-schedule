@echo off
@rem ============================================================
@rem  LAN file server for getting the APK onto your phone.
@rem  Double-click this file, then open the printed URL in the
@rem  phone browser. Press Ctrl+C in this window to stop.
@rem
@rem  NOTE: keep this file ASCII-only. cmd.exe parses batch files
@rem  byte by byte, so UTF-8 comments can break execution.
@rem ============================================================
setlocal
cd /d "%~dp0"

where node >NUL 2>&1
if errorlevel 1 (
  echo [ERROR] Node.js not found in PATH. Install it first, or run
  echo         the server from a machine that has Node.js.
  pause
  exit /b 1
)

echo ============================================================
echo   Serving APK folder over local network
echo   Folder: app\build\outputs\apk\release
echo.
echo   Make sure the phone and this PC are on the SAME network
echo   (easiest: turn on the phone hotspot and connect this PC to it).
echo   Then open the URL printed below in the phone browser.
echo ============================================================
echo.

node tools\wifi-share.mjs --dir app\build\outputs\apk\release --port 8001

echo.
echo Server stopped.
pause
