@echo off
chcp 65001 >nul
echo ============================================
echo   Сборка J.A.R.V.I.S. (Go)
echo ============================================
where go >nul 2>nul
if errorlevel 1 (
    echo [!] Go не найден. Установи с https://go.dev/dl/ и перезапусти консоль.
    pause
    exit /b 1
)
echo [*] Собираю jarvis.exe (GUI-окно, WebView2)...
go build -ldflags "-s -w -H windowsgui" -o jarvis.exe .
if errorlevel 1 (
    echo [!] Ошибка сборки GUI-версии.
    pause
    exit /b 1
)
echo [*] Собираю jarvis-server.exe (серверный режим, без GUI)...
go build -tags headless -ldflags "-s -w" -o jarvis-server.exe .
echo.
echo [+] Готово:
dir /b jarvis*.exe
echo.
echo Положи рядом со jarvis.exe файл shortcuts.json из этой папки.
pause
