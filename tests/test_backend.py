"""Tests for the J.A.R.V.I.S. backend whitelist (mocked subprocess) and WS layer."""
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

class FakeRunner:
    """Captures subprocess calls and returns canned (code, out, err)."""

    def __init__(self, result=(0, "ok", "")):
        self.result = result
        self.calls: list[list[str]] = []

    def __call__(self, cmd, timeout=15.0, capture=True):
        self.calls.append(list(cmd))
        return self.result


@pytest.fixture
def run(monkeypatch):
    fake = FakeRunner()
    monkeypatch.setitem(B._ACTIVE, B.RUNNER_KEY, fake)
    return fake


@pytest.fixture
def home(tmp_path, monkeypatch):
    """Подменяет USER_HOME, BACKUP_DIR, REG_BACKUP_DIR, AUDIT_LOG, SHORTCUTS_FILE на tmp_path."""
    monkeypatch.setattr(B, "USER_HOME", tmp_path)
    monkeypatch.setattr(B, "BACKUP_DIR", tmp_path / ".dsh" / "jarvis_backups")
    monkeypatch.setattr(B, "REG_BACKUP_DIR", tmp_path / ".dsh" / "registry_backup")
    monkeypatch.setattr(B, "AUDIT_LOG", tmp_path / "audit.log")
    monkeypatch.setattr(B, "SHORTCUTS_FILE", tmp_path / "shortcuts.json")
    return tmp_path


@pytest.fixture(autouse=True)
def _reset_dispatcher_state():
    """Перед каждым тестом чистим глобальное состояние DISPATCHER (pending tokens + rate buckets).

    Без этого тесты на confirm/rate-limit мутируют глобальный DISPATCHER и
    влияют на последующие тесты (особенно test_ws_confirm_full_flow).
    """
    if hasattr(B, "DISPATCHER"):
        with B.DISPATCHER._lock:
            B.DISPATCHER._pending.clear()
        B.DISPATCHER._rate.clear()
    yield


# ============================================================================
# --- path guards -------------------------------------------------------
# ============================================================================

def test_inside_profile(home):
    assert B.inside_profile(home / "Desktop")
    assert not B.inside_profile(Path("/etc"))


def test_resolve_tilde():
    p = B.resolve_path("~")
    assert p.is_absolute()


def test_allowed_user_dir(home):
    ok, _ = B.allowed_user_dir(home / "Downloads")
    assert ok
    ok, _ = B.allowed_user_dir(Path("/Windows"))
    assert not ok


# ============================================================================
# --- A: open / launch ---------------------------------------------------
# ============================================================================

def test_open_url_https(monkeypatch):
    opened = []
    monkeypatch.setattr(B.webbrowser, "open", opened.append)
    B.cmd_open_url("example.com")
    assert opened[0].startswith("https://")


def test_open_url_rejects_file_scheme():
    with pytest.raises(ValueError):
        B.cmd_open_url("file:///C:/Windows/win.ini")


def test_open_url_rejects_other_scheme():
    with pytest.raises(ValueError):
        B.cmd_open_url("javascript:alert(1)")


def test_open_url_rejects_ftp():
    with pytest.raises(ValueError):
        B.cmd_open_url("ftp://example.com/file")


def test_open_url_accepts_https_explicit(monkeypatch):
    opened = []
    monkeypatch.setattr(B.webbrowser, "open", opened.append)
    B.cmd_open_url("https://google.com")
    assert opened[0] == "https://google.com"


def test_launch_app_unknown_key(home):
    (home / "shortcuts.json").write_text('{"discord": "steam://x"}', encoding="utf-8")
    with pytest.raises(KeyError):
        B.cmd_launch_app("nonexistent-app")


def test_launch_app_runs_shortcut(home, monkeypatch):
    (home / "shortcuts.json").write_text('{"discord": "steam://rungameid/1"}', encoding="utf-8")
    started = []
    monkeypatch.setattr(B.os, "startfile", lambda target: started.append(target), raising=False)
    out = B.cmd_launch_app("discord")
    assert "discord" in out.lower()
    assert started


