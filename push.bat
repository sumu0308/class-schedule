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
rem QUIRK 3: behind a proxy that only allows a domain allowlist, github.com:443 is
rem   refused outright ("CONNECT tunnel failed, response 502") while api.github.com
rem   keeps working. Retrying git push can never help in that case, so after the 8
rem   attempts this script falls back to tools\push-auto.mjs, which rebuilds the
rem   commit through the GitHub Git Data API and only moves the remote ref when
rem   every SHA matches the local one (so it cannot cause a divergence).
rem
rem !!! KEEP THIS FILE PURE ASCII !!!
rem cmd.exe parses .bat/.cmd byte by byte; non-ASCII comments desynchronise that
rem parsing and the script starts executing its own comments as commands. This
rem already broke gradlew.bat once - see the README "pitfalls" table.
rem
rem USAGE
rem   push.bat                 push to main
rem   push.bat -m "message"    commit all changes first, then push
rem   set FORCE=1 & push.bat   force-with-lease (only after rewriting history)
rem
rem NOTE ON CALLING THIS FROM POWERSHELL
rem   Passing the message through a nested cmd /c "..." string mangles the
rem   quotes: the commit ends up with a message of just a double quote.
rem   That really happened to three commits here. Call it with an argument
rem   array instead, so PowerShell does the quoting:
rem     & cmd.exe /c .\push.bat -m 'a message with spaces'
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
rem Set FORCE=1 to replace rewritten history.
rem
rem Why the lease value is spelled out instead of just --force-with-lease:
rem a bare --force-with-lease compares against the remote-tracking ref, and this
rem clone never fetched, so it always answered "stale info". Reading the remote
rem SHA with ls-remote and passing it as the expectation is explicit and does not
rem depend on tracking refs. It still refuses if the remote moved in between.
set "FORCEARG="
if defined FORCE (
  for /f "usebackq delims=" %%R in (`git ls-remote origin refs/heads/main`) do set "LEASESHA=%%R"
  for /f "tokens=1" %%S in ("!LEASESHA!") do set "LEASESHA=%%S"
  set "FORCEARG=--force-with-lease=main:!LEASESHA!"
)

:attempt
set /a TRY+=1
echo [2/3] Push attempt !TRY! of 8 ... !FORCEARG!
git -c credential.helper= -c http.sslBackend=openssl push !FORCEARG! "!URL!" main:main
if not errorlevel 1 (
  set "OK=1"
  goto done
)
if !TRY! lss 8 (
  echo       failed, retrying in 3 s ...
  rem Do NOT use "timeout /t 3": timeout.exe refuses to run whenever stdin is
  rem redirected ("ERROR: Input redirection is not supported"), and redirecting
  rem stdin from nul does not help either - it is still a redirection. Measured
  rem here: the 3 s pause silently became an instant retry, so all 8 attempts
  rem fired within a second. ping's delay works regardless of stdin.
  rem -n 4 = 3 intervals of about 1 s each.
  ping -n 4 127.0.0.1 >nul 2>&1
  goto attempt
)

:done
if defined OK (
  echo [3/3] Pushed. Verifying remote against local ...
  node tools\verify-remote.mjs
) else (
  echo [3/3] All 8 attempts failed. Falling back to the GitHub API ...
  node tools\push-auto.mjs --api-only
  if errorlevel 1 (
    echo [3/3] API fallback failed too. The commit is safe locally - try again later.
    exit /b 1
  )
)
endlocal
