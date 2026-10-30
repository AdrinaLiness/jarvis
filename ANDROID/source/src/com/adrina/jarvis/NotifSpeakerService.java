package com.adrina.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * v1.7: озвучка сработавших напоминаний/таймеров в фоне.
 *
 * Раньше напоминание произносилось только через WebView (слой 4) — если
 * приложение свёрнуто или убито, `pushNotifFired` молча выходил, и Джарвис
 * не говорил ничего, только мигала шторка. Теперь NotifReceiver запускает
 * этот сервис: как SmsSpeakerService, он сам инициализирует TTS, читает
 * фразу и завершается. На Android 8+ — foreground-service с тихим
 * уведомлением (на Android 14+ — тип shortService, до 3 минут).
 */
public class NotifSpeakerService extends Service {

    private static final String CH = "jarvis_remind_speak";
    private static final int FGS_ID = 42712;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
    private static boolean speaking = false;
    /** 0 = TTS инициализируется, 1 = готов, 2 = не смог. */
    private static int ttsState = 0;

    private TextToSpeech tts;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        goForeground();
        initTts();
    }

    private void initTts() {
        synchronized (LOCK) { ttsState = 0; }
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                boolean ok = (status == TextToSpeech.SUCCESS);
                if (ok) {
                    try {
                        int r = tts.setLanguage(new Locale("ru", "RU"));
                        if (r < 0) tts.setLanguage(Locale.getDefault());
                    } catch (Exception ignored) { }
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) { }
                        @Override public void onDone(String id) { doneOne(); }
                        @Override public void onError(String id) { doneOne(); }
                    });
                }
                synchronized (LOCK) { ttsState = ok ? 1 : 2; }
                drain();
            }
        });
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i != null) {
            String title = i.getStringExtra("title");
            String text = i.getStringExtra("text");
            synchronized (LOCK) { QUEUE.add(buildPhrase(title, text)); }
            if (tts == null) {
                goForeground();
                initTts();
            } else {
                drain();
            }
        }
        return START_NOT_STICKY;
    }

    /** Та же логика фраз, что была в слое 4 (v1.2…v1.6). */
    static String buildPhrase(String title, String text) {
        String msg = text == null ? "" : text.trim();
        if (title != null && title.toUpperCase(Locale.ROOT).contains("ТАЙМЕР")) {
            return msg.isEmpty() ? "Время вышло, сэр!" : msg;
        }
        if (msg.isEmpty() || "Напоминание, сэр".equals(msg)) {
            return "Сэр, напоминание.";
        }
        return "Сэр, напоминание: " + msg;
    }

    /** Качаем очередь: что-то сказать / ждать TTS / завершиться. */
    private void drain() {
        String phrase;
        synchronized (LOCK) {
            if (ttsState == 0) return;              /* ждём инициализацию */
            if (QUEUE.isEmpty() || ttsState == 2) {
                if (!speaking) finishSelf();
                return;
            }
            if (speaking) return;                   /* текущая фраза ещё звучит */
            phrase = QUEUE.poll();
            speaking = true;
        }
        try {
            tts.speak(phrase, TextToSpeech.QUEUE_ADD, null,
                    "r" + System.nanoTime());
        } catch (Exception e) {
            synchronized (LOCK) { speaking = false; }
            drain();
        }
    }

    private void doneOne() {
        synchronized (LOCK) { speaking = false; }
        drain();
    }

    /** Тихий foreground: без звука и вибрации, исчезает после чтения. */
    private void goForeground() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH,
                        "J.A.R.V.I.S. — голос напоминаний",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Показывается, пока ассистент читает напоминание вслух");
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.enableLights(false);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH)
                    : new Notification.Builder(this)
                            .setPriority(Notification.PRIORITY_LOW);
            Notification n = b.setSmallIcon(R.drawable.notif_icon)
                    .setContentTitle("J.A.R.V.I.S.")
                    .setContentText("Читаю напоминание вслух")
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FGS_ID, n,
                        android.content.pm.ServiceInfo
                                .FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            } else {
                startForeground(FGS_ID, n);
            }
        } catch (Exception ignored) { }
    }

    private void finishSelf() {
        try {
            if (tts != null) { tts.stop(); tts.shutdown(); }
        } catch (Exception ignored) { }
        tts = null;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) { }
        stopSelf();
    }
}
