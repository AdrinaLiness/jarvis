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
powershell -NoProfile -STA -ExecutionPolicy Bypass -File "%JARVIS_PS1%" -BatPath "%JARVIS_BAT%" %*
set "EC=%ERRORLEVEL%"
del "%JARVIS_PS1%" >nul 2>nul
if "%EC%"=="7" exit /b 7
pause
exit /b %EC%
##### PS-PAYLOAD #####
# ============================================================
#  J.A.R.V.I.S. Launcher v2.0 - GUI (WPF)
#  Один файл ставит любую версию JARVIS с GitHub и сам
#  проверяет обновления при каждом запуске.
#  (c) AdrinaLiness - MIT. Репозиторий: AdrinaLiness/jarvis
# ============================================================
param(
    [string]$BatPath = '',
    [string]$Variant = '',
    [string]$Dir = '',
    [switch]$Cli,
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
$LauncherVer = '2.0'
$UA          = @{ 'User-Agent' = 'JARVIS-Launcher' }
$ProgressPreference = 'SilentlyContinue'

# ---------------- консоль ----------------
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
try { $Host.UI.RawUI.WindowTitle = 'J.A.R.V.I.S. - Launcher' } catch {}

# ---------------- какие версии бывают ----------------
# p = путь в репозитории; d = куда положить в папке установки
$VARIANTS = @(
    @{
        Key = 'EXE'; Folder = 'JARVIS-EXE'; Accent = '#22D3EE'; Mono = 'EXE'
        Title = 'Готовая сборка для Windows'
        Badge = 'РЕКОМЕНДУЮ НОВИЧКАМ'
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
        Key = 'HTML'; Folder = 'JARVIS-HTML'; Accent = '#A78BFA'; Mono = 'HTML'
        Title = 'Веб-версия: Python-сервер + интерфейс в браузере'
        Badge = ''
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
        Key = 'GO'; Folder = 'JARVIS-GO'; Accent = '#60A5FA'; Mono = 'GO'
        Title = 'Исходники на Go: один быстрый бинарник'
        Badge = ''
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
        Key = 'ANDROID'; Folder = 'JARVIS-ANDROID'; Accent = '#3DDC84'; Mono = 'APK'
        Title = 'Версия для телефона (APK)'
        Badge = ''
        Pros = @(
            'JARVIS всегда с собой: голос, громкость, фонарик, Wi-Fi, ярлыки',
            'Встроенный ИИ-мозг (свой ключ через OpenAI-совместимый API)',
            'При каждом запуске сам проверяет, не вышла ли новая версия'
        )
        Cons = @(
            'Ставится на телефон (Android 8.0+), а не на компьютер',
            'APK надо передать на телефон и разрешить установку из неизвестных источников'
        )
        Need = 'apk'
        Files = @(
            @{ p = 'ANDROID/JARVIS-v1.3.apk'; d = 'JARVIS-v1.3.apk' },
            @{ p = 'ANDROID/README.txt';      d = 'README.txt' }
        )
    }
)

# ---------------- мелкие помощники ----------------
function Human([long]$n) {
    if ($n -ge 1MB) { return ('{0:N1} MB' -f ($n / 1MB)) }
    if ($n -ge 1KB) { return ('{0:N0} KB' -f ($n / 1KB)) }
    return ("$n B")
}

# a новее b?  "2.10" > "2.9" -> true
function Test-Newer([string]$a, [string]$b) {
    try {
        if (-not $a -or -not $b) { return $false }
        $pa = @($a -split '\.' | ForEach-Object { if ($_ -match '\d+') { [int]$Matches[0] } else { 0 } })
        $pb = @($b -split '\.' | ForEach-Object { if ($_ -match '\d+') { [int]$Matches[0] } else { 0 } })
        $n = [Math]::Max($pa.Count, $pb.Count)
        for ($i = 0; $i -lt $n; $i++) {
            $x = 0; $y = 0
            if ($i -lt $pa.Count) { $x = $pa[$i] }
            if ($i -lt $pb.Count) { $y = $pb[$i] }
            if ($x -gt $y) { return $true }
            if ($x -lt $y) { return $false }
        }
    } catch {}
    return $false
}

function HexToColor([string]$hex) {
    $h = $hex.TrimStart('#')
    $r = [Convert]::ToByte($h.Substring(0, 2), 16)
    $g = [Convert]::ToByte($h.Substring(2, 2), 16)
    $b = [Convert]::ToByte($h.Substring(4, 2), 16)
    return [System.Windows.Media.Color]::FromRgb($r, $g, $b)
}

function Get-Variant([string]$key) {
    foreach ($v in $VARIANTS) { if ($v.Key -eq $key) { return $v } }
    return $null
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

# ---------------- консольные помощники (для -Cli / fallback) ----------------
function Info([string]$m) { Write-Host $m }
function Ok([string]$m)   { Write-Host $m -ForegroundColor Green }
function Warn([string]$m) { Write-Host $m -ForegroundColor Yellow }
function Err([string]$m)  { Write-Host $m -ForegroundColor Red }
function Hr               { Write-Host ('-' * 66) -ForegroundColor DarkGray }
function Throw-Cancel { throw [System.OperationCanceledException]::new('Отменено пользователем') }

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

function Ensure-Python-Console {
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
        $r = Offer-Fix 'Python 3.10+' 'Python.Python.3.12' 'https://www.python.org/downloads/'
        if ($r -eq 'retry') { continue }
        if ($r -eq 'skip') { return $null }
        Throw-Cancel
    }
}

function Ensure-Go-Console {
    while ($true) {
        $g = Find-Go
        if ($g) {
            $v = Get-GoVersion $g
            if ($v -and ($v.Major * 100 + $v.Minor) -ge 121) {
                Ok "  [+] Go найден: $g"
                return $g
            }
            if ($v) { Warn ("  [!] Найден Go 1.{0}, но нужен 1.21+." -f $v.Minor) }
        } else {
            Warn '  [!] Go не найден.'
        }
        $r = Offer-Fix 'Go 1.21+' 'GoLang.Go' 'https://go.dev/dl/'
        if ($r -eq 'retry') { continue }
        if ($r -eq 'skip') { return $null }
        Throw-Cancel
    }
}

# ---------------- общий контекст для движка ----------------
$CFG = @{
    RepoOwner   = $RepoOwner
    RepoName    = $RepoName
    Branch      = $Branch
    RawBase     = $RawBase
    ApiBase     = $ApiBase
    RepoUrl     = $RepoUrl
    LauncherVer = $LauncherVer
    UA          = $UA
}

# ============================================================
#  ДВИЖОК УСТАНОВКИ
#  Работает и в консоли ($sync нет -> печатает в консоль),
#  и в GUI-ранспейсе ($sync задан -> пишет в очередь лога).
# ============================================================
$ENGINE_SRC = @'
function Eng-Log([string]$m, [string]$k = 'info') {
    if ($sync) {
        [void]$sync.Log.Add(@{ m = $m; k = $k })
    } else {
        switch ($k) {
            'ok'    { Write-Host $m -ForegroundColor Green }
            'warn'  { Write-Host $m -ForegroundColor Yellow }
            'err'   { Write-Host $m -ForegroundColor Red }
            'step'  { Write-Host $m -ForegroundColor Cyan }
            default { Write-Host $m }
        }
    }
}

function Eng-Pct([int]$p) { if ($sync) { $sync.Pct = $p } }

function Eng-Indet([bool]$on) { if ($sync) { $sync.Indet = $on } }

# размер в человекочитаемый вид (нужна и в транспейсе)
function Human([long]$n) {
    if ($n -ge 1MB) { return ('{0:N1} MB' -f ($n / 1MB)) }
    if ($n -ge 1KB) { return ('{0:N0} KB' -f ($n / 1KB)) }
    return ("$n B")
}

function Test-Gcc { return [bool](Get-Command 'gcc' -ErrorAction SilentlyContinue) }

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
        $url = $CFG.ApiBase + '/git/trees/' + $CFG.Branch + '?recursive=1'
        $r = Invoke-RestMethod -Uri $url -Headers $CFG.UA -TimeoutSec 30
        $map = @{}
        foreach ($e in $r.tree) {
            if ($e.type -eq 'blob') { $map[$e.path] = @{ sha = $e.sha; size = [long]$e.size } }
        }
        return $map
    } catch {
        Eng-Log ('    (контрольные суммы недоступны: ' + $_.Exception.Message + ')') 'warn'
        return $null
    }
}

function Get-FileVerified([string]$rel, [string]$dest, [long]$expectSize, [string]$expectSha) {
    for ($try = 1; $try -le 3; $try++) {
        try {
            Invoke-WebRequest -Uri ($CFG.RawBase + '/' + $rel) -OutFile $dest -UseBasicParsing -TimeoutSec 600 -Headers $CFG.UA
            if (-not (Test-Path -LiteralPath $dest)) { throw 'файл не создан' }
            $len = (Get-Item -LiteralPath $dest).Length
            if ($expectSize -gt 0 -and $len -ne $expectSize) { throw "размер не совпал ($len вместо $expectSize)" }
            if ($expectSha) {
                $sha = Get-GitBlobSha1 $dest
                if ($sha -ne $expectSha.ToLower()) { throw 'контрольная сумма не совпала' }
            }
            return $true
        } catch {
            Eng-Log ("    попытка {0}/3 не удалась: {1}" -f $try, $_.Exception.Message) 'warn'
            if ($try -lt 3) { Start-Sleep -Seconds 2 }
        }
    }
    return $false
}