def test_launch_app_case_insensitive(home, monkeypatch):
    (home / "shortcuts.json").write_text('{"Discord": "steam://rungameid/1"}', encoding="utf-8")
    started = []
    monkeypatch.setattr(B.os, "startfile", lambda target: started.append(target), raising=False)
    B.cmd_launch_app("discord")
    assert started


def test_steam_game_rejects_non_digits():
    with pytest.raises(ValueError):
        B.cmd_steam_game("abc")


def test_steam_game_accepts_digits(monkeypatch):
    started = []
    monkeypatch.setattr(B.os, "startfile", lambda target: started.append(target), raising=False)
    out = B.cmd_steam_game("227300")
    assert "227300" in out
    assert started and "227300" in started[0]


def test_launch_exe_rejects_outside_roots():
    with pytest.raises((PermissionError, FileNotFoundError)):
        B.cmd_launch_exe(r"D:\hack\evil.exe")


def test_launch_exe_rejects_non_exe(home):
    f = home / "script.bat"
    f.write_text("echo hi", encoding="utf-8")
    with pytest.raises(ValueError):
        B.cmd_launch_exe(str(f))


# ============================================================================
# --- B: system ------------------------------------------------------------
# ============================================================================

def test_system_info_runs(run):
    out = B.cmd_system_info()
    assert "ОС" in out or "OS" in out


def test_volume_set_and_get(monkeypatch):
    state = {"level": 0}

    class FakeWinmm:
        def waveOutSetVolume(self, handle, level):
            state["level"] = level
            return 0

        def waveOutGetVolume(self, handle, ref):
            ref._obj.value = state["level"]
            return 0

    class FakeWindll:
        winmm = FakeWinmm()

    monkeypatch.setattr(B.ctypes, "windll", FakeWindll(), raising=False)
    out = B.cmd_volume_set("40")
    assert "40" in out
    out = B.cmd_volume_get()
    assert "%" in out


def test_volume_set_rejects_out_of_range():
    with pytest.raises(ValueError):
        B.cmd_volume_set(150)


def test_shutdown_clamps_delay(run):
    out = B.cmd_shutdown("99999")
    assert "3600" in out
    assert run.calls[-1][:3] == ["shutdown", "/s", "/t"]


def test_shutdown_zero(run):
    assert "0" in B.cmd_shutdown("0")


def test_cancel_shutdown_runs(run):
    B.cmd_cancel_shutdown()
    assert run.calls
    assert "/a" in run.calls[-1]


def test_lock_pc_runs(run):
    B.cmd_lock_pc()
    assert run.calls


def test_restart_uses_r_flag(run):
    B.cmd_restart("10")
    assert "/r" in run.calls[-1]


# ============================================================================
# --- C: files ---------------------------------------------------------------
# ============================================================================

def test_list_dir(home):
    (home / "a.txt").write_text("hi", encoding="utf-8")
    out = B.cmd_list_dir(str(home))
    assert "a.txt" in out


def test_list_dir_marks_dirs(home):
    (home / "subdir").mkdir()
    (home / "file.txt").write_text("x", encoding="utf-8")
    out = B.cmd_list_dir(str(home))
    assert "[D] subdir" in out
    assert "[F] file.txt" in out


def test_create_folder(home):
    target = home / "new" / "nested"
    B.cmd_create_folder(str(target))
    assert target.is_dir()


def test_delete_file_sends_to_trash(home, monkeypatch):
    target = home / "tmp.txt"
    target.write_text("bye", encoding="utf-8")
    seen: list[str] = []

    fake_module = type(sys)("send2trash")
    fake_module.send2trash = lambda path: seen.append(path)
    monkeypatch.setitem(sys.modules, "send2trash", fake_module)

    B.cmd_delete_file(str(target))
    assert seen


def test_delete_blocks_outside_profile(home):
    with pytest.raises((PermissionError, FileNotFoundError)):
        B.cmd_delete_file(r"C:\Windows\explorer.exe")


