@echo off
@rem ============================================================
@rem  One-click build -- produces app\build\outputs\apk\release\app-release.apk
@rem  Double-click this file; Android Studio is not needed.
@rem ============================================================
setlocal

cd /d "%~dp0"

@rem JDK: uses the system one by default; edit this line if yours is elsewhere
if not defined JAVA_HOME set "JAVA_HOME=C:\Path\To\Your\JDK-21"

@rem Keep the Gradle cache inside the project (no C: usage, easy to move)
set "GRADLE_USER_HOME=%~dp0.gradle-home"

@rem Android SDK also lives inside the project
set "ANDROID_HOME=%~dp0.android-sdk"
set "ANDROID_SDK_ROOT=%~dp0.android-sdk"

echo ============================================================
echo   正在打包课表 App
echo   JDK    : %JAVA_HOME%
echo   SDK    : %ANDROID_HOME%
echo   Gradle : %GRADLE_USER_HOME%
echo ============================================================
echo.

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [错误] 找不到 Java：%JAVA_HOME%\bin\java.exe
  echo        请编辑本文件，把 JAVA_HOME 改成你电脑上 JDK 的实际路径。
  echo.
  pause
  exit /b 1
)

if not exist "gradlew.bat" (
  echo [错误] 找不到 gradlew.bat，请确认本文件在项目根目录下运行。
  echo.
  pause
  exit /b 1
)

if "%~1"=="" (
  @rem default: release build. Use "build.bat debug" for a debug build.
  set "TASK=assembleRelease"
) else if /i "%~1"=="debug" (
  set "TASK=assembleDebug"
) else if /i "%~1"=="clean" (
  set "TASK=clean assembleRelease"
) else if /i "%~1"=="test" (
  set "TASK=test"
) else (
  set "TASK=%~1"
)

echo 执行任务：%TASK%
echo （首次运行需要下载依赖，视网络情况约 3-10 分钟）
echo.

call gradlew.bat %TASK% --no-daemon
set "RESULT=%ERRORLEVEL%"

echo.
if "%RESULT%"=="0" (
  echo ============================================================
  echo   打包成功
  echo ============================================================
  for %%f in ("app\build\outputs\apk\release\app-release.apk") do (
    if exist "%%f" echo   APK: %%~ff  (%%~zf 字节)
  )
  for %%f in ("app\build\outputs\apk\debug\app-debug.apk") do (
    if exist "%%f" echo   APK: %%~ff  (%%~zf 字节)
  )
  echo.
  echo   下一步：运行 tools\serve-apk.bat 把 APK 传到手机
) else (
  echo ============================================================
  echo   打包失败，退出码 %RESULT%
  echo ============================================================
  echo   把上面的报错内容整段发给我，我来定位。
)
echo.
pause
