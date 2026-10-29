#!/usr/bin/env python3
"""Сборка assets/jarvis.html для Android APK:
репозиторный HTML/jarvis.html
+ Layer1 (shims) после <body>
+ Layer2 (голос/ИИ/телефон) перед </body>
+ Layer3 (ориентации) перед </body>
+ Layer4 (уведомления/напоминания) перед </body>
+ Layer5 (смс/мессенджеры/звонки, v1.5) перед </body>
+ патч openTarget → нативный мост (pkg:/http запуски через Java,
  вместе с именем ярлыка — для умного поиска на телефоне)
+ патч launchByName → fallback на умный поиск приложений в Java
+ патч fallback-карты ярлыков → только телефонные цели (v1.4,
  без steam:// и прочих ПК-схем)."""
import io, os, re, sys

BASE = "/home/z/my-project/jarvis_android"
SRC = "/home/z/my-project/jarvis_repo/HTML/jarvis.html"

html = io.open(SRC, encoding="utf-8").read()
l1 = io.open(f"{BASE}/patch/layer1.js", encoding="utf-8").read()
l2 = io.open(f"{BASE}/patch/layer2.js", encoding="utf-8").read()
l3 = io.open(f"{BASE}/patch/layer3.js", encoding="utf-8").read()
l4 = io.open(f"{BASE}/patch/layer4.js", encoding="utf-8").read()
l5 = io.open(f"{BASE}/patch/layer5.js", encoding="utf-8").read()

# --- Патч 1: openTarget → нативный мост (надёжный запуск pkg:/https,
#     минуя window.open, который в WebView ненадёжен для кастомных схем) ---
ot_old = """      var w = window.open(target, '_blank');
      if (!w) window.location.href = target;
      addLog('sys', '✅ Запуск: ' + name + '\\n    → ' + target);"""
ot_new = """      if (window.Android){
        var r = JSON.parse(Android.exec(JSON.stringify({cmd:'open_url', args:[String(target), String(name||'')]})));
        addLog('sys', (r && r.ok) ? ('✅ Запуск: ' + name + '\\n    → ' + target) : ('❌ ' + ((r && r.error) || 'не удалось')));
      } else {
        var w = window.open(target, '_blank');
        if (!w) window.location.href = target;
        addLog('sys', '✅ Запуск: ' + name + '\\n    → ' + target);
      }"""
assert ot_old in html, "патч openTarget: исходный блок не найден"
html = html.replace(ot_old, ot_new)

# --- Патч 2: launchByName — перед «Не нашёл» пробуем умный поиск
#     установленных приложений на стороне Java ---
lb_old = re.compile(
    r"return R\('Не нашёл «' \+ rawName \+ '». Скажите «список программ»\.',\s*"
    r"'Unknown \"' \+ rawName \+ '\"\.'\);")
lb_new = """    if (window.Android){
      try{
        var nr = JSON.parse(Android.exec(JSON.stringify({cmd:'launch_app', args:[String(rawName)]})));
        if (nr && nr.ok){
          var lbl = nr.label || rawName;
          addLog('sys', '✅ Запуск: ' + lbl);
          return R('Запускаю ' + lbl + '.', 'Launching ' + lbl + '.');
        }
        if (nr && nr.error) return R(nr.error, 'Unknown "' + rawName + '".');
      }catch(e){}
    }
    return R('Не нашёл «' + rawName + '». Скажите «список программ».',
             'Unknown "' + rawName + '".');"""
assert lb_old.search(html), "патч launchByName: исходный блок не найден"
html = lb_old.sub(lb_new, html, count=1)

# --- Патч 3 (v1.4): fallback-карта ярлыков — ТОЛЬКО приложения телефона.
#     На ПК-базе fallback указывает на steam:// ярлыки; в WebView fetch()
#     file:// часто падает → срабатывает fallback → «запусти спотифай»
#     уходило в steam:// → «работает только на ПК». Теперь fallback —
#     телефонные pkg-цели, а не-телефонные схемы Java доисковывает по имени. ---
fb_old = '''        SHORTCUTS = {
          "discord": "steam://rungameid/1359653083732303872",
          "spotify": "steam://rungameid/12186439978347134976",
          "roblox": "steam://rungameid/11069516278275244032",
          "euro_truck": "steam://rungameid/227300",
          "telegram": "steam://rungameid/17847010478505590784",
          "chrome": "steam://rungameid/13223750798820769792",
          "yandex_music": "https://music.yandex.ru",
          "youtube": "https://www.youtube.com",
          "zona": "steam://rungameid/18333690144986497024",
          "faceit_ac": "steam://rungameid/11940698790236782592",
          "proxy": "steam://rungameid/12269805332217200640",
          "VPN": "https://nerp.bot/cabinet",
                "Code Studio": "steam://rungameid/779260"
        };'''
