package com.adrina.jarvis;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * J.A.R.V.I.S. Android — WebView-оболочка для jarvis.html.
 * Мост "Android" даёт UI: голос (TTS/STT), системные команды телефона,
 * ИИ-мозг (chat/completions), настройки и аудит.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "file:///android_asset/jarvis.html";
    private static final String PREFS = "jarvis_prefs";
    private static final String K_SETTINGS = "settings_json";
    private static final int REQ_STT = 7001;
    private static final int REQ_MIC = 7002;
    private static final int AUDIT_MAX = 60;

    private WebView web;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private AudioManager audio;
    private SharedPreferences prefs;
    private final Handler ui = new Handler();
    private final ArrayDeque<String> audit = new ArrayDeque<>();
    private boolean sttBusy = false;
    private boolean torchOn = false;
    private String pendingDangerToken = null;
    private String pendingDangerCmd = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Разрешение на микрофон запрашиваем сразу — нужно для голосового ввода
        if (checkSelfPermission("android.permission.RECORD_AUDIO")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, REQ_MIC);
        }

        initTts();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " JarvisAndroid/1.0");

        web.setWebViewClient(new JarvisClient());
        web.setWebChromeClient(new JarvisChrome());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        hideSystemUi();
        web.loadUrl(START_URL);
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    /* ==================== TTS ==================== */

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (ttsReady) tts.setLanguage(new Locale("ru", "RU"));
        });
    }

    private void nativeSpeak(final String text, String lang, float rate, float pitch,
                             final String token) {
        if (!ttsReady) return;
        Locale loc = (lang != null && lang.startsWith("en")) ? new Locale("en", "GB")
                : new Locale("ru", "RU");
        try {
            tts.setLanguage(loc);
            tts.setSpeechRate(clamp(rate, 0.5f, 2.0f));
            Bundle params = new Bundle();
            params.putFloat("pitch", clamp(pitch, 0.5f, 2.0f)); // параметр читают TTS-движки
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, token);
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) {
                    js("__ttsDone(" + quoteJs(utteranceId) + ")");
                }
                @Override public void onError(String utteranceId) {
                    js("__ttsDone(" + quoteJs(utteranceId) + ")");
                }
            });
        } catch (Exception ignored) { }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /* ==================== WebView clients ==================== */

    private class JarvisClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            // Раздаём ассеты с CORS-заголовком, чтобы fetch('shortcuts.json') работал с file://
            if ("file".equals(u.getScheme()) && u.getPath() != null
                    && u.getPath().startsWith("/android_asset/")) {
                String path = u.getPath().substring("/android_asset/".length());
                String mime = guessMime(path);
                try {
                    InputStream is = getAssets().open(path);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Access-Control-Allow-Origin", "*");
                    WebResourceResponse resp = new WebResourceResponse(mime, "utf-8", is);
                    resp.setResponseHeaders(headers);
                    return resp;
                } catch (Exception e) {
                    return notFound();
                }
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String url = u.toString();
            if ("file".equals(u.getScheme())) return false; // свой интерфейс — внутри
            handleExternalTarget(url);
            return true;
        }
    }

    /** Запуск внешних целей: pkg: имя пакета, http(s) — браузер, steam:// — только ПК. */
    private void handleExternalTarget(String target) {
        try {
            if (target == null || target.isEmpty()) return;
            if (target.startsWith("pkg:")) {
                String pkg = target.substring(4).trim();
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    startActivity(i);
                    return;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=" + pkg)));
                } catch (Exception ee) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/search?q=" + pkg)));
                }
                return;
            }
            if (target.startsWith("steam://")) {
                toast("steam:// работает только на ПК, сэр");
                return;
            }
            if (target.startsWith("http://") || target.startsWith("https://")) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                return;
            }
            // что-то другое — пробуем как пакет, потом как URL
            Intent i = getPackageManager().getLaunchIntentForPackage(target);
            if (i != null) startActivity(i);
            else startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(target))));
        } catch (ActivityNotFoundException anf) {
            toast("Приложение не найдено: " + target);
        } catch (Exception e) {
            toast("Ошибка запуска: " + e.getMessage());
        }
    }

    private class JarvisChrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                try {
                    ArrayList<String> grant = new ArrayList<>();
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                                && checkSelfPermission("android.permission.RECORD_AUDIO")
                                        == PackageManager.PERMISSION_GRANTED) {
                            grant.add(r);
                        }
                    }
                    if (!grant.isEmpty()) request.grant(grant.toArray(new String[0]));
                    else request.deny();
                } catch (Exception ignored) { }
            });
        }
    }

    /* ==================== JS BRIDGE ==================== */

    private class Bridge {

        /** Главная точка входа: {id, cmd, args} → {id, ok, stdout|error}. */
        @JavascriptInterface
        public String exec(String payload) {
            try {
                JSONObject p = new JSONObject(payload);
                String id = p.optString("id", "c0");
                String cmd = p.optString("cmd", "");
                JSONArray args = p.optJSONArray("args");
                if ("__confirm__".equals(cmd)) {
                    // UI присылает {id, cmd:'__confirm__', token, answer} — token/answer на верхнем уровне
                    JSONArray carr = new JSONArray();
                    carr.put(p.optBoolean("answer", false));
                    JSONObject resp = runCommand(cmd, carr);
                    resp.put("id", id);
                    return resp.toString();
                }
                JSONObject resp = runCommand(cmd, args);
                resp.put("id", id);
                addAudit(cmd, args, resp.optBoolean("ok", false));
                return resp.toString();
            } catch (Exception e) {
                return jsonErr("c0", "Ошибка моста: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public String getSettings() {
            String saved = prefs.getString(K_SETTINGS, null);
            return saved != null ? saved : defaultSettings();
        }

        @JavascriptInterface
        public String saveSettings(String json) {
            try {
                new JSONObject(json); // валидация
                prefs.edit().putString(K_SETTINGS, json).apply();
                return "{\"ok\":true}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public String resetSettings() {
            String def = defaultSettings();
            prefs.edit().putString(K_SETTINGS, def).apply();
            return "{\"ok\":true,\"config\":" + def + "}";
        }

        @JavascriptInterface
        public String audit() {
            StringBuilder sb = new StringBuilder("{\"entries\":[");
            boolean first = true;
            for (String e : audit) {
                if (!first) sb.append(',');
                sb.append(e);
                first = false;
            }
            return sb.append("]}").toString();
        }

        /** Синхронный вызов ИИ (OpenAI-совместимый). Вход: {messages, model, base_url, api_key}. */
        @JavascriptInterface
        public String chat(String body) {
            try {
                JSONObject b = new JSONObject(body);
                String base = b.optString("base_url", "").trim();
                String key = b.optString("api_key", "").trim();
                String model = b.optString("model", "deepseek4pro").trim();
                if (base.isEmpty() || key.isEmpty()) {
                    return "{\"ok\":false,\"error\":\"нет base_url или api_key\"}";
                }
                if (!base.matches(".*(/v[0-9]+)$")) {
                    base = base.replaceAll("/+$", "") + "/v1";
                }
                JSONObject req = new JSONObject();
                req.put("model", model);
                req.put("messages", b.getJSONArray("messages"));
                req.put("temperature", 0.7);
                req.put("max_tokens", 900);
                HttpURLConnection c = (HttpURLConnection)
                        new URL(base.replaceAll("/+$", "") + "/chat/completions")
                                .openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(25000);
                c.setReadTimeout(60000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + key);
                byte[] out = req.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(out.length);
                c.getOutputStream().write(out);
                int code = c.getResponseCode();
                String text = readStream(code >= 400 ? c.getErrorStream() : c.getInputStream());
                c.disconnect();
                if (code >= 400) {
                    return "{\"ok\":false,\"error\":\"HTTP " + code + ": "
                            + jsEscape(clip(text, 300)) + "\"}";
                }
                JSONObject r = new JSONObject(text);
                String content = r.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content", "");
                JSONObject ok = new JSONObject();
                ok.put("ok", true);
                ok.put("content", content);
                return ok.toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + jsEscape(String.valueOf(e.getMessage())) + "\"}";
            }
        }

        /** Список моделей провайдера. */
        @JavascriptInterface
        public String models(String baseUrl, String apiKey) {
            try {
                String base = baseUrl.trim();
                if (!base.matches(".*(/v[0-9]+)$")) base = base.replaceAll("/+$", "") + "/v1";
                HttpURLConnection c = (HttpURLConnection) new URL(base + "/models")
                        .openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                int code = c.getResponseCode();
                String text = readStream(code >= 400 ? c.getErrorStream() : c.getInputStream());
                c.disconnect();
                if (code >= 400) {
                    return "{\"ok\":false,\"error\":\"HTTP " + code + "\"}";
                }
                JSONObject r = new JSONObject(text);
                JSONArray data = r.optJSONArray("data");
                JSONArray list = new JSONArray();
                if (data != null) {
                    for (int i = 0; i < data.length() && i < 60; i++) {
                        list.put(data.getJSONObject(i).optString("id", "?"));
                    }
                }
                return "{\"ok\":true,\"models\":" + list + "}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + jsEscape(String.valueOf(e.getMessage())) + "\"}";
            }
        }

        /** Нативный синтез речи. Вход: {token, text, lang, rate, pitch}. */
        @JavascriptInterface
        public String speak(String payload) {
            try {
                JSONObject p = new JSONObject(payload);
                nativeSpeak(p.optString("text", ""), p.optString("lang", "ru-RU"),
                        (float) p.optDouble("rate", 0.93),
                        (float) p.optDouble("pitch", 0.78),
                        p.optString("token", "t" + System.currentTimeMillis()));
                return "{\"ok\":true}";
            } catch (Exception e) {
                return "{\"ok\":false}";
            }
        }

        @JavascriptInterface
        public String stopSpeak() {
            try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
            return "{\"ok\":true}";
        }

        /** Однократное распознавание речи (диалог Google). */
        @JavascriptInterface
        public String startSTT() {
            return fireSTT("ru-RU");
        }

        @JavascriptInterface
        public String startSTTLang(String lang) {
            return fireSTT(lang == null || lang.isEmpty() ? "ru-RU" : lang);
        }

        @JavascriptInterface
        public String battery() {
            return batteryJson().toString();
        }

        @JavascriptInterface
        public String vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) v.vibrate(Math.max(10, Math.min(3000, ms)));
                return "{\"ok\":true}";
            } catch (Exception e) {
                return "{\"ok\":false}";
            }
        }

        @JavascriptInterface
        public String torch(boolean on) {
            try {
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String id = null;
                for (String cid : cm.getCameraIdList()) {
                    if (cm.getCameraCharacteristics(cid)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == Boolean.TRUE) {
                        id = cid;
                        break;
                    }
                }
                if (id == null) return "{\"ok\":false,\"error\":\"фонарик не найден\"}";
                cm.setTorchMode(id, on);
                torchOn = on;
                return "{\"ok\":true}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + jsEscape(String.valueOf(e.getMessage())) + "\"}";
            }
        }

        @JavascriptInterface
        public String toast(String msg) {
            MainActivity.this.runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
            return "{\"ok\":true}";
        }

        @JavascriptInterface
        public String deviceInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                o.put("android", Build.VERSION.RELEASE);
                o.put("sdk", Build.VERSION.SDK_INT);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    /* ==================== КОМАНДЫ ==================== */

    private JSONObject runCommand(String cmd, JSONArray args) {
        String a0 = str(args, 0);
        switch (cmd == null ? "" : cmd) {

            case "volume_set": {
                try {
                    int pct = Integer.parseInt(a0.trim());
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int v = Math.max(0, Math.min(max, Math.round(max * pct / 100f)));
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                    return jsonOk("Громкость " + pct + "%");
                } catch (Exception e) {
                    return jsonErr("Не смог изменить громкость: " + e.getMessage());
                }
            }
            case "volume_mute": {
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                    return jsonOk("Звук выключен");
                } catch (Exception e) {
                    return jsonErr(String.valueOf(e.getMessage()));
                }
            }
            case "brightness_set": {
                try {
                    if (!android.provider.Settings.System.canWrite(this)) {
                        return jsonErr("Нет разрешения на изменение яркости (Настройки → Приложения → J.A.R.V.I.S. → Изменение настроек)");
                    }
                    int pct = Integer.parseInt(a0.trim());
                    int v = Math.max(5, Math.min(255, Math.round(255f * pct / 100)));
                    android.provider.Settings.System.putInt(getContentResolver(),
                            android.provider.Settings.System.SCREEN_BRIGHTNESS, v);
                    return jsonOk("Яркость " + pct + "%");
                } catch (Exception e) {
                    return jsonErr(String.valueOf(e.getMessage()));
                }
            }
            case "system_info": {
                JSONObject b = batteryJson();
                String ram = ramInfo();
                String out = "АККУМУЛЯТОР " + b.optInt("level", -1) + "%"
                        + ("true".equals(String.valueOf(b.opt("charging"))) ? " (заряжается)" : "")
                        + " · ОЗУ " + ram
                        + " · Android " + Build.VERSION.RELEASE;
                return jsonOk(out);
            }
            case "battery": {
                JSONObject b = batteryJson();
                String out = "Заряд " + b.optInt("level", -1) + "%"
                        + ("true".equals(String.valueOf(b.opt("charging"))) ? ", заряжается, сэр" : "");
                return jsonOk(out);
            }
            case "media_play_pause": return mediaKey(85); // KEYCODE_MEDIA_PLAY_PAUSE
            case "media_next": return mediaKey(87);   // KEYCODE_MEDIA_NEXT
            case "media_prev": return mediaKey(88);   // KEYCODE_MEDIA_PREVIOUS
            case "open_url": {
                try {
                    handleExternalTarget(a0);
                    return jsonOk("Открыто");
                } catch (Exception e) {
                    return jsonErr(String.valueOf(e.getMessage()));
                }
            }
            case "launch_app": {
                String target = resolveShortcut(a0);
                if (target == null) {
                    return jsonErr("Не найден ярлык «" + a0 + "». Добавьте его в настройках.");
                }
                handleExternalTarget(target);
                return jsonOk("Запускаю " + a0);
            }
            case "torch_on": return torchResult(true);
            case "torch_off": return torchResult(false);
            case "open_settings": {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                    return jsonOk("Открываю настройки");
                } catch (Exception e) { return jsonErr(String.valueOf(e.getMessage())); }
            }
            case "open_wifi": {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                    return jsonOk("Настройки Wi-Fi");
                } catch (Exception e) { return jsonErr(String.valueOf(e.getMessage())); }
            }
            case "open_bluetooth": {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
                    return jsonOk("Настройки Bluetooth");
                } catch (Exception e) { return jsonErr(String.valueOf(e.getMessage())); }
            }
            case "__confirm__": {
                boolean answer = args != null && args.length() > 0 && args.optBoolean(0);
                if (answer && pendingDangerCmd != null) {
                    String c = pendingDangerCmd;
                    pendingDangerCmd = null;
                    pendingDangerToken = null;
                    return runCommand(c, new JSONArray());
                }
                pendingDangerCmd = null;
                pendingDangerToken = null;
                return jsonOk("Отменено");
            }
            /* ПК-команды: честно отвечаем, что это не для телефона */
            case "shutdown":
            case "restart":
            case "sleep_pc":
            case "lock_pc":
            case "screenshot":
            case "open_explorer":
            case "list_dir":
            case "create_folder":
            case "delete_file":
            case "minimize_all":
            case "show_desktop":
            case "cancel_shutdown":
                return jsonErr("Эта команда — для ПК-версии JARVIS. На телефоне недоступна, сэр.");
            default:
                return jsonErr("Неизвестная команда: " + cmd);
        }
    }

    /** Для подтверждения опасных команд: запоминаем, что выполнять. */
    private JSONObject mediaKey(int keycode) {
        try {
            audio.dispatchMediaKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, keycode));
            audio.dispatchMediaKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_UP, keycode));
            return jsonOk("Готово, сэр");
        } catch (Exception e) {
            return jsonErr(String.valueOf(e.getMessage()));
        }
    }

    private JSONObject torchResult(boolean on) {
        Bridge b = new Bridge();
        String r = b.torch(on);
        try {
            JSONObject j = new JSONObject(r);
            if (j.optBoolean("ok")) return jsonOk(on ? "Фонарик включен" : "Фонарик выключен");
            return jsonErr(j.optString("error", "ошибка"));
        } catch (Exception e) {
            return jsonErr("ошибка фонарика");
        }
    }

    private String resolveShortcut(String name) {
        try {
            JSONObject cfg = new JSONObject(prefs.getString(K_SETTINGS, defaultSettings()));
            JSONObject sc = cfg.optJSONObject("shortcuts");
            if (sc != null && sc.has(name)) return sc.optString(name);
        } catch (Exception ignored) { }
        try {
            byte[] buf = new byte[8192];
            InputStream is = getAssets().open("shortcuts.json");
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            is.close();
            JSONObject sc = new JSONObject(sb.toString());
            if (sc.has(name)) return sc.optString(name);
        } catch (Exception ignored) { }
        return null;
    }

    private JSONObject batteryJson() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONObject o = new JSONObject();
        try {
            int level = i != null ? i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            int scale = i != null ? i.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
            int status = i != null ? i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
            int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
            o.put("level", pct);
            o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);
        } catch (Exception ignored) { }
        return o;
    }

    private String ramInfo() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            float total = mi.totalMem / 1073741824f;
            float free = mi.availMem / 1073741824f;
            return String.format(Locale.US, "%.1f/%.1f ГБ свободно", free, total);
        } catch (Exception e) {
            return "—";
        }
    }

    /* ==================== STT ==================== */

    private String fireSTT(String lang) {
        if (sttBusy) return "{\"ok\":true,\"busy\":true}";
        if (checkSelfPermission("android.permission.RECORD_AUDIO")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, REQ_MIC);
            return "{\"ok\":false,\"error\":\"нет разрешения на микрофон\"}";
        }
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Слушаю, сэр...");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            sttBusy = true;
            startActivityForResult(i, REQ_STT);
            return "{\"ok\":true}";
        } catch (ActivityNotFoundException e) {
            sttBusy = false;
            return "{\"ok\":false,\"error\":\"Распознавание речи не установлено (Google app)\"}";
        } catch (Exception e) {
            sttBusy = false;
            return "{\"ok\":false,\"error\":\"" + jsEscape(String.valueOf(e.getMessage())) + "\"}";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_STT) {
            sttBusy = false;
            String text = null;
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> res = data.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS);
                if (res != null && !res.isEmpty()) text = res.get(0);
            }
            final String t = text;
            if (web != null) {
                web.post(() -> {
                    String script = (t == null || t.trim().isEmpty())
                            ? "window.__nativeSRError && __nativeSRError('no-speech');"
                            : "window.__nativeSRResult && __nativeSRResult("
                                    + quoteJs(t.trim()) + ", true);";
                    web.evaluateJavascript(script, null);
                });
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_MIC) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!ok) toast("Без микрофона голосовой ввод не работает, сэр");
        }
    }

    /* ==================== УТИЛИТЫ ==================== */

    private void js(final String script) {
        if (web == null) return;
        runOnUiThread(() -> web.evaluateJavascript(script, null));
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private synchronized void addAudit(String cmd, JSONArray args, boolean ok) {
        try {
            if ("__confirm__".equals(cmd)) return;
            JSONObject e = new JSONObject();
            e.put("ts", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    .format(new Date()));
            e.put("cmd", cmd);
            e.put("args", args != null ? args : new JSONArray());
            e.put("ok", ok);
            e.put("danger", false);
            audit.addLast(e.toString());
            while (audit.size() > AUDIT_MAX) audit.removeFirst();
        } catch (Exception ignored) { }
    }

    private String defaultSettings() {
        return "{\"shortcuts\":{"
                + "\"telegram\":\"pkg:org.telegram.messenger\","
                + "\"whatsapp\":\"pkg:com.whatsapp\","
                + "\"youtube\":\"https://www.youtube.com\","
                + "\"chrome\":\"pkg:com.android.chrome\","
                + "\"spotify\":\"pkg:com.spotify.music\","
                + "\"discord\":\"pkg:com.discord\","
                + "\"roblox\":\"pkg:com.roblox.client\","
                + "\"tiktok\":\"pkg:com.zhiliaoapp.musically\","
                + "\"instagram\":\"pkg:com.instagram.android\","
                + "\"vk\":\"pkg:com.vkontakte.android\","
                + "\"yandex_music\":\"pkg:ru.yandex.music\","
                + "\"карты\":\"pkg:com.google.android.apps.maps\","
                + "\"gmail\":\"pkg:com.google.android.gm\","
                + "\"камера\":\"pkg:com.android.camera2\","
                + "\"steam\":\"https://store.steampowered.com\""
                + "},\"allowedFolders\":[],\"allowedExeRoots\":[],"
                + "\"dangerousCommands\":{},"
                + "\"voice\":{\"mode\":\"russian\",\"rate\":1,\"pitch\":1},"
                + "\"ai\":{\"enabled\":false,"
                + "\"base_url\":\"https://ai.starimg.ru/v1\","
                + "\"model\":\"deepseek4pro\","
                + "\"api_key\":\"\","
                + "\"persona\":\"Ты — J.A.R.V.I.S. (Just A Rather Very Intelligent System), персональный ИИ-ассистент. Общайся на русском, кратко, по делу, с лёгким юмором и обращайся к пользователю как к «сэр». Ты управляешь его телефоном. Чтобы выполнить действие, в конце ответа вставь отдельной строкой метку: [[OPEN:https://...]] — открыть ссылку, [[RUN:имя]] — запустить приложение из списка ярлыков (например: telegram, youtube, chrome, spotify), [[SEARCH:запрос]] — искать в интернете. Если действие не требуется — метку не ставь. Никогда не объясняй формат меток.\""
                + "}}";
    }

    private static String str(JSONArray a, int i) {
        if (a == null || i >= a.length()) return "";
        return a.optString(i, "");
    }

    private static String guessMime(String path) {
        String p = (path == null ? "" : path).toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }

    private static JSONObject jsonOk(String stdout) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("stdout", stdout);
        } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject jsonErr(String err) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("stderr", err);
            o.put("error", err);
        } catch (Exception ignored) { }
        return o;
    }

    private static String jsonErr(String id, String err) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("ok", false);
            o.put("error", err);
        } catch (Exception ignored) { }
        return o.toString();
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String clip(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : String.valueOf(s);
    }

    private static String jsEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static String quoteJs(String s) {
        return "\"" + jsEscape(s) + "\"";
    }

    private WebResourceResponse notFound() {
        try {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    new HashMap<>(), new ByteArrayInputStream("not found".getBytes()));
        } catch (Exception e) {
            return null;
        }
    }

    /* ==================== BACK ==================== */

    @Override
    public void onBackPressed() {
        // полноэкранный ассистент: назад сворачивает приложение
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
