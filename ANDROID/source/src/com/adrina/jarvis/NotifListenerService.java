package com.adrina.jarvis;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;

/**
 * v1.7: чтение ЧУЖИХ уведомлений вслух (мессенджеры и прочие приложения).
 *
 * Как работает: сервис — NotificationListenerService, система сама держит его
 * подключённым. На каждое уведомление (Telegram, WhatsApp, VK, банковские
 * пуш-сообщения и т.п.) собираем заголовок и текст, отсеиваем мусор
 * (свои уведомления, прогресс-бары, музыку, групповые сводки, повторы)
 * и произносим через TextToSpeech: «Сэр, сообщение от Telegram: …».
 *
 * Чтобы система выдала нам уведомления, пользователь один раз разрешает
 * «Доступ к уведомлениям» (Настройки → Специальные возможности →
 * Доступ к уведомлениям); приложение само предлагает открыть этот экран.
 *
 * Выключается голосовой командой «выключи чтение уведомлений»
 * (преф notif_speak в jarvis_prefs).
 */
public class NotifListenerService extends NotificationListenerService {

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
    private static final int QUEUE_MAX = 8;
    /** Повторы: одинаковые (пакет+заголовок+текст) в пределах 4 секунд молчим. */
    private static final HashMap<String, Long> RECENT = new HashMap<>();
    private static final long DEDUPE_MS = 4000;

    private static TextToSpeech tts;
    private static boolean ttsReady = false;
    private static boolean speaking = false;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /* Пропускаем системные пакеты — их уведомления вслух не читают */
    private static final String[] SKIP_PKGS = {
            "com.adrina.jarvis",        /* свои напоминания — озвучивает NotifSpeakerService */
            "android",
            "com.android.systemui",
            "com.android.settings"
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        initTts();
    }

    private void initTts() {
        synchronized (LOCK) {
            if (tts != null) return; /* уже создан */
        }
        new Thread(() -> {
            TextToSpeech t = new TextToSpeech(this, status -> {
                synchronized (LOCK) {
                    ttsReady = (status == TextToSpeech.SUCCESS);
                    if (ttsReady && tts != null) {
                        try { tts.setLanguage(new Locale("ru", "RU")); } catch (Exception ignored) { }
                    }
                }
                drain();
            });
            synchronized (LOCK) { tts = t; }
        }).start();
    }

