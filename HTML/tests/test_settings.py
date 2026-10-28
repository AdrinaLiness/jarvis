"""Tests for the Settings API (config.json management)."""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import backend as B


# ============================================================================
# Fixtures
# ============================================================================

@pytest.fixture
def home(tmp_path, monkeypatch):
    """Подменяет пути на tmp_path."""
    monkeypatch.setattr(B, "ROOT", tmp_path)
    monkeypatch.setattr(B, "USER_HOME", tmp_path)
    monkeypatch.setattr(B, "AUDIT_LOG", tmp_path / "audit.log")
    monkeypatch.setattr(B, "SHORTCUTS_FILE", tmp_path / "shortcuts.json")
    monkeypatch.setattr(B, "CONFIG_FILE", tmp_path / "config.json")
    monkeypatch.setattr(B, "CONFIG_BACKUP_DIR", tmp_path / ".dsh" / "config_backups")
    monkeypatch.setattr(B, "BACKUP_DIR", tmp_path / ".dsh" / "jarvis_backups")
    monkeypatch.setattr(B, "REG_BACKUP_DIR", tmp_path / ".dsh" / "registry_backup")
    return tmp_path


def _make_app():
    """Создаёт aiohttp Application с settings-роутами."""
    app = B.web.Application(middlewares=[B.origin_middleware])
    app.router.add_get("/api/settings", B._settings_get)
    app.router.add_post("/api/settings", B._settings_post)
    app.router.add_post("/api/settings/reset", B._settings_reset)
    return app


def _settings_request(method, path, body=None):
    """Запускает TestServer, делает HTTP-запрос, возвращает ответ."""
    from aiohttp.test_utils import TestServer
    import aiohttp

    async def _run():
        app = _make_app()
        server = TestServer(app)
        await server.start_server()
        url = f"http://{server.host}:{server.port}{path}"
        try:
            async with aiohttp.ClientSession() as session:
                if method == "GET":
                    async with session.get(url) as resp:
                        return resp.status, await resp.json()
                elif method == "POST":
                    async with session.post(url, json=body) as resp:
                        return resp.status, await resp.json()
        finally:
            await server.close()

    return asyncio.run(_run())


# ============================================================================
# --- Validation tests ------------------------------------------------------
# ============================================================================

def test_validate_default_config(home):
    cfg = B._default_config()
    ok, err = B._validate_config(cfg)
    assert ok, f"valid default config should pass: {err}"


def test_validate_rejects_non_dict(home):
    ok, err = B._validate_config("not a dict")
    assert not ok
    assert "must be a JSON object" in err


def test_validate_rejects_bad_shortcut_scheme(home):
    cfg = B._default_config()
    cfg["shortcuts"] = {"evil": "javascript:alert(1)"}
    ok, err = B._validate_config(cfg)
    assert not ok
    assert "scheme not allowed" in err


def test_validate_accepts_steam_scheme(home):
    cfg = B._default_config()
    cfg["shortcuts"] = {"game": "steam://rungameid/123"}
    ok, err = B._validate_config(cfg)
    assert ok, err


def test_validate_accepts_file_scheme(home):
    cfg = B._default_config()
    cfg["shortcuts"] = {"local": "file:///C:/test.txt"}
    ok, err = B._validate_config(cfg)
    assert ok, err


def test_validate_rejects_empty_shortcut_name(home):
    cfg = B._default_config()
    cfg["shortcuts"] = {"": "https://example.com"}
    ok, err = B._validate_config(cfg)
    assert not ok
    assert "empty" in err


def test_validate_rejects_bad_folders(home):
    cfg = B._default_config()
    cfg["allowedFolders"] = "not-a-list"
    ok, err = B._validate_config(cfg)
    assert not ok
    assert "list of strings" in err


def test_validate_rejects_unknown_dangerous_command(home):
    cfg = B._default_config()
    cfg["dangerousCommands"] = {"unknown_cmd": True}
    ok, err = B._validate_config(cfg)
    assert not ok
    assert "unknown dangerous command" in err


