#!/usr/bin/env python3
"""
J.A.R.V.I.S. backend — локальный HTTP+WebSocket сервер для голосового ассистента.

Раздаёт jarvis.html / shortcuts.json / статику на 127.0.0.1:8000,
принимает команды через WebSocket /ws и исполняет только whitelisted-действия.

Команды разделены на:
  A) Открытие/запуск (open_url, open_path, launch_app, launch_exe, steam_game)
  B) Системные      (system_info, volume_*, brightness_set, shutdown, restart, sleep_pc, lock_pc, cancel_shutdown)
  C) Файлы/папки    (list_dir, create_folder, delete_file, open_explorer, screenshot, file_size, clipboard_set, search_files)
  D) Медиа/Windows  (media_play_pause, media_next, media_prev, minimize_all, show_desktop)
  E) Опасные        (run_powershell, edit_registry, edit_file) — требуют двойного подтверждения

Опасные команды (E + shutdown/restart/sleep_pc) возвращают {"need_confirm": true, "token": "..."}.
Клиент должен подтвердить через отдельное сообщение {"cmd": "confirm", "token": "..."}.

Запуск:
    python backend.py --i-understand-the-risks

Без флага --i-understand-the-risks сервер не стартует (защита от случайного запуска).
"""
from __future__ import annotations

import argparse
import base64
import ctypes
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
import webbrowser
from collections import defaultdict, deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

try:
    from aiohttp import WSMsgType, web
except ImportError:  # pragma: no cover
    print("ERROR: aiohttp не установлен. Выполни: pip install -r requirements.txt", file=sys.stderr)
    sys.exit(1)


# ============================================================================
# Константы / пути
# ============================================================================

HOST = "127.0.0.1"
DEFAULT_PORT = 8000
ALLOWED_ORIGINS = {
    "http://localhost:8000",
    "http://127.0.0.1:8000",
    "http://localhost",
    "http://127.0.0.1",
}
RATE_LIMIT = 10           # команд в секунду с одного IP
CONFIRM_TTL_SEC = 60      # время жизни токена подтверждения
AUDIT_TAIL = 20           # сколько последних записей отдаёт /audit
SHUTDOWN_DELAY_MAX = 3600 # максимум секунд до shutdown/restart

ROOT = Path(__file__).parent.resolve()
AUDIT_LOG = ROOT / "jarvis_audit.log"
SHORTCUTS_FILE = ROOT / "shortcuts.json"
CONFIG_FILE = ROOT / "config.json"          # новый: все настройки GUI

USER_HOME = Path.home()
BACKUP_DIR = USER_HOME / ".dsh" / "jarvis_backups"
REG_BACKUP_DIR = USER_HOME / ".dsh" / "registry_backup"
CONFIG_BACKUP_DIR = USER_HOME / ".dsh" / "config_backups"

# Mutable-контейнеры (можно изменять через GUI без перезапуска backend)
DEFAULT_ALLOWED_USER_FOLDERS = {
    "Downloads", "Documents", "Desktop", "Pictures", "Music", "Videos",
    "Загрузки", "Документы", "Рабочий стол", "Изображения", "Музыка", "Видео",
}
ALLOWED_USER_FOLDERS: set[str] = set(DEFAULT_ALLOWED_USER_FOLDERS)

DEFAULT_ALLOWED_EXE_ROOTS = [
    r"C:\Program Files",
    r"C:\Program Files (x86)",
    os.environ.get("LOCALAPPDATA", r"C:\Users\Public"),
]
ALLOWED_EXE_ROOTS: list[str] = list(DEFAULT_ALLOWED_EXE_ROOTS)

# Какие команды считаются опасными (можно отключать через GUI)
DEFAULT_DANGEROUS_ENABLED = {
    "shutdown": True,
    "restart": True,
    "sleep_pc": True,
    "run_powershell": True,
    "edit_registry": True,
    "edit_file": True,
}
DANGEROUS_ENABLED: dict[str, bool] = dict(DEFAULT_DANGEROUS_ENABLED)

TEXT_EXTENSIONS = {
    ".txt", ".md", ".json", ".yaml", ".yml", ".xml", ".csv", ".tsv",
    ".ini", ".cfg", ".conf", ".log", ".py", ".js", ".ts", ".html",
    ".css", ".bat", ".ps1", ".sh", ".sql", ".toml", ".rst",
}

# Виртуальные клавиши Windows (для media / show_desktop / minimize)
VK = {
    "media_play_pause": 0xB3,
    "media_next_track": 0xB0,
    "media_prev_track": 0xB1,
    "volume_mute":      0xAD,
    "win":              0x5B,  # LWIN
    "d":                0x44,
    "m":                0x4D,
}
KEYEVENTF_KEYUP = 0x0002

log = logging.getLogger("jarvis")


# ============================================================================
# Mock-able активный слой (для pytest)
# ============================================================================

RUNNER_KEY = "runner"


def _real_runner(cmd: list[str], timeout: float = 15.0, capture: bool = True) -> tuple[int, str, str]:
    """Реальный запуск subprocess без shell. Возвращает (exit_code, stdout, stderr)."""
    try:
        p = subprocess.run(
            cmd,
            capture_output=capture,
            text=True,
            timeout=timeout,
            check=False,
            shell=False,
        )
        return (p.returncode, (p.stdout or "").strip(), (p.stderr or "").strip())
    except FileNotFoundError:
        return (-1, "", "not found")
    except subprocess.TimeoutExpired:
        return (-1, "", f"timeout after {timeout}s")
    except Exception as e:  # noqa: BLE001
        return (-1, "", f"internal: {e}")


# Активные хендлеры. Тесты подменяют значения в этом dict.
_ACTIVE: dict[str, Any] = {
    RUNNER_KEY: _real_runner,
}


def _run(cmd: list[str], timeout: float = 15.0, capture: bool = True) -> tuple[int, str, str]:
    """Запуск команды через активный runner (mock-able через _ACTIVE[RUNNER_KEY])."""
    runner = _ACTIVE[RUNNER_KEY]
    return runner(cmd, timeout=timeout, capture=capture)


