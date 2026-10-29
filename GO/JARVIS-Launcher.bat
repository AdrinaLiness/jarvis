@echo off
setlocal EnableExtensions
chcp 65001 >nul
title J.A.R.V.I.S. Launcher
set "JARVIS_BAT=%~f0"
set "JARVIS_PS1=%TEMP%\jarvis_launcher_payload.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $f=[IO.File]::ReadAllText($env:JARVIS_BAT,[Text.Encoding]::UTF8); $m=[regex]::Match($f,'(?s)##### PS-PAYLOAD #####\r?\n(.*)'); if(-not $m.Success){throw 'payload marker not found'}; [IO.File]::WriteAllText($env:JARVIS_PS1,$m.Groups[1].Value,(New-Object System.Text.UTF8Encoding($true)))"
if errorlevel 1 (
  echo [!] Launcher error: cannot extract payload.
  pause
  exit /b 3
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%JARVIS_PS1%" -BatPath "%JARVIS_BAT%" %*
set "EC=%ERRORLEVEL%"
del "%JARVIS_PS1%" >nul 2>nul
pause
exit /b %EC%
##### PS-PAYLOAD #####
# ============================================================
#  J.A.R.V.I.S. Launcher v1.0 — payload (PowerShell)
#  Один файл ставит любую версию JARVIS с GitHub:
#  проверяет программы, качает файлы с контролем целостности,
#  складывает в отдельную папку и подсказывает, как запустить.
#  (c) AdrinaLiness - MIT. Репозиторий: AdrinaLiness/jarvis
# ============================================================
param(
    [string]$BatPath = '',
    [string]$Variant = '',
    [string]$Dir = '',
    [switch]$Force,
    [switch]$SkipDeps
)

$ErrorActionPreference = 'Stop'

# ---------------- константы ----------------
$RepoOwner   = 'AdrinaLiness'
$RepoName    = 'jarvis'
$Branch      = 'main'
$RawBase     = "https://raw.githubusercontent.com/$RepoOwner/$RepoName/$Branch"
$ApiBase     = "https://api.github.com/repos/$RepoOwner/$RepoName"
$RepoUrl     = "https://github.com/$RepoOwner/$RepoName"
$LauncherVer = '1.0'
$UA          = @{ 'User-Agent' = 'JARVIS-Launcher' }

# ---------------- консоль ----------------
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
try { $Host.UI.RawUI.WindowTitle = 'J.A.R.V.I.S. - Launcher' } catch {}
$ProgressPreference = 'SilentlyContinue'   # иначе Invoke-WebRequest на PS 5.1 рисует прогресс и качает в разы медленнее

# ---------------- вывод ----------------
function Info([string]$m) { Write-Host $m }
function Ok([string]$m)   { Write-Host $m -ForegroundColor Green }
function Warn([string]$m) { Write-Host $m -ForegroundColor Yellow }
function Err([string]$m)  { Write-Host $m -ForegroundColor Red }
function Hr               { Write-Host ('-' * 66) -ForegroundColor DarkGray }

function Human([long]$n) {
    if ($n -ge 1MB) { return ('{0:N1} MB' -f ($n / 1MB)) }
    if ($n -ge 1KB) { return ('{0:N0} KB' -f ($n / 1KB)) }
    return ("$n B")
}

function Throw-Cancel { throw [System.OperationCanceledException]::new('Отменено пользователем') }

# ---------------- какие версии бывают ----------------
# p = путь в репозитории; d = куда положить в папке установки
$VARIANTS = @(
    @{
        Key = 'EXE'; Folder = 'JARVIS-EXE'
        Title = 'EXE - готовая сборка для Windows (рекомендую новичкам)'
        Pros = @(
            'Ничего не нужно устанавливать: даже Python не требуется',
            'Запуск двойным кликом по jarvis.exe - работает сразу',
            'Все 59 команд и ярлыки, тот же интерфейс, что в полной версии'
        )
        Cons = @(
            'Только Windows 10/11 (нужен WebView2 - обычно уже встроен)',
            'Исходники изменить нельзя: это готовые exe-файлы',
            'Самый большой объём скачивания (~12 МБ)'
        )
        Need = 'exe'
        Files = @(
            @{ p = 'EXE/jarvis.exe';        d = 'jarvis.exe' },
            @{ p = 'EXE/jarvis-server.exe'; d = 'jarvis-server.exe' },
            @{ p = 'EXE/shortcuts.json';    d = 'shortcuts.json' },
            @{ p = 'EXE/README.txt';        d = 'README.txt' }
        )
    },
    @{
        Key = 'HTML'; Folder = 'JARVIS-HTML'
        Title = 'HTML - веб-версия: Python-сервер + интерфейс в браузере'
        Pros = @(
            'Полные исходники на Python - можно дорабатывать и менять',
            'Кроссплатформенная: Windows, Linux и macOS',
            'ИИ-модуль со своим API-ключом и самый полный набор команд'
        )
        Cons = @(
            'Нужен Python 3.10+ (лаунчер проверит и поможет установить)',
            'При первом запуске качаются pip-пакеты (нужен интернет)'
        )
        Need = 'python'
        Files = @(
            @{ p = 'HTML/backend.py';      d = 'backend.py' },
            @{ p = 'HTML/jarvis.html';     d = 'jarvis.html' },
            @{ p = 'HTML/shortcuts.json';  d = 'shortcuts.json' },
            @{ p = 'HTML/requirements.txt'; d = 'requirements.txt' },
            @{ p = 'HTML/Server.bat';      d = 'Server.bat' }
        )
    },
    @{
        Key = 'GO'; Folder = 'JARVIS-GO'
        Title = 'GO - исходники на Go: один быстрый бинарник'
        Pros = @(
            'Python не нужен: всё ядро на Go, минимум памяти',
            'Тот же интерфейс - веб-страница вшивается в бинарник'
        )
        Cons = @(
            'Нужен Go 1.21+ для сборки (лаунчер проверит и предложит)',
            'Для GUI-окна нужен ещё C-компилятор (gcc); без него соберётся серверный вариант',
            'Это исходники: после скачивания программу надо собрать'
        )
        Need = 'go'
        Files = @(
            @{ p = 'GO/main.go';              d = 'main.go' },
            @{ p = 'GO/commands.go';          d = 'commands.go' },
            @{ p = 'GO/gui_server.go';        d = 'gui_server.go' },
            @{ p = 'GO/gui_webview.go';       d = 'gui_webview.go' },
            @{ p = 'GO/go.mod';               d = 'go.mod' },
            @{ p = 'GO/go.sum';               d = 'go.sum' },
            @{ p = 'GO/shortcuts.json';       d = 'shortcuts.json' },
            @{ p = 'GO/jarvis.conf.example';  d = 'jarvis.conf.example' },
            @{ p = 'GO/build_exe.bat';        d = 'build_exe.bat' },
            @{ p = 'GO/web/jarvis.html';      d = 'web/jarvis.html' }
        )
    },
    @{
        Key = 'ANDROID'; Folder = 'JARVIS-ANDROID'
        Title = 'ANDROID - версия для телефона (APK)'
        Pros = @(
            'JARVIS всегда с собой: голос Google, громкость, фонарик, Wi-Fi, ярлыки',
            'Встроенный ИИ-мозг (свой ключ через OpenAI-совместимый API)'
        )
        Cons = @(
            'Ставится на телефон (Android 8.0+), а не на компьютер',
            'APK надо передать на телефон и разрешить установку из неизвестных источников'
        )
        Need = 'apk'
        Files = @(
            @{ p = 'ANDROID/JARVIS-v1.2.apk'; d = 'JARVIS-v1.2.apk' },
            @{ p = 'ANDROID/README.txt';      d = 'README.txt' }
        )
    }
)

# ---------------- меню выбора ----------------
function Show-Menu {
    Info ''
    Hr
    Write-Host '   Какого J.A.R.V.I.S. установить?' -ForegroundColor Cyan
    Info '   Лаунчер проверит нужные программы, скачает файлы с GitHub'
    Info '   в отдельную папку и подскажет, как запустить.'
    Info ''
    foreach ($v in $VARIANTS) {
        $num = [array]::IndexOf($VARIANTS, $v) + 1
        Write-Host ("  [{0}] {1}" -f $num, $v.Title) -ForegroundColor Yellow
        foreach ($p in $v.Pros) { Write-Host "      + $p" -ForegroundColor Green }
        foreach ($c in $v.Cons) { Write-Host "      - $c" -ForegroundColor Red }
        Info ''
    }
    Write-Host '  [0] Выход' -ForegroundColor Yellow
    Hr
}

function Choose-Variant {
    while ($true) {
        Show-Menu
        $a = Read-Host '  Введи номер и нажми Enter'
        if ($a -eq '0') { Throw-Cancel }
        if ($a -match '^[1-4]$') { return $VARIANTS[[int]$a - 1] }
        Warn '  Нет такого номера. Попробуй ещё раз.'
    }
}

# ---------------- проверка целостности ----------------
# git-blob SHA1 = SHA1("blob <размер>\0" + содержимое) - ровно то,
# что GitHub показывает в дереве репозитория, так что скачанный
# файл сверяется с оригиналом байт-в-байт.
function Get-GitBlobSha1([string]$Path) {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $sha = [System.Security.Cryptography.SHA1]::Create()
    try {
        $header = [System.Text.Encoding]::ASCII.GetBytes('blob ' + $bytes.Length + [char]0)
        $buf = New-Object byte[] ($header.Length + $bytes.Length)
        [Array]::Copy($header, 0, $buf, 0, $header.Length)
        [Array]::Copy($bytes, 0, $buf, $header.Length, $bytes.Length)
        $h = $sha.ComputeHash($buf)
        return ((($h | ForEach-Object { $_.ToString('x2') }) -join '').ToLower())
    } finally { $sha.Dispose() }
}

function Get-TreeMap {
    try {
        $url = "${ApiBase}/git/trees/${Branch}?recursive=1"
        $r = Invoke-RestMethod -Uri $url -Headers $UA -TimeoutSec 30
        $map = @{}
        foreach ($e in $r.tree) {
            if ($e.type -eq 'blob') { $map[$e.path] = @{ sha = $e.sha; size = [long]$e.size } }
        }
        return $map
    } catch {
        Warn ("    (контрольные суммы недоступны, причина: " + $_.Exception.Message + ")")
        return $null
    }
}

function Get-FileVerified([string]$rel, [string]$dest, [long]$expectSize, [string]$expectSha) {
    for ($try = 1; $try -le 3; $try++) {
        try {
            Invoke-WebRequest -Uri "$RawBase/$rel" -OutFile $dest -UseBasicParsing -TimeoutSec 600 -Headers $UA
            if (-not (Test-Path -LiteralPath $dest)) { throw 'файл не создан' }
            $len = (Get-Item -LiteralPath $dest).Length
            if ($expectSize -gt 0 -and $len -ne $expectSize) { throw "размер не совпал ($len вместо $expectSize)" }
            if ($expectSha) {
                $sha = Get-GitBlobSha1 $dest
                if ($sha -ne $expectSha.ToLower()) { throw 'контрольная сумма не совпала' }
            }
            return $true
        } catch {
            Warn ("    попытка {0}/3 не удалась: {1}" -f $try, $_.Exception.Message)
            if ($try -lt 3) { Start-Sleep -Seconds 2 }
        }
    }
    return $false
}

# ---------------- поиск программ ----------------
function Get-PyVersion([string]$exe) {
    try {
        $out = (& $exe --version 2>&1 | Out-String).Trim()
        if ($out -match 'Python\s+(\d+)\.(\d+)') {
            return @{ Major = [int]$Matches[1]; Minor = [int]$Matches[2] }
        }
    } catch {}
    return $null
}

function Find-Python {
    foreach ($name in @('python', 'python3')) {
        $c = Get-Command $name -ErrorAction SilentlyContinue
        if ($c) {
            $v = Get-PyVersion $c.Source
            if ($v) { return @{ Exe = $c.Source; Label = $name; Ver = $v } }
        }
    }
    $py = Get-Command 'py' -ErrorAction SilentlyContinue
    if ($py) {
        try {
            $exe = (& py -3 -c "import sys; print(sys.executable)" 2>$null | Out-String).Trim()
            if ($exe -and (Test-Path $exe)) {
                $v = Get-PyVersion $exe
                if ($v) { return @{ Exe = $exe; Label = 'py -3'; Ver = $v } }
            }
        } catch {}
    }
    $cands = @(
        "$env:LOCALAPPDATA\Programs\Python\Python313\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python311\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python310\python.exe",
        'C:\Program Files\Python313\python.exe',
        'C:\Program Files\Python312\python.exe',
        'C:\Program Files\Python311\python.exe',
        'C:\Program Files\Python310\python.exe'
    )
    foreach ($p in $cands) {
        if (Test-Path $p) {
            $v = Get-PyVersion $p
            if ($v) { return @{ Exe = $p; Label = $p; Ver = $v } }
        }
    }
    return $null
}

function Find-Go {
    $c = Get-Command 'go' -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    if (Test-Path 'C:\Program Files\Go\bin\go.exe') { return 'C:\Program Files\Go\bin\go.exe' }
    return $null
}

function Get-GoVersion([string]$exe) {
    try {
        $out = (& $exe version 2>&1 | Out-String).Trim()
        if ($out -match 'go(\d+)\.(\d+)') {
            return @{ Major = [int]$Matches[1]; Minor = [int]$Matches[2] }
        }
    } catch {}
    return $null
}

function Test-Gcc { return [bool](Get-Command 'gcc' -ErrorAction SilentlyContinue) }

function Test-WebView2 {
    $keys = @(
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}',
        'HKLM:\SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}',
        'HKCU:\SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}'
    )
    foreach ($k in $keys) {
        try {
            $p = Get-ItemProperty -Path $k -ErrorAction Stop
            if ($p.pv) { return $true }
        } catch {}
    }
    return $false
}