fb_new = '''        /* [JARVIS ANDROID] v1.4: fallback — только приложения телефона */
        SHORTCUTS = {
          "telegram": "pkg:org.telegram.messenger",
          "whatsapp": "pkg:com.whatsapp",
          "youtube": "pkg:com.google.android.youtube",
          "chrome": "pkg:com.android.chrome",
          "spotify": "pkg:com.spotify.music",
          "discord": "pkg:com.discord",
          "roblox": "pkg:com.roblox.client",
          "tiktok": "pkg:com.zhiliaoapp.musically",
          "instagram": "pkg:com.instagram.android",
          "vk": "pkg:com.vkontakte.android",
          "yandex_music": "pkg:ru.yandex.music",
          "карты": "pkg:com.google.android.apps.maps",
          "gmail": "pkg:com.google.android.gm",
          "камера": "pkg:com.android.camera2",
          "галерея": "pkg:com.google.android.apps.photos",
          "ютуб": "pkg:com.google.android.youtube",
          "почта": "pkg:com.google.android.gm"
        };'''
assert fb_old in html, "патч fallback-карты: исходный steam-блок не найден"
html = html.replace(fb_old, fb_new)

# --- Баг оригинала: IIFE «Привязка событий» модалки выполняется до того,
# как модалка появилась в DOM (script в 250-2148, модалка с 2149).
# Вырезаем блок и выполняем его в слое 2 (после всего DOM). ---
marker = "/* --- Привязка событий --- */"
idx_start = html.index(marker)
script_end = html.index("</script>", idx_start)
idx_end = html.rindex("})();", idx_start, script_end) + len("})();")
bind_block = html[idx_start:idx_end]
html = html[:idx_start] + "/* [JARVIS ANDROID] привязка событий модалки перенесена в слой 2 */" + html[idx_end:]
l2 = bind_block + "\n\n" + l2

# защита от преждевременного закрытия script
for name, js in (("layer1", l1), ("layer2", l2), ("layer3", l3), ("layer4", l4), ("layer5", l5)):
    if "</script" in js.lower():
        sys.exit(f"FATAL: {name} содержит </script>")

marker_body = "<body>"
idx = html.index(marker_body) + len(marker_body)
html = html[:idx] + "\n<script>\n" + l1 + "\n</script>\n" + html[idx:]

close = html.rindex("</body>")
html = html[:close] + "<script>\n" + l2 + "\n</script>\n<script>\n" + l3 + "\n</script>\n<script>\n" + l4 + "\n</script>\n<script>\n" + l5 + "\n</script>\n" + html[close:]

out = f"{BASE}/assets/jarvis.html"
io.open(out, "w", encoding="utf-8").write(html)

size = os.path.getsize(out)
n_android = html.count("JARVIS ANDROID")
print(f"OK: {out} ({size} bytes, markers: {n_android})")
# быстрые проверки
assert html.count("<script>") >= 4
assert "FakeWebSocket" in html and "NativeSR" in html and "aiPanel" in html
assert "jarvisOrient" in html, "нет слоя 3 (ориентации)"
assert "cmd:'open_url'" in html, "openTarget не пропатчен"
assert "nr.label" in html, "launchByName не пропатчен"
assert "__jarvisNotifFired" in html, "нет слоя 4 (уведомления)"
assert "remind_in" in html, "нет голосовых команд напоминаний"
assert "steam://rungameid" not in html, "остались ПК steam-ярлыки!"
assert "String(name||'')" in html, "openTarget не передаёт имя"
assert "'send_sms'" in html and "'sms_toggle'" in html, "нет слоя 5 (смс/мессенджеры/звонки)"
assert "__jarvisSms" in html, "нет хука входящих СМС"
assert "smsChip" in html, "нет чипа чтения СМС"
assert "'open_chat'" in html and "appFrom" in html, "нет маршрутизации мессенджеров"
print("checks passed: shims + v1.1 + v1.2 + v1.4 + v1.5 (смс/звонки) present")
