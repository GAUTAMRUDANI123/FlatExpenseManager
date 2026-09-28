@echo off
REM Daily backup, run by Task Scheduler. Kept as a .cmd because Task Scheduler
REM handles a batch file far more predictably than a node command line.
cd /d "%~dp0\.."
"C:\Users\Admin\android-dev\node\node.exe" scripts\backup.js >> "%~dp0..\backups\backup.log" 2>&1
