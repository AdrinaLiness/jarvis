package com.adrina.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Приёмник будильников AlarmManager (v1.2): публикует уведомление-напоминание.
 * Работает даже если J.A.R.V.I.S. выгружен из памяти — будильник будит процесс.
 * После срабатывания убирает напоминание из списка запланированных и, если
 * приложение живо, передаёт событие в WebView (лог + озвучка).
 */
public class NotifReceiver extends BroadcastReceiver {

    /** Идентификатор канала уведомлений (Android 8+). */
    static final String CH = "jarvis_alerts";

    @Override
    public void onReceive(Context ctx, Intent i) {
        String id = i.getStringExtra("id");
        String title = i.getStringExtra("title");
        String text = i.getStringExtra("text");
        if (title == null || title.isEmpty()) title = "J.A.R.V.I.S.";
        if (text == null) text = "";

        /* 1. Сработавшее напоминание — из списка запланированных */
        try {
            SharedPreferences p =
                    ctx.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString("notifs_json", "[]"));
            JSONArray out = new JSONArray();
            for (int k = 0; k < arr.length(); k++) {
                JSONObject o = arr.getJSONObject(k);
                if (!o.optString("id", "").equals(id)) out.put(o);
            }
            p.edit().putString("notifs_json", out.toString()).apply();
        } catch (Exception ignored) { }

        /* 2. Публикуем уведомление */
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify((id == null || id.isEmpty() ? "jarvis" : id).hashCode(),
                    buildNotif(ctx, title, text, tapIntent(ctx)));
        } catch (Exception ignored) { }

        /* 3. Если приложение живо — лог и озвучка в интерфейсе */
        MainActivity.pushNotifFired(id, title, text);
    }

    /* ---------- Помощники уведомлений (использует и MainActivity) ---------- */

    /** Создание канала (идемпотентно, Android 8+; на 7- просто пропускаем). */
    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CH, "J.A.R.V.I.S. — напоминания", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Напоминания, таймеры и служебные уведомления ассистента");
            ch.enableVibration(true);
            ch.enableLights(true);
            ch.setLightColor(0xFF38E8FF);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) { }
    }

    /** Сборка уведомления: heads-up, со звуком/вибрацией канала, big-text. */
    static Notification buildNotif(Context ctx, String title, String text,
                                   PendingIntent tap) {
        ensureChannel(ctx);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CH)
                : new Notification.Builder(ctx)
                        .setPriority(Notification.PRIORITY_HIGH)
                        .setDefaults(Notification.DEFAULT_SOUND
                                | Notification.DEFAULT_VIBRATE);
        return b.setSmallIcon(R.drawable.notif_icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(text == null ? "" : text))
                .setColor(0xFF38E8FF)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build();
    }

    /** Тап по уведомлению → открыть J.A.R.V.I.S. (singleTask вернёт в сессию). */
    static PendingIntent tapIntent(Context ctx) {
        return PendingIntent.getActivity(ctx, 42700,
                new Intent(ctx, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