def test_screenshot_powershell_fallback(run, home, monkeypatch):
    monkeypatch.setattr(B, "USER_HOME", home)

    def fake_run(cmd, timeout=15.0, capture=True):
        pictures = home / "Pictures"
        pictures.mkdir(parents=True, exist_ok=True)
        for f in pictures.glob("jarvis_*"):
            return (0, "", "")
        (pictures / "jarvis_fake.png").write_bytes(b"\x89PNG")
        return (0, "", "")

    monkeypatch.setitem(B._ACTIVE, B.RUNNER_KEY, fake_run)
    out = B.cmd_screenshot()
    assert "jarvis_" in out


def test_file_size(home):
    f = home / "big.bin"
    f.write_bytes(b"x" * 4096)
    assert "4096" in B.cmd_file_size(str(f))


def test_clipboard_set_encodes(run):
    B.cmd_clipboard_set("привет")
    assert any("FromBase64" in " ".join(c) for c in run.calls)


def test_clipboard_set_rejects_empty():
    with pytest.raises(ValueError):
        B.cmd_clipboard_set("")


def test_search_files(home):
    (home / "needle.txt").write_text("x", encoding="utf-8")
    assert "needle.txt" in B.cmd_search_files("needle", str(home))


def test_search_files_no_match(home):
    (home / "foo.txt").write_text("x", encoding="utf-8")
    assert "no matches" in B.cmd_search_files("zzz", str(home))


# ============================================================================
# --- D: media / windows -----------------------------------------------------
# ============================================================================

def test_minimize_all_runs(run):
    B.cmd_minimize_all()
    assert run.calls


def test_show_desktop_sends_key(monkeypatch):
    seen = []
    monkeypatch.setattr(B, "key_event", lambda vk, count=1: seen.append(vk) or True)
    B.cmd_show_desktop()
    # key_event должен вызываться для win и для d
    assert B.VK["win"] in seen
    assert B.VK["d"] in seen


def test_media_play_pause_sends_key(monkeypatch):
    seen = []
    monkeypatch.setitem(B._ACTIVE, "key", lambda vk, count: seen.append(vk))
    B.cmd_media_play_pause()
    assert seen == [B.VK["media_play_pause"]]


def test_media_next_sends_key(monkeypatch):
    seen = []
    monkeypatch.setitem(B._ACTIVE, "key", lambda vk, count: seen.append(vk))
    B.cmd_media_next()
    assert seen == [B.VK["media_next_track"]]


def test_media_prev_sends_key(monkeypatch):
    seen = []
    monkeypatch.setitem(B._ACTIVE, "key", lambda vk, count: seen.append(vk))
    B.cmd_media_prev()
    assert seen == [B.VK["media_prev_track"]]


# ============================================================================
# --- E: dangerous commands ----------------------------------------------------
# ============================================================================

def test_run_powershell_requires_marker(run):
    with pytest.raises(PermissionError):
        B.cmd_run_powershell("Remove-Item -Recurse C:\\temp")


def test_run_powershell_with_marker_executes(run):
    run.result = (0, "done", "")
    out = B.cmd_run_powershell("#jarvis-approved\nWrite-Output 'done'")
    assert "done" in out


def test_edit_registry_requires_hkcu(run):
    with pytest.raises(PermissionError):
        B.cmd_edit_registry("HKLM", "Software\\Other", "k", "v")


def test_edit_registry_rejects_foreign_key(run):
    with pytest.raises(PermissionError):
        B.cmd_edit_registry("HKCU", "Software\\Microsoft", "k", "v")


def test_edit_registry_allows_jarvis_key(run, home):
    monkeypatch_dir = B.REG_BACKUP_DIR
    try:
        B.REG_BACKUP_DIR = home / "regbak"
        run.result = (0, "OK", "")
        out = B.cmd_edit_registry("HKCU", "Software\\Jarvis\\Settings", "Volume", "50")
        assert "Jarvis" in out
    finally:
        B.REG_BACKUP_DIR = monkeypatch_dir


