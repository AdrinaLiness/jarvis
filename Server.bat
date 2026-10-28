@echo off
setlocal
cd /d "%~dp0"

REM ============================================================
REM  J.A.R.V.I.S. launcher
REM  Usage:  Server.bat              — запуск backend
REM          Server.bat --test       — только pytest
REM          Server.bat --test --run — pytest затем backend
REM ============================================================

where python >nul 2>nul
if errorlevel 1 (
    echo [JARVIS] Python 3.10+ не найден в PATH. Установи и повтори.
    pause
    exit /b 1
)

REM Проверка зависимостей и установка при первом запуске
python -c "import aiohttp, psutil" >nul 2>nul
if errorlevel 1 (
    echo [JARVIS] Первый запуск: установка зависимостей...
    python -m pip install --upgrade pip
    python -m pip install -r requirements.txt
    if errorlevel 1 (
        echo [JARVIS] pip install не удался. Проверь интернет / Python.
        pause
        exit /b 1
    )
)

REM Если передан --test — прогоняем pytest и выходим (если без --run)
set RUN_TESTS=0
set RUN_BACKEND=1
echo.%* | findstr /C:"--test" >nul && set RUN_TESTS=1
echo.%* | findstr /C:"--test" >nul && set RUN_BACKEND=0
echo.%* | findstr /C:"--run" >nul && set RUN_BACKEND=1

if "%RUN_TESTS%"=="1" (
    echo [JARVIS] Запуск pytest...
    python -m pytest tests/ -v
    if errorlevel 1 (
        echo [JARVIS] Тесты не прошли.
        if "%RUN_BACKEND%"=="0" pause & exit /b 1
    ) else (
        echo [JARVIS] Все тесты прошли.
    )
)

if "%RUN_BACKEND%"=="0" goto :eof

REM Браузер откроется через 2 секунды, пока backend поднимается.
start "" /b cmd /c "timeout /t 2 >nul & start http://localhost:8000/jarvis.html"

echo [JARVIS] Запуск backend... (Ctrl+C — остановить)
python backend.py --i-understand-the-risks
pause