function Install-ViaWinget([string]$id, [string]$what) {
    if (-not (Get-Command 'winget' -ErrorAction SilentlyContinue)) {
        Warn '  winget на этой системе не найден.'
        return $false
    }
    Info "  Ставлю $what через winget (может занять пару минут)..."
    try {
        & winget install -e --id $id --accept-source-agreements --accept-package-agreements
        return ($LASTEXITCODE -eq 0)
    } catch {
        Warn "  winget не справился: $($_.Exception.Message)"
        return $false
    }
}

# возвращает 'retry' | 'skip' | 'cancel'
function Offer-Fix([string]$prog, [string]$wingetId, [string]$url) {
    while ($true) {
        Info ''
        Warn "  [!] $prog не найден. Что делаем?"
        Info '    [1] Установить автоматически (через winget)'
        Info "    [2] Открыть страницу загрузки ($url)"
        Info '    [3] Продолжить без него (файлы скачаются, но программа не запустится)'
        Info '    [0] Отмена'
        $a = Read-Host '  Твой выбор'
        switch ($a) {
            '1' {
                if (Install-ViaWinget $wingetId $prog) {
                    Ok "  [+] $prog установлен."
                    return 'retry'
                } else {
                    Warn '  Автоустановка не получилась. Попробуй вариант [2].'
                }
            }
            '2' {
                try { Start-Process $url } catch {}
                Info ''
                $b = Read-Host '  Когда установишь - нажми Enter (я проверю снова). Введи s - продолжить без программы, 0 - отмена'
                if ($b -eq '') { return 'retry' }
                if ($b -eq 's') { return 'skip' }
                if ($b -eq '0') { return 'cancel' }
            }
            '3' { return 'skip' }
            '0' { return 'cancel' }
            default { Warn '  Нет такого варианта.' }
        }
    }
}

