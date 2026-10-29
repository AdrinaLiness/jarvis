package com.adrina.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * v1.5: приёмник входящих SMS (регистрация в манифесте — работает в фоне,
 * даже если J.A.R.V.I.S. выгружен из памяти; SMS-броадкасты не подпадают
 * под запрет неявных броадкастов). Если «чтение СМС вслух» включено —
 * запускает SmsSpeakerService: SMS-броадкаст ставит приложение в
 * temporary allowlist, поэтому startForegroundService легален.
 * Плюс передаёт событие в интерфейс (лог), если приложение живо.
 */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (intent == null
                    || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
                return;
            }
            SharedPreferences p =
                    ctx.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE);
            boolean speak = p.getBoolean("sms_speak", true);

            if (ctx.checkSelfPermission("android.permission.RECEIVE_SMS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (msgs == null || msgs.length == 0) return;

            String from = msgs[0] != null ? msgs[0].getOriginatingAddress() : null;
            StringBuilder sb = new StringBuilder();
            for (SmsMessage m : msgs) {
                if (m != null && m.getMessageBody() != null) sb.append(m.getMessageBody());
            }
            String body = sb.toString().trim();
            if (body.isEmpty()) return;

            /* Имя отправителя из контактов, если разрешено (иначе номер) */
            String who = PhoneTools.contactName(ctx, from == null ? "" : from);
            String display = who != null ? who : (from == null ? "" : from);

            MainActivity.pushSms(display, body);
            if (!speak) {
                MainActivity.pushSmsLog("Чтение СМС вслух выключено — сообщение в логе");
                return;
            }

            Intent svc = new Intent(ctx, SmsSpeakerService.class);
            svc.putExtra("from", display);
            svc.putExtra("body", body);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } catch (Exception ignored) { }
    }
}