def test_edit_file_requires_text_extension(home):
    with pytest.raises(PermissionError):
        B.cmd_edit_file(str(home / "x.exe"), "data")


def test_edit_file_writes_and_backs_up(home):
    f = home / "note.txt"
    f.write_text("old", encoding="utf-8")
    out = B.cmd_edit_file(str(f), "new content")
    assert f.read_text(encoding="utf-8") == "new content"
    assert B.BACKUP_DIR.exists()
    assert any(B.BACKUP_DIR.glob("note.txt.*.bak"))


def test_edit_file_rejects_outside_profile(home):
    with pytest.raises(PermissionError):
        B.cmd_edit_file("/etc/passwd", "hacked")


# ============================================================================
# --- dispatcher ---------------------------------------------------------------
# ============================================================================

def test_unknown_command():
    res = B.DISPATCHER.execute("definitely_not_a_command")
    assert res["ok"] is False
    assert res["error"] == "unknown command"


def test_safe_command_executes(home):
    f = home / "x.txt"
    f.write_text("hi", encoding="utf-8")
    res = B.DISPATCHER.execute("file_size", [str(f)])
    assert res["ok"] is True


def test_dangerous_needs_confirm(run):
    res = B.DISPATCHER.execute("shutdown", ["5"])
    assert res["ok"] is False
    assert res["need_confirm"] is True
    assert res["token"]


def test_confirm_executes_pending(run):
    res = B.DISPATCHER.execute("shutdown", ["5"])
    done = B.process_request({"cmd": "confirm", "token": res["token"]})
    assert done["ok"] is True


def test_token_single_use(run):
    res = B.DISPATCHER.execute("shutdown", ["5"])
    token = res["token"]
    first = B.process_request({"cmd": "confirm", "token": token})
    second = B.process_request({"cmd": "confirm", "token": token})
    assert first["ok"] is True
    assert second["ok"] is False


def test_deny_removes_pending(run):
    res = B.DISPATCHER.execute("shutdown", ["5"])
    token = res["token"]
    out = B.process_request({"cmd": "deny", "token": token})
    assert out["ok"] is True
    assert B.DISPATCHER.take(token) is None


def test_rate_limit_blocks_burst():
    d = B.Dispatcher()
    for _ in range(B.RATE_LIMIT):
        assert d.allow("127.0.0.1-test")
    assert not d.allow("127.0.0.1-test")


# ============================================================================
# --- audit --------------------------------------------------------------------
# ============================================================================

def test_audit_writes_log(home):
    B.audit("test", ["a"], True, "done")
    log = (home / "audit.log").read_text(encoding="utf-8")
    assert "test" in log and "done" in log


def test_read_audit_reversed(home):
    B.audit("one", [], True, "r1")
    B.audit("two", [], True, "r2")
    rows = B.read_audit()
    assert rows and rows[0]["cmd"] == "two"


def test_empty_audit(home):
    assert B.read_audit() == []


# ============================================================================
# --- process_request ----------------------------------------------------------
# ============================================================================

def test_process_request_commands_list():
    out = B.process_request({"cmd": "commands", "id": "1"})
    assert out["ok"] is True
    assert any(c["name"] == "shutdown" for c in out["commands"])


def test_process_request_ping():
    out = B.process_request({"cmd": "ping", "id": "1"})
    assert out["pong"] is True


def test_process_request_unknown_returns_error():
    out = B.process_request({"cmd": "does-not-exist", "id": "x"})
    assert out["ok"] is False
    assert out["error"] == "unknown command"


# ============================================================================
# --- WebSocket layer (через asyncio.run + aiohttp.TestServer) ----------------
# ============================================================================

