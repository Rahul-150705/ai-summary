@echo off
REM ════════════════════════════════════════════════════════════════════
REM  restore-db.bat — Restores a PostgreSQL backup made by backup-db.bat
REM
REM  Usage: restore-db.bat backups\teaching_assistant_2024-02-23_14-30-00.sql
REM ════════════════════════════════════════════════════════════════════

SET BACKUP_FILE=%1

IF "%BACKUP_FILE%"=="" (
    echo.
    echo ❌ Please provide a backup file path.
    echo    Usage: restore-db.bat backups\teaching_assistant_2024-02-23.sql
    echo.
    echo    Available backups:
    dir /b backups\*.sql 2>nul || echo    (no backups found in backups\ folder)
    echo.
    pause
    exit /b 1
)

IF NOT EXIST %BACKUP_FILE% (
    echo.
    echo ❌ Backup file not found: %BACKUP_FILE%
    echo.
    pause
    exit /b 1
)

echo.
echo ╔══════════════════════════════════════════════════╗
echo ║     Teaching Assistant — Database Restore        ║
echo ╚══════════════════════════════════════════════════╝
echo.
echo  File to restore: %BACKUP_FILE%
echo.
echo  ⚠️  WARNING: This will OVERWRITE your current database!
echo     All existing data will be replaced with the backup.
echo.
set /p CONFIRM="  Type YES to continue: "
IF /I NOT "%CONFIRM%"=="YES" (
    echo.
    echo  Restore cancelled.
    pause
    exit /b 0
)

echo.
echo [1/3] Dropping existing database...
docker exec teaching_assistant_db psql -U postgres -c "DROP DATABASE IF EXISTS teaching_assistant;"

echo [2/3] Creating fresh database...
docker exec teaching_assistant_db psql -U postgres -c "CREATE DATABASE teaching_assistant;"
docker exec teaching_assistant_db psql -U postgres -d teaching_assistant -c "CREATE EXTENSION IF NOT EXISTS vector;"

echo [3/3] Restoring from backup...
docker exec -i teaching_assistant_db psql -U postgres teaching_assistant < %BACKUP_FILE%

IF %ERRORLEVEL% EQU 0 (
    echo.
    echo ✅ Restore successful! Your data is back.
) ELSE (
    echo.
    echo ❌ Restore failed. Check the backup file is valid.
)

echo.
pause
