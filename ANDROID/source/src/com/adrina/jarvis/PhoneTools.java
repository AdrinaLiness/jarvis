package com.adrina.jarvis;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v1.5: утилиты телефонов и контактов.
 * Нормализация номеров (8→7, чистка знаков), поиск контакта по имени
 * и имя по номеру (PhoneLookup). Всё, что требует READ_CONTACTS,
 * проверяет разрешение и молча деградирует в null.
 */
public final class PhoneTools {

    private PhoneTools() { }

    /** «+7 (912) 345-67-89» → «+79123456789»; «89123456789» → «+79123456789». */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String d = raw.replaceAll("[^0-9+]", "");
        if (d.startsWith("8") && d.length() == 11) d = "7" + d.substring(1);
        if (d.length() == 10 && !d.startsWith("+")) d = "7" + d;
        if (!d.startsWith("+") && !d.isEmpty()) d = "+" + d;
        return d;
    }

    /** Похоже на телефон: цифры/скобки/плюс/дефисы, 10–15 значащих цифр. */
    public static boolean looksLikePhone(String s) {
        if (s == null || s.trim().isEmpty()) return false;
        String t = s.trim();
        if (!t.matches("^[+0-9()\\s\\-]+$")) return false;
        int digits = t.replaceAll("[^0-9]", "").length();
        return digits >= 10 && digits <= 15;
    }

    /** Нижний регистр + ё→е + схлопнуть пробелы — для сравнения имён. */
    public static String normName(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').trim()
                .replaceAll("\\s+", " ");
    }

    private static boolean hasContacts(Context ctx) {
        try {
            return ctx.checkSelfPermission("android.permission.READ_CONTACTS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Имя контакта по номеру (null — если нет разрешения или не найдено). */
    public static String contactName(Context ctx, String number) {
        if (ctx == null || number == null || number.trim().isEmpty()) return null;
        if (!hasContacts(ctx)) return null;
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number.trim()));
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = cr.query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst() && !c.isNull(0)) return c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static final String[] PROJ = {
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
    };

    /** Поиск контакта по имени: точное → «начинается с» → «содержит». */
    public static String[] findByName(Context ctx, String query) {
        List<String[]> all = findByNameAll(ctx, query, 1);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Совпадения по имени (до limit). Порядок: точное совпадение →
     * «начинается с» → «содержит». Возвращает пары {имя, номер}.
     */
    public static List<String[]> findByNameAll(Context ctx, String query, int limit) {
        List<String[]> out = new ArrayList<>();
        String q = normName(query);
        if (ctx == null || q.isEmpty() || limit <= 0) return out;
        if (!hasContacts(ctx)) return out;
        Cursor c = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    PROJ, null, null, null);
            if (c == null) return out;
            List<String[]> exact = new ArrayList<>();
            List<String[]> starts = new ArrayList<>();
            List<String[]> contains = new ArrayList<>();
            while (c.moveToNext()) {
                String name = c.getString(0);
                String num = c.getString(1);
                if (name == null || num == null) continue;
                String n = normName(name);
                String[] pair = {name, num};
                if (n.equals(q)) exact.add(pair);
                else if (n.startsWith(q)) starts.add(pair);
                else if (n.contains(q)) contains.add(pair);
                if (exact.size() >= limit) break;
            }
            c.close();
            c = null;
            for (List<String[]> bucket : new List[]{exact, starts, contains}) {
                for (Object p : bucket) {
                    out.add((String[]) p);
                    if (out.size() >= limit) return out;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (c != null) c.close(); } catch (Exception ignored) { }
        }
        return out;
    }
}
