# Changelog

Все заметные изменения проекта J.A.R.V.I.S. задокументированы здесь.

Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.0.0/),
версионирование — [Semantic Versioning](https://semver.org/lang/ru/).

## [2.3.0] — 2026-10-29

### Исправлено — Android v1.1

- **Запуск приложений на Android 11+**: в `AndroidManifest.xml` отсутствовал
  блок `<queries>` — без него система скрывает установленные приложения,
  `getLaunchIntentForPackage()` возвращал `null`, и вместо запуска открывался
  Play Market. Теперь JARVIS видит все запускаемые приложения.
- **Открытие целей**: `openTarget` в UI ходит через нативный мост (`open_url`),
  минуя ненадёжный `window.open` WebView (важно для схем `pkg:`).
- **Вёрстка на узких экранах**: канвасы реактора/волны и шапка растягивали
  layout до ~844px в портрете — грид-колонки теперь сжимаются (min-width:0),
  длинные строки журнала переносятся.

### Добавлено — Android v1.1

- **Умный запуск приложений по названию**: «запусти ютуб», «запусти фотошоп».
  Цепочка: псевдонимы RU/EN (ютуб, телега, хрум, плей маркет…) → точное
  совпадение по названию установленного приложения → вхождение → нечёткий
  поиск (Левенштейн ≥ 0.6). Ярлыки пользователя в ⚙ Настройки приоритетны.
- **Комфорт в портрете и ландшафте** (новый `source/patch/layer3.js`):
  ландшафт — три колонки как на десктопе, компактная шапка, чипы в одну
  строку со скроллом (на экранах ниже 380px реактор уступает место журналу);
  портрет — крупное поле ввода 16px (без автозума клавиатурой), высокий
  журнал, подзаголовок бренда скрыт.

### Изменено

- Персона ИИ по умолчанию: `[[RUN:название]]` запускает приложения на телефоне
  по названию (ютуб, телеграм, whatsapp, карты…), а не только по ярлыкам.
- `ANDROID/`: опубликован `JARVIS-v1.1.apk` (versionCode 2; подпись та же —
  ставится поверх v1.0 без удаления данных), `JARVIS-v1.0.apk` остался
  в релизе v1.0.0 для отката.
- Релиз **v1.1.0** на странице Releases.

## [2.2.0] — 2026-10-28

### Добавлено — Android-приложение

- **Папка `ANDROID/`**: готовый `JARVIS-v1.0.apk` (Android 8.0+, minSdk 24,
  target 34) + исходники (`source/`: Java-мост, патчи UI, скрипт сборки).
- **Тот же UI** (`jarvis.html`) внутри WebView + Java-мост вместо
  Python/Go-бэкенда:
  - голосовой ввод — нативный STT Google (диалог «Слушаю, сэр…»), озвучка —
    системный TTS (RU/EN, пресеты и интонации сохранены);
  - телефонные команды: громкость, фонарик, заряд, Wi-Fi/Bluetooth-настройки,
    медиа-кнопки, ярлыки приложений (`pkg:` / URL, правятся в ⚙ Настройки);
  - ПК-команды корректно отклоняются («это для ПК-версии, сэр»).
- **ИИ-мозг встроен**: чип «ИИ: ВЫКЛ» в шапке → настройки (base URL, модель,
  ключ, персона). Непонятые фразы уходят нейросети (OpenAI-совместимый API),
  метки `[[OPEN]]/[[RUN]]/[[SEARCH]]` исполняются на телефоне.
- **Шим-слой для UI** (интерфейс десктопа работает без изменений):
  WebSocket → синхронный мост, `SpeechRecognition` → нативный STT,
  fetch-шим для `/api/settings*` и `/audit`, раздача ассетов с CORS.
- **Исправление, вошедшее в Android-сборку**: блок «Привязка событий» модалки
  настроек выполнялся до появления модалки в DOM (кнопки «Сохранить / Отмена / ×»
  и вкладки не реагировали). В Android-ассете блок переносится в конец `<body>`.
- Сборка без Gradle: `javac → d8 → aapt2 → zipalign → apksigner`
  (`source/build_apk.sh`).

## [2.1.0] — 2026-10-28

### Добавлено — Settings Panel (GUI)

- **Кнопка «⚙ НАСТРОЙКИ»** в шапке UI — открывает полноэкранный модал настроек.
- **6 вкладок**:
  - **Ярлыки** — CRUD для `shortcuts.json` (добавление/редактирование/удаление ярлыков через UI)
  - **Папки** — список разрешённых папок внутри `%USERPROFILE%` (`Downloads`, `Documents`, + кастомные)
  - **EXE корни** — директории для `launch_exe` (по умолчанию Program Files, + кастомные)
  - **Опасные** — включить/выключить подтверждение для shutdown/restart/sleep/PowerShell/registry/file-edit
  - **Голос** — RU/EN режим, скорость (0.5–2.0), высота (0.5–2.0), кнопка «ТЕСТ ГОЛОСА»
  - **Импорт/Экспорт** — скачать `config.json`, загрузить свой, кнопка «СБРОС К ДЕФОЛТУ»

- **`config.json`** (новый файл) — централизованное хранение всех настроек:
  ```json
  {
    "shortcuts": {...},
    "allowedFolders": [...],
    "allowedExeRoots": [...],
    "dangerousCommands": {...},
    "voice": {"mode","rate","pitch"},
    "server": {"host","port"}
  }
  ```

- **Backend endpoints**:
  - `GET  /api/settings` — отдаёт текущий config
  - `POST /api/settings` — валидирует → бэкапит → сохраняет → применяет в рантайме
  - `POST /api/settings/reset` — сброс к дефолту

- **Runtime-применение настроек** — после POST не нужно перезапускать backend:
  - `ALLOWED_USER_FOLDERS`, `ALLOWED_EXE_ROOTS`, `DANGEROUS_CMDS` обновляются сразу

- **Валидация**:
  - Схемы ярлыков: только `http`, `https`, `steam`, `file` (никакого `javascript:`!)
  - Voice rate/pitch: диапазон 0.5–2.0
  - Whitelist опасных команд: только известные имена

- **Бэкапы**:
  - `~/.dsh/config_backups/config.{ts}.json.bak` — хранятся последние 10
  - Перед каждым сохранением создаётся новый бэкап

- **33 новых теста** в `tests/test_settings.py`:
  - Валидация (schemes, ranges, dangerous toggles)
  - Загрузка/создание config.json
  - apply_config (runtime update)
  - Backup rotation (max 10)
  - HTTP endpoints (GET/POST/reset)
  - Shortcuts loading priority (config.json > shortcuts.json)
  - Audit integration

### Изменено
- `ALLOWED_USER_FOLDERS` теперь mutable (можно менять через GUI)
- `ALLOWED_EXE_ROOTS` теперь хранятся как строки (не Path)
- `_load_shortcuts()` теперь приоритетно читает из `config.json`
- При старте backend загружает `config.json` и применяет `apply_config()`

---

## [2.0.0] — 2026-10-28

### Добавлено
- **Backend на Python (aiohttp)**: HTTP+WebSocket сервер на `127.0.0.1:8000`.
- **Whitelist команд (31 команда)**:
  - A: `open_url`, `open_path`, `launch_app`, `launch_exe`, `steam_game`
  - B: `system_info`, `volume_set`, `volume_get`, `volume_mute`, `brightness_set`, `shutdown`, `restart`, `sleep_pc`, `lock_pc`, `cancel_shutdown`
  - C: `list_dir`, `create_folder`, `delete_file`, `open_explorer`, `screenshot`, `file_size`, `clipboard_set`, `search_files`
  - D: `media_play_pause`, `media_next`, `media_prev`, `minimize_all`, `show_desktop`
  - E (опасные): `run_powershell` (с маркером `#jarvis-approved`), `edit_registry` (только HKCU\Software\Jarvis\*), `edit_file` (только текстовые расширения)
- **Двойное подтверждение** опасных команд через token (TTL 60 сек).
- **Безопасность**:
  - Слушает только `127.0.0.1` (не `0.0.0.0`)
  - Origin whitelist (`localhost:8000`, `127.0.0.1:8000`)
  - Rate-limit: 10 команд/сек с IP
  - Флаг `--i-understand-the-risks` обязателен для запуска
- **Аудит**: `jarvis_audit.log` (JSON lines), эндпоинт `/audit` для UI.
- **Dispatcher**: класс с `execute()`, `confirm()`, `deny()`, `take()`, `allow()`.
- **`process_request()`** — единая точка входа для WS-сообщений.
- **Тесты (69 шт.)**: pytest, покрыты все whitelist-функции, WS-слой, security.
- **README.md** с 10 разделами (установка, команды, опасные, аудит, откат, тесты, безопасность, архитектура, риски).
- **MARVEL-grade голос**:
  - Улучшенный скоринг голосов (приоритет Google UK English Male, Microsoft George, Apple Daniel)
  - 8 пресетов интонации: `default`, `formal`, `alert`, `confirmation`, `danger`, `thinking`, `boot`, `error`
  - Случайные приветствия при старте (6 вариантов RU/EN)
  - Pre-filler фразы («At once, sir», «Right away, sir» и т.д.)
  - Scan-эффект (нисходящий свип 800→120 Hz) перед важными сообщениями
  - Микро-вариации pitch/rate между фразами (естественность)
  - Подъём pitch в конце вопросов, замедление в восклицаниях
- **Индикатор WS** в шапке (зелёный/красный/жёлтый)
- **Панель «АУДИТ»** с автообновлением каждые 3 сек
- **Модал подтверждения** опасных команд (красный overlay)
- **Голосовое подтверждение** «да» / «нет» при открытом модале
- **Новые regex**: «сверни всё», «покажи рабочий стол», «громче», «тише»
- **`Server.bat --test`** — прогон pytest без запуска backend

### Изменено
- **Приоритет команд в `handleCommand`**: BACKEND_PATTERNS проверяются ПЕРВЫМИ (до локальных CMDS). Это исправляет баг, когда «выключи компьютер» перехватывалось локальной командой «Выход».
- **Поле аудита**: `command` → `cmd` (соответствует UI). Фикс бага `undefined` в панели аудита.
- **Regex «Выход»**: `/выключ|отключ/i` → `/^(?:выключись|отключись|заверши работу|пока|goodbye|shutdown)$/i` — теперь ловит только точные фразы, не префиксы.

### Безопасность
- **HKCU-only для реестра**: `edit_registry` принимает только `HKCU`/`HKEY_CURRENT_USER`, путь должен начинаться с `Software\Jarvis\`.
- **Текстовые расширения для `edit_file`**: `.txt .md .json .yaml .xml .csv .ini .py .js ...` (не `.exe`, `.bat` и т.д.).
- **Бэкапы**: перед `edit_registry` → `~/.dsh/registry_backup/{ts}.reg`, перед `edit_file` → `~/.dsh/jarvis_backups/{basename}.{ts}.bak`.
- **Marкер `#jarvis-approved`** обязателен для `run_powershell`.

### Тесты
- 69 тестов в `tests/test_backend.py` (Linux-совместимая часть проходит, Windows-only помечены для запуска на Windows).
- Smoke-test `scripts/smoke_test.py` (7 проверок: ping, commands, unknown, system_info, shutdown+confirm, /audit).

---

## [1.0.0] — предыдущая версия

Простой `python -m http.server 8000` + `jarvis.html` без backend, без WebSocket, без whitelist. Только локальные команды (столицы, цвета, умножение) и launcher через `shortcuts.json`.