function Ensure-Python {
    while ($true) {
        $py = Find-Python
        if ($py) {
            $num = $py.Ver.Major * 100 + $py.Ver.Minor
            if ($num -ge 310) {
                Ok ("  [+] Python найден: {0} ({1}.{2})" -f $py.Label, $py.Ver.Major, $py.Ver.Minor)
                return $py
            }
            Warn ("  [!] Найден Python {0}.{1}, но нужен 3.10 или новее." -f $py.Ver.Major, $py.Ver.Minor)
        } else {
            Warn '  [!] Python не найден.'
        }
        if ($IsCli) {
            Warn '  Тихий режим: продолжаю без него. Файлы скачаются, зависимости поставит Server.bat при первом запуске.'
            return $null
        }
        $r = Offer-Fix 'Python 3.10+' 'Python.Python.3.12' 'https://www.python.org/downloads/'
        if ($r -eq 'retry') { continue }
        if ($r -eq 'skip') { return $null }
        Throw-Cancel
    }
}

function Ensure-Go {
    while ($true) {
        $g = Find-Go
        if ($g) {
            $v = Get-GoVersion $g
            if ($v -and ($v.Major * 100 + $v.Minor) -ge 121) {
                Ok ("  [+] Go найден: $g (1.{0})" -f $v.Minor)
                return $g
            }
            if ($v) { Warn ("  [!] Найден Go 1.{0}, но нужен 1.21+." -f $v.Minor) }
        } else {
            Warn '  [!] Go не найден.'
        }
        if ($IsCli) {
            Warn '  Тихий режим: продолжаю без него. Исходники скачаются, собрать можно позже через build_exe.bat.'
            return $null
        }
        $r = Offer-Fix 'Go 1.21+' 'GoLang.Go' 'https://go.dev/dl/'
        if ($r -eq 'retry') { continue }
        if ($r -eq 'skip') { return $null }
        Throw-Cancel
    }
}

