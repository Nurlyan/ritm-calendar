package io.github.nurlyan.ritm;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Native side of reminders and the day card. Everything is driven by data the web layer hands over
 * (stored in app-private preferences), so alarms keep working when the WebView is not running.
 */
final class Notifier {
    static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    static final String PREFS = "ritm_native";
    static final int CARD_ID = 1;
    static final String A_REMIND = "io.github.nurlyan.ritm.REMIND", A_SNOOZE = "io.github.nurlyan.ritm.SNOOZE",
        A_CARD = "io.github.nurlyan.ritm.CARD", A_CARD_DISMISSED = "io.github.nurlyan.ritm.CARD_DISMISSED",
        A_OPEN = "io.github.nurlyan.ritm.OPEN";
    static final String[][] CHANNELS = {
        {"day", "Текущий день", "Постоянная карточка с текущим и следующим занятием", "2"},
        {"activities", "Занятия", "Начало и окончание занятий", "4"},
        {"breaks", "Перерывы", "Перерыв и возвращение к работе", "3"},
        {"diaries", "Дневники", "Напоминания дополнить дневник и оценить эффект", "3"},
        {"sleep", "Сон", "Утренняя отметка и подготовка ко сну", "3"},
    };
    private Notifier() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (String[] ch : CHANNELS) {
            NotificationChannel channel = new NotificationChannel(ch[0], ch[1], Integer.parseInt(ch[3]));
            channel.setDescription(ch[2]);
            channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PRIVATE);
            if (ch[0].equals("day")) { channel.setShowBadge(false); channel.setSound(null, null); }
            nm.createNotificationChannel(channel);
        }
    }

    static boolean exactAllowed(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static void setAlarm(Context c, long at, PendingIntent pi) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (exactAllowed(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    private static PendingIntent reminderIntent(Context c, String key, String json, int flags) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(A_REMIND).setData(Uri.parse("ritm://reminder/" + Uri.encode(key)));
        if (json != null) i.putExtra("reminder", json);
        return PendingIntent.getBroadcast(c, 0, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Replaces the whole reminder set: removed or moved items are cancelled, so a moved activity never fires twice. */
    static int replaceReminders(Context c, JSONArray next) throws JSONException {
        SharedPreferences p = prefs(c);
        JSONArray previous = new JSONArray(p.getString("reminders", "[]"));
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < next.length(); i++) keep.add(next.getJSONObject(i).getString("key"));
        AlarmManager am = c.getSystemService(AlarmManager.class);
        for (int i = 0; i < previous.length(); i++) {
            String key = previous.getJSONObject(i).optString("key");
            if (keep.contains(key)) continue;
            PendingIntent old = reminderIntent(c, key, null, PendingIntent.FLAG_NO_CREATE);
            if (old != null) { am.cancel(old); old.cancel(); }
        }
        p.edit().putString("reminders", next.toString()).apply();
        return armAll(c);
    }

    /** (Re)arms future reminders from storage. Used after updates, reboots, time changes and permission changes. */
    static int armAll(Context c) {
        int armed = 0;
        try {
            JSONArray list = new JSONArray(prefs(c).getString("reminders", "[]"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                long at = r.getLong("at");
                if (at <= now) continue;
                setAlarm(c, at, reminderIntent(c, r.getString("key"), r.toString(), PendingIntent.FLAG_UPDATE_CURRENT));
                armed++;
            }
        } catch (JSONException ignored) {}
        return armed;
    }

    static PendingIntent openApp(Context c, int code, JSONObject action) {
        Intent i = new Intent(c, MainActivity.class).setAction(A_OPEN).setData(Uri.parse("ritm://open/" + code))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("ritmAction", action.toString());
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void show(Context c, JSONObject r) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return;
        ensureChannels(c);
        String key = r.optString("key");
        int id = key.hashCode() | 0x10000;
        boolean sensitive = r.optBoolean("sensitive"), lockDetails = prefs(c).getBoolean("lockDetails", false);
        JSONObject action = new JSONObject();
        try { action.put("route", r.optString("route", "today")).put("key", key); } catch (JSONException ignored) {}
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, r.optString("channel", "activities"))
            .setSmallIcon(R.drawable.ic_stat_ritm)
            .setContentTitle(r.optString("title"))
            .setContentText(r.optString("text"))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(r.optString("text")))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(c, id, action))
            .setVisibility(sensitive || !lockDetails ? NotificationCompat.VISIBILITY_PRIVATE : NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(new NotificationCompat.Builder(c, r.optString("channel", "activities"))
                .setSmallIcon(R.drawable.ic_stat_ritm).setContentTitle("Ритм")
                .setContentText(sensitive ? "Пора дополнить дневник" : "Напоминание о плане дня").build());
        if (r.optBoolean("snooze", true)) {
            int minutes = prefs(c).getInt("snoozeMinutes", 10);
            Intent s = new Intent(c, ReminderReceiver.class).setAction(A_SNOOZE).setData(Uri.parse("ritm://snooze/" + Uri.encode(key)))
                .putExtra("reminder", r.toString()).putExtra("notificationId", id);
            b.addAction(0, "Отложить на " + minutes + " мин", PendingIntent.getBroadcast(c, id, s, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        try { NotificationManagerCompat.from(c).notify(id, b.build()); } catch (SecurityException ignored) {}
    }

    /** Snoozing only re-posts the notification later. It never moves the activity, sleep or the call. */
    static void snooze(Context c, JSONObject r, int notificationId) throws JSONException {
        NotificationManagerCompat.from(c).cancel(notificationId);
        int minutes = prefs(c).getInt("snoozeMinutes", 10);
        JSONObject again = new JSONObject(r.toString()).put("key", r.optString("key") + ":snooze").put("at", System.currentTimeMillis() + minutes * 60000L);
        setAlarm(c, again.getLong("at"), reminderIntent(c, again.getString("key"), again.toString(), PendingIntent.FLAG_UPDATE_CURRENT));
    }

    // ---- Day card ----

    private static String clock(int m) { return String.format(java.util.Locale.ROOT, "%02d:%02d", (m / 60) % 24, m % 60); }

    private static long epoch(LocalDate planDay, int minutes) {
        return planDay.atStartOfDay(MSK).plusMinutes(minutes).toInstant().toEpochMilli();
    }

    private static PendingIntent cardRefreshIntent(Context c) {
        return PendingIntent.getBroadcast(c, 1, new Intent(c, ReminderReceiver.class).setAction(A_CARD), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void hideCard(Context c) {
        NotificationManagerCompat.from(c).cancel(CARD_ID);
        c.getSystemService(AlarmManager.class).cancel(cardRefreshIntent(c));
    }

    /** Draws the card for the current Moscow plan day and arms one alarm for the next block boundary. */
    static void refreshCard(Context c) {
        SharedPreferences p = prefs(c);
        JSONObject card;
        try { card = new JSONObject(p.getString("card", "{}")); } catch (JSONException e) { card = new JSONObject(); }
        if (!card.optBoolean("enabled") || !NotificationManagerCompat.from(c).areNotificationsEnabled()) { hideCard(c); return; }
        ensureChannels(c);
        ZonedDateTime now = ZonedDateTime.now(MSK);
        LocalDate day = now.toLocalDate();
        int m = now.getHour() * 60 + now.getMinute();
        if (now.getHour() < 9) { m += 1440; day = day.minusDays(1); } // 00:15 belongs to the previous plan day
        String dayKey = day.toString();
        if (dayKey.equals(p.getString("cardDismissed", ""))) {
            NotificationManagerCompat.from(c).cancel(CARD_ID);
            setAlarm(c, epoch(day.plusDays(1), 570), cardRefreshIntent(c));
            return;
        }
        JSONObject days = card.optJSONObject("days");
        JSONArray blocks = days == null ? null : days.optJSONArray(dayKey);
        JSONObject current = null, next = null;
        if (blocks != null) {
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject b = blocks.optJSONObject(i);
                if (b.optBoolean("actualStart") && !b.optBoolean("actualEnd")) { current = b; break; }
            }
            for (int i = 0; current == null && i < blocks.length(); i++) {
                JSONObject b = blocks.optJSONObject(i);
                if (b.optInt("start") <= m && b.optInt("end") > m) current = b;
            }
            int after = current != null ? Math.max(m, current.optInt("end")) : m;
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject b = blocks.optJSONObject(i);
                if (b != current && b.optInt("start") >= after && !b.optBoolean("actualEnd")) { next = b; break; }
            }
            if (next == null && current != null) for (int i = 0; i < blocks.length(); i++) {
                JSONObject b = blocks.optJSONObject(i);
                if (b != current && b.optInt("start") > m && !b.optBoolean("actualEnd")) { next = b; break; }
            }
        }
        String title, text, status;
        if (current != null) {
            status = current.optBoolean("actualEnd") ? "завершено" : current.optBoolean("actualStart") ? "начато" : "по плану";
            title = current.optString("title");
            text = clock(current.optInt("start")) + "–" + clock(current.optInt("end")) + " · " + status
                + (next != null ? "\nДалее: " + next.optString("title") + " в " + clock(next.optInt("start")) : "");
        } else if (next != null) {
            status = "по плану";
            title = "Далее: " + next.optString("title");
            text = "Начало в " + clock(next.optInt("start"));
        } else if (blocks == null) {
            status = "";
            title = "План дня не загружен";
            text = "Откройте приложение, чтобы обновить карточку";
        } else {
            status = "";
            title = m >= 1470 ? "Время для сна" : "План на сегодня завершён";
            text = "Отход ко сну 00:30–00:45 · подъём 09:30";
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, "day")
            .setSmallIcon(R.drawable.ic_stat_ritm)
            .setContentTitle(title)
            .setContentText(text.replace("\n", " · "))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(card.optBoolean("lockDetails") ? NotificationCompat.VISIBILITY_PUBLIC : NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(new NotificationCompat.Builder(c, "day").setSmallIcon(R.drawable.ic_stat_ritm).setContentTitle("Ритм").setContentText("План дня").build())
            .setContentIntent(openApp(c, 2, action("open", dayKey, null)))
            .setDeleteIntent(PendingIntent.getBroadcast(c, 2, new Intent(c, ReminderReceiver.class).setAction(A_CARD_DISMISSED).putExtra("day", dayKey), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (current != null && card.optBoolean("canEdit") && !current.optBoolean("locked")) {
            if (!current.optBoolean("actualStart")) b.addAction(0, "Начать", openApp(c, 3, action("start", dayKey, current.optString("id"))));
            else if (!current.optBoolean("actualEnd")) b.addAction(0, "Завершить", openApp(c, 4, action("finish", dayKey, current.optString("id"))));
        }
        b.addAction(0, "Открыть", openApp(c, 5, action("open", dayKey, null)));
        try { NotificationManagerCompat.from(c).notify(CARD_ID, b.build()); } catch (SecurityException ignored) {}

        int boundary = 1470 + 570; // tomorrow 09:30 by default
        if (blocks != null) for (int i = 0; i < blocks.length(); i++) {
            JSONObject x = blocks.optJSONObject(i);
            for (int t : new int[]{x.optInt("start"), x.optInt("end")}) if (t > m && t < boundary) boundary = t;
        }
        if (boundary == 2040) setAlarm(c, epoch(day.plusDays(1), 570), cardRefreshIntent(c));
        else setAlarm(c, epoch(day, boundary), cardRefreshIntent(c));
    }

    private static JSONObject action(String op, String day, String id) {
        JSONObject a = new JSONObject();
        try { a.put("route", "block").put("op", op).put("date", day); if (id != null) a.put("id", id); } catch (JSONException ignored) {}
        return a;
    }
}