# ---------------- скачивание ----------------
function Install-Files($variant, $dest) {
    $shaMap = Get-TreeMap
    if ($shaMap) {
        Eng-Log '  Контрольные суммы GitHub получены - скачиваю с проверкой целостности' 'ok'
    } else {
        Eng-Log '  Контрольные суммы недоступны - проверю только размеры файлов' 'warn'
    }
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    $files = $variant.Files
    $installed = @()
    $i = 0
    foreach ($f in $files) {
        $i++
        Eng-Pct ([int](($i - 1) * 100 / $files.Count))
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
        $okr = Get-FileVerified $f.p $d $expSize $expSha
        if (-not $okr) { throw ("Не удалось скачать " + $f.p + " - проверь интернет и попробуй ещё раз.") }
        $item = Get-Item -LiteralPath $d
        $installed += $item
        Eng-Log ("  [{0}/{1}] {2} - OK ({3})" -f $i, $files.Count, $f.d, (Human $item.Length)) 'ok'
    }
    Eng-Pct 100
    return ,$installed
}

# ---------------- служебные файлы ----------------
function Write-AsciiFile([string]$path, [string]$content) {
    [System.IO.File]::WriteAllText($path, ($content -replace "`n", "`r`n") + "`r`n", [System.Text.Encoding]::ASCII)
}

function Write-Utf8Txt([string]$path, [string]$content) {
    [System.IO.File]::WriteAllText($path, ($content -replace "`n", "`r`n") + "`r`n", (New-Object System.Text.UTF8Encoding($true)))
}

function Get-RunBatContent($variant) {
    switch ($variant.Key) {
        'EXE' {
            return @"
@echo off
cd /d "%~dp0"
if exist jarvis.exe (start "" jarvis.exe) else (start "" jarvis-server.exe)
"@
        }
        'HTML' {
            return @"
@echo off
cd /d "%~dp0"
call Server.bat
"@
        }
        'GO' {
            return @"
@echo off
cd /d "%~dp0"
if not exist jarvis.exe if not exist jarvis-server.exe call build_exe.bat
if exist jarvis.exe (start "" jarvis.exe) else (if exist jarvis-server.exe (start "" jarvis-server.exe) else (echo Build failed - run build_exe.bat again. & pause))
"@
        }
    }
    return $null
}

function Write-InstallNotes($variant, $dest, $installed) {
    $lines = @()
    $lines += 'J.A.R.V.I.S. - что здесь лежит и как запустить'
    $lines += ('Установлено лаунчером v{0}, {1}' -f $CFG.LauncherVer, (Get-Date -Format 'dd.MM.yyyy HH:mm'))
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
            $lines += '  1. Передай JARVIS-v1.3.apk на телефон (Telegram, USB, облако - что угодно).'
            $lines += '  2. Открой файл на телефоне и разреши установку из неизвестных источников.'
            $lines += '  3. После установки открой JARVIS и дай нужные разрешения (микрофон и т.д.).'
        }
    }
    $lines += ''
    $lines += 'Обновления:'
    $lines += '  Все приложения JARVIS при каждом запуске сами проверяют, не вышла'
    $lines += '  ли новая версия. Чтобы обновиться - просто запусти лаунчер заново'
    $lines += '  (тот же JARVIS-Launcher.bat), он скачает свежие файлы. Сам лаунчер'
    $lines += '  тоже проверяет обновления при запуске и умеет обновлять себя.'
    $lines += ''
    $lines += ('Репозиторий: ' + $CFG.RepoUrl)
    Write-Utf8Txt (Join-Path $dest 'О-УСТАНОВКЕ.txt') ($lines -join "`n")
}

# ---------------- пост-установка (HTML / GO) ----------------
function Start-Pip([string]$pyExe, [string]$dest) {
    Eng-Indet $true
    Eng-Log '  Ставлю зависимости Python (aiohttp, psutil, pillow...)' 'step'
    try {
        Push-Location $dest
        try {
            & $pyExe -m pip install --upgrade pip 2>&1 | Out-Null
            $out = & $pyExe -m pip install -r requirements.txt 2>&1
            $code = $LASTEXITCODE
        } finally { Pop-Location }
        if ($code -eq 0) {
            Eng-Log '  Зависимости Python установлены' 'ok'
        } else {
            Eng-Log "  pip вернул ошибку ($code). Не страшно: Server.bat сам поставит зависимости при первом запуске." 'warn'
            $tail = @($out | Select-Object -Last 2) -join ' | '
            if ($tail) { Eng-Log ("    pip: " + $tail) 'warn' }
        }
    } finally { Eng-Indet $false }
}

function Build-GoBinaries([string]$goExe, [string]$dest) {
    Eng-Indet $true
    Push-Location $dest
    try {
        $env:GO111MODULE = 'on'
        Eng-Log '  [1/3] Скачиваю зависимости Go (go mod download)...' 'step'
        & $goExe mod download 2>&1 | Out-Null
        if (Test-Gcc) {
            Eng-Log '  [2/3] Собираю jarvis.exe (окно JARVIS, WebView2)...' 'step'
            & $goExe build -ldflags "-s -w -H windowsgui" -o jarvis.exe .
            if ($LASTEXITCODE -eq 0) {
                Eng-Log '  jarvis.exe собран' 'ok'
            } else {
                Eng-Log '  GUI-сборка не удалась. Серверный вариант всё равно соберётся.' 'warn'
            }
        } else {
            Eng-Log '  [2/3] gcc не найден - GUI-окно собрать не выйдет (нужен C-компилятор).' 'warn'
            Eng-Log '        Собираю только серверный вариант (без окна, интерфейс в браузере).' 'info'
        }
        Eng-Log '  [3/3] Собираю jarvis-server.exe (серверный вариант)...' 'step'
        $env:CGO_ENABLED = '0'
        & $goExe build -tags headless -ldflags "-s -w" -o jarvis-server.exe .
        Remove-Item Env:\CGO_ENABLED -ErrorAction SilentlyContinue
        if ($LASTEXITCODE -eq 0) {
            Eng-Log '  jarvis-server.exe собран' 'ok'
        } else {
            Eng-Log '  Сборка сервера не удалась - попробуй позже через build_exe.bat.' 'warn'
        }
        Eng-Log '  Как запустить: двойной клик по jarvis.exe (или по ЗАПУСК.bat).' 'info'
    } finally {
        Pop-Location
        Eng-Indet $false
        Remove-Item Env:\CGO_ENABLED -ErrorAction SilentlyContinue
    }
}

# ---------------- главная установка (для GUI-ранспейса) ----------------
function Main-Install {
    $sel = $sync.Sel
    $dest = $sync.Dest
    try {
        Eng-Log ('  Папка установки: ' + $dest) 'ok'
        $installed = Install-Files $sel $dest
        Write-ServiceFiles $sel $dest $installed
        switch ($sel.Key) {
            'HTML' {
                if (-not $sync.SkipDeps -and $sync.PyExe) { Start-Pip $sync.PyExe $dest }
            }
            'GO' {
                if ($sync.GoExe) {
                    Build-GoBinaries $sync.GoExe $dest
                } else {
                    Eng-Log '  Go не установлен - исходники скачаны в папку.' 'warn'
                    Eng-Log '  Когда установишь Go 1.21+ (go.dev/dl), запусти build_exe.bat.' 'warn'
                }
            }
            'ANDROID' {
                Eng-Log '  APK скачан. Передай его на телефон и открой там.' 'info'
            }
        }
        $exe1 = Join-Path $dest 'jarvis.exe'
        $exe2 = Join-Path $dest 'jarvis-server.exe'
        $run  = Join-Path $dest 'ЗАПУСК.bat'
        switch ($sel.Key) {
            'EXE'     { if (Test-Path $exe1) { $sync.LaunchPath = $exe1 } else { $sync.LaunchPath = $exe2 } }
            'HTML'    { $sync.LaunchPath = $run }
            'GO'      { if (Test-Path $exe1) { $sync.LaunchPath = $exe1 } elseif (Test-Path $exe2) { $sync.LaunchPath = $exe2 } else { $sync.LaunchPath = '' } }
            'ANDROID' { $sync.LaunchPath = '' }
        }
        $sync.State = 'done'
    } catch {
        $sync.State = 'error'
        $sync.ErrMsg = $_.Exception.Message
    }
}
'@
Invoke-Expression $ENGINE_SRC