# ---------------- папка установки ----------------
function Resolve-DestFolder($variant) {
    $base = $null
    if ($Dir) { $base = $Dir }
    elseif ($BatPath) { $base = Split-Path -Parent $BatPath }
    else { $base = (Get-Location).Path }
    $name = $variant.Folder
    while ($true) {
        $full = Join-Path $base $name
        if (-not (Test-Path -LiteralPath $full)) { return $full }
        if ($Force) {
            Remove-Item -LiteralPath $full -Recurse -Force
            return $full
        }
        if ($IsCli) { throw "Папка '$full' уже существует. Запусти лаунчер с флагом -Force для переустановки." }
        Info ''
        Warn "  Папка '$full' уже существует."
        Info '    [1] Удалить и установить заново'
        Info '    [2] Указать другое имя папки'
        Info '    [0] Отмена'
        $a = Read-Host '  Твой выбор'
        switch ($a) {
            '1' { Remove-Item -LiteralPath $full -Recurse -Force; return $full }
            '2' {
                $n = Read-Host '  Имя папки'
                $n = ($n.Trim() -replace '[\\/:*?"<>|]', '-')
                if ($n) { $name = $n }
            }
            '0' { Throw-Cancel }
            default { Warn '  Нет такого варианта.' }
        }
    }
}