def key_event(vk: int, count: int = 1) -> bool:
    """Эмуляция нажатия виртуальной клавиши через user32.keybd_event. На не-Windows — False."""
    if sys.platform != "win32":
        return False
    try:
        user32 = ctypes.windll.user32
        for _ in range(count):
            user32.keybd_event(vk, 0, 0, 0)
            user32.keybd_event(vk, 0, KEYEVENTF_KEYUP, 0)
        return True
    except Exception:  # noqa: BLE001
        return False


# ============================================================================
# Утилиты путей
# ============================================================================

def _is_within(child: Path, parent: Path) -> bool:
    """True, если child лежит внутри parent (после resolve)."""
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except (ValueError, OSError):
        return False


def inside_profile(path: Path | str) -> bool:
    """True, если path находится внутри USER_HOME."""
    try:
        Path(path).resolve().relative_to(USER_HOME.resolve())
        return True
    except (ValueError, OSError):
        return False


def resolve_path(path: str | Path) -> Path:
    """Расширяет ~ и возвращает абсолютный путь."""
    return Path(path).expanduser().resolve()


def allowed_user_dir(path: Path | str) -> tuple[bool, str]:
    """Проверяет, что папка — одна из whitelisted внутри USER_HOME.

    Возвращает (ok, message). message — имя разрешённой папки или причина отказа.
    """
    p = resolve_path(path)
    if not inside_profile(p):
        return (False, "outside user profile")
    try:
        rel = p.relative_to(USER_HOME)
    except ValueError:
        return (False, "outside user profile")
    if not rel.parts:
        return (True, "user profile root")
    # Проверяем как EN, так и RU-имя (из config)
    folder_name = rel.parts[0]
    folder_lower = folder_name.lower()
    allowed_lower = {f.lower() for f in ALLOWED_USER_FOLDERS}
    if folder_lower not in allowed_lower:
        return (False, f"folder not in whitelist: {folder_name}")
    return (True, str(folder_name))


# ============================================================================
# CONFIG (config.json) — управление настройками через GUI
# ============================================================================

def _default_config() -> dict[str, Any]:
    """Дефолтный config (используется при первом запуске)."""
    return {
        "shortcuts": _load_shortcuts_file(),
        "allowedFolders": sorted(DEFAULT_ALLOWED_USER_FOLDERS),
        "allowedExeRoots": list(DEFAULT_ALLOWED_EXE_ROOTS),
        "dangerousCommands": dict(DEFAULT_DANGEROUS_ENABLED),
        "voice": {
            "mode": "russian",          # 'russian' | 'authentic'
            "rate": 1.0,
            "pitch": 1.0,
        },
        "server": {
            "host": HOST,
            "port": DEFAULT_PORT,
        },
    }


def _load_shortcuts_file() -> dict[str, str]:
    """Читает shortcuts.json напрямую (без config.json)."""
    try:
        with SHORTCUTS_FILE.open("r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return {str(k): str(v) for k, v in data.items()}
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}


def _load_config() -> dict[str, Any]:
    """Загружает config.json. Если его нет — создаёт из дефолта."""
    if not CONFIG_FILE.exists():
        cfg = _default_config()
        try:
            with CONFIG_FILE.open("w", encoding="utf-8") as f:
                json.dump(cfg, f, ensure_ascii=False, indent=2)
            log.info("config.json создан с дефолтными настройками: %s", CONFIG_FILE)
        except OSError as e:
            log.warning("не удалось создать config.json: %s", e)
        return cfg
    try:
        with CONFIG_FILE.open("r", encoding="utf-8") as f:
            cfg = json.load(f)
        if not isinstance(cfg, dict):
            log.warning("config.json: не dict, использую дефолт")
            return _default_config()
        # Backfill отсутствующих ключей из дефолта
        default = _default_config()
        for k, v in default.items():
            if k not in cfg:
                cfg[k] = v
        return cfg
    except (json.JSONDecodeError, OSError) as e:
        log.warning("config.json не прочитан (%s), использую дефолт", e)
        return _default_config()


def apply_config(cfg: dict[str, Any]) -> None:
    """Применяет config к глобальным переменным (без перезапуска backend)."""
    global ALLOWED_USER_FOLDERS, ALLOWED_EXE_ROOTS, DANGEROUS_ENABLED
    folders = cfg.get("allowedFolders", [])
    if isinstance(folders, list) and folders:
        ALLOWED_USER_FOLDERS = set(str(f) for f in folders)
    else:
        ALLOWED_USER_FOLDERS = set(DEFAULT_ALLOWED_USER_FOLDERS)

    roots = cfg.get("allowedExeRoots", [])
    if isinstance(roots, list) and roots:
        ALLOWED_EXE_ROOTS = [str(r) for r in roots]
    else:
        ALLOWED_EXE_ROOTS = list(DEFAULT_ALLOWED_EXE_ROOTS)

    dangerous = cfg.get("dangerousCommands", {})
    if isinstance(dangerous, dict):
        DANGEROUS_ENABLED = {k: bool(v) for k, v in dangerous.items()}
    else:
        DANGEROUS_ENABLED = dict(DEFAULT_DANGEROUS_ENABLED)

    # Синхронизируем DANGEROUS_CMDS с DANGEROUS_ENABLED
    # (команды с False в DANGEROUS_ENABLED не считаются опасными)
    _rebuild_dangerous_set()


def _rebuild_dangerous_set() -> None:
    """Перестраивает DANGEROUS_CMDS на основе DANGEROUS_ENABLED."""
    global DANGEROUS_CMDS
    DANGEROUS_CMDS = {
        name for name, enabled in DANGEROUS_ENABLED.items() if enabled
    }


