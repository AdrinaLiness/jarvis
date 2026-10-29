package com.adrina.jarvis;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;

/**
 * v1.5: отчёт об отправке SMS (sentIntent из MainActivity.doSendSms).
 * Результат уходит в лог интерфейса (если приложение живо) и в toast.
 */
public class SmsSentReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent i) {
        String to = i != null ? i.getStringExtra("to") : null;
        String msg;
        switch (getResultCode()) {
            case Activity.RESULT_OK:
                msg = "SMS отправлено" + (to != null && !to.isEmpty() ? " → " + to : "");
                break;
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                msg = "SMS не отправлено — ошибка оператора/сети";
                break;
            case SmsManager.RESULT_ERROR_NO_SERVICE:
                msg = "SMS не отправлено — нет сети";
                break;
            case SmsManager.RESULT_ERROR_NULL_PDU:
                msg = "SMS не отправлено — PDU null";
                break;
            case SmsManager.RESULT_ERROR_RADIO_OFF:
                msg = "SMS не отправлено — радиомодуль выключен";
                break;
            default:
                msg = "SMS не отправлено (код " + getResultCode() + ")";
        }
        MainActivity.pushSmsLog(msg);
    }
}