def _make_test_app_factory(tmp_path, monkeypatch):
    """Возвращает функцию-фабрику для создания aiohttp Application ВНУТРИ asyncio.run().

    Application должен создаваться внутри event loop, к которому привязывается.
    """
    # Подмены делаем здесь (это не зависит от loop)
    monkeypatch.setattr(B, "USER_HOME", tmp_path)
    monkeypatch.setattr(B, "AUDIT_LOG", tmp_path / "audit.log")
    monkeypatch.setattr(B, "SHORTCUTS_FILE", tmp_path / "shortcuts.json")
    monkeypatch.setattr(B, "BACKUP_DIR", tmp_path / ".dsh" / "jarvis_backups")
    monkeypatch.setattr(B, "REG_BACKUP_DIR", tmp_path / ".dsh" / "registry_backup")

    async def fake_index(request):
        return B.web.Response(text="jarvis-ui-stub", content_type="text/html")

    def factory():
        application = B.web.Application(middlewares=[B.origin_middleware])
        application.router.add_get("/", fake_index)
        application.router.add_get("/ws", B._ws_handler)
        application.router.add_get("/audit", B._audit_endpoint)
        return application

    return factory


def _ws_send_and_receive(app_factory, messages):
    """Запускает TestServer, отправляет список JSON-сообщений, возвращает ответы."""
    from aiohttp.test_utils import TestServer
    import aiohttp

    async def _run():
        app = app_factory()  # создаём ВНУТРИ event loop
        server = TestServer(app)
        await server.start_server()
        url = f"http://{server.host}:{server.port}/ws"
        try:
            async with aiohttp.ClientSession() as session:
                async with session.ws_connect(url) as ws:
                    responses = []
                    for msg in messages:
                        await ws.send_json(msg)
                        resp = await ws.receive_json(timeout=3)
                        responses.append(resp)
                    return responses
        finally:
            await server.close()

    return asyncio.run(_run())


def test_ws_ping(tmp_path, monkeypatch):
    app_factory = _make_test_app_factory(tmp_path, monkeypatch)
    responses = _ws_send_and_receive(app_factory, [{"cmd": "ping", "id": "1"}])
    assert responses[0]["pong"] is True
    assert responses[0]["id"] == "1"


def test_ws_unknown_command(tmp_path, monkeypatch):
    app_factory = _make_test_app_factory(tmp_path, monkeypatch)
    responses = _ws_send_and_receive(
        app_factory, [{"cmd": "definitely-unknown", "id": "x"}]
    )
    assert responses[0]["ok"] is False
    assert responses[0]["error"] == "unknown command"


def test_ws_commands_list(tmp_path, monkeypatch):
    app_factory = _make_test_app_factory(tmp_path, monkeypatch)
    responses = _ws_send_and_receive(app_factory, [{"cmd": "commands", "id": "2"}])
    assert responses[0]["ok"] is True
    assert any(c["name"] == "shutdown" for c in responses[0]["commands"])


def test_ws_multiple_commands(tmp_path, monkeypatch):
    """Smoke-test: 5 команд подряд (ping, commands, unknown, ping, ping)."""
    app_factory = _make_test_app_factory(tmp_path, monkeypatch)
    messages = [
        {"cmd": "ping", "id": "1"},
        {"cmd": "commands", "id": "2"},
        {"cmd": "nope", "id": "3"},
        {"cmd": "ping", "id": "4"},
        {"cmd": "ping", "id": "5"},
    ]
    responses = _ws_send_and_receive(app_factory, messages)
    assert len(responses) == 5
    assert responses[0]["pong"] is True
    assert responses[1]["ok"] is True
    assert responses[2]["ok"] is False
    assert responses[3]["pong"] is True
    assert responses[4]["pong"] is True


def test_ws_rate_limit(tmp_path, monkeypatch):
    """Rate-limit: 11-я команда за секунду должна быть отклонена."""
    counter = {"n": 0}
    def fake_allow(ip):
        counter["n"] += 1
        return counter["n"] <= 10
    monkeypatch.setattr(B.DISPATCHER, "allow", fake_allow)
    app_factory = _make_test_app_factory(tmp_path, monkeypatch)
    messages = [{"cmd": "ping", "id": str(i)} for i in range(11)]
    responses = _ws_send_and_receive(app_factory, messages)
    # Первые 10 — pong:true, 11-я — rate limit
    for i in range(10):
        assert responses[i].get("pong") is True, f"response {i}: {responses[i]}"
    assert responses[10].get("ok") is False
    assert "rate limit" in responses[10].get("error", "")