# ---------------- запись служебных файлов ----------------
function Write-AsciiFile([string]$path, [string]$content) {
    [System.IO.File]::WriteAllText($path, ($content -replace "`n", "`r`n") + "`r`n", [System.Text.Encoding]::ASCII)
}

function Write-Utf8Txt([string]$path, [string]$content) {
    [System.IO.File]::WriteAllText($path, ($content -replace "`n", "`r`n") + "`r`n", (New-Object System.Text.UTF8Encoding($true)))
}

function Get-RunBatContent($variant) {
    switch ($variant.Key) {
        'EXE' {
            return @'
@echo off
cd /d "%~dp0"
if exist jarvis.exe (start "" jarvis.exe) else (start "" jarvis-server.exe)
'@
        }
        'HTML' {
            return @'
@echo off
cd /d "%~dp0"
call Server.bat
'@
        }
        'GO' {
            return @'
@echo off
cd /d "%~dp0"
if not exist jarvis.exe if not exist jarvis-server.exe call build_exe.bat
if exist jarvis.exe (start "" jarvis.exe) else (if exist jarvis-server.exe (start "" jarvis-server.exe) else (echo Build failed - run build_exe.bat again. & pause))
'@
        }
    }
    return $null
}

function Write-InstallNotes($variant, $dest, $installed) {
    $lines = @()
    $lines += 'J.A.R.V.I.S. - что здесь лежит и как запустить'
    $lines += ('Установлено лаунчером v{0}, {1}' -f $LauncherVer, (Get-Date -Format 'dd.MM.yyyy HH:mm'))
    $lines += ('Версия: {0}' -f $variant.Title)
    $lines += ''
    $lines += 'Файлы:'
    foreach ($f in $installed) {
        $lines += ('  {0}  ({1})' -f $f.Name, (Human $f.Length))
    }
    $lines += ''
    switch ($variant.Key) {
        'EXE' {
            $lines += 'Как запустить:'
            $lines += '  Двойной клик по jarvis.exe - откроется окно J.A.R.V.I.S.'
            $lines += '  Если окно не открылось - запусти jarvis-server.exe:'
            $lines += '  сервер поднимется и откроет браузер с тем же интерфейсом.'
            $lines += '  (Порт 8000 должен быть свободен.)'
        }
        'HTML' {
            $lines += 'Как запустить:'
            $lines += '  Двойной клик по Server.bat (или по ЗАПУСК.bat - это то же самое).'
            $lines += '  При первом запуске Server.bat сам поставит pip-зависимости.'
            $lines += '  Браузер откроется на http://localhost:8000/jarvis.html'
        }
        'GO' {
            $lines += 'Как запустить:'
            $lines += '  1. Двойной клик по build_exe.bat - соберутся jarvis.exe и jarvis-server.exe'
            $lines += '     (нужен Go 1.21+; для GUI-окна ещё и gcc).'
            $lines += '  2. Дальше - двойной клик по jarvis.exe (или jarvis-server.exe).'
            $lines += '  Свой ключ ИИ: скопируй jarvis.conf.example в jarvis.conf и впиши ключ.'
        }
        'ANDROID' {
            $lines += 'Как установить на телефон:'
            $lines += '  1. Передай JARVIS-v1.2.apk на телефон (Telegram, USB, облако - что угодно).'
            $lines += '  2. Открой файл на телефоне и разреши установку из неизвестных источников.'
            $lines += '  3. После установки открой JARVIS и дай нужные разрешения (микрофон и т.д.).'
        }
    }
    $lines += ''
    $lines += 'Как обновить версию:'
    $lines += '  Запусти лаунчер заново (тот же JARVIS-Launcher.bat) - он скачает свежие файлы.'
    $lines += ''
    $lines += "Репозиторий: $RepoUrl"
    Write-Utf8Txt (Join-Path $dest 'О-УСТАНОВКЕ.txt') ($lines -join "`n")
}

