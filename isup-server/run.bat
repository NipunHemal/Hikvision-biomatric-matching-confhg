@echo off
REM Free ports 7660 (ISUP) and 8090 (HTTP API) from any previous instance,
REM so re-running never hits "Address already in use".
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 7660,8090 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }"

REM The ISUP DLLs live in lib/ (jna.library.path); lib is on PATH so their
REM inter-dependencies resolve.
set PATH=%CD%\lib;%PATH%
java -Djna.library.path=lib -cp "out;lib/*;src/main/resources" com.hrm.isup.App