def test_ws_dangerous_command_returns_token(tmp_path, monkeypatch):
    """Опасная команда (shutdown) возвращает need_confirm + token, не исполняя."""
    counter = {"calls": 0}
    original_shutdown = B.cmd_shutdown
    def fake_shutdown(*args):
        counter["calls"] += 1
        return "shutdown called"
    monkeypatch.setattr(B, "cmd_shutdown", fake_shutdown)
    B.WHITELIST["shutdown"] = fake_shutdown  # patch registry
    try:
        app_factory = _make_test_app_factory(tmp_path, monkeypatch)
        responses = _ws_send_and_receive(
            app_factory, [{"cmd": "shutdown", "args": ["60"], "id": "x"}]
        )
        assert responses[0]["ok"] is False
        assert responses[0]["need_confirm"] is True
        assert responses[0]["token"]
        assert counter["calls"] == 0  # не вызвана без подтверждения
    finally:
        B.WHITELIST["shutdown"] = original_shutdown
        monkeypatch.undo()


def test_ws_confirm_full_flow(tmp_path, monkeypatch):
    """Полный цикл: shutdown → need_confirm → confirm → исполнение."""
    # Используем cancel_shutdown как безопасный заменитель shutdown
    fake_results = {"called": False}
    def fake_shutdown(delay):
        fake_results["called"] = True
        return f"shutdown in {delay}s"
    monkeypatch.setattr(B, "cmd_shutdown", fake_shutdown)
    B.WHITELIST["shutdown"] = fake_shutdown
    try:
        app_factory = _make_test_app_factory(tmp_path, monkeypatch)
        # Шаг 1: shutdown → need_confirm + token
        responses1 = _ws_send_and_receive(
            app_factory, [{"cmd": "shutdown", "args": ["30"], "id": "1"}]
        )
        token = responses1[0].get("token")
        assert token, f"no token: {responses1[0]}"
        assert fake_results["called"] is False
        # Шаг 2: confirm с этим токеном
        responses2 = _ws_send_and_receive(
            app_factory, [{"cmd": "confirm", "token": token, "id": "2"}]
        )
        assert responses2[0]["ok"] is True, f"confirm failed: {responses2[0]}"
        assert fake_results["called"] is True
    finally:
        B.WHITELIST["shutdown"] = B.cmd_shutdown


def test_ws_origin_forbidden(tmp_path, monkeypatch):
    """Чужой Origin → 403 при попытке подключиться к WS."""
    from aiohttp.test_utils import TestServer
    import aiohttp

    app_factory = _make_test_app_factory(tmp_path, monkeypatch)

    async def _run():
        app = app_factory()
        server = TestServer(app)
        await server.start_server()
        url = f"http://{server.host}:{server.port}/ws"
        try:
            async with aiohttp.ClientSession() as session:
                try:
                    async with session.ws_connect(
                        url,
                        headers={"Origin": "http://evil.com"},
                    ) as ws:
                        # Если соединение удалось — это баг
                        return False
                except aiohttp.WSServerHandshakeError:
                    return True  # ожидаемо: сервер отказал с 403
                except Exception:
                    return True  # тоже OK — соединение отклонено
        finally:
            await server.close()

    result = asyncio.run(_run())
    assert result is True


# ============================================================================
# --- Security --------------------------------------------------------------
# ============================================================================

def test_main_refuses_without_risks_flag(capsys):
    """Без --i-understand-the-risks main() возвращает exit code 2."""
    code = B.main([])
    assert code == 2
    captured = capsys.readouterr()
    assert "--i-understand-the-risks" in captured.err