def _load_shortcuts() -> dict[str, str]:
    """Читает ярлыки. Приоритет: config.json → shortcuts.json → пустой dict."""
    # 1) Если config.json уже загружен — берём shortcuts из него
    if CONFIG_FILE.exists():
        try:
            with CONFIG_FILE.open("r", encoding="utf-8") as f:
                cfg = json.load(f)
            if isinstance(cfg, dict) and isinstance(cfg.get("shortcuts"), dict):
                return {str(k): str(v) for k, v in cfg["shortcuts"].items()}
        except (json.JSONDecodeError, OSError) as e:
            log.warning("config.json не прочитан (%s), fallback на shortcuts.json", e)
    # 2) Fallback: shortcuts.json
    try:
        with SHORTCUTS_FILE.open("r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            return {str(k): str(v) for k, v in data.items()}
    except (FileNotFoundError, json.JSONDecodeError, OSError) as e:
        log.warning("shortcuts.json не прочитан: %s", e)
    return {}


# ============================================================================
# A. ОТКРЫТИЕ / ЗАПУСК
# ============================================================================

def cmd_open_url(url: str) -> str:
    """Открывает URL в браузере по умолчанию.

    Разрешены только http/https. Если схемы нет — добавляет https://.
    Если схема есть, но не http/https (file://, javascript:, ftp://) — бросает ValueError.
    """
    if not isinstance(url, str) or not url.strip():
        raise ValueError("empty url")
    u = url.strip()
    # Если есть явная схема (что-то перед первым ":")
    m = re.match(r"^([a-zA-Z][a-zA-Z0-9+.\-]*):", u)
    if m:
        scheme = m.group(1).lower()
        if scheme not in ("http", "https"):
            raise ValueError(f"scheme not allowed: {scheme}")
    else:
        # Нет схемы → добавляем https://
        u = "https://" + u
    # Финальная проверка: после нормализации схема должна быть http/https
    if "://" in u:
        scheme = u.split("://", 1)[0].lower()
        if scheme not in ("http", "https"):
            raise ValueError(f"scheme not allowed: {scheme}")
    webbrowser.open(u)
    return u


def cmd_open_path(path: str) -> str:
    """os.startfile для файла/папки внутри %USERPROFILE% и whitelisted-папок."""
    p = resolve_path(path)
    ok, msg = allowed_user_dir(p)
    if not ok:
        raise PermissionError(msg)
    if not p.exists():
        raise FileNotFoundError(str(p))
    os.startfile(str(p))  # type: ignore[attr-defined]
    return str(p)


def cmd_launch_app(name: str) -> str:
    """Запускает приложение/ссылку из shortcuts.json (регистронезависимо).

    Бросает KeyError, если имени нет в shortcuts.json.
    """
    shortcuts = _load_shortcuts()
    target: str | None = None
    matched_key: str | None = None
    for k, v in shortcuts.items():
        if k.lower() == str(name).lower():
            target = v
            matched_key = k
            break
    if not target:
        raise KeyError(name)
    os.startfile(target)  # type: ignore[attr-defined]
    return matched_key or name


def cmd_launch_exe(path: str) -> str:
    """Запускает .exe только из Program Files / Program Files (x86) / LocalAppData.

    Бросает ValueError для не-.exe, PermissionError вне разрешённых корней,
    FileNotFoundError если файла нет.
    """
    p = Path(path).resolve()
    if p.suffix.lower() != ".exe":
        raise ValueError("only .exe allowed")
    allowed_roots = [r for r in ALLOWED_EXE_ROOTS if r and Path(r).exists()]
    if not any(_is_within(p, root) for root in allowed_roots):
        raise PermissionError("exe not in allowed directories")
    if not p.exists():
        raise FileNotFoundError(str(p))
    subprocess.Popen([str(p)], close_fds=True)
    return str(p)


def cmd_steam_game(appid: str | int) -> str:
    """Запускает игру через steam://rungameid/{appid}. appid — только цифры."""
    appid_s = str(appid).strip()
    if not appid_s.isdigit():
        raise ValueError("appid must be numeric")
    target = f"steam://rungameid/{appid_s}"
    os.startfile(target)  # type: ignore[attr-defined]
    return target


# ============================================================================
# B. СИСТЕМНЫЕ
# ============================================================================

def cmd_system_info() -> str:
    """CPU%, RAM%, диски, ОС. Требует psutil (fallback на platform)."""
    import platform
    try:
        import psutil  # type: ignore  # noqa: WPS433
    except ImportError:
        return f"ОС: {platform.system()} {platform.release()}"
    try:
        cpu = psutil.cpu_percent(interval=0.5)
        ram = psutil.virtual_memory()
        lines = [
            f"ОС: {platform.system()} {platform.release()}",
            f"CPU: {cpu}%",
            f"RAM: {ram.percent}% (free {ram.available // (1024 ** 2)} MB)",
        ]
        for part in psutil.disk_partitions(all=False):
            try:
                usage = psutil.disk_usage(part.mountpoint)
                lines.append(
                    f"DISK {part.device}: {usage.percent}% "
                    f"(free {usage.free // (1024 ** 3)} GB)"
                )
            except (PermissionError, OSError):
                continue
        return "\n".join(lines)
    except Exception as e:  # noqa: BLE001
        return f"ОС: {platform.system()} {platform.release()} (error: {e})"


def cmd_volume_set(n: int | str) -> str:
    """Устанавливает громкость 0–100 через winmm.waveOutSetVolume (0xFFFF = 100%)."""
    level = int(n)
    if level < 0 or level > 100:
        raise ValueError("volume must be 0..100")
    vol = int((level / 100.0) * 0xFFFF)
    ctypes.windll.winmm.waveOutSetVolume(0, vol)
    return f"volume -> {level}%"


def cmd_volume_get() -> str:
    """Возвращает текущую громкость в процентах (через winmm.waveOutGetVolume)."""
    ref = ctypes.c_uint32(0)
    ctypes.windll.winmm.waveOutGetVolume(0, ctypes.byref(ref))
    level = ref.value & 0xFFFF
    pct = round(level / 0xFFFF * 100)
    return f"{pct}%"


def cmd_volume_mute() -> str:
    """Переключает mute через VK_VOLUME_MUTE."""
    key_event(VK["volume_mute"], 1)
    return "mute toggled"


def cmd_brightness_set(n: int | str) -> str:
    """Устанавливает яркость 0–100 через WMI (WmiMonitorBrightnessMethods)."""
    level = int(n)
    if level < 0 or level > 100:
        raise ValueError("brightness must be 0..100")
    try:
        import wmi  # type: ignore  # noqa: WPS433
    except ImportError:
        return "wmi not installed (pip install wmi)"
    c = wmi.WMI(namespace="wmi")
    methods = c.WmiMonitorBrightnessMethods()[0]
    methods.WmiSetBrightness(level, 0)
    return f"brightness -> {level}%"


def cmd_shutdown(delay_sec: int | str = 60) -> str:
    """shutdown /s /t {delay}, delay clamped to SHUTDOWN_DELAY_MAX (3600)."""
    delay = max(0, min(SHUTDOWN_DELAY_MAX, int(delay_sec)))
    code, out, err = _run(["shutdown", "/s", "/t", str(delay)])
    if code != 0:
        raise RuntimeError(err or out or "shutdown failed")
    return f"shutdown scheduled in {delay}s"


def cmd_restart(delay_sec: int | str = 60) -> str:
    """shutdown /r /t {delay}, delay clamped to SHUTDOWN_DELAY_MAX (3600)."""
    delay = max(0, min(SHUTDOWN_DELAY_MAX, int(delay_sec)))
    code, out, err = _run(["shutdown", "/r", "/t", str(delay)])
    if code != 0:
        raise RuntimeError(err or out)
    return f"restart scheduled in {delay}s"


def cmd_sleep_pc() -> str:
    """SetSuspendState(False, False, False) через ctypes.windll.powrprof."""
    ctypes.windll.powrprof.SetSuspendState(False, False, False)
    return "suspend requested"


def cmd_lock_pc() -> str:
    """rundll32 user32.dll,LockWorkStation."""
    code, out, err = _run(["rundll32.exe", "user32.dll,LockWorkStation"])
    if code != 0:
        raise RuntimeError(err)
    return "locked"


def cmd_cancel_shutdown() -> str:
    """shutdown /a — отменяет запланированное выключение."""
    code, out, err = _run(["shutdown", "/a"])
    if code != 0:
        raise RuntimeError(err)
    return "shutdown cancelled"


# ============================================================================
# C. ФАЙЛЫ / ПАПКИ
# ============================================================================

def cmd_list_dir(path: str) -> str:
    """Список файлов внутри %USERPROFILE% (папки [D] / файлы [F])."""
    p = resolve_path(path)
    ok, msg = allowed_user_dir(p)
    if not ok:
        raise PermissionError(msg)
    if not p.is_dir():
        raise FileNotFoundError(f"not a directory: {p}")
    entries = sorted(p.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower()))
    return "\n".join(f"{'[D]' if e.is_dir() else '[F]'} {e.name}" for e in entries)


