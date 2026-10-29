package com.adrina.jarvis;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.database.Cursor;
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
import android.telephony.SmsManager;
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
import java.util.List;
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
    private static final int REQ_PERMS = 7003; /* микрофон + уведомления одним диалогом */
    private static final int AUDIT_MAX = 60;
    private static final String K_NOTIFS = "notifs_json"; /* список запланированных напоминаний */
    /** Живой экземпляр — NotifReceiver передаёт срабатывания в WebView. */
    private static MainActivity inst;

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
    /** Индекс установленных приложений: "нижнее регистра имя" → [package, label]. */
    private HashMap<String, String[]> appIndex = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        inst = this;
        NotifReceiver.ensureChannel(this);

        // Разрешения одним диалогом: микрофон (голос) + уведомления (напоминания,
        // Android 13+ требует рантайм-запрос POST_NOTIFICATIONS)
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission("android.permission.RECORD_AUDIO")
                != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.RECORD_AUDIO");
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        /* v1.5: SMS (чтение вслух в фоне + отправка) и контакты (имена вместо
           номеров). Все — опасные, поэтому рантайм-запрос тем же диалогом. */
        String[] smsNeed = {"android.permission.RECEIVE_SMS",
                "android.permission.READ_SMS",
                "android.permission.SEND_SMS",
                "android.permission.READ_CONTACTS"};
        for (String pr : smsNeed) {
            if (checkSelfPermission(pr) != PackageManager.PERMISSION_GRANTED) need.add(pr);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);

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

        // Проверка обновлений — при каждом запуске приложения (в фоне,
        // не тормозит старт; если вышла новая версия — предложим скачать)
        checkForAppUpdate();
    }

    /* ==================== ПРОВЕРКА ОБНОВЛЕНИЙ (v1.3) ==================== */

    private static final String APP_VERSION = "1.5";
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/AdrinaLiness/jarvis/main/VERSION.json";
    private boolean updateDialogShown = false;

    /** "1.10" -> [1, 10] — сравнение версий по-числовому, а не по-строчному. */
    private static int[] verTuple(String v) {
        int[] res = new int[4];
        int n = 0, cur = 0;
        boolean has = false;
        for (int i = 0; i <= v.length() && n < 4; i++) {
            if (i == v.length() || v.charAt(i) == '.') {
                res[n++] = has ? cur : 0;
                cur = 0;
                has = false;
            } else {
                char c = v.charAt(i);
                if (c >= '0' && c <= '9') {
                    cur = cur * 10 + (c - '0');
                    has = true;
                }
            }
        }
        return res;
    }

    private static boolean verOlder(String a, String b) {
        int[] x = verTuple(a), y = verTuple(b);
        for (int i = 0; i < 4; i++) {
            if (x[i] < y[i]) return true;
            if (x[i] > y[i]) return false;
        }
        return false;
    }

    /**
     * Фоново сверяет APP_VERSION с VERSION.json в репозитории.
     * Любая ошибка (нет сети, таймаут) — молча пропускаем: это норма,
     * проверка повторится при следующем запуске приложения.
     */
    private void checkForAppUpdate() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String latest = "";
                String download = "https://github.com/AdrinaLiness/jarvis/releases/latest";
                try {
                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) new java.net.URL(VERSION_URL).openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("User-Agent", "JARVIS-android");
                    int code = conn.getResponseCode();
                    if (code != 200) throw new Exception("HTTP " + code);
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject data = new JSONObject(sb.toString());
                    latest = data.getJSONObject("components").optString("android", "");
                    String dl = data.optString("download", "");
                    if (!dl.isEmpty()) download = dl;
                } catch (Exception e) {
                    return; /* офлайн — не страшно */
                }
                if (latest.isEmpty() || !verOlder(APP_VERSION, latest)) return;
                final String fLatest = latest;
                final String fDownload = download;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (updateDialogShown || isFinishing()) return;
                        updateDialogShown = true;
                        try {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Доступно обновление JARVIS")
                                    .setMessage("У тебя v" + APP_VERSION
                                            + ", свежая версия — v" + fLatest
                                            + ".\n\nСкачать новый APK со страницы релизов?"
                                            + "\n\n(Если телефон не захочет ставить поверх старой версии — удали JARVIS и установи заново.)")
                                    .setPositiveButton("Скачать", new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface d, int w) {
                                            try {
                                                startActivity(new Intent(Intent.ACTION_VIEW,
                                                        Uri.parse(fDownload)));
                                            } catch (Exception ignored) { }
                                        }
                                    })
                                    .setNegativeButton("Позже", null)
                                    .show();
                        } catch (Exception ignored) { }
                    }
                });
            }
        }, "jarvis-update-check").start();
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
            handleExternalTarget(url, null);
            return true;
        }
    }

    /** Запуск внешних целей НА ТЕЛЕФОНЕ: pkg: пакет, http(s) — браузер,
     *  всё остальное (в т.ч. ПК-схемы вроде steam://) — умный поиск
     *  приложения по имени среди установленных на телефоне. */
    private void handleExternalTarget(String target, String name) {
        try {
            if (target == null || target.isEmpty()) return;
            if (target.startsWith("pkg:")) {
                String pkg = target.substring(4).trim();
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    startActivity(i);
                    return;
                }
                toast("«" + pkg + "» не установлено — открываю Play Market, сэр");
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=" + pkg)));
                } catch (Exception ee) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/search?q=" + pkg)));
                }
                return;
            }
            if (target.startsWith("http://") || target.startsWith("https://")) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                return;
            }
            /* Не телефонная схема (steam:// и пр.) — ищем приложение на телефоне */
            String probe = (name != null && !name.isEmpty())
                    ? name : target.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
            String[] app = resolveInstalledApp(probe);
            if (app != null) {
                Intent li = getPackageManager().getLaunchIntentForPackage(app[0]);
                if (li != null) {
                    startActivity(li);
                    return;
                }
            }
            /* Последний шанс — трактуем цель как имя пакета */
            Intent i = getPackageManager().getLaunchIntentForPackage(target);
            if (i != null) {
                startActivity(i);
            } else {
                toast("«" + probe + "» не найдено на телефоне, сэр");
            }
        } catch (ActivityNotFoundException anf) {
            toast("Приложение не найдено: "
                    + (name != null && !name.isEmpty() ? name : target));
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
            vibrateSafe(ms);
            return "{\"ok\":true}";
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
                    handleExternalTarget(a0, str(args, 1));
                    return jsonOk("Открыто");
                } catch (Exception e) {
                    return jsonErr(String.valueOf(e.getMessage()));
                }
            }
            case "launch_app": {
                /* 1) Ярлыки пользователя и встроенные */
                String target = resolveShortcut(a0);
                if (target != null) {
                    handleExternalTarget(target, a0);
                    return jsonOk("Запускаю " + a0);
                }
                /* 2) Умный поиск среди установленных приложений по названию */
                String[] app = resolveInstalledApp(a0);
                if (app != null) {
                    try {
                        Intent i = getPackageManager().getLaunchIntentForPackage(app[0]);
                        if (i != null) {
                            startActivity(i);
                            return jsonOk("Запускаю " + app[1], app[1]);
                        }
                    } catch (Exception e) {
                        return jsonErr("Не смог запустить «" + app[1] + "»: " + e.getMessage());
                    }
                }
                return jsonErr("Не нашёл «" + a0 + "» среди установленных приложений, сэр.\nДобавьте ярлык в настройках или проверьте название.");
            }
            case "torch_on": return torchResult(true);
            case "torch_off": return torchResult(false);
            /* ==================== УВЕДОМЛЕНИЯ (v1.2) ==================== */
            case "notify": {
                String title = a0.isEmpty() ? "J.A.R.V.I.S." : a0;
                String err = postNotifNow("n" + System.currentTimeMillis(),
                        title, str(args, 1));
                if (err != null) return jsonErr(err);
                return jsonOk("Уведомление отправлено, сэр");
            }
            case "notify_test": {
                String err = postNotifNow("test" + System.currentTimeMillis(),
                        "\uD83D\uDD14 ТЕСТ СВЯЗИ", "Уведомления работают, сэр!");
                if (err != null) return jsonErr(err);
                vibrateSafe(180);
                return jsonOk("Уведомления работают, сэр");
            }
            case "remind_in": {
                long sec;
                try { sec = Long.parseLong(a0.trim()); }
                catch (Exception e) { return jsonErr("Не понял интервал, сэр"); }
                if (sec < 5) return jsonErr("Интервал слишком короткий, сэр — минимум 5 секунд");
                if (sec > 2592000L) return jsonErr("Слишком длинный интервал, сэр — максимум 30 дней");
                String text = str(args, 1);
                String label = str(args, 2);
                if (label.isEmpty()) label = "через " + fmtDuration(sec);
                String id = "r" + System.currentTimeMillis();
                scheduleNotif(id, System.currentTimeMillis() + sec * 1000L,
                        "⏰ НАПОМИНАНИЕ", text.isEmpty() ? "Напоминание, сэр" : text);
                return jsonOk("Принято, сэр. Напомню " + label
                        + (text.isEmpty() ? "." : ": «" + text + "»"));
            }
            case "timer_start": {
                long sec;
                try { sec = Long.parseLong(a0.trim()); }
                catch (Exception e) { return jsonErr("Не понял интервал, сэр"); }
                if (sec < 5) return jsonErr("Интервал слишком короткий, сэр — минимум 5 секунд");
                if (sec > 2592000L) return jsonErr("Слишком длинный интервал, сэр — максимум 30 дней");
                String id = "t" + System.currentTimeMillis();
                scheduleNotif(id, System.currentTimeMillis() + sec * 1000L,
                        "⏰ ТАЙМЕР", "Время вышло, сэр!");
                return jsonOk("Таймер на " + fmtDuration(sec)
                        + " запущен, сэр — пришлю уведомление");
            }
            case "notif_list": {
                List<JSONObject> items = sortedNotifs();
                if (items.isEmpty()) return jsonOk("Активных напоминаний нет, сэр");
                long now = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder("Активные напоминания, сэр:\n");
                for (int k = 0; k < items.size(); k++) {
                    JSONObject o = items.get(k);
                    long left = Math.max(0, (o.optLong("at") - now) / 1000L);
                    String what;
                    if ("⏰ ТАЙМЕР".equals(o.optString("title"))) {
                        what = "таймер";
                    } else {
                        what = o.optString("text", "");
                        if (what.isEmpty() || "Напоминание, сэр".equals(what)) {
                            what = o.optString("title", "напоминание");
                        }
                    }
                    sb.append(k + 1).append(". через ").append(fmtDuration(left))
                      .append(" — ").append(what).append('\n');
                }
                return jsonOk(sb.toString().trim());
            }
            case "notif_cancel_all": {
                JSONArray arr = loadNotifs();
                boolean had = arr.length() > 0;
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                for (int k = 0; k < arr.length(); k++) {
                    try { am.cancel(notifAlarmPI(arr.getJSONObject(k).optString("id"))); }
                    catch (Exception ignored) { }
                }
                saveNotifs(new JSONArray());
                try {
                    NotificationManager nm = (NotificationManager)
                            getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.cancelAll();
                } catch (Exception ignored) { }
                return jsonOk(had ? "Отменил все напоминания и таймеры, сэр"
                        : "Активных напоминаний и не было, сэр");
            }
            case "notif_cancel": {
                int num;
                try { num = Integer.parseInt(a0.trim()); }
                catch (Exception e) {
                    return jsonErr("Какое напоминание отменить, сэр? Скажите: покажи напоминания");
                }
                List<JSONObject> items = sortedNotifs();
                if (num < 1 || num > items.size()) {
                    return jsonErr("Нет напоминания с номером " + num + ", сэр");
                }
                String id = items.get(num - 1).optString("id");
                cancelNotifById(id);
                return jsonOk("Отменил напоминание №" + num + ", сэр");
            }
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
            /* ============ СМС, МЕССЕНДЖЕРЫ, ЗВОНКИ (v1.5) ============ */
            case "send_sms": {
                String text = str(args, 1);
                if (a0.trim().isEmpty() || text.isEmpty()) {
                    return jsonErr("Формат: отправь смс <номер или имя> <текст>, сэр");
                }
                return doSendSms(a0.trim(), text);
            }
            case "open_chat": {
                String target = str(args, 1).trim();
                String text = str(args, 2);
                if (a0.trim().isEmpty() || target.isEmpty()) {
                    return jsonErr("Формат: напиши в <ватсап|телеграм|вайбер|вк|смс> <кому> [текст], сэр");
                }
                return doOpenChat(normName(a0), target, text);
            }
            case "dial": {
                if (a0.trim().isEmpty()) return jsonErr("Укажите номер или имя, сэр");
                String[] r = resolvePhone(a0.trim());
                if (r == null) {
                    return jsonErr("Не нашёл «" + a0.trim() + "» ни в номерах, ни в контактах, сэр");
                }
                try {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + r[1])));
                    return jsonOk("Набираю " + r[1] + (r[0].isEmpty() ? "" : " (" + r[0] + ")")
                            + " — нажмите вызов, сэр");
                } catch (Exception e) {
                    return jsonErr("Ошибка набора: " + e.getMessage());
                }
            }
            case "call": {
                if (a0.trim().isEmpty()) return jsonErr("Укажите номер или имя, сэр");
                String[] r = resolvePhone(a0.trim());
                if (r == null) {
                    return jsonErr("Не нашёл «" + a0.trim() + "» ни в номерах, ни в контактах, сэр");
                }
                if (checkSelfPermission("android.permission.CALL_PHONE")
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.CALL_PHONE"}, REQ_PERMS);
                    try {
                        startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + r[1])));
                    } catch (Exception ignored) { }
                    return jsonOk("Разрешение на звонки запросил, сэр; пока открыл набор "
                            + r[1] + " — нажмите вызов");
                }
                try {
                    startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + r[1])));
                    return jsonOk("Звоню " + (r[0].isEmpty() ? r[1] : r[0] + " (" + r[1] + ")") + ", сэр");
                } catch (Exception e) {
                    return jsonErr("Ошибка звонка: " + e.getMessage());
                }
            }
            case "sms_status": {
                JSONObject o = smsStatusJson();
                return jsonOk("Чтение СМС вслух: "
                        + (o.optBoolean("speak", true) ? "ВКЛЮЧЕНО" : "выключено")
                        + " · приём: " + (o.optBoolean("receive") ? "ок" : "нет разрешения")
                        + " · отправка: " + (o.optBoolean("send") ? "ок" : "нет разрешения")
                        + " · контакты: " + (o.optBoolean("contacts") ? "ок" : "нет разрешения"));
            }
            case "sms_toggle": {
                boolean cur = prefs.getBoolean("sms_speak", true);
                boolean nv;
                if (a0.trim().isEmpty()) {
                    nv = !cur;
                } else {
                    String s = a0.trim().toLowerCase(Locale.ROOT);
                    nv = s.matches("вкл\\w*|on|true|1|да");
                }
                prefs.edit().putBoolean("sms_speak", nv).apply();
                return jsonOk(nv ? "Чтение СМС вслух включено, сэр"
                        : "Чтение СМС вслух выключено, сэр");
            }
            case "sms_list": {
                return doSmsList();
            }
            /* ПК-команды удалены (v1.4): в приложении для телефона
               осталось только то, что работает на самом телефоне */
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

    /* ============ СМС, МЕССЕНДЖЕРЫ, ЗВОНКИ (v1.5) ============ */

    /** Номер по «кому»: телефон → нормализация; имя → контакт (READ_CONTACTS).
     *  Возвращает {имя, номер} или null (не найдено / нет разрешения). */
    private String[] resolvePhone(String who) {
        if (PhoneTools.looksLikePhone(who)) {
            return new String[]{"", PhoneTools.normalize(who)};
        }
        if (checkSelfPermission("android.permission.READ_CONTACTS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.READ_CONTACTS"}, REQ_PERMS);
            return null;
        }
        String[] hit = PhoneTools.findByName(this, who);
        return hit != null ? new String[]{hit[0], PhoneTools.normalize(hit[1])} : null;
    }

    private JSONObject doSendSms(String to, String text) {
        if (checkSelfPermission("android.permission.SEND_SMS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.SEND_SMS"}, REQ_PERMS);
            return jsonErr("Нет разрешения на отправку СМС — выдал запрос, разрешите и повторите, сэр");
        }
        String num;
        String name = "";
        if (PhoneTools.looksLikePhone(to)) {
            num = PhoneTools.normalize(to);
        } else {
            String[] r = resolvePhone(to);
            if (r == null) {
                return jsonErr("В контактах нет «" + to + "» (или нет разрешения на контакты), сэр. Можно так: отправь смс на +7… текст");
            }
            name = r[0];
            num = r[1];
        }
        try {
            SmsManager sm = (Build.VERSION.SDK_INT >= 31)
                    ? getSystemService(SmsManager.class)
                    : SmsManager.getDefault();
            Intent sent = new Intent(this, SmsSentReceiver.class);
            sent.putExtra("to", num);
            PendingIntent pi = PendingIntent.getBroadcast(this,
                    ("sent" + num + System.currentTimeMillis()).hashCode(), sent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            ArrayList<String> parts = sm.divideMessage(text);
            if (parts.size() <= 1) {
                sm.sendTextMessage(num, null, text, pi, null);
            } else {
                ArrayList<PendingIntent> pis = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) pis.add(pi);
                sm.sendMultipartTextMessage(num, null, parts, pis, null);
            }
            if (name.isEmpty()) {
                String cn = PhoneTools.contactName(this, num);
                if (cn != null) name = cn;
            }
            return jsonOk("Отправляю SMS " + (name.isEmpty() ? "на " + num
                    : "для " + name + " (" + num + ")") + ", сэр — доложу о доставке");
        } catch (Exception e) {
            return jsonErr("SMS не отправилось: " + e.getMessage());
        }
    }

    /** Чат мессенджера: whatsapp/telegram/viber/vk/sms. Текст — предзаполняем
     *  (автоотправка в мессенджерах сторонним приложениям недоступна). */
    private JSONObject doOpenChat(String app, String target, String text) {
        String num = null;
        boolean phoneish = PhoneTools.looksLikePhone(target);
        if (phoneish) {
            num = PhoneTools.normalize(target);
        } else if (!"telegram".equals(app)) {
            String[] r = resolvePhone(target);
            if (r == null) {
                return jsonErr("В контактах нет «" + target + "» (или нет разрешения на контакты), сэр");
            }
            num = r[1];
        }
        try {
            Intent i;
            if ("whatsapp".equals(app)) {
                if (num == null) return jsonErr("WhatsApp открывается по номеру телефона, сэр");
                String u = "https://wa.me/" + num.replace("+", "");
                if (!text.isEmpty()) u += "?text=" + Uri.encode(text);
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            } else if ("telegram".equals(app)) {
                String t = target.replaceFirst("^@", "").trim();
                if (phoneish) {
                    i = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("tg://resolve?phone=" + num.replace("+", "")));
                } else {
                    i = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("tg://resolve?domain=" + Uri.encode(t)));
                }
            } else if ("viber".equals(app)) {
                if (num == null) return jsonErr("Viber открывается по номеру телефона, сэр");
                i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("viber://chat?number=" + Uri.encode(num)));
            } else if ("vk".equals(app)) {
                if (!text.isEmpty()) {
                    i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.setPackage("com.vkontakte.android");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                } else {
                    i = getPackageManager().getLaunchIntentForPackage("com.vkontakte.android");
                    if (i == null) return jsonErr("VK не установлен, сэр");
                }
            } else if ("sms".equals(app)) {
                i = new Intent(Intent.ACTION_SENDTO,
                        Uri.parse("smsto:" + (num != null ? num : target)));
                if (!text.isEmpty()) i.putExtra("sms_body", text);
            } else {
                return jsonErr("Не знаю мессенджер «" + app + "», сэр. Доступны: ватсап, телеграм, вайбер, вк, смс");
            }
            startActivity(i);
            String label = num != null ? num : target;
            return jsonOk("Открываю чат " + label + (text.isEmpty()
                    ? ", сэр" : " — текст вставлен, останется нажать «отправить», сэр"));
        } catch (Exception e) {
            return jsonErr("Не смог открыть мессенджер: " + e.getMessage()
                    + " (проверьте, что приложение установлено)");
        }
    }

    private JSONObject doSmsList() {
        if (checkSelfPermission("android.permission.READ_SMS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.READ_SMS"}, REQ_PERMS);
            return jsonErr("Нет разрешения на чтение СМС — выдал запрос, разрешите и повторите, сэр");
        }
        try {
            Cursor c = getContentResolver().query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    new String[]{android.provider.Telephony.Sms.ADDRESS,
                            android.provider.Telephony.Sms.BODY,
                            android.provider.Telephony.Sms.DATE},
                    null, null, android.provider.Telephony.Sms.DATE + " DESC");
            StringBuilder sb = new StringBuilder("Последние сообщения, сэр:\n");
            int n = 0;
            if (c != null) {
                while (c.moveToNext() && n < 5) {
                    String addr = c.getString(0);
                    String body = c.getString(1);
                    long date = c.getLong(2);
                    String who = PhoneTools.contactName(this, addr == null ? "" : addr);
                    String when = new SimpleDateFormat("dd.MM HH:mm", Locale.US)
                            .format(new Date(date));
                    String clip = body == null ? "" : body.replace('\n', ' ');
                    if (clip.length() > 60) clip = clip.substring(0, 60) + "…";
                    sb.append(++n).append(". ").append(when).append(" — ")
                      .append(who != null ? who : (addr == null ? "?" : addr))
                      .append(": ").append(clip).append('\n');
                }
                c.close();
            }
            if (n == 0) return jsonOk("Входящих сообщений не нашёл, сэр");
            return jsonOk(sb.toString().trim());
        } catch (Exception e) {
            return jsonErr("Не смог прочитать СМС: " + e.getMessage());
        }
    }

    private JSONObject smsStatusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("speak", prefs.getBoolean("sms_speak", true));
            o.put("receive", checkSelfPermission("android.permission.RECEIVE_SMS")
                    == PackageManager.PERMISSION_GRANTED);
            o.put("send", checkSelfPermission("android.permission.SEND_SMS")
                    == PackageManager.PERMISSION_GRANTED);
            o.put("read", checkSelfPermission("android.permission.READ_SMS")
                    == PackageManager.PERMISSION_GRANTED);
            o.put("contacts", checkSelfPermission("android.permission.READ_CONTACTS")
                    == PackageManager.PERMISSION_GRANTED);
        } catch (Exception ignored) { }
        return o;
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

    /* ==================== УМНЫЙ ЗАПУСК ПРИЛОЖЕНИЙ ==================== */

    /** Популярные псевдонимы: фраза → пакеты-кандидаты (первый установленный побеждает). */
    private static final Map<String, String[]> ALIAS = new HashMap<>();
    static {
        ALIAS.put("ютуб", new String[]{"com.google.android.youtube"});
        ALIAS.put("youtube", new String[]{"com.google.android.youtube"});
        ALIAS.put("ютуб музыка", new String[]{"com.google.android.apps.youtube.music"});
        ALIAS.put("ютубмьюзик", new String[]{"com.google.android.apps.youtube.music"});
        ALIAS.put("телеграм", new String[]{"org.telegram.messenger", "org.telegram.plus"});
        ALIAS.put("телега", new String[]{"org.telegram.messenger", "org.telegram.plus"});
        ALIAS.put("тг", new String[]{"org.telegram.messenger", "org.telegram.plus"});
        ALIAS.put("telegram", new String[]{"org.telegram.messenger", "org.telegram.plus"});
        ALIAS.put("ватсап", new String[]{"com.whatsapp"});
        ALIAS.put("вотсап", new String[]{"com.whatsapp"});
        ALIAS.put("whatsapp", new String[]{"com.whatsapp"});
        ALIAS.put("вк", new String[]{"com.vkontakte.android"});
        ALIAS.put("вконтакте", new String[]{"com.vkontakte.android"});
        ALIAS.put("vk", new String[]{"com.vkontakte.android"});
        ALIAS.put("инстаграм", new String[]{"com.instagram.android"});
        ALIAS.put("инста", new String[]{"com.instagram.android"});
        ALIAS.put("instagram", new String[]{"com.instagram.android"});
        ALIAS.put("тикток", new String[]{"com.zhiliaoapp.musically"});
        ALIAS.put("tiktok", new String[]{"com.zhiliaoapp.musically"});
        ALIAS.put("дискорд", new String[]{"com.discord"});
        ALIAS.put("discord", new String[]{"com.discord"});
        ALIAS.put("спотифай", new String[]{"com.spotify.music"});
        ALIAS.put("spotify", new String[]{"com.spotify.music"});
        ALIAS.put("роблокс", new String[]{"com.roblox.client"});
        ALIAS.put("roblox", new String[]{"com.roblox.client"});
        ALIAS.put("хром", new String[]{"com.android.chrome"});
        ALIAS.put("хрум", new String[]{"com.android.chrome"});
        ALIAS.put("chrome", new String[]{"com.android.chrome"});
        ALIAS.put("гугл", new String[]{"com.google.android.googlequicksearchbox"});
        ALIAS.put("карты", new String[]{"com.google.android.apps.maps", "ru.yandex.yandexmaps"});
        ALIAS.put("навигатор", new String[]{"com.google.android.apps.maps", "ru.yandex.yandexmaps"});
        ALIAS.put("почта", new String[]{"com.google.android.gm", "ru.mail.mailapp", "com.my.mail"});
        ALIAS.put("gmail", new String[]{"com.google.android.gm"});
        ALIAS.put("яндекс музыка", new String[]{"ru.yandex.music"});
        ALIAS.put("яндексмузыка", new String[]{"ru.yandex.music"});
        ALIAS.put("вкладки", new String[]{"com.sec.android.app.launcher"});
        ALIAS.put("маркет", new String[]{"com.android.vending"});
        ALIAS.put("плей маркет", new String[]{"com.android.vending"});
        ALIAS.put("play market", new String[]{"com.android.vending"});
    }

    /** Нормализация имён: нижний регистр, ё→е, схлопывание пробелов. */
    private static String normName(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').trim().replaceAll("\\s+", " ");
    }

    /** Индекс установленных запускаемых приложений (лениво, один раз). */
    private void buildAppIndex() {
        appIndex = new HashMap<>();
        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> list =
                    getPackageManager().queryIntentActivities(main, 0);
            for (android.content.pm.ResolveInfo ri : list) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if ("com.adrina.jarvis".equals(pkg)) continue;
                CharSequence lbl = ri.loadLabel(getPackageManager());
                if (lbl == null || lbl.length() == 0) continue;
                String label = lbl.toString().trim();
                String key = normName(label);
                if (!appIndex.containsKey(key)) appIndex.put(key, new String[]{pkg, label});
            }
        } catch (Exception ignored) { }
    }

    /** Поиск установленного приложения по названию: псевдоним → точное → вхождение → fuzzy. */
    private String[] resolveInstalledApp(String raw) {
        String q = normName(raw);
        if (q.isEmpty()) return null;
        if (appIndex == null) buildAppIndex();

        /* 1) Псевдоним → первый установленный пакет-кандидат */
        String[] cands = ALIAS.get(q);
        if (cands != null) {
            for (String pkg : cands) {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    String label = labelOfPackage(pkg);
                    return new String[]{pkg, label != null ? label : pkg};
                }
            }
        }

        /* 2) Точное совпадение по названию приложения */
        String[] exact = appIndex.get(q);
        if (exact != null) return exact;

        /* 3) Вхождение: «ютуб мьюзик» содержит/содержится в label */
        String[] partial = null;
        for (Map.Entry<String, String[]> e : appIndex.entrySet()) {
            String label = e.getKey();
            if (label.contains(q) || q.contains(label)) {
                if (partial == null || label.length() < partial[1].length()) partial = e.getValue();
            }
        }
        if (partial != null) return partial;

        /* 4) Нечёткое совпадение (порог 0.6) */
        String[] best = null;
        double bs = 0;
        for (Map.Entry<String, String[]> e : appIndex.entrySet()) {
            double s = sim(q, e.getKey());
            if (s > bs && s >= 0.6) { bs = s; best = e.getValue(); }
        }
        return best;
    }

    private String labelOfPackage(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence l = getPackageManager().getApplicationLabel(ai);
            return l != null ? l.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Похожесть строк 0..1 (нормализованное расстояние Левенштейна). */
    private static double sim(String a, String b) {
        if (a.equals(b)) return 1.0;
        int la = a.length(), lb = b.length();
        if (la == 0 || lb == 0) return 0.0;
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        int dist = prev[lb];
        return 1.0 - (double) dist / Math.max(la, lb);
    }

    /* ==================== УВЕДОМЛЕНИЯ И НАПОМИНАНИЯ (v1.2) ==================== */

    /** Немедленное уведомление. Возвращает null при успехе, иначе текст ошибки. */
    private String postNotifNow(String id, String title, String text) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                    "android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                        REQ_PERMS);
                return "Нет разрешения на уведомления — отправил запрос, разрешите и повторите, сэр";
            }
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(id.hashCode(),
                    NotifReceiver.buildNotif(this, title, text,
                            NotifReceiver.tapIntent(this)));
            return null;
        } catch (Exception e) {
            return "Ошибка уведомления: " + e.getMessage();
        }
    }

    /** Запланировать напоминание: список в prefs + будильник AlarmManager. */
    private void scheduleNotif(String id, long atMs, String title, String text) {
        try {
            JSONArray arr = loadNotifs();
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("at", atMs);
            o.put("title", title);
            o.put("text", text);
            arr.put(o);
            saveNotifs(arr);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = notifAlarmPI(id);
            /* Точный будильник: Android 12 — по умолчанию разрешён; 13+ — если
               пользователь не выдал «Будильники и напоминания», берём неточный
               (в обычном режиме срабатывает в ту же секунду). */
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= 31) {
                try { exact = am.canScheduleExactAlarms(); } catch (Exception ee) { exact = false; }
            }
            if (exact) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
                    return;
                } catch (Exception ignored) { }
            }
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
        } catch (Exception e) {
            toast("Ошибка планирования напоминания: " + e.getMessage());
        }
    }

    /** PendingIntent будильника: matching по requestCode + компоненту (extras не участвуют). */
    private PendingIntent notifAlarmPI(String id) {
        Intent i = new Intent(this, NotifReceiver.class);
        return PendingIntent.getBroadcast(this, id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Отменить запланированное напоминание по id. */
    private void cancelNotifById(String id) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.cancel(notifAlarmPI(id));
            JSONArray arr = loadNotifs();
            JSONArray out = new JSONArray();
            for (int k = 0; k < arr.length(); k++) {
                JSONObject o = arr.getJSONObject(k);
                if (!id.equals(o.optString("id"))) out.put(o);
            }
            saveNotifs(out);
        } catch (Exception ignored) { }
    }

    private JSONArray loadNotifs() {
        try { return new JSONArray(prefs.getString(K_NOTIFS, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private void saveNotifs(JSONArray a) {
        prefs.edit().putString(K_NOTIFS, a.toString()).apply();
    }

    /** Запланированные напоминания по времени срабатывания. */
    private List<JSONObject> sortedNotifs() {
        List<JSONObject> items = new ArrayList<>();
        JSONArray arr = loadNotifs();
        for (int k = 0; k < arr.length(); k++) {
            try { items.add(arr.getJSONObject(k)); } catch (Exception ignored) { }
        }
        items.sort((x, y) -> Long.compare(x.optLong("at"), y.optLong("at")));
        return items;
    }

    /** «90 сек», «5 мин», «1 ч 30 мин» — для подтверждений и списка. */
    private static String fmtDuration(long sec) {
        if (sec < 60) return sec + " сек";
        long h = sec / 3600;
        long m = (sec % 3600 + 30) / 60; /* округление до минуты */
        if (m >= 60) { h++; m = 0; }
        if (h > 0 && m > 0) return h + " ч " + m + " мин";
        if (h > 0) return h + " ч";
        return m + " мин";
    }

    private void vibrateSafe(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(Math.max(10, Math.min(3000, ms)));
        } catch (Exception ignored) { }
    }

    /** NotifReceiver → WebView: напоминание сработало (лог + озвучка), если приложение живо. */
    static void pushNotifFired(final String id, final String title, final String text) {
        final MainActivity m = inst;
        if (m == null || m.web == null) return;
        m.runOnUiThread(() -> {
            try {
                m.web.evaluateJavascript(
                        "window.__jarvisNotifFired && __jarvisNotifFired("
                                + quoteJs(id == null ? "" : id) + ","
                                + quoteJs(title == null ? "" : title) + ","
                                + quoteJs(text == null ? "" : text) + ");", null);
            } catch (Exception ignored) { }
        });
    }

    /** SmsReceiver → WebView: пришло SMS (лог в интерфейсе), если приложение живо. */
    static void pushSms(final String from, final String body) {
        final MainActivity m = inst;
        if (m == null || m.web == null) return;
        m.runOnUiThread(() -> {
            try {
                m.web.evaluateJavascript(
                        "window.__jarvisSms && __jarvisSms("
                                + quoteJs(from == null ? "" : from) + ","
                                + quoteJs(body == null ? "" : body) + ");", null);
            } catch (Exception ignored) { }
        });
    }

    /** SmsSentReceiver → WebView: статус отправки/чтения СМС. */
    static void pushSmsLog(final String text) {
        final MainActivity m = inst;
        if (m == null || m.web == null) return;
        m.runOnUiThread(() -> {
            try {
                m.web.evaluateJavascript(
                        "window.__jarvisSmsLog && __jarvisSmsLog("
                                + quoteJs(text == null ? "" : text) + ");", null);
            } catch (Exception ignored) { }
        });
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
        if (requestCode == REQ_PERMS || requestCode == REQ_MIC) {
            boolean smsDenied = false;
            boolean contactsDenied = false;
            for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
                if ("android.permission.RECORD_AUDIO".equals(permissions[i])
                        && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    toast("Без микрофона голосовой ввод не работает, сэр");
                }
                if ("android.permission.POST_NOTIFICATIONS".equals(permissions[i])
                        && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    toast("Без уведомлений напоминания не придут, сэр (Настройки → J.A.R.V.I.S.)");
                }
                if (("android.permission.RECEIVE_SMS".equals(permissions[i])
                        || "android.permission.READ_SMS".equals(permissions[i])
                        || "android.permission.SEND_SMS".equals(permissions[i]))
                        && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    smsDenied = true;
                }
                if ("android.permission.READ_CONTACTS".equals(permissions[i])
                        && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    contactsDenied = true;
                }
            }
            if (smsDenied) {
                toast("Без SMS-разрешений чтение и отправка СМС не работают, сэр");
            }
            if (contactsDenied) {
                toast("Без контактов пишем и звоним только по номерам, сэр");
            }
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
                + "\"persona\":\"Ты — J.A.R.V.I.S. (Just A Rather Very Intelligent System), персональный ИИ-ассистент. Общайся на русском, кратко, по делу, с лёгким юмором и обращайся к пользователю как к «сэр». Ты управляешь его телефоном. Чтобы выполнить действие, в конце ответа вставь отдельной строкой метку: [[OPEN:https://...]] — открыть ссылку, [[RUN:название]] — запустить приложение на телефоне по названию (например: ютуб, телеграм, whatsapp, карты) или ярлыку (telegram, chrome, spotify), [[SEARCH:запрос]] — искать в интернете. Если действие не требуется — метку не ставь. Никогда не объясняй формат меток.\""
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

    /** jsonOk с меткой приложения (JS показывает её в логе запуска). */
    private static JSONObject jsonOk(String stdout, String label) {
        JSONObject o = jsonOk(stdout);
        try {
            o.put("label", label);
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
        if (inst == this) inst = null;
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