# ---------------- скачивание ----------------
function Install-Files($variant, $dest) {
    $shaMap = Get-TreeMap
    if ($shaMap) {
        Ok '  [+] Список файлов с GitHub получен: проверка по контрольным суммам включена'
    } else {
        Warn '  [!] Не удалось получить контрольные суммы (GitHub API) - проверю только размеры.'
    }
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    $files = $variant.Files
    $installed = @()
    $i = 0
    foreach ($f in $files) {
        $i++
        $d = Join-Path $dest $f.d
        $ddir = Split-Path -Parent $d
        if ($ddir -and -not (Test-Path -LiteralPath $ddir)) {
            New-Item -ItemType Directory -Path $ddir -Force | Out-Null
        }
        $expSize = [long]0; $expSha = $null
        if ($shaMap -and $shaMap.ContainsKey($f.p)) {
            $expSize = $shaMap[$f.p].size
            $expSha = $shaMap[$f.p].sha
        }
        Write-Host ("  [{0}/{1}] Скачиваю {2} ... " -f $i, $files.Count, $f.d) -NoNewline
        $okr = Get-FileVerified $f.p $d $expSize $expSha
        if (-not $okr) { throw "Не удалось скачать $($f.p) - проверь интернет и попробуй ещё раз." }
        $installed += (Get-Item -LiteralPath $d)
        Ok ("OK ({0})" -f (Human $installed[$installed.Count-1].Length))
    }
    return ,$installed
}

# ---------------- пост-установка ----------------
function Post-EXE($dest) {
    Info ''
    Info '  Как запустить: двойной клик по jarvis.exe (или по ЗАПУСК.bat).'
    Info '  Запасной вариант без окна: jarvis-server.exe (интерфейс в браузере).'
    $a = Read-Host '  Запустить JARVIS сейчас? [Y/n]'
    if ($a -eq '' -or $a -match '^[yYдД]') {
        $exe = Join-Path $dest 'jarvis.exe'
        if (Test-Path $exe) {
            Start-Process -FilePath $exe -WorkingDirectory $dest
            Ok '  [+] Запускаю jarvis.exe...'
        } else {
            Warn '  jarvis.exe не найден - что-то странное, проверь папку.'
        }
    }
}