def cmd_create_folder(path: str) -> str:
    """os.makedirs внутри %USERPROFILE%."""
    p = resolve_path(path)
    if not inside_profile(p):
        raise PermissionError("outside user profile")
    p.mkdir(parents=True, exist_ok=True)
    return str(p)


def cmd_delete_file(path: str) -> str:
    """Удаляет файл (в Корзину через send2trash, иначе os.remove).

    Только внутри %USERPROFILE%.
    """
    p = resolve_path(path)
    if not inside_profile(p):
        raise PermissionError("outside user profile")
    if not p.is_file():
        raise FileNotFoundError(str(p))
    try:
        from send2trash import send2trash  # type: ignore  # noqa: WPS433
        send2trash(str(p))
        return f"moved to trash: {p}"
    except ImportError:
        p.unlink()
        return f"deleted: {p}"


def cmd_open_explorer(path: str) -> str:
    """Открывает проводник на указанной папке (внутри %USERPROFILE%)."""
    p = resolve_path(path)
    ok, msg = allowed_user_dir(p)
    if not ok:
        raise PermissionError(msg)
    if not p.exists():
        raise FileNotFoundError(str(p))
    subprocess.Popen(["explorer.exe", str(p)])
    return str(p)


def cmd_screenshot() -> str:
    """Скриншот экрана в ~/Pictures/jarvis_{ts}.png.

    Приоритет: PIL.ImageGrab → PowerShell (System.Windows.Forms + System.Drawing).
    Fallback на самый свежий jarvis_*.png если есть.
    """
    pictures = USER_HOME / "Pictures"
    pictures.mkdir(parents=True, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_path = pictures / f"jarvis_{ts}.png"

    # PIL путь (только на Windows или при наличии display)
    if sys.platform == "win32":
        try:
            from PIL import ImageGrab  # type: ignore  # noqa: WPS433
            img = ImageGrab.grab()
            img.save(out_path)
            return str(out_path)
        except Exception:  # noqa: BLE001
            pass

    # PowerShell fallback
    ps_script = (
        "Add-Type -AssemblyName System.Windows.Forms,"
        "System.Drawing;"
        "$b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds;"
        "$bmp = New-Object System.Drawing.Bitmap($b.Width, $b.Height);"
        "$g = [System.Drawing.Graphics]::FromImage($bmp);"
        "$g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size);"
        f"$bmp.Save('{out_path}');"
        "$g.Dispose(); $bmp.Dispose()"
    )
    code, out, err = _run(["powershell", "-NoProfile", "-Command", ps_script])
    if code != 0 or not out_path.exists():
        existing = sorted(pictures.glob("jarvis_*.png"))
        if existing:
            return str(existing[-1])
        raise RuntimeError(err or "screenshot failed")
    return str(out_path)


def cmd_file_size(path: str) -> str:
    """Размер файла в байтах."""
    p = resolve_path(path)
    if not p.is_file():
        raise FileNotFoundError(str(p))
    return str(p.stat().st_size)


def cmd_clipboard_set(text: str) -> str:
    """Установка текста в буфер обмена через PowerShell (base64-encoded UTF-16LE)."""
    if not text:
        raise ValueError("empty text")
    b64 = base64.b64encode(text.encode("utf-16-le")).decode("ascii")
    ps = (
        "Add-Type -AssemblyName System.Windows.Forms;"
        "[System.Windows.Forms.Clipboard]::SetText("
        "[System.Text.Encoding]::Unicode.GetString("
        f"[Convert]::FromBase64String('{b64}')))"
    )
    code, out, err = _run(["powershell", "-NoProfile", "-Command", ps])
    if code != 0:
        raise RuntimeError(err)
    return f"clipboard set: {len(text)} chars"


def cmd_search_files(needle: str, path: str | None = None) -> str:
    """Поиск файлов по имени внутри USER_HOME (или path). Глубина ≤ 5."""
    p = resolve_path(path or str(USER_HOME))
    if not inside_profile(p):
        raise PermissionError("outside user profile")
    matches: list[Path] = []
    for root, dirs, files in os.walk(p):
        depth = len(Path(root).relative_to(p).parts)
        if depth >= 5:
            dirs[:] = []
            continue
        for name in files:
            if needle.lower() in name.lower():
                matches.append(Path(root) / name)
                if len(matches) >= 50:
                    return "\n".join(str(m) for m in matches)
    if not matches:
        return "no matches"
    return "\n".join(str(m) for m in matches)


# ============================================================================
# D. МЕДИА / WINDOWS
# ============================================================================

def cmd_media_play_pause() -> bool:
    """VK_MEDIA_PLAY_PAUSE через _ACTIVE['key'] или key_event."""
    fn = _ACTIVE.get("key", key_event)
    fn(VK["media_play_pause"], 1)
    return True


def cmd_media_next() -> bool:
    """VK_MEDIA_NEXT_TRACK."""
    fn = _ACTIVE.get("key", key_event)
    fn(VK["media_next_track"], 1)
    return True


def cmd_media_prev() -> bool:
    """VK_MEDIA_PREV_TRACK."""
    fn = _ACTIVE.get("key", key_event)
    fn(VK["media_prev_track"], 1)
    return True


def cmd_minimize_all() -> str:
    """Свернуть все окна через Shell.Application.MinimizeAll (PowerShell).

    Fallback на keybd_event Win+M если PowerShell недоступен.
    """
    ps = (
        "$w = New-Object -ComObject Shell.Application;"
        "$w.MinimizeAll()"
    )
    code, out, err = _run(["powershell", "-NoProfile", "-Command", ps])
    if code != 0:
        if sys.platform == "win32":
            user32 = ctypes.windll.user32
            user32.keybd_event(VK["win"], 0, 0, 0)
            user32.keybd_event(VK["m"], 0, 0, 0)
            user32.keybd_event(VK["m"], 0, KEYEVENTF_KEYUP, 0)
            user32.keybd_event(VK["win"], 0, KEYEVENTF_KEYUP, 0)
        else:
            raise RuntimeError(err or "minimize failed")
    return "minimized"


def cmd_show_desktop() -> bool:
    """Win+D через key_event (переключатель рабочего стола)."""
    key_event(VK["win"], 1)
    key_event(VK["d"], 1)
    return True


# ============================================================================
# E. ОПАСНЫЕ (требуют подтверждения через токен)
# ============================================================================

def cmd_run_powershell(script: str) -> str:
    """Запуск PowerShell-скрипта. ТОЛЬКО если есть маркер '#jarvis-approved'.

    Без маркера бросает PermissionError. Маркер — защита от случайного запуска
    произвольных скриптов: даже если кто-то отправит вредоносный код, backend
    откажется его выполнять без явного маркера.
    """
    if not isinstance(script, str) or "#jarvis-approved" not in script:
        raise PermissionError("script missing '#jarvis-approved' marker")
    code, out, err = _run(["powershell", "-NoProfile", "-Command", script])
    if code != 0:
        raise RuntimeError(err or out)
    return out or "ok"


def cmd_edit_registry(hive: str, path: str, name: str, value: str) -> str:
    """Реестр: только HKCU + только Software\\\\Jarvis\\\\*.

    Перед изменением делает бэкап текущего ключа в REG_BACKUP_DIR/{ts}.reg.
    """
    hive_upper = str(hive).upper()
    if hive_upper not in ("HKCU", "HKEY_CURRENT_USER"):
        raise PermissionError(f"hive not allowed: {hive}")
    path_norm = str(path).replace("/", "\\").strip("\\")
    if not path_norm.lower().startswith("software\\jarvis\\"):
        raise PermissionError(f"path must start with Software\\Jarvis\\: {path_norm}")

    REG_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_file = REG_BACKUP_DIR / f"{ts}.reg"
    # Экспорт текущего состояния (для отката)
    _run(["reg", "export", "HKCU", path_norm, str(backup_file), "/y"])

    # Установка значения
    code, out, err = _run([
        "reg", "add", f"HKCU\\{path_norm}",
        "/v", str(name), "/t", "REG_SZ", "/d", str(value), "/f",
    ])
    if code != 0:
        raise RuntimeError(err or out)
    return f"registry set: HKCU\\{path_norm}\\{name} = {value}"


def cmd_edit_file(path: str, content: str) -> str:
    """Редактирование текстового файла внутри %USERPROFILE% с бэкапом оригинала.

    Разрешены только текстовые расширения (TEXT_EXTENSIONS).
    Бэкап сохраняется в BACKUP_DIR/{basename}.{ts}.bak.
    """
    p = resolve_path(path)
    if not inside_profile(p):
        raise PermissionError("outside user profile")
    if p.suffix.lower() not in TEXT_EXTENSIONS:
        raise PermissionError(f"extension not allowed: {p.suffix}")

    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    if p.exists():
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup = BACKUP_DIR / f"{p.name}.{ts}.bak"
        shutil.copy2(p, backup)

    p.write_text(content, encoding="utf-8")
    return f"file written: {p}"


# ============================================================================
# Реестр команд (имя, функция, is_dangerous)
# ============================================================================

COMMANDS_REGISTRY: list[tuple[str, Callable[..., Any], bool]] = [
    # A — открытие/запуск
    ("open_url",       cmd_open_url,       False),
    ("open_path",      cmd_open_path,      False),
    ("launch_app",     cmd_launch_app,     False),
    ("launch_exe",     cmd_launch_exe,     False),
    ("steam_game",     cmd_steam_game,     False),
    # B — системные (shutdown/restart/sleep — опасные, требуют подтверждения)
    ("system_info",    cmd_system_info,    False),
    ("volume_set",     cmd_volume_set,     False),
    ("volume_get",     cmd_volume_get,     False),
    ("volume_mute",    cmd_volume_mute,    False),
    ("brightness_set", cmd_brightness_set, False),
    ("shutdown",       cmd_shutdown,       True),
    ("restart",        cmd_restart,        True),
    ("sleep_pc",       cmd_sleep_pc,       True),
    ("lock_pc",        cmd_lock_pc,        False),
    ("cancel_shutdown", cmd_cancel_shutdown, False),
    # C — файлы/папки
    ("list_dir",       cmd_list_dir,       False),
    ("create_folder",  cmd_create_folder,  False),
    ("delete_file",    cmd_delete_file,    False),
    ("open_explorer",  cmd_open_explorer,  False),
    ("screenshot",     cmd_screenshot,     False),
    ("file_size",      cmd_file_size,      False),
    ("clipboard_set",  cmd_clipboard_set,  False),
    ("search_files",   cmd_search_files,   False),
    # D — медиа/Windows
    ("media_play_pause", cmd_media_play_pause, False),
    ("media_next",     cmd_media_next,     False),
    ("media_prev",     cmd_media_prev,     False),
    ("minimize_all",   cmd_minimize_all,   False),
    ("show_desktop",   cmd_show_desktop,   False),
    # E — опасные
    ("run_powershell", cmd_run_powershell, True),
    ("edit_registry",  cmd_edit_registry,  True),
    ("edit_file",      cmd_edit_file,      True),
]

WHITELIST: dict[str, Callable[..., Any]] = {name: fn for name, fn, _ in COMMANDS_REGISTRY}
DANGEROUS_CMDS: set[str] = {name for name, _, d in COMMANDS_REGISTRY if d}


# ============================================================================
# АУДИТ
# ============================================================================

_audit_lock = threading.Lock()


def audit(command: str, args: list, ok: bool, note: str = "") -> None:
    """Пишет одну JSON-строку в jarvis_audit.log.

    Формат: {"ts", "cmd", "args", "ok", "note"}.
    Поле называется "cmd" (не "command"), чтобы совпадать с UI jarvis.html
    (строка `var cmdText = r.cmd + ...`).
    """
    entry = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "cmd": command,
        "args": args,
        "ok": ok,
        "note": note,
    }
    try:
        with _audit_lock:
            with AUDIT_LOG.open("a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except OSError as e:
        log.error("audit write failed: %s", e)


def read_audit() -> list[dict[str, Any]]:
    """Последние AUDIT_TAIL записей аудита (новые первыми)."""
    if not AUDIT_LOG.exists():
        return []
    try:
        with AUDIT_LOG.open("r", encoding="utf-8") as f:
            lines = f.readlines()[-AUDIT_TAIL:]
    except OSError:
        return []
    out: list[dict[str, Any]] = []
    for line in reversed(lines):
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return out


# ============================================================================
# ДИСПЕТЧЕР КОМАНД (rate-limit + confirm-token для опасных)
# ============================================================================

class Dispatcher:
    """Маршрутизатор команд.

    Безопасные команды исполняет сразу. Опасные — возвращает need_confirm + token,
    которые клиент должен подтвердить отдельным сообщением.
    """

    def __init__(self) -> None:
        self._pending: dict[str, dict[str, Any]] = {}
        self._lock = threading.Lock()
        self._rate: dict[str, deque[float]] = defaultdict(lambda: deque(maxlen=RATE_LIMIT))

    def allow(self, ip: str) -> bool:
        """Rate-limit: True, если с этого IP можно принять ещё одну команду."""
        now = time.monotonic()
        dq = self._rate[ip]
        while dq and now - dq[0] > 1.0:
            dq.popleft()
        if len(dq) >= RATE_LIMIT:
            return False
        dq.append(now)
        return True

    def execute(self, name: str, args: list | None = None) -> dict[str, Any]:
        """Исполняет команду или возвращает need_confirm для опасных."""
        args = args or []
        if name not in WHITELIST:
            return {"ok": False, "error": "unknown command"}
        fn = WHITELIST[name]
        is_dangerous = name in DANGEROUS_CMDS
        if is_dangerous:
            token = uuid.uuid4().hex
            preview = self._preview(name, args)
            with self._lock:
                self._pending[token] = {
                    "name": name,
                    "args": args,
                    "expires": time.time() + CONFIRM_TTL_SEC,
                }
            audit(name, args, ok=False, note=f"pending confirm: {preview}")
            return {
                "ok": False,
                "need_confirm": True,
                "token": token,
                "danger": name,
                "preview": preview,
            }
        try:
            result = fn(*args)
            audit(name, args, ok=True, note=str(result)[:200])
            return {"ok": True, "stdout": str(result)}
        except (ValueError, KeyError, PermissionError, FileNotFoundError) as e:
            audit(name, args, ok=False, note=f"{type(e).__name__}: {e}")
            return {"ok": False, "error": f"{type(e).__name__}: {e}"}
        except Exception as e:  # noqa: BLE001
            audit(name, args, ok=False, note=f"internal: {e}")
            return {"ok": False, "error": f"internal: {e}"}

    def take(self, token: str) -> dict[str, Any] | None:
        """Достаёт pending-запись по токену (одноразово)."""
        with self._lock:
            entry = self._pending.pop(token, None)
        if not entry:
            return None
        if entry["expires"] < time.time():
            return None
        return entry

    def confirm(self, token: str) -> dict[str, Any]:
        """Подтверждает и исполняет опасную команду по токену."""
        entry = self.take(token)
        if not entry:
            return {"ok": False, "error": "invalid or expired token"}
        name = entry["name"]
        args = entry["args"]
        fn = WHITELIST.get(name)
        if not fn:
            return {"ok": False, "error": "unknown command"}
        try:
            result = fn(*args)
            audit(name, args, ok=True, note=str(result)[:200])
            return {"ok": True, "stdout": str(result)}
        except (ValueError, KeyError, PermissionError, FileNotFoundError) as e:
            audit(name, args, ok=False, note=f"{type(e).__name__}: {e}")
            return {"ok": False, "error": f"{type(e).__name__}: {e}"}
        except Exception as e:  # noqa: BLE001
            audit(name, args, ok=False, note=f"internal: {e}")
            return {"ok": False, "error": f"internal: {e}"}

    def deny(self, token: str) -> dict[str, Any]:
        """Отменяет опасную команду по токену."""
        with self._lock:
            entry = self._pending.pop(token, None)
        if not entry:
            return {"ok": False, "error": "invalid or expired token"}
        audit(entry["name"], entry["args"], ok=False, note="denied by user")
        return {"ok": True, "stdout": "cancelled"}

    def _preview(self, name: str, args: list) -> str:
        """Человекочитаемое описание опасной команды для модала подтверждения."""
        if name == "run_powershell":
            script = args[0] if args else ""
            return f"PowerShell script:\n{script[:500]}"
        if name == "edit_registry":
            if len(args) >= 4:
                return f"Registry: {args[0]}\\{args[1]}\\{args[2]} = {args[3]}"
            return f"Registry: {args}"
        if name == "edit_file":
            path = args[0] if args else "?"
            content = args[1] if len(args) > 1 else ""
            return f"File: {path}\nContent preview:\n{content[:500]}"
        if name == "shutdown":
            delay = args[0] if args else 60
            return f"Shutdown PC in {delay}s"
        if name == "restart":
            delay = args[0] if args else 60
            return f"Restart PC in {delay}s"
        if name == "sleep_pc":
            return "Sleep PC"
        return f"{name}({args})"


DISPATCHER = Dispatcher()


# ============================================================================
# ЕДИНАЯ ТОЧКА ВХОДА process_request
# ============================================================================

def process_request(msg: dict[str, Any]) -> dict[str, Any]:
    """Обрабатывает JSON-сообщение от клиента и возвращает ответ.

    Поддерживаемые msg.cmd:
      - "ping"          → {"pong": true}
      - "commands"      → список всех команд с флагом dangerous
      - "confirm"       → подтверждение опасной команды (msg.token)
      - "deny"          → отмена опасной команды (msg.token)
      - любое имя из WHITELIST → исполнение
    """
    cmd = str(msg.get("cmd", ""))
    cmd_id = str(msg.get("id", ""))
    args = msg.get("args") or []
    if not isinstance(args, list):
        return {"id": cmd_id, "ok": False, "error": "args must be a list"}

    if cmd == "ping":
        return {"id": cmd_id, "pong": True}
    if cmd == "commands":
        return {
            "id": cmd_id,
            "ok": True,
            "commands": [
                {"name": name, "dangerous": name in DANGEROUS_CMDS}
                for name, _, _ in COMMANDS_REGISTRY
            ],
        }
    if cmd == "confirm":
        token = str(msg.get("token", ""))
        out = DISPATCHER.confirm(token)
        return {"id": cmd_id, **out}
    if cmd == "deny":
        token = str(msg.get("token", ""))
        out = DISPATCHER.deny(token)
        return {"id": cmd_id, **out}

    out = DISPATCHER.execute(cmd, args)
    return {"id": cmd_id, **out}


# ============================================================================
# HTTP / WS сервер (aiohttp)
# ============================================================================

@web.middleware
async def origin_middleware(request: web.Request, handler: Callable[..., Any]) -> web.StreamResponse:
    """Разрешает запросы только с localhost-Origin."""
    origin = request.headers.get("Origin")
    if origin and origin not in ALLOWED_ORIGINS:
        return web.Response(status=403, text="forbidden origin")
    return await handler(request)


async def _index(request: web.Request) -> web.StreamResponse:
    """Отдаёт jarvis.html."""
    return web.FileResponse(ROOT / "jarvis.html")


async def _static_file(request: web.Request) -> web.StreamResponse:
    """Отдаёт файл из ROOT (shortcuts.json и т.д.)."""
    name = request.match_info["name"]
    p = (ROOT / name).resolve()
    if not _is_within(p, ROOT) or not p.is_file():
        return web.Response(status=404, text="not found")
    return web.FileResponse(p)


async def _audit_endpoint(request: web.Request) -> web.Response:
    """Возвращает последние AUDIT_TAIL записей аудита (для UI)."""
    return web.json_response({"entries": read_audit()})


# ============================================================================
# SETTINGS API (config.json — управление через GUI)
# ============================================================================

# Валидация: разрешённые схемы для ярлыков
ALLOWED_SHORTCUT_SCHEMES = ("http", "https", "steam", "file")

# Валидация: опасные имена команд (только из этого списка можно переключать)
TOGGLEABLE_DANGEROUS = {
    "shutdown", "restart", "sleep_pc",
    "run_powershell", "edit_registry", "edit_file",
}


def _validate_config(cfg: dict[str, Any]) -> tuple[bool, str]:
    """Валидирует config. Возвращает (ok, error_message)."""
    if not isinstance(cfg, dict):
        return (False, "config must be a JSON object")
    # shortcuts
    sc = cfg.get("shortcuts")
    if sc is not None:
        if not isinstance(sc, dict):
            return (False, "shortcuts must be an object")
        for k, v in sc.items():
            if not isinstance(k, str) or not isinstance(v, str):
                return (False, f"shortcut '{k}' must be string→string")
            if not k.strip():
                return (False, "shortcut name cannot be empty")
            # Проверка схемы (для URL-ярлыков) — ищем "scheme:" в начале значения
            import re as _re
            m = _re.match(r"^([a-zA-Z][a-zA-Z0-9+.\-]*)\s*:", v)
            if m:
                scheme = m.group(1).lower()
                if scheme not in ALLOWED_SHORTCUT_SCHEMES:
                    return (False, f"shortcut scheme not allowed: {scheme}")
    # allowedFolders
    af = cfg.get("allowedFolders")
    if af is not None:
        if not isinstance(af, list) or not all(isinstance(x, str) for x in af):
            return (False, "allowedFolders must be a list of strings")
    # allowedExeRoots
    ar = cfg.get("allowedExeRoots")
    if ar is not None:
        if not isinstance(ar, list) or not all(isinstance(x, str) for x in ar):
            return (False, "allowedExeRoots must be a list of strings")
    # dangerousCommands
    dc = cfg.get("dangerousCommands")
    if dc is not None:
        if not isinstance(dc, dict):
            return (False, "dangerousCommands must be an object")
        for k, v in dc.items():
            if k not in TOGGLEABLE_DANGEROUS:
                return (False, f"unknown dangerous command: {k}")
            if not isinstance(v, bool):
                return (False, f"dangerousCommands.{k} must be boolean")
    # voice
    v = cfg.get("voice")
    if v is not None:
        if not isinstance(v, dict):
            return (False, "voice must be an object")
        if v.get("mode") not in (None, "russian", "authentic"):
            return (False, "voice.mode must be 'russian' or 'authentic'")
        rate = v.get("rate", 1.0)
        if not isinstance(rate, (int, float)) or rate < 0.5 or rate > 2.0:
            return (False, "voice.rate must be 0.5..2.0")
        pitch = v.get("pitch", 1.0)
        if not isinstance(pitch, (int, float)) or pitch < 0.5 or pitch > 2.0:
            return (False, "voice.pitch must be 0.5..2.0")
    return (True, "")


def _backup_config() -> None:
    """Сохраняет текущий config.json в ~/.dsh/config_backups/{ts}.json.bak."""
    if not CONFIG_FILE.exists():
        return
    try:
        CONFIG_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup = CONFIG_BACKUP_DIR / f"config.{ts}.json.bak"
        shutil.copy2(CONFIG_FILE, backup)
        # Храним максимум 10 бэкапов
        backups = sorted(CONFIG_BACKUP_DIR.glob("config.*.json.bak"))
        if len(backups) > 10:
            for old in backups[:-10]:
                old.unlink()
    except OSError as e:
        log.warning("config backup failed: %s", e)


async def _settings_get(request: web.Request) -> web.Response:
    """GET /api/settings — отдаёт текущий config."""
    cfg = _load_config()
    return web.json_response(cfg)


async def _settings_post(request: web.Request) -> web.Response:
    """POST /api/settings — валидирует и сохраняет новый config."""
    try:
        body = await request.json()
    except json.JSONDecodeError:
        return web.json_response({"ok": False, "error": "invalid json"}, status=400)
    if not isinstance(body, dict):
        return web.json_response({"ok": False, "error": "expected object"}, status=400)

    ok, err = _validate_config(body)
    if not ok:
        audit("settings_save", [body], ok=False, note=f"validation failed: {err}")
        return web.json_response({"ok": False, "error": err}, status=400)

    # Merge с существующим config (чтобы не потерять ключи, которые UI не прислал)
    current = _load_config()
    for k, v in body.items():
        current[k] = v

    # Backup старого
    _backup_config()

    # Запись нового
    try:
        with CONFIG_FILE.open("w", encoding="utf-8") as f:
            json.dump(current, f, ensure_ascii=False, indent=2)
    except OSError as e:
        audit("settings_save", [], ok=False, note=f"write failed: {e}")
        return web.json_response({"ok": False, "error": str(e)}, status=500)

    # Применяем изменения в рантайме
    apply_config(current)
    audit("settings_save", [list(body.keys())], ok=True, note="config updated")
    return web.json_response({"ok": True, "config": current})


async def _settings_reset(request: web.Request) -> web.Response:
    """POST /api/settings/reset — сброс к дефолту."""
    _backup_config()
    default = _default_config()
    try:
        with CONFIG_FILE.open("w", encoding="utf-8") as f:
            json.dump(default, f, ensure_ascii=False, indent=2)
        apply_config(default)
        audit("settings_reset", [], ok=True, note="config reset to defaults")
        return web.json_response({"ok": True, "config": default})
    except OSError as e:
        return web.json_response({"ok": False, "error": str(e)}, status=500)


async def _ws_handler(request: web.Request) -> web.StreamResponse:
    """WebSocket-эндпоинт /ws."""
    origin = request.headers.get("Origin")
    if origin and origin not in ALLOWED_ORIGINS:
        return web.Response(status=403, text="forbidden origin")

    ws = web.WebSocketResponse(heartbeat=30, max_msg_size=1 << 20)
    await ws.prepare(request)
    peer_ip = request.remote or "unknown"
    log.info("WS connected: %s", peer_ip)

    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                if not DISPATCHER.allow(peer_ip):
                    await ws.send_json({"id": None, "ok": False, "error": "rate limit exceeded"})
                    continue
                try:
                    data = json.loads(msg.data)
                except json.JSONDecodeError:
                    await ws.send_json({"id": None, "ok": False, "error": "invalid json"})
                    continue
                if not isinstance(data, dict):
                    await ws.send_json({"id": None, "ok": False, "error": "expected object"})
                    continue
                response = process_request(data)
                await ws.send_json(response)
            elif msg.type == WSMsgType.ERROR:
                log.warning("WS error: %s", ws.exception())
                break
    finally:
        log.info("WS disconnected: %s", peer_ip)
    return ws


# ============================================================================
# Точка входа
# ============================================================================

def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="J.A.R.V.I.S. local backend")
    parser.add_argument(
        "--i-understand-the-risks",
        action="store_true",
        help="обязательный флаг: подтверждает, что ты понимаешь риски",
    )
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    """CLI-точка входа."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    args = _parse_args(argv)

    if not args.i_understand_the_risks:
        print(
            "ERROR: запуск без --i-understand-the-risks запрещён.\n"
            "Это защита от случайного исполнения системных команд.\n"
            "Если ты понимаешь риски — запусти:\n"
            "    python backend.py --i-understand-the-risks",
            file=sys.stderr,
        )
        return 2

    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    REG_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_BACKUP_DIR.mkdir(parents=True, exist_ok=True)

    # Загружаем config.json (создастся автоматически при первом запуске)
    cfg = _load_config()
    apply_config(cfg)
    log.info("config.json загружен: shortcuts=%d, folders=%d, exeRoots=%d",
             len(cfg.get("shortcuts", {})),
             len(cfg.get("allowedFolders", [])),
             len(cfg.get("allowedExeRoots", [])))

    app = web.Application(middlewares=[origin_middleware])
    app.router.add_get("/", _index)
    app.router.add_get("/ws", _ws_handler)
    app.router.add_get("/audit", _audit_endpoint)
    app.router.add_get("/api/settings", _settings_get)
    app.router.add_post("/api/settings", _settings_post)
    app.router.add_post("/api/settings/reset", _settings_reset)
    app.router.add_get("/{name}", _static_file)

    log.info("J.A.R.V.I.S. backend на http://%s:%s (WS: /ws)", HOST, args.port)
    try:
        web.run_app(app, host=HOST, port=args.port, print=None, access_log=None)
    except OSError as e:
        print(f"ERROR: не удалось занять порт {args.port}: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