    @Override
    public void onNotificationPosted(final StatusBarNotification sbn) {
        if (sbn == null) return;
        try {
            if (!speakEnabled(this)) return;          /* выключено командой */
            String pkg = sbn.getPackageName() == null ? "" : sbn.getPackageName();
            for (String bad : SKIP_PKGS) if (bad.equals(pkg)) return;

            Notification n = sbn.getNotification();
            if (n == null) return;
            if (sbn.isOngoing()) return;              /* постоянные: зарядка, плеер, VPN */
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return; /* сводка группы */
            String cat = n.category;
            if (Notification.CATEGORY_PROGRESS.equals(cat)
                    || Notification.CATEGORY_SERVICE.equals(cat)
                    || Notification.CATEGORY_TRANSPORT.equals(cat)
                    || Notification.CATEGORY_SYSTEM.equals(cat)) return;

            Bundle ex = n.extras;
            if (ex != null && ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                return;                               /* плееры (медиасессия) */
            }
            String title = cs(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text = cs(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty()) text = cs(ex.getCharSequence(Notification.EXTRA_TEXT));

            /* Сообщения MessagingStyle (Telegram/WhatsApp кладут их в EXTRA_MESSAGES):
               берём последнее сообщение и автора — точнее, чем общий текст */
            try {
                android.os.Parcelable[] msgs =
                        (android.os.Parcelable[]) ex.get(Notification.EXTRA_MESSAGES);
                if (msgs != null && msgs.length > 0) {
                    Object last = msgs[msgs.length - 1];
                    if (last instanceof Notification.MessagingStyle.Message) {
                        Notification.MessagingStyle.Message m =
                                (Notification.MessagingStyle.Message) last;
                        String who = "";
                        try {
                            if (m.getSenderPerson() != null) {
                                who = cs(m.getSenderPerson().getName());
                            }
                        } catch (Throwable tt) {
                            try { who = cs(m.getSender()); } catch (Throwable t2) { }
                        }
                        String body = cs(m.getText());
                        if (!body.isEmpty()) {
                            text = who.isEmpty() ? body : who + ": " + body;
                        }
                    }
                }
            } catch (Throwable ignored) { /* старые стили — берём title/text */ }

            if (text.isEmpty()) return;               /* нечего читать */
            /* «Такие-то новых сообщений» из сводок и пустые заголовки-приложения */
            if (text.matches("(?s).*\\d+\\s*(новых\\s+сообщен|new\\s+messages).*")
                    && title.isEmpty()) return;

            String label = appLabel(pkg);
            String phrase;
            if (title.isEmpty() || title.equals(label)) {
                phrase = "Сэр, сообщение от " + label + ": " + text;
            } else {
                phrase = "Сэр, сообщение от " + label + ", " + title + ": " + text;
            }
            if (phrase.length() > 260) phrase = phrase.substring(0, 257) + "…";

            /* дедупликация повторов */
            String key = pkg + "|" + title + "|" + text;
            long now = System.currentTimeMillis();
            synchronized (LOCK) {
                Long prev = RECENT.get(key);
                if (prev != null && now - prev < DEDUPE_MS) return;
                RECENT.put(key, now);
                if (RECENT.size() > 40) {             /* держим карту компактной */
                    for (HashMap.Entry<String, Long> e : RECENT.entrySet()) {
                        if (now - e.getValue() > 30000) { RECENT.remove(e.getKey()); break; }
                    }
                }
                while (QUEUE.size() >= QUEUE_MAX) QUEUE.poll();
                QUEUE.add(phrase);
            }
            drain();
        } catch (Throwable ignored) { /* уведомления бывают странными — не падаем */ }
    }

    private void drain() {
        String phrase;
        synchronized (LOCK) {
            if (QUEUE.isEmpty() || speaking) return;
            if (!ttsReady || tts == null) {           /* TTS ещё инициализируется */
                if (tts == null) initTts();
                return;
            }
            phrase = QUEUE.poll();
            speaking = true;
        }
        try {
            tts.speak(phrase, TextToSpeech.QUEUE_ADD, null,
                    "n" + System.nanoTime());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { doneOne(); }
                @Override public void onError(String id) { doneOne(); }
            });
        } catch (Throwable t) {
            synchronized (LOCK) { speaking = false; }
            UI.postDelayed(this::drain, 500);
        }
    }

    private void doneOne() {
        synchronized (LOCK) { speaking = false; }
        drain();
    }

    @Override
    public void onDestroy() {
        synchronized (LOCK) {
            try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) { }
            tts = null;
            ttsReady = false;
            speaking = false;
            QUEUE.clear();
            RECENT.clear();
        }
        super.onDestroy();
    }

    /* ---------- помощники ---------- */

    private static String cs(CharSequence c) {
        return c == null ? "" : String.valueOf(c).trim();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            CharSequence l = pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0));
            String s = cs(l);
            if (!s.isEmpty()) return s;
        } catch (Exception ignored) { }
        return pkg;
    }

    /** Чтение уведомлений включено? (команда «выключи чтение уведомлений») */
    static boolean speakEnabled(Context ctx) {
        try {
            SharedPreferences p =
                    ctx.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE);
            return p.getBoolean("notif_speak", true);
        } catch (Exception e) {
            return true;
        }
    }

    /** Выдан ли нашему пакету доступ к уведомлениям (Settings.Secure). */
    static boolean listenerEnabled(Context ctx) {
        try {
            String listeners = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_notification_listeners");
            return listeners != null && listeners.contains(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }
}