def test_validate_rejects_bad_voice_mode(home):
    cfg = B._default_config()
    cfg["voice"] = {"mode": "italian", "rate": 1, "pitch": 1}
    ok, err = B._validate_config(cfg)
    assert not ok
    assert "mode" in err


def test_validate_rejects_voice_rate_out_of_range(home):
    cfg = B._default_config()
    cfg["voice"] = {"mode": "russian", "rate": 5.0, "pitch": 1}
    ok, err = B._validate_config(cfg)
    assert not ok
    assert "rate" in err


def test_validate_rejects_voice_pitch_out_of_range(home):
    cfg = B._default_config()
    cfg["voice"] = {"mode": "russian", "rate": 1, "pitch": 0.1}
    ok, err = B._validate_config(cfg)
    assert not ok
    assert "pitch" in err


# ============================================================================
# --- Config loading / creation ---------------------------------------------
# ============================================================================

def test_load_config_creates_default_when_missing(home):
    """Если config.json не существует — _load_config создаёт его."""
    assert not B.CONFIG_FILE.exists()
    cfg = B._load_config()
    assert B.CONFIG_FILE.exists()
    assert "shortcuts" in cfg
    assert "allowedFolders" in cfg
    assert "dangerousCommands" in cfg
    assert "voice" in cfg


def test_load_config_reads_existing(home):
    """Если config.json существует — читает его."""
    custom = {
        "shortcuts": {"test": "https://test.com"},
        "allowedFolders": ["Desktop"],
        "allowedExeRoots": ["C:\\Test"],
        "dangerousCommands": {"shutdown": False},
        "voice": {"mode": "authentic", "rate": 1.2, "pitch": 0.9},
        "server": {"host": "127.0.0.1", "port": 8000},
    }
    with B.CONFIG_FILE.open("w", encoding="utf-8") as f:
        json.dump(custom, f)
    cfg = B._load_config()
    assert cfg["shortcuts"] == {"test": "https://test.com"}
    assert cfg["voice"]["mode"] == "authentic"
    assert cfg["dangerousCommands"]["shutdown"] is False


def test_load_config_backfills_missing_keys(home):
    """Если в config.json нет какого-то ключа — он берётся из дефолта."""
    with B.CONFIG_FILE.open("w", encoding="utf-8") as f:
        json.dump({"shortcuts": {"x": "https://x.com"}}, f)
    cfg = B._load_config()
    assert cfg["shortcuts"] == {"x": "https://x.com"}
    # Backfilled:
    assert "allowedFolders" in cfg
    assert "dangerousCommands" in cfg
    assert "voice" in cfg


# ============================================================================
# --- apply_config ----------------------------------------------------------
# ============================================================================

def test_apply_config_updates_folders(home):
    cfg = B._default_config()
    cfg["allowedFolders"] = ["MyFolder", "Desktop"]
    B.apply_config(cfg)
    assert "MyFolder" in B.ALLOWED_USER_FOLDERS
    assert "Desktop" in B.ALLOWED_USER_FOLDERS


def test_apply_config_updates_exe_roots(home):
    cfg = B._default_config()
    cfg["allowedExeRoots"] = ["/custom/path"]
    B.apply_config(cfg)
    assert "/custom/path" in B.ALLOWED_EXE_ROOTS


def test_apply_config_updates_dangerous(home):
    cfg = B._default_config()
    cfg["dangerousCommands"] = {
        "shutdown": False,
        "restart": True,
        "sleep_pc": False,
        "run_powershell": True,
        "edit_registry": True,
        "edit_file": True,
    }
    B.apply_config(cfg)
    assert "shutdown" not in B.DANGEROUS_CMDS
    assert "restart" in B.DANGEROUS_CMDS
    assert "sleep_pc" not in B.DANGEROUS_CMDS
    assert "run_powershell" in B.DANGEROUS_CMDS