function Post-HTML($dest, $py) {
    if ($py) {
        if ($SkipDeps) {
            Info '  (зависимости не ставлю: флаг -SkipDeps)'
        } else {
            Info ''
            Info '  Ставлю зависимости Python (aiohttp, psutil, pillow...) - пару минут.'
            Push-Location $dest
            try {
                & $py.Exe -m pip install --upgrade pip
                & $py.Exe -m pip install -r requirements.txt
                if ($LASTEXITCODE -eq 0) {
                    Ok '  [+] Зависимости установлены.'
                } else {
                    Warn '  [!] pip вернул ошибку. Не страшно: Server.bat сам поставит'
                    Warn '      зависимости при первом запуске.'
                }
            } finally { Pop-Location }
        }
        Info ''
        Info '  Как запустить: двойной клик по Server.bat (или по ЗАПУСК.bat).'
        $a = Read-Host '  Запустить JARVIS сейчас? [Y/n]'
        if ($a -eq '' -or $a -match '^[yYдД]') {
            Start-Process -FilePath (Join-Path $dest 'ЗАПУСК.bat') -WorkingDirectory $dest
            Ok '  [+] Запускаю: откроется консоль сервера, потом браузер.'
        }
    } else {
        Info ''
        Info '  Python не установлен, но файлы скачаны. Когда поставишь Python 3.10+,'
        Info '  просто запусти Server.bat - он сам поставит зависимости при первом запуске.'
    }
}

function Build-GO($dest, $go) {
    Push-Location $dest
    try {
        $env:GO111MODULE = 'on'
        Info '  [1/3] Скачиваю зависимости Go (go mod download)...'
        & $go mod download
        if (Test-Gcc) {
            Info '  [2/3] Собираю jarvis.exe (окно JARVIS, WebView2)...'
            & $go build -ldflags "-s -w -H windowsgui" -o jarvis.exe .
            if ($LASTEXITCODE -eq 0) { Ok '  [+] jarvis.exe собран.' }
            else { Warn '  [!] GUI-сборка не удалась. Серверный вариант всё равно соберётся.' }
        } else {
            Warn '  [2/3] gcc не найден - GUI-окно собрать не выйдет (нужен C-компилятор).'
            Info '        Собираю только серверный вариант (без окна, интерфейс в браузере).'
        }
        Info '  [3/3] Собираю jarvis-server.exe (серверный вариант)...'
        $env:CGO_ENABLED = '0'
        & $go build -tags headless -ldflags "-s -w" -o jarvis-server.exe .
        Remove-Item Env:\CGO_ENABLED -ErrorAction SilentlyContinue
        if ($LASTEXITCODE -eq 0) { Ok '  [+] jarvis-server.exe собран.' }
        else { Warn '  [!] Сборка сервера не удалась - попробуй позже через build_exe.bat.' }
    } finally { Pop-Location }
}

function Post-GO($dest, $go) {
    if (-not $go) {
        Info ''
        Info '  Go не установлен - исходники скачаны в эту папку.'
        Info '  Когда установишь Go 1.21+ (go.dev/dl), запусти build_exe.bat -'
        Info '  он соберёт jarvis.exe и jarvis-server.exe.'
        return
    }
    $a = $null
    if (-not $IsCli) {
        Info ''
        $a = Read-Host '  Собрать JARVIS из исходников сейчас? [Y/n]'
    }
    if ($IsCli -or $a -eq '' -or $a -match '^[yYдД]') {
        Build-GO $dest $go
        Info ''
        Info '  Как запустить: двойной клик по jarvis.exe (или по ЗАПУСК.bat).'
    } else {
        Info '  Ок. Собрать можно позже двойным кликом по build_exe.bat.'
    }
}

function Post-ANDROID($dest) {
    Info ''
    Info '  APK скачан. Дальше:'
    Info '   1. Передай JARVIS-v1.2.apk на телефон (Telegram, USB, облако).'
    Info '   2. Открой файл на телефоне, разреши установку из неизвестных источников.'
    Info '   3. Дай приложению нужные разрешения (микрофон, уведомления).'
    Info '  Подробности - в README.txt и О-УСТАНОВКЕ.txt в папке установки.'
}

