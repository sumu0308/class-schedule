@echo off
rem push.bat - push this project to GitHub, working around two local quirks.
rem
rem QUIRK 1: connecting to github.com:443 is unreliable on this machine
rem   (measured: roughly 1 in 3 attempts times out; a success takes 1-10 s).
rem   So this script retries up to 8 times instead of failing on the first try.
rem
rem QUIRK 2: git cannot use any credential helper here. Every helper is launched
rem   through git's bundled sh.exe, and sh.exe crashes on this machine with
rem   "couldn't create signal pipe, Win32 error 5". Instead of a helper, this
rem   script puts the gh CLI token into the push URL and clears the helper chain
rem   with -c credential.helper= (the token is never written to .git/config).
rem
rem !!! KEEP THIS FILE PURE ASCII !!!
rem cmd.exe parses .bat/.cmd byte by byte; non-ASCII comments desynchronise that
rem parsing and the script starts executing its own comments as commands. This
rem already broke gradlew.bat once - see the README "pitfalls" table.
rem
rem USAGE
rem   push.bat                 push to main
rem   push.bat -m "message"    commit all changes first, then push
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "MSG="
if /i "%~1"=="-m" set "MSG=%~2"

if defined MSG (
  echo [1/3] Committing: %MSG%
  git add -A
  git commit -m "%MSG%"
  if errorlevel 1 echo       nothing to commit, continuing
) else (
  echo [1/3] No -m given, pushing what is already committed
)

for /f "usebackq delims=" %%T in (`gh auth token`) do set "TOK=%%T"
if "!TOK!"=="" (
  echo ERROR: could not get a token from "gh auth token".
  echo        Run "gh auth login" first.
  exit /b 1
)

set "URL=https://x-access-token:!TOK!@github.com/sumu0308/class-schedule.git"
set "TRY=0"
set "OK="

:attempt
set /a TRY+=1
echo [2/3] Push attempt !TRY! of 8 ...
git -c credential.helper= -c http.sslBackend=openssl push "!URL!" main:main
if not errorlevel 1 (
  set "OK=1"
  goto done
)
if !TRY! lss 8 (
  echo       failed, retrying in 3 s ...
  timeout /t 3 /nobreak >nul
  goto attempt
)

:done
if defined OK (
  echo [3/3] Pushed. Verifying remote against local ...
  node tools\verify-remote.mjs
) else (
  echo [3/3] All 8 attempts failed. The commit is safe locally - try again later.
  exit /b 1
)
endlocal