def test_apply_config_handles_empty_folders(home):
    """Если allowedFolders пустой — fallback на дефолт."""
    cfg = B._default_config()
    cfg["allowedFolders"] = []
    B.apply_config(cfg)
    assert B.ALLOWED_USER_FOLDERS == B.DEFAULT_ALLOWED_USER_FOLDERS


# ============================================================================
# --- Backup ----------------------------------------------------------------
# ============================================================================

def test_backup_config_creates_backup(home):
    """_backup_config создаёт .bak файл."""
    B.CONFIG_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    with B.CONFIG_FILE.open("w", encoding="utf-8") as f:
        json.dump({"shortcuts": {}}, f)
    B._backup_config()
    backups = list(B.CONFIG_BACKUP_DIR.glob("config.*.json.bak"))
    assert len(backups) == 1
    content = backups[0].read_text(encoding="utf-8")
    assert "shortcuts" in content


def test_backup_config_keeps_max_10(home):
    """Хранится максимум 10 бэкапов."""
    B.CONFIG_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    with B.CONFIG_FILE.open("w", encoding="utf-8") as f:
        json.dump({"v": 0}, f)
    # Создаём 15 бэкапов
    for i in range(15):
        B._backup_config()
    backups = sorted(B.CONFIG_BACKUP_DIR.glob("config.*.json.bak"))
    assert len(backups) <= 10


# ============================================================================
# --- HTTP endpoints --------------------------------------------------------
# ============================================================================

def test_settings_get_returns_config(home):
    """GET /api/settings возвращает текущий config."""
    # Создаём config.json с известным содержимым
    custom = B._default_config()
    custom["shortcuts"] = {"test_app": "https://test.com"}
    with B.CONFIG_FILE.open("w", encoding="utf-8") as f:
        json.dump(custom, f)
    status, resp = _settings_request("GET", "/api/settings")
    assert status == 200
    assert "shortcuts" in resp
    assert resp["shortcuts"]["test_app"] == "https://test.com"


def test_settings_post_saves_valid_config(home):
    """POST /api/settings с валидным config — 200, сохраняет."""
    new_config = B._default_config()
    new_config["shortcuts"] = {"new_app": "https://new.com"}
    new_config["allowedFolders"] = ["TestFolder"]
    status, resp = _settings_request("POST", "/api/settings", new_config)
    assert status == 200
    assert resp["ok"] is True
    # Проверяем, что config.json обновлён
    saved = json.loads(B.CONFIG_FILE.read_text(encoding="utf-8"))
    assert saved["shortcuts"]["new_app"] == "https://new.com"
    assert "TestFolder" in saved["allowedFolders"]


def test_settings_post_rejects_invalid(home):
    """POST /api/settings с невалидным config — 400."""
    bad_config = {
        "shortcuts": {"evil": "javascript:alert(1)"},
    }
    status, resp = _settings_request("POST", "/api/settings", bad_config)
    assert status == 400
    assert resp["ok"] is False
    assert "scheme not allowed" in resp["error"]


def test_settings_post_rejects_non_object(home):
    """POST с не-объектом — 400."""
    status, resp = _settings_request("POST", "/api/settings", None)
    assert status == 400
    assert resp["ok"] is False


def test_settings_post_creates_backup(home):
    """POST создаёт backup перед перезаписью."""
    # Создаём initial config
    with B.CONFIG_FILE.open("w", encoding="utf-8") as f:
        json.dump({"shortcuts": {"old": "https://old.com"}}, f)
    # POST с новым config
    new_config = B._default_config()
    new_config["shortcuts"] = {"new": "https://new.com"}
    _settings_request("POST", "/api/settings", new_config)
    # Backup должен быть создан
    backups = list(B.CONFIG_BACKUP_DIR.glob("config.*.json.bak"))
    assert len(backups) >= 1
    backup_content = backups[0].read_text(encoding="utf-8")
    assert "old" in backup_content