# ---------------- главная ----------------
$IsCli = ($Variant -ne '')

try {
    Info ''
    Hr
    Write-Host ('   J.A.R.V.I.S. ЛАУНЧЕР v{0}' -f $LauncherVer) -ForegroundColor Cyan
    Write-Host '   Один файл - и любой JARVIS у тебя на компьютере' -ForegroundColor Cyan
    Hr
    Info ("  Репозиторий : $RepoUrl")
    if ($BatPath) { Info ("  Лаунчер лежит: $BatPath") }

    # вариант установки
    if ($IsCli) {
        $sel = $null
        $want = $Variant.ToUpper()
        foreach ($v in $VARIANTS) { if ($v.Key -eq $want) { $sel = $v } }
        if (-not $sel) { throw "Неизвестная версия '$Variant'. Доступны: EXE, HTML, GO, ANDROID." }
        Info ("  Режим: тихая установка версии $($sel.Key)")
    } else {
        $sel = Choose-Variant
    }

    # папка установки
    $dest = Resolve-DestFolder $sel
    Info ''
    Ok ("  [+] Папка установки: $dest")

    # место на диске
    try {
        $qual = Split-Path -Qualifier $dest
        if ($qual) {
            $drive = New-Object System.IO.DriveInfo($qual)
            if ($drive.AvailableFreeSpace -lt 100MB) {
                Err '  [!] На диске меньше 100 МБ свободного места - установка может не удаться.'
            }
        }
    } catch {}

    # проверка программ (ДО скачивания)
    Info ''
    Info '  Проверяю, что всё нужное на месте...'
    $py = $null; $go = $null
    switch ($sel.Need) {
        'exe' {
            Ok '  [+] Для EXE-версии ничего ставить не нужно - это её главный плюс.'
            if (-not (Test-WebView2)) {
                Warn '  [!] WebView2 не найден в системе. Обычно он уже встроен в Windows 10/11.'
                Info '       Если jarvis.exe не откроется - поставь отсюда:'
                Info '       https://developer.microsoft.com/microsoft-edge/webview2/'
            }
        }
        'python' { $py = Ensure-Python }
        'go'     { $go = Ensure-Go }
        'apk'    { Ok '  [+] Для APK ничего на компьютере не нужно.' }
    }

    # скачивание с проверкой целостности
    Info ''
    Info '  Скачиваю файлы JARVIS с GitHub...'
    $installed = Install-Files $sel $dest

    # служебные файлы
    $runBat = Get-RunBatContent $sel
    if ($runBat) { Write-AsciiFile (Join-Path $dest 'ЗАПУСК.bat') $runBat }
    Write-InstallNotes $sel $dest $installed

    # пост-установка
    switch ($sel.Key) {
        'EXE'     { if (-not $IsCli) { Post-EXE $dest } }
        'HTML'    { if (-not $IsCli) { Post-HTML $dest $py } elseif (-not $SkipDeps -and $py) {
                        Push-Location $dest
                        try { & $py.Exe -m pip install -r requirements.txt } finally { Pop-Location }
                    } }
        'GO'      { Post-GO $dest $go }
        'ANDROID' { Post-ANDROID $dest }
    }

    # итог
    Info ''
    Hr
    Ok ('  [+] ГОТОВО! JARVIS (' + $sel.Key + ') установлен в папку:')
    Ok ("      $dest")
    if ($sel.Key -ne 'ANDROID') {
        Info '  Запуск в будущем: двойной клик по ЗАПУСК.bat в этой папке.'
    }
    Info "  Подробности - в файле О-УСТАНОВКЕ.txt внутри папки."
    Hr
    exit 0

} catch [System.OperationCanceledException] {
    Info ''
    Warn '  Установка отменена. Возвращайся!'
    exit 2
} catch {
    Info ''
    Err ("  [!] Ошибка: " + $_.Exception.Message)
    Err '  Попробуй запустить лаунчер ещё раз (проверь интернет).'
    Err "  Не помогает - напиши автору: $RepoUrl"
    exit 1
}
