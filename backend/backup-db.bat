@echo off
REM ════════════════════════════════════════════════════════════════════
REM  backup-db.bat — Backs up the PostgreSQL database to a .sql file
REM  Run this BEFORE: docker compose down -v  (or anytime for safety)
REM
REM  Usage: Double-click this file OR run in terminal:
REM         backup-db.bat
REM ════════════════════════════════════════════════════════════════════

SET BACKUP_DIR=backups
SET TIMESTAMP=%DATE:~10,4%-%DATE:~4,2%-%DATE:~7,2%_%TIME:~0,2%-%TIME:~3,2%-%TIME:~6,2%
SET TIMESTAMP=%TIMESTAMP: =0%
SET BACKUP_FILE=%BACKUP_DIR%\teaching_assistant_%TIMESTAMP%.sql

REM Create backups folder if it doesn't exist
IF NOT EXIST %BACKUP_DIR% mkdir %BACKUP_DIR%

echo.
echo ╔══════════════════════════════════════════════════╗
echo ║     Teaching Assistant — Database Backup         ║
echo ╚══════════════════════════════════════════════════╝
echo.
echo [1/2] Dumping database to: %BACKUP_FILE%
echo       (Container must be running: docker compose up -d)
echo.

docker exec teaching_assistant_db pg_dump -U postgres teaching_assistant > %BACKUP_FILE%

IF %ERRORLEVEL% EQU 0 (
    echo [2/2] ✅ Backup successful!
    echo.
    echo       File : %BACKUP_FILE%
    for %%A in (%BACKUP_FILE%) do echo       Size : %%~zA bytes
    echo.
    echo  To restore this backup later, run:
    echo       restore-db.bat %BACKUP_FILE%
) ELSE (
    echo [2/2] ❌ Backup FAILED. Is the container running?
    echo       Run: docker compose up -d
    del %BACKUP_FILE% 2>nul
)

echo.
pause