# ---------------- папка установки (консоль) ----------------
function Resolve-DestFolder-Console($variant) {
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

# ============================================================
#  КОНСОЛЬНЫЙ РЕЖИМ (-Cli или JARVIS-Launcher.bat EXE|HTML|GO|ANDROID)
# ============================================================
$IsCli = ($Variant -ne '') -or $Cli

function Start-CliFlow {
    try {
        Info ''
        Hr
        Write-Host ('   J.A.R.V.I.S. ЛАУНЧЕР v{0} (консольный режим)' -f $LauncherVer) -ForegroundColor Cyan
        Hr
        Info ("  Репозиторий : $RepoUrl")

        # проверка обновлений лаунчера
        try {
            $vi = Invoke-RestMethod -Uri "$RawBase/VERSION.json" -Headers $UA -TimeoutSec 6
            $latest = $vi.components.launcher
            if (Test-Newer $latest $LauncherVer) {
                Warn ("  [!] Вышла новая версия лаунчера: v{0} (у тебя v{1})." -f $latest, $LauncherVer)
                $a = Read-Host '  Обновить лаунчер прямо сейчас? [Y/n]'
                if ($a -eq '' -or $a -match '^[yYдД]') {
                    $newBat = Join-Path $env:TEMP 'JARVIS-Launcher-new.bat'
                    Invoke-WebRequest -Uri "$RawBase/EXE/JARVIS-Launcher.bat" -OutFile $newBat -UseBasicParsing -TimeoutSec 120 -Headers $UA
                    $self = if ($BatPath) { $BatPath } else { $null }
                    if ($self) {
                        Copy-Item -LiteralPath $newBat -Destination $self -Force
                        Remove-Item -LiteralPath $newBat -Force
                        Ok '  [+] Лаунчер обновлён. Перезапускаюсь...'
                        Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "start `"`" `"$self`""
                        exit 7
                    } else {
                        Ok "  [+] Новый лаунчер скачан: $newBat - запусти его."
                        exit 7
                    }
                }
            } else {
                Ok ("  [+] Лаунчер актуален (v{0})." -f $LauncherVer)
            }
        } catch {}

        # выбор версии
        if ($Variant -ne '') {
            $want = $Variant.ToUpper()
            $sel = Get-Variant $want
            if (-not $sel) { throw "Неизвестная версия '$Variant'. Доступны: EXE, HTML, GO, ANDROID." }
            Info ("  Режим: тихая установка версии $($sel.Key)")
        } else {
            while ($true) {
                Info ''
                Hr
                Write-Host '   Какого J.A.R.V.I.S. установить?' -ForegroundColor Cyan
                Info '   Лаунчер проверит нужные программы, скачает файлы с GitHub'
                Info '   в отдельную папку и подскажет, как запустить.'
                Info ''
                $num = 0
                foreach ($v in $VARIANTS) {
                    $num++
                    Write-Host ("  [{0}] {1}" -f $num, $v.Title) -ForegroundColor Yellow
                    foreach ($p in $v.Pros) { Write-Host "      + $p" -ForegroundColor Green }
                    foreach ($c in $v.Cons) { Write-Host "      - $c" -ForegroundColor Red }
                    Info ''
                }
                Write-Host '  [0] Выход' -ForegroundColor Yellow
                Hr
                $a = Read-Host '  Введи номер и нажми Enter'
                if ($a -eq '0') { Throw-Cancel }
                if ($a -match '^[1-4]$') { $sel = $VARIANTS[[int]$a - 1]; break }
                Warn '  Нет такого номера. Попробуй ещё раз.'
            }
        }

        $dest = Resolve-DestFolder-Console $sel
        Ok ("  [+] Папка установки: $dest")

        try {
            $qual = Split-Path -Qualifier $dest
            if ($qual) {
                $drive = New-Object System.IO.DriveInfo($qual)
                if ($drive.AvailableFreeSpace -lt 100MB) {
                    Err '  [!] На диске меньше 100 МБ свободного места - установка может не удаться.'
                }
            }
        } catch {}

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
            'python' { $py = Ensure-Python-Console }
            'go'     { $go = Ensure-Go-Console }
            'apk'    { Ok '  [+] Для APK ничего на компьютере не нужно.' }
        }

        Info ''
        Info '  Скачиваю файлы JARVIS с GitHub...'
        $installed = Install-Files $sel $dest

        $runBat = Get-RunBatContent $sel
        if ($runBat) { Write-AsciiFile (Join-Path $dest 'ЗАПУСК.bat') $runBat }
        Write-InstallNotes $sel $dest $installed

        switch ($sel.Key) {
            'EXE' {
                Info ''
                Info '  Как запустить: двойной клик по jarvis.exe (или по ЗАПУСК.bat).'
                $a = Read-Host '  Запустить JARVIS сейчас? [Y/n]'
                if ($a -eq '' -or $a -match '^[yYдД]') {
                    $exe = Join-Path $dest 'jarvis.exe'
                    if (Test-Path $exe) {
                        Start-Process -FilePath $exe -WorkingDirectory $dest
                        Ok '  [+] Запускаю jarvis.exe...'
                    }
                }
            }
            'HTML' {
                if ($py) {
                    if (-not $SkipDeps) {
                        $sync = $null
                        Start-Pip $py.Exe $dest
                    }
                    Info ''
                    Info '  Как запустить: двойной клик по Server.bat (или по ЗАПУСК.bat).'
                    $a = Read-Host '  Запустить JARVIS сейчас? [Y/n]'
                    if ($a -eq '' -or $a -match '^[yYдД]') {
                        Start-Process -FilePath (Join-Path $dest 'ЗАПУСК.bat') -WorkingDirectory $dest
                    }
                } else {
                    Info ''
                    Info '  Python не установлен, но файлы скачаны. Когда поставишь Python 3.10+,'
                    Info '  просто запусти Server.bat - он сам поставит зависимости.'
                }
            }
            'GO' {
                if ($go) {
                    Info ''
                    $a = Read-Host '  Собрать JARVIS из исходников сейчас? [Y/n]'
                    if ($a -eq '' -or $a -match '^[yYдД]') {
                        $sync = $null
                        Build-GoBinaries $go $dest
                    } else {
                        Info '  Ок. Собрать можно позже двойным кликом по build_exe.bat.'
                    }
                } else {
                    Info ''
                    Info '  Go не установлен - исходники скачаны в эту папку.'
                    Info '  Когда установишь Go 1.21+ (go.dev/dl), запусти build_exe.bat.'
                }
            }
            'ANDROID' {
                Info ''
                Info '  APK скачан. Дальше:'
                Info '   1. Передай JARVIS-v1.3.apk на телефон (Telegram, USB, облако).'
                Info '   2. Открой файл на телефоне, разреши установку из неизвестных источников.'
                Info '   3. Дай приложению нужные разрешения (микрофон, уведомления).'
            }
        }

        Info ''
        Hr
        Ok ('  [+] ГОТОВО! JARVIS (' + $sel.Key + ') установлен в папку:')
        Ok ("      $dest")
        Info '  Подробности - в файле О-УСТАНОВКЕ.txt внутри папки.'
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
}

if ($IsCli) { Start-CliFlow }

# ============================================================
#  GUI (WPF) - вид как у настоящих лаунчеров
# ============================================================
$script:ExitCode   = 0
$script:Phase      = 'idle'
$script:Selected   = 'EXE'
$script:LogN       = 0
$script:UpdShown   = $false
$script:PendingDep = $null
$script:Sync       = $null
$script:ProgAnim   = $null
$script:InstPy     = ''
$script:InstGo     = ''
$script:LaunchPath = ''
$script:Dest       = ''
if ($Dir) { $script:BaseDir = $Dir } elseif ($BatPath) { $script:BaseDir = Split-Path -Parent $BatPath } else { $script:BaseDir = (Get-Location).Path }

try {
Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$xamlSrc = @'
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="J.A.R.V.I.S. — Launcher"
        Height="720" Width="1000"
        WindowStartupLocation="CenterScreen"
        ResizeMode="CanMinimize"
        Background="#0B1121"
        FontFamily="Segoe UI"
        TextOptions.TextFormattingMode="Display"
        UseLayoutRounding="True">
  <Window.Resources>
    <Style x:Key="BtnAccent" TargetType="Button">
      <Setter Property="Foreground" Value="#FFFFFF"/>
      <Setter Property="FontSize" Value="14"/>
      <Setter Property="FontWeight" Value="Bold"/>
      <Setter Property="Background" Value="#0891B2"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="Button">
            <Border x:Name="bd" CornerRadius="10" Background="{TemplateBinding Background}">
              <ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center" Margin="18,0"/>
            </Border>
            <ControlTemplate.Triggers>
              <Trigger Property="IsMouseOver" Value="True">
                <Setter TargetName="bd" Property="Background" Value="#06B6D4"/>
              </Trigger>
              <Trigger Property="IsPressed" Value="True">
                <Setter TargetName="bd" Property="Background" Value="#0E7490"/>
              </Trigger>
              <Trigger Property="IsEnabled" Value="False">
                <Setter TargetName="bd" Property="Background" Value="#1B2742"/>
                <Setter Property="Foreground" Value="#64748B"/>
              </Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style x:Key="BtnGreen" TargetType="Button">
      <Setter Property="Foreground" Value="#FFFFFF"/>
      <Setter Property="FontSize" Value="14"/>
      <Setter Property="FontWeight" Value="Bold"/>
      <Setter Property="Background" Value="#059669"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="Button">
            <Border x:Name="bd" CornerRadius="10" Background="{TemplateBinding Background}">
              <ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center" Margin="18,0"/>
            </Border>
            <ControlTemplate.Triggers>
              <Trigger Property="IsMouseOver" Value="True">
                <Setter TargetName="bd" Property="Background" Value="#10B981"/>
              </Trigger>
              <Trigger Property="IsPressed" Value="True">
                <Setter TargetName="bd" Property="Background" Value="#047857"/>
              </Trigger>
              <Trigger Property="IsEnabled" Value="False">
                <Setter TargetName="bd" Property="Background" Value="#1B2742"/>
                <Setter Property="Foreground" Value="#64748B"/>
              </Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style x:Key="BtnGhost" TargetType="Button">
      <Setter Property="Foreground" Value="#C7D2E8"/>
      <Setter Property="FontSize" Value="12"/>
      <Setter Property="Background" Value="#16203B"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="Button">
            <Border x:Name="bd" CornerRadius="8" Background="{TemplateBinding Background}" BorderBrush="#2E3F63" BorderThickness="1">
              <ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center" Margin="10,0"/>
            </Border>
            <ControlTemplate.Triggers>
              <Trigger Property="IsMouseOver" Value="True">
                <Setter TargetName="bd" Property="Background" Value="#1E2A4A"/>
              </Trigger>
              <Trigger Property="IsPressed" Value="True">
                <Setter TargetName="bd" Property="Background" Value="#14203A"/>
              </Trigger>
              <Trigger Property="IsEnabled" Value="False">
                <Setter Property="Foreground" Value="#475569"/>
              </Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style x:Key="BarStyle" TargetType="ProgressBar">
      <Setter Property="Foreground" Value="#22D3EE"/>
      <Setter Property="Background" Value="#0D1428"/>
      <Setter Property="BorderThickness" Value="0"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="ProgressBar">
            <Grid>
              <Border x:Name="PART_Track" CornerRadius="5" Background="{TemplateBinding Background}"/>
              <Border x:Name="PART_Indicator" CornerRadius="5" Background="#22D3EE" HorizontalAlignment="Left">
                <Border.Effect>
                  <DropShadowEffect Color="#22D3EE" BlurRadius="10" ShadowDepth="0" Opacity="0.7"/>
                </Border.Effect>
              </Border>
            </Grid>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
  </Window.Resources>

  <Grid>
    <Grid.RowDefinitions>
      <RowDefinition Height="Auto"/>
      <RowDefinition Height="104"/>
      <RowDefinition Height="*"/>
      <RowDefinition Height="Auto"/>
      <RowDefinition Height="Auto"/>
    </Grid.RowDefinitions>

    <Border x:Name="UpdBanner" Grid.Row="0" Background="#241B06" BorderBrush="#B45309" BorderThickness="0,0,0,1" Padding="16,8" Visibility="Collapsed">
      <DockPanel>
        <Button x:Name="BtnUpdClose" DockPanel.Dock="Right" Content="✕" Width="30" Height="30" Style="{StaticResource BtnGhost}" FontSize="11" Margin="8,0,0,0"/>
        <Button x:Name="BtnUpdNow" DockPanel.Dock="Right" Content="ОБНОВИТЬ" Height="30" MinWidth="110" Style="{StaticResource BtnAccent}" FontSize="11" Margin="8,0,0,0"/>
        <TextBlock x:Name="UpdText" Text="" Foreground="#FCD34D" FontSize="13" VerticalAlignment="Center" TextTrimming="CharacterEllipsis"/>
      </DockPanel>
    </Border>

    <Border Grid.Row="1" Background="#0D1528">
      <Grid Margin="22,0">
        <Grid.ColumnDefinitions>
          <ColumnDefinition Width="Auto"/>
          <ColumnDefinition Width="*"/>
          <ColumnDefinition Width="Auto"/>
        </Grid.ColumnDefinitions>
        <Canvas Width="72" Height="72" VerticalAlignment="Center" Margin="0,0,18,0">
          <Ellipse Canvas.Left="7" Canvas.Top="7" Width="58" Height="58" Stroke="#155E75" StrokeThickness="6"/>
          <Ellipse Canvas.Left="7" Canvas.Top="7" Width="58" Height="58" Stroke="#22D3EE" StrokeThickness="2" Opacity="0.85">
            <Ellipse.Effect>
              <DropShadowEffect Color="#22D3EE" BlurRadius="16" ShadowDepth="0" Opacity="0.9"/>
            </Ellipse.Effect>
          </Ellipse>
          <Ellipse x:Name="ReactRing" Canvas.Left="2" Canvas.Top="2" Width="68" Height="68" Stroke="#67E8F9" StrokeThickness="2" StrokeDashArray="3 2.2" Opacity="0.6" RenderTransformOrigin="0.5,0.5"/>
          <Ellipse Canvas.Left="26" Canvas.Top="26" Width="20" Height="20">
            <Ellipse.Fill>
              <RadialGradientBrush>
                <GradientStop Color="#A5F3FC" Offset="0"/>
                <GradientStop Color="#0891B2" Offset="1"/>
              </RadialGradientBrush>
            </Ellipse.Fill>
          </Ellipse>
        </Canvas>
        <StackPanel Grid.Column="1" VerticalAlignment="Center">
          <StackPanel Orientation="Horizontal">
            <TextBlock Text="J.A.R.V.I.S." FontFamily="Segoe UI Light" FontSize="30" Foreground="#F1F5F9"/>
            <Border Background="#0B2A3A" CornerRadius="6" Margin="14,7,0,0" VerticalAlignment="Top" Padding="9,3">
              <TextBlock Text="L A U N C H E R" Foreground="#22D3EE" FontSize="11" FontWeight="Bold"/>
            </Border>
          </StackPanel>
          <TextBlock Text="Один файл - и любой JARVIS у тебя на компьютере: проверит программы, скачает свежую версию, подскажет запуск." Foreground="#7C8DB0" FontSize="12" Margin="2,7,0,0"/>
        </StackPanel>
        <StackPanel Grid.Column="2" VerticalAlignment="Center" HorizontalAlignment="Right">
          <TextBlock x:Name="VerBadge" Text="v2.0" Foreground="#22D3EE" FontSize="15" FontWeight="Bold" HorizontalAlignment="Right"/>
          <TextBlock x:Name="LnkRepo" Text="github.com/AdrinaLiness/jarvis" Foreground="#64748B" FontSize="11" Cursor="Hand" HorizontalAlignment="Right" Margin="0,5,0,0"/>
        </StackPanel>
      </Grid>
    </Border>

    <UniformGrid Grid.Row="2" Rows="2" Columns="2" Margin="16,12,16,4">
      <Border x:Name="Card_EXE" Tag="EXE" CornerRadius="12" Background="#111A31" BorderBrush="#223052" BorderThickness="1.5" Cursor="Hand" Margin="0,0,8,8" Padding="14,12">
        <Grid>
          <Grid.RowDefinitions>
            <RowDefinition Height="Auto"/>
            <RowDefinition Height="*"/>
          </Grid.RowDefinitions>
          <DockPanel>
            <Ellipse x:Name="Dot_EXE" DockPanel.Dock="Right" Width="12" Height="12" Fill="#22D3EE" Visibility="Collapsed" VerticalAlignment="Center"/>
            <Border DockPanel.Dock="Left" Width="38" Height="38" CornerRadius="9" Background="#0B2A3A" VerticalAlignment="Center">
              <TextBlock Text="EXE" Foreground="#22D3EE" FontWeight="Bold" FontSize="12" HorizontalAlignment="Center" VerticalAlignment="Center"/>
            </Border>
            <StackPanel Margin="11,0,0,0" VerticalAlignment="Center">
              <TextBlock Text="Готовая сборка для Windows" Foreground="#E8EEF9" FontSize="13.5" FontWeight="SemiBold" TextWrapping="Wrap"/>
              <TextBlock Text="РЕКОМЕНДУЮ НОВИЧКАМ" Foreground="#22D3EE" FontSize="9.5" FontWeight="Bold" Margin="0,3,0,0"/>
            </StackPanel>
          </DockPanel>
          <StackPanel Grid.Row="1" Margin="2,9,0,0">
            <TextBlock Text="＋ Ничего не нужно устанавливать: даже Python не требуется" Foreground="#4ADE80" FontSize="11.5" TextWrapping="Wrap"/>
            <TextBlock Text="＋ Запуск двойным кликом по jarvis.exe - работает сразу" Foreground="#4ADE80" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
            <TextBlock Text="－ Только Windows 10/11 (нужен WebView2 - обычно уже встроен)" Foreground="#F87171" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
            <TextBlock Text="－ Исходники изменить нельзя: это готовые exe-файлы" Foreground="#F87171" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
          </StackPanel>
        </Grid>
      </Border>
      <Border x:Name="Card_HTML" Tag="HTML" CornerRadius="12" Background="#111A31" BorderBrush="#223052" BorderThickness="1.5" Cursor="Hand" Margin="8,0,0,8" Padding="14,12">
        <Grid>
          <Grid.RowDefinitions>
            <RowDefinition Height="Auto"/>
            <RowDefinition Height="*"/>
          </Grid.RowDefinitions>
          <DockPanel>
            <Ellipse x:Name="Dot_HTML" DockPanel.Dock="Right" Width="12" Height="12" Fill="#A78BFA" Visibility="Collapsed" VerticalAlignment="Center"/>
            <Border DockPanel.Dock="Left" Width="38" Height="38" CornerRadius="9" Background="#221A3F" VerticalAlignment="Center">
              <TextBlock Text="HTML" Foreground="#A78BFA" FontWeight="Bold" FontSize="11" HorizontalAlignment="Center" VerticalAlignment="Center"/>
            </Border>
            <StackPanel Margin="11,0,0,0" VerticalAlignment="Center">
              <TextBlock Text="Веб-версия: Python-сервер + браузер" Foreground="#E8EEF9" FontSize="13.5" FontWeight="SemiBold" TextWrapping="Wrap"/>
              <TextBlock Text="→ папка JARVIS-HTML" Foreground="#A78BFA" FontSize="9.5" FontWeight="Bold" Margin="0,3,0,0"/>
            </StackPanel>
          </DockPanel>
          <StackPanel Grid.Row="1" Margin="2,9,0,0">
            <TextBlock Text="＋ Полные исходники на Python - можно дорабатывать и менять" Foreground="#4ADE80" FontSize="11.5" TextWrapping="Wrap"/>
            <TextBlock Text="＋ Кроссплатформенная: Windows, Linux и macOS" Foreground="#4ADE80" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
            <TextBlock Text="＋ ИИ-модуль со своим API-ключом и самый полный набор команд" Foreground="#4ADE80" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
            <TextBlock Text="－ Нужен Python 3.10+ (лаунчер проверит и поможет установить)" Foreground="#F87171" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
          </StackPanel>
        </Grid>
      </Border>
      <Border x:Name="Card_GO" Tag="GO" CornerRadius="12" Background="#111A31" BorderBrush="#223052" BorderThickness="1.5" Cursor="Hand" Margin="0,8,8,0" Padding="14,12">
        <Grid>
          <Grid.RowDefinitions>
            <RowDefinition Height="Auto"/>
            <RowDefinition Height="*"/>
          </Grid.RowDefinitions>
          <DockPanel>
            <Ellipse x:Name="Dot_GO" DockPanel.Dock="Right" Width="12" Height="12" Fill="#60A5FA" Visibility="Collapsed" VerticalAlignment="Center"/>
            <Border DockPanel.Dock="Left" Width="38" Height="38" CornerRadius="9" Background="#14233F" VerticalAlignment="Center">
              <TextBlock Text="GO" Foreground="#60A5FA" FontWeight="Bold" FontSize="12" HorizontalAlignment="Center" VerticalAlignment="Center"/>
            </Border>
            <StackPanel Margin="11,0,0,0" VerticalAlignment="Center">
              <TextBlock Text="Исходники на Go: один быстрый бинарник" Foreground="#E8EEF9" FontSize="13.5" FontWeight="SemiBold" TextWrapping="Wrap"/>
              <TextBlock Text="→ папка JARVIS-GO" Foreground="#60A5FA" FontSize="9.5" FontWeight="Bold" Margin="0,3,0,0"/>
            </StackPanel>
          </DockPanel>
          <StackPanel Grid.Row="1" Margin="2,9,0,0">
            <TextBlock Text="＋ Python не нужен: всё ядро на Go, минимум памяти" Foreground="#4ADE80" FontSize="11.5" TextWrapping="Wrap"/>
            <TextBlock Text="＋ Тот же интерфейс - веб-страница вшивается в бинарник" Foreground="#4ADE80" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
            <TextBlock Text="－ Нужен Go 1.21+ для сборки (лаунчер проверит и предложит)" Foreground="#F87171" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
            <TextBlock Text="－ Для GUI-окна нужен ещё gcc; это исходники: после скачивания надо собрать" Foreground="#F87171" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
          </StackPanel>
        </Grid>
      </Border>
      <Border x:Name="Card_ANDROID" Tag="ANDROID" CornerRadius="12" Background="#111A31" BorderBrush="#223052" BorderThickness="1.5" Cursor="Hand" Margin="8,8,0,0" Padding="14,12">
        <Grid>
          <Grid.RowDefinitions>
            <RowDefinition Height="Auto"/>
            <RowDefinition Height="*"/>
          </Grid.RowDefinitions>
          <DockPanel>
            <Ellipse x:Name="Dot_ANDROID" DockPanel.Dock="Right" Width="12" Height="12" Fill="#3DDC84" Visibility="Collapsed" VerticalAlignment="Center"/>
            <Border DockPanel.Dock="Left" Width="38" Height="38" CornerRadius="9" Background="#12301F" VerticalAlignment="Center">
              <TextBlock Text="APK" Foreground="#3DDC84" FontWeight="Bold" FontSize="11" HorizontalAlignment="Center" VerticalAlignment="Center"/>
            </Border>
            <StackPanel Margin="11,0,0,0" VerticalAlignment="Center">
              <TextBlock Text="Версия для телефона (APK)" Foreground="#E8EEF9" FontSize="13.5" FontWeight="SemiBold" TextWrapping="Wrap"/>
              <TextBlock Text="→ папка JARVIS-ANDROID" Foreground="#3DDC84" FontSize="9.5" FontWeight="Bold" Margin="0,3,0,0"/>
            </StackPanel>
          </DockPanel>
          <StackPanel Grid.Row="1" Margin="2,9,0,0">
            <TextBlock Text="＋ JARVIS всегда с собой: голос, фонарик, Wi-Fi, ярлыки" Foreground="#4ADE80" FontSize="11.5" TextWrapping="Wrap"/>
            <TextBlock Text="＋ Встроенный ИИ-мозг (свой ключ через OpenAI-совместимый API)" Foreground="#4ADE80" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
            <TextBlock Text="＋ При каждом запуске сам проверяет, не вышла ли новая версия" Foreground="#4ADE80" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
            <TextBlock Text="－ Ставится на телефон (Android 8.0+), а не на компьютер" Foreground="#F87171" FontSize="11.5" TextWrapping="Wrap" Margin="0,3,0,0"/>
          </StackPanel>
        </Grid>
      </Border>
    </UniformGrid>

    <StackPanel Grid.Row="3" Margin="16,6,16,0">
      <TextBlock Text="Папка установки:" Foreground="#93A4C3" FontSize="11" Margin="2,0,0,4"/>
      <Grid>
        <Grid.ColumnDefinitions>
          <ColumnDefinition Width="*"/>
          <ColumnDefinition Width="Auto"/>
        </Grid.ColumnDefinitions>
        <TextBox x:Name="TxtPath" Height="30" Background="#0D1428" Foreground="#E2E8F0" BorderBrush="#223052" BorderThickness="1" Padding="8,5" FontSize="12" VerticalContentAlignment="Center"/>
        <Button x:Name="BtnBrowse" Grid.Column="1" Content="Обзор…" Height="30" Width="86" Margin="8,0,0,0" Style="{StaticResource BtnGhost}" FontSize="11.5"/>
      </Grid>
      <Grid Margin="0,10,0,0">
        <Grid.ColumnDefinitions>
          <ColumnDefinition Width="Auto"/>
          <ColumnDefinition Width="*"/>
        </Grid.ColumnDefinitions>
        <Button x:Name="BtnInstall" Content="УСТАНОВИТЬ" Height="46" Width="200" Style="{StaticResource BtnAccent}" FontSize="14.5"/>
        <StackPanel Grid.Column="1" Margin="14,0,0,0" VerticalAlignment="Center">
          <ProgressBar x:Name="Prog" Height="10" Style="{StaticResource BarStyle}" Minimum="0" Maximum="100" Value="0"/>
          <TextBlock x:Name="TxtStatus" Text="Выбери версию JARVIS и нажми «Установить»" Foreground="#7C8DB0" FontSize="12" Margin="2,8,0,0" TextTrimming="CharacterEllipsis"/>
        </StackPanel>
      </Grid>
      <Border CornerRadius="9" Background="#0A0F1F" BorderBrush="#1B2742" BorderThickness="1" Height="106" Margin="0,10,0,0" Padding="10,7">
        <ScrollViewer x:Name="LogScroll" VerticalScrollBarVisibility="Auto">
          <ItemsControl x:Name="LogList"/>
        </ScrollViewer>
      </Border>
    </StackPanel>

    <Border Grid.Row="4" Background="#0D1528" Margin="0,12,0,0" Padding="16,7">
      <DockPanel>
        <TextBlock DockPanel.Dock="Right" Text="Лаунчер и все приложения JARVIS проверяют обновления при каждом запуске" Foreground="#5B6B8C" FontSize="10.5" VerticalAlignment="Center"/>
        <TextBlock Text="MIT • AdrinaLiness • JARVIS-Launcher v2.0" Foreground="#5B6B8C" FontSize="10.5" VerticalAlignment="Center"/>
      </DockPanel>
    </Border>
  </Grid>
</Window>
'@
$w = [Windows.Markup.XamlReader]::Parse($xamlSrc)

# ---------------- элементы ----------------
$UpdBanner  = $w.FindName('UpdBanner');  $UpdText   = $w.FindName('UpdText')
$BtnUpdNow  = $w.FindName('BtnUpdNow');  $BtnUpdClose = $w.FindName('BtnUpdClose')
$LnkRepo    = $w.FindName('LnkRepo');    $VerBadge  = $w.FindName('VerBadge')
$TxtPath    = $w.FindName('TxtPath');    $BtnBrowse = $w.FindName('BtnBrowse')
$BtnInstall = $w.FindName('BtnInstall'); $Prog      = $w.FindName('Prog')
$TxtStatus  = $w.FindName('TxtStatus');  $LogList   = $w.FindName('LogList')
$LogScroll  = $w.FindName('LogScroll')

function HexBrush([string]$hex) {
    return (New-Object System.Windows.Media.SolidColorBrush((HexToColor $hex)))
}

function Set-Status([string]$text, [string]$color) {
    $TxtStatus.Text = $text
    $TxtStatus.Foreground = HexBrush $color
}

function Add-LogLine([string]$m, [string]$k) {
    $tb = New-Object System.Windows.Controls.TextBlock
    $tb.Text = $m
    $tb.FontSize = 11
    $tb.FontFamily = New-Object System.Windows.Media.FontFamily('Consolas')
    $tb.TextWrapping = [System.Windows.TextWrapping]::Wrap
    $tb.Margin = New-Object System.Windows.Thickness(0, 1, 0, 1)
    switch ($k) {
        'ok'    { $tb.Foreground = HexBrush '#4ADE80' }
        'warn'  { $tb.Foreground = HexBrush '#FBBF24' }
        'err'   { $tb.Foreground = HexBrush '#F87171' }
        'step'  { $tb.Foreground = HexBrush '#67E8F9' }
        default { $tb.Foreground = HexBrush '#9DB2D6' }
    }
    [void]$LogList.Items.Add($tb)
    $script:LogN++
    if ($script:LogN -gt 250) { $LogList.Items.RemoveAt(0); $script:LogN-- }
    $LogScroll.ScrollToEnd()
}

function ProgStartAnim {
    if ($script:ProgAnim) { return }
    $anim = New-Object System.Windows.Media.Animation.DoubleAnimation
    $anim.From = 0
    $anim.To = 100
    $anim.Duration = New-Object System.Windows.Duration([TimeSpan]::FromSeconds(5))
    $anim.RepeatBehavior = [System.Windows.Media.Animation.RepeatBehavior]::Forever
    $Prog.BeginAnimation([System.Windows.Controls.ProgressBar]::ValueProperty, $anim)
    $script:ProgAnim = $anim
}

function ProgStopAnim {
    $Prog.BeginAnimation([System.Windows.Controls.ProgressBar]::ValueProperty, $null)
    $script:ProgAnim = $null
}

# ---------------- выбор карточки ----------------
function Select-Variant([string]$key) {
    $script:Selected = $key
    foreach ($v in $VARIANTS) {
        $card = $w.FindName('Card_' + $v.Key)
        $dot  = $w.FindName('Dot_' + $v.Key)
        if ($v.Key -eq $key) {
            $card.BorderBrush = HexBrush $v.Accent
            $card.BorderThickness = New-Object System.Windows.Thickness(2)
            $card.Background = HexBrush '#142045'
            $fx = New-Object System.Windows.Media.Effects.DropShadowEffect
            $fx.Color = HexToColor $v.Accent
            $fx.BlurRadius = 26; $fx.ShadowDepth = 0; $fx.Opacity = 0.55
            $card.Effect = $fx
            $dot.Visibility = 'Visible'
        } else {
            $card.BorderBrush = HexBrush '#223052'
            $card.BorderThickness = New-Object System.Windows.Thickness(1.5)
            $card.Background = HexBrush '#111A31'
            $card.Effect = $null
            $dot.Visibility = 'Collapsed'
        }
    }
    $sel = Get-Variant $key
    $TxtPath.Text = Join-Path $script:BaseDir $sel.Folder
    if ($script:Phase -eq 'idle') {
        Set-Status ('Выбрано: ' + $key + ' — ' + $sel.Title) '#7C8DB0'
    }
}

foreach ($v in $VARIANTS) {
    $card = $w.FindName('Card_' + $v.Key)
    $card.Add_MouseLeftButtonUp({ param($s, $e) Select-Variant ([string]$s.Tag) })
    $card.Add_MouseEnter({ param($s, $e) if (([string]$s.Tag) -ne $script:Selected) { $s.Background = HexBrush '#16213F' } })
    $card.Add_MouseLeave({ param($s, $e) if (([string]$s.Tag) -ne $script:Selected) { $s.Background = HexBrush '#111A31' } })
}

# ---------------- ссылки и обзор папки ----------------
$LnkRepo.Add_MouseLeftButtonUp({ try { Start-Process $RepoUrl } catch {} })
$VerBadge.Text = 'v' + $LauncherVer

$BtnBrowse.Add_Click({
    try {
        $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
        $dlg.Description = 'Куда установить JARVIS'
        if (Test-Path -LiteralPath $TxtPath.Text) { $dlg.SelectedPath = $TxtPath.Text }
        if ($dlg.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
            $TxtPath.Text = $dlg.SelectedPath
        }
    } catch {
        Set-Status ('Не удалось открыть выбор папки: ' + $_.Exception.Message) '#F87171'
    }
})

# ---------------- проверка зависимостей (GUI) ----------------
# 'ok'  -> Value = путь или '' (продолжаем без программы)
# 'winget' -> Proc запущен, ждём окончания в таймере
# 'abort' -> пользователь прервал
function Ensure-Python-GUI {
    while ($true) {
        $py = Find-Python
        if ($py -and ($py.Ver.Major * 100 + $py.Ver.Minor) -ge 310) {
            Set-Status ("Python найден: {0} ({1}.{2})" -f $py.Label, $py.Ver.Major, $py.Ver.Minor) '#4ADE80'
            Add-LogLine ("Python найден: {0} ({1}.{2})" -f $py.Label, $py.Ver.Major, $py.Ver.Minor) 'ok'
            return @{ State = 'ok'; Value = $py.Exe }
        }
        if ($py) { $msg = "Найден Python {0}.{1}, но нужен 3.10+.`n`n" -f $py.Ver.Major, $py.Ver.Minor }
        else     { $msg = "Python не найден (HTML-версии нужен Python 3.10+).`n`n" }
        $msg += "Да — установить автоматически через winget`nНет — открыть python.org, я перепроверю после установки`nОтмена — продолжить без Python (зависимости поставит Server.bat)"
        $mb = [System.Windows.MessageBox]::Show($w, $msg, 'Python 3.10+', 'YesNoCancel', 'Warning')
        if ($mb -eq 'Yes') {
            if (-not (Get-Command 'winget' -ErrorAction SilentlyContinue)) {
                [void][System.Windows.MessageBox]::Show($w, "winget на этой системе не найден. Открываю python.org — установи Python 3.10+ оттуда.", 'winget не найден', 'OK', 'Information')
                try { Start-Process 'https://www.python.org/downloads/' } catch {}
                $mb2 = [System.Windows.MessageBox]::Show($w, "Когда установишь Python — нажми ОК, я проверю ещё раз.`n(Отмена — продолжить без Python)", 'Жду установку Python', 'OKCancel', 'Information')
                if ($mb2 -eq 'OK') { continue }
                return @{ State = 'ok'; Value = '' }
            }
            try {
                Add-LogLine 'Ставлю Python через winget — откроется окно установки…' 'step'
                $proc = Start-Process -FilePath 'winget' -ArgumentList @('install', '-e', '--id', 'Python.Python.3.12', '--accept-source-agreements', '--accept-package-agreements') -PassThru
                return @{ State = 'winget'; Proc = $proc; Kind = 'python' }
            } catch {
                Set-Status ('winget не запустился: ' + $_.Exception.Message) '#F87171'
                return @{ State = 'abort' }
            }
        }
        if ($mb -eq 'No') {
            try { Start-Process 'https://www.python.org/downloads/' } catch {}
            $mb2 = [System.Windows.MessageBox]::Show($w, "Установи Python 3.10+, затем нажми ОК — я проверю ещё раз.`n(Отмена — продолжить без Python)", 'Жду установку Python', 'OKCancel', 'Information')
            if ($mb2 -eq 'OK') { continue }
            return @{ State = 'ok'; Value = '' }
        }
        return @{ State = 'ok'; Value = '' }
    }
}

function Ensure-Go-GUI {
    while ($true) {
        $g = Find-Go
        if ($g) {
            $v = Get-GoVersion $g
            if ($v -and ($v.Major * 100 + $v.Minor) -ge 121) {
                Set-Status ('Go найден: ' + $g) '#4ADE80'
                Add-LogLine ('Go найден: ' + $g) 'ok'
                return @{ State = 'ok'; Value = $g }
            }
        }
        if ($g -and $v) { $msg = "Найден Go 1.{0}, но нужен 1.21+.`n`n" -f $v.Minor }
        else            { $msg = "Go не найден (GO-версии нужен Go 1.21+ для сборки).`n`n" }
        $msg += "Да — установить автоматически через winget`nНет — открыть go.dev/dl, я перепроверю после установки`nОтмена — продолжить без Go (исходники скачаются, собрать можно позже)"
        $mb = [System.Windows.MessageBox]::Show($w, $msg, 'Go 1.21+', 'YesNoCancel', 'Warning')
        if ($mb -eq 'Yes') {
            if (-not (Get-Command 'winget' -ErrorAction SilentlyContinue)) {
                [void][System.Windows.MessageBox]::Show($w, "winget на этой системе не найден. Открою go.dev/dl — установи Go оттуда.", 'winget не найден', 'OK', 'Information')
                try { Start-Process 'https://go.dev/dl/' } catch {}
                $mb2 = [System.Windows.MessageBox]::Show($w, "Когда установишь Go — нажми ОК, я проверю ещё раз.`n(Отмена — продолжить без Go)", 'Жду установку Go', 'OKCancel', 'Information')
                if ($mb2 -eq 'OK') { continue }
                return @{ State = 'ok'; Value = '' }
            }
            try {
                Add-LogLine 'Ставлю Go через winget — откроется окно установки…' 'step'
                $proc = Start-Process -FilePath 'winget' -ArgumentList @('install', '-e', '--id', 'GoLang.Go', '--accept-source-agreements', '--accept-package-agreements') -PassThru
                return @{ State = 'winget'; Proc = $proc; Kind = 'go' }
            } catch {
                Set-Status ('winget не запустился: ' + $_.Exception.Message) '#F87171'
                return @{ State = 'abort' }
            }
        }
        if ($mb -eq 'No') {
            try { Start-Process 'https://go.dev/dl/' } catch {}
            $mb2 = [System.Windows.MessageBox]::Show($w, "Установи Go 1.21+, затем нажми ОК — я проверю ещё раз.`n(Отмена — продолжить без Go)", 'Жду установку Go', 'OKCancel', 'Information')
            if ($mb2 -eq 'OK') { continue }
            return @{ State = 'ok'; Value = '' }
        }
        return @{ State = 'ok'; Value = '' }
    }
}

function Resume-AfterWinget([string]$kind) {
    if ($kind -eq 'python') {
        $r = Ensure-Python-GUI
        if ($r.State -eq 'ok') { $script:InstPy = $r.Value; Start-EngineRun }
        elseif ($r.State -eq 'winget') { Enter-WingetWait $r }
        else { $script:Phase = 'idle'; $BtnInstall.IsEnabled = $true; Set-Status 'Установка прервана' '#F87171' }
    } else {
        $r = Ensure-Go-GUI
        if ($r.State -eq 'ok') { $script:InstGo = $r.Value; Start-EngineRun }
        elseif ($r.State -eq 'winget') { Enter-WingetWait $r }
        else { $script:Phase = 'idle'; $BtnInstall.IsEnabled = $true; Set-Status 'Установка прервана' '#F87171' }
    }
}

function Enter-WingetWait($r) {
    $script:Phase = 'winget'
    $script:PendingDep = $r
    $BtnInstall.IsEnabled = $false
    if ($r.Kind -eq 'python') { Set-Status 'Ставлю Python через winget — жду окончания установки…' '#FBBF24' }
    else { Set-Status 'Ставлю Go через winget — жду окончания установки…' '#FBBF24' }
}

# ---------------- запуск движка в транспейсе ----------------
function Start-EngineRun {
    $sel = $script:InstSel
    $dest = $script:InstDest
    $script:Phase = 'installing'
    $BtnInstall.IsEnabled = $false
    Set-Status ('Скачиваю файлы JARVIS (' + $sel.Key + ')…') '#67E8F9'
    $sync = [hashtable]::Synchronized(@{})
    $sync.Log = [System.Collections.ArrayList]::Synchronized((New-Object System.Collections.ArrayList))
    $sync.State = 'running'
    $sync.Pct = 0
    $sync.Indet = $false
    $sync.Sel = $sel
    $sync.Dest = $dest
    $sync.SkipDeps = [bool]$SkipDeps
    $sync.PyExe = $script:InstPy
    $sync.GoExe = $script:InstGo
    $script:Sync = $sync
    try {
        $rs = [runspacefactory]::CreateRunspace()
        $rs.Open()
        $rs.SessionStateProxy.SetVariable('sync', $sync)
        $rs.SessionStateProxy.SetVariable('VARIANTS', $VARIANTS)
        $rs.SessionStateProxy.SetVariable('CFG', $CFG)
        $rsp = [powershell]::Create()
        $rsp.Runspace = $rs
        [void]$rsp.AddScript($ENGINE_SRC)
        [void]$rsp.AddScript('Main-Install')
        [void]$rsp.BeginInvoke()
        $script:RS = $rs
        $script:RSP = $rsp
    } catch {
        $sync.State = 'error'
        $sync.ErrMsg = $_.Exception.Message
        Finish-Install
    }
}

function Finish-Install {
    $sync = $script:Sync
    $BtnInstall.IsEnabled = $true
    ProgStopAnim
    if ($sync.State -eq 'done') {
        $script:Phase = 'done'
        $Prog.Value = 100
        $script:LaunchPath = [string]$sync.LaunchPath
        $script:Dest = [string]$sync.Dest
        $BtnInstall.Style = $w.Resources['BtnGreen']
        if ($script:Selected -eq 'ANDROID' -or -not $script:LaunchPath) {
            $BtnInstall.Content = 'ОТКРЫТЬ ПАПКУ'
            Set-Status ('ГОТОВО — файлы в папке: ' + $script:Dest) '#4ADE80'
        } else {
            $BtnInstall.Content = 'ЗАПУСТИТЬ ▶'
            Set-Status ('ГОТОВО — JARVIS установлен: ' + $script:Dest) '#4ADE80'
        }
        Add-LogLine '=== Установка завершена ===' 'ok'
    } else {
        $script:Phase = 'error'
        $BtnInstall.Content = 'ПОВТОРИТЬ'
        Set-Status ('Ошибка: ' + $sync.ErrMsg) '#F87171'
        Add-LogLine ('Ошибка установки: ' + $sync.ErrMsg) 'err'
        Add-LogLine 'Нажми «ПОВТОРИТЬ» или проверь интернет и запусти лаунчер заново.' 'warn'
    }
    $script:Sync = $null
}

# ---------------- установка: проверка -> зависимости -> движок ----------------
function Start-Install {
    $sel = Get-Variant $script:Selected
    if (-not $sel) { return }
    $dest = $TxtPath.Text.Trim()
    if (-not $dest) { Set-Status 'Укажи папку установки' '#F87171'; return }
    if (Test-Path -LiteralPath $dest) {
        $mb = [System.Windows.MessageBox]::Show($w, "Папка '$dest' уже существует.`n`nДа — удалить и поставить заново`nНет — поставить рядом (добавится -2, -3…)`nОтмена — ничего не делать", 'Папка существует', 'YesNoCancel', 'Warning')
        if ($mb -eq 'Cancel') { return }
        if ($mb -eq 'Yes') {
            try { Remove-Item -LiteralPath $dest -Recurse -Force } catch {
                Set-Status ('Не удалось удалить папку: ' + $_.Exception.Message) '#F87171'
                return
            }
        } else {
            $n = 2
            while (Test-Path -LiteralPath ($dest + '-' + $n)) { $n++ }
            $dest = $dest + '-' + $n
        }
    }
    try {
        $full = [System.IO.Path]::GetFullPath($dest)
        $root = [System.IO.Path]::GetPathRoot($full)
        $drive = New-Object System.IO.DriveInfo($root)
        if ($drive.IsReady -and $drive.AvailableFreeSpace -lt 100MB) {
            $mb = [System.Windows.MessageBox]::Show($w, "На диске меньше 100 МБ свободного места. Продолжить установку?", 'Мало места', 'YesNo', 'Warning')
            if ($mb -ne 'Yes') { return }
        }
    } catch {}
    $script:InstSel = $sel
    $script:InstDest = $dest
    $script:InstPy = ''
    $script:InstGo = ''
    switch ($sel.Need) {
        'exe' {
            if (-not (Test-WebView2)) {
                $mb = [System.Windows.MessageBox]::Show($w, "WebView2 не найден в системе (обычно он уже встроен в Windows 10/11).`n`nОткрыть страницу установки WebView2?`n(Если jarvis.exe не откроется - поставь WebView2 и запусти снова)", 'WebView2', 'YesNo', 'Warning')
                if ($mb -eq 'Yes') { try { Start-Process 'https://developer.microsoft.com/microsoft-edge/webview2/' } catch {} }
            }
        }
        'python' {
            $r = Ensure-Python-GUI
            if ($r.State -eq 'winget') { Enter-WingetWait $r; return }
            if ($r.State -eq 'abort') { return }
            $script:InstPy = $r.Value
        }
        'go' {
            $r = Ensure-Go-GUI
            if ($r.State -eq 'winget') { Enter-WingetWait $r; return }
            if ($r.State -eq 'abort') { return }
            $script:InstGo = $r.Value
        }
        'apk' { }
    }
    Start-EngineRun
}

function Do-InstallOrLaunch {
    if ($script:Phase -eq 'done') {
        $lp = $script:LaunchPath
        try {
            if ($script:Selected -eq 'ANDROID' -or -not $lp) {
                Start-Process 'explorer.exe' $script:Dest
            } elseif (([System.IO.Path]::GetExtension($lp)).ToLower() -eq '.bat') {
                Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $lp -WorkingDirectory (Split-Path -Parent $lp)
            } else {
                Start-Process -FilePath $lp -WorkingDirectory (Split-Path -Parent $lp)
            }
        } catch {
            Set-Status ('Не удалось запустить: ' + $_.Exception.Message) '#F87171'
        }
        return
    }
    if ($script:Phase -eq 'error') {
        $script:Phase = 'idle'
        $BtnInstall.Content = 'УСТАНОВИТЬ'
        $BtnInstall.Style = $w.Resources['BtnAccent']
        Set-Status 'Проверяю заново…' '#7C8DB0'
    }
    if ($script:Phase -ne 'idle') { return }
    try {
        Start-Install
    } catch {
        $script:Phase = 'idle'
        Set-Status ('Ошибка: ' + $_.Exception.Message) '#F87171'
    }
}

$BtnInstall.Add_Click({ Do-InstallOrLaunch })

# ---------------- самообновление лаунчера ----------------
function Do-SelfUpdate {
    if ($script:Phase -eq 'installing' -or $script:Phase -eq 'updating' -or $script:Phase -eq 'winget') { return }
    $script:Phase = 'updating'
    $BtnUpdNow.IsEnabled = $false
    Set-Status ('Скачиваю лаунчер v' + $script:UpdLatest + '…') '#67E8F9'
    Add-LogLine ('Самообновление: скачиваю свежий JARVIS-Launcher.bat (v' + $script:UpdLatest + ')') 'step'
    try {
        $map = Get-TreeMap
        $expSha = $null
        $expSize = [long]0
        if ($map -and $map.ContainsKey('EXE/JARVIS-Launcher.bat')) {
            $expSize = $map['EXE/JARVIS-Launcher.bat'].size
            $expSha = $map['EXE/JARVIS-Launcher.bat'].sha
        }
        $newBat = Join-Path $env:TEMP 'JARVIS-Launcher-new.bat'
        $okd = Get-FileVerified 'EXE/JARVIS-Launcher.bat' $newBat $expSize $expSha
        if (-not $okd) { throw 'не удалось скачать новый лаунчер с GitHub' }
        if (-not $BatPath) {
            Set-Status ('Новый лаунчер скачан: ' + $newBat + ' — запусти его') '#4ADE80'
            Add-LogLine ('Новый лаунчер лежит в ' + $newBat) 'ok'
            try { Start-Process 'explorer.exe' ('/select,"' + $newBat + '"') } catch {}
            $script:Phase = 'idle'
            return
        }
        # перезапуск: скрытый powershell с EncodedCommand (без проблем с кодировкой путей)
        $rpl = 'ping -n 2 127.0.0.1 >nul; '
        $rpl += 'Copy-Item -LiteralPath "' + $newBat + '" -Destination "' + $BatPath + '" -Force; '
        $rpl += 'if ($?) { Start-Process -FilePath "' + $BatPath + '" } else { Start-Process -FilePath "' + $newBat + '" }; '
        $rpl += 'Remove-Item -LiteralPath "' + $newBat + '" -ErrorAction SilentlyContinue'
        $enc = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($rpl))
        Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile', '-WindowStyle', 'Hidden', '-EncodedCommand', $enc
        Set-Status 'Лаунчер обновлён — открываю новую версию…' '#4ADE80'
        Add-LogLine 'Лаунчер обновлён. Перезапускаюсь…' 'ok'
        $script:ExitCode = 7
        $w.Close()
    } catch {
        $script:Phase = 'idle'
        $BtnUpdNow.IsEnabled = $true
        Set-Status ('Самообновление не удалось: ' + $_.Exception.Message) '#F87171'
        Add-LogLine ('Самообновление не удалось: ' + $_.Exception.Message) 'err'
    }
}

$BtnUpdNow.Add_Click({ Do-SelfUpdate })
$BtnUpdClose.Add_Click({ $UpdBanner.Visibility = 'Collapsed' })

# ---------------- фоновая проверка обновлений (при каждом запуске) ----------------
$script:Upd = [hashtable]::Synchronized(@{ Done = $false; Info = $null })
try {
    $urs = [runspacefactory]::CreateRunspace()
    $urs.Open()
    $urs.SessionStateProxy.SetVariable('upd', $script:Upd)
    $ups = [powershell]::Create()
    $ups.Runspace = $urs
    $updSrc = @'
try {
    $r = Invoke-RestMethod -Uri 'https://raw.githubusercontent.com/AdrinaLiness/jarvis/main/VERSION.json' -Headers @{ 'User-Agent' = 'JARVIS-Launcher' } -TimeoutSec 6
    $upd.Info = $r
} catch {}
$upd.Done = $true
'@
    [void]$ups.AddScript($updSrc)
    [void]$ups.BeginInvoke()
    $script:URS = $urs
    $script:UPS = $ups
} catch { $script:Upd.Done = $true }

# ---------------- таймер ----------------
function On-Tick {
    $sync = $script:Sync
    if ($sync) {
        while ($sync.Log.Count -gt 0) {
            $e = $sync.Log[0]
            $sync.Log.RemoveAt(0)
            Add-LogLine ([string]$e.m) ([string]$e.k)
        }
        if ($sync.State -eq 'running') {
            if ($sync.Indet) { ProgStartAnim } else { ProgStopAnim; $Prog.Value = [double]$sync.Pct }
        }
        if ($sync.State -eq 'done' -or $sync.State -eq 'error') { Finish-Install }
    }
    if ($script:Phase -eq 'winget' -and $script:PendingDep) {
        $proc = $script:PendingDep.Proc
        if ($proc -and $proc.HasExited) {
            $kind = $script:PendingDep.Kind
            $script:PendingDep = $null
            $BtnInstall.IsEnabled = $true
            $script:Phase = 'idle'
            Add-LogLine ('winget завершил работу — проверяю ' + $kind + ' ещё раз…') 'step'
            Resume-AfterWinget $kind
        }
    }
    if ($script:Upd -and $script:Upd.Done -and -not $script:UpdShown) {
        $script:UpdShown = $true
        try {
            $latest = [string]$script:Upd.Info.components.launcher
            if (Test-Newer $latest $LauncherVer) {
                $UpdText.Text = ('Доступна новая версия лаунчера: v' + $latest + ' (у тебя v' + $LauncherVer + '). Обновлюсь за пару секунд — файл тот же, настройки не тронет.')
                $UpdBanner.Visibility = 'Visible'
                $script:UpdLatest = $latest
                Add-LogLine ('Обновление лаунчера: доступна v' + $latest) 'warn'
            }
        } catch {}
    }
}

$timer = New-Object System.Windows.Threading.DispatcherTimer
$timer.Interval = [TimeSpan]::FromMilliseconds(120)
$timer.Add_Tick({ try { On-Tick } catch {} })
$timer.Start()

# ---------------- анимация реактора ----------------
try {
    $ring = $w.FindName('ReactRing')
    $rot = New-Object System.Windows.Media.RotateTransform
    $ring.RenderTransform = $rot
    $rAnim = New-Object System.Windows.Media.Animation.DoubleAnimation
    $rAnim.From = 0
    $rAnim.To = 360
    $rAnim.Duration = New-Object System.Windows.Duration([TimeSpan]::FromSeconds(9))
    $rAnim.RepeatBehavior = [System.Windows.Media.Animation.RepeatBehavior]::Forever
    $rot.BeginAnimation([System.Windows.Media.RotateTransform]::AngleProperty, $rAnim)
} catch {}

# ---------------- старт ----------------
Select-Variant 'EXE'
Add-LogLine ('J.A.R.V.I.S. Launcher v' + $LauncherVer + ' готов') 'ok'
Add-LogLine 'Проверяю обновления лаунчера…' 'info'

$w.Add_Closed({
    try { if ($script:RSP) { $script:RSP.Stop() } } catch {}
    try { if ($script:RS) { $script:RS.Close() } } catch {}
    try { if ($script:UPS) { $script:UPS.Stop() } } catch {}
    try { if ($script:URS) { $script:URS.Close() } } catch {}
})

[void]$w.ShowDialog()
exit $script:ExitCode

} catch {
    # WPF недоступен (редкий случай) - уходим в консольный режим
    try {
        Write-Host ''
        Write-Host ('[!] Графический интерфейс не открылся: ' + $_.Exception.Message) -ForegroundColor Yellow
        Write-Host '[i] Переключаюсь в консольный режим...' -ForegroundColor Cyan
    } catch {}
    Start-CliFlow
}