def test_settings_reset_returns_default(home):
    """POST /api/settings/reset — сбрасывает к дефолту."""
    # Сначала создаём кастомный config
    custom = B._default_config()
    custom["shortcuts"] = {"x": "https://x.com"}
    with B.CONFIG_FILE.open("w", encoding="utf-8") as f:
        json.dump(custom, f)
    # Сбрасываем
    status, resp = _settings_request("POST", "/api/settings/reset", None)
    assert status == 200
    assert resp["ok"] is True
    assert "x" not in resp["config"]["shortcuts"]


def test_settings_post_applies_config_in_runtime(home):
    """После POST новая папка сразу доступна в ALLOWED_USER_FOLDERS."""
    new_config = B._default_config()
    new_config["allowedFolders"] = ["MyCustomFolder"]
    _settings_request("POST", "/api/settings", new_config)
    assert "MyCustomFolder" in B.ALLOWED_USER_FOLDERS


# ============================================================================
# --- Shortcuts loading priority --------------------------------------------
# ============================================================================

def test_load_shortcuts_prefers_config_json(home):
    """Если есть config.json — берёт shortcuts из него (не из shortcuts.json)."""
    # Создаём shortcuts.json со старым ярлыком
    with B.SHORTCUTS_FILE.open("w", encoding="utf-8") as f:
        json.dump({"old_app": "https://old.com"}, f)
    # Создаём config.json с новым ярлыком
    with B.CONFIG_FILE.open("w", encoding="utf-8") as f:
        json.dump({
            "shortcuts": {"new_app": "https://new.com"},
            "allowedFolders": [],
        }, f)
    shortcuts = B._load_shortcuts()
    assert "new_app" in shortcuts
    assert "old_app" not in shortcuts


def test_load_shortcuts_falls_back_to_shortcuts_json(home):
    """Если config.json нет — берёт из shortcuts.json."""
    with B.SHORTCUTS_FILE.open("w", encoding="utf-8") as f:
        json.dump({"fallback_app": "https://fallback.com"}, f)
    shortcuts = B._load_shortcuts()
    assert "fallback_app" in shortcuts


# ============================================================================
# --- Toggle dangerous commands effect --------------------------------------
# ============================================================================

def test_disabling_shutdown_removes_from_dangerous_set(home):
    """Если отключить shutdown через settings — он не в DANGEROUS_CMDS."""
    new_config = B._default_config()
    new_config["dangerousCommands"]["shutdown"] = False
    _settings_request("POST", "/api/settings", new_config)
    assert "shutdown" not in B.DANGEROUS_CMDS
    # Но restart всё ещё опасный
    assert "restart" in B.DANGEROUS_CMDS


def test_re_enabling_shutdown_adds_back(home):
    """Если включить обратно — снова в DANGEROUS_CMDS."""
    new_config = B._default_config()
    new_config["dangerousCommands"]["shutdown"] = False
    _settings_request("POST", "/api/settings", new_config)
    assert "shutdown" not in B.DANGEROUS_CMDS
    # Включаем обратно
    new_config["dangerousCommands"]["shutdown"] = True
    _settings_request("POST", "/api/settings", new_config)
    assert "shutdown" in B.DANGEROUS_CMDS


# ============================================================================
# --- Audit integration -----------------------------------------------------
# ============================================================================

def test_settings_save_audited(home):
    """Сохранение настроек пишется в аудит-лог."""
    B.AUDIT_LOG.parent.mkdir(parents=True, exist_ok=True)
    new_config = B._default_config()
    new_config["shortcuts"] = {"audited_app": "https://audited.com"}
    _settings_request("POST", "/api/settings", new_config)
    log_content = B.AUDIT_LOG.read_text(encoding="utf-8")
    assert "settings_save" in log_content


def test_settings_reset_audited(home):
    """Сброс настроек пишется в аудит-лог."""
    B.AUDIT_LOG.parent.mkdir(parents=True, exist_ok=True)
    _settings_request("POST", "/api/settings/reset", None)
    log_content = B.AUDIT_LOG.read_text(encoding="utf-8")
    assert "settings_reset" in log_content
