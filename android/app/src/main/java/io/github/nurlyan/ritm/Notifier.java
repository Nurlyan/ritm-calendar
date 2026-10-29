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
    static final int CARD_ID = 1, DAY_ID = 8;
    private static final int GAP = 0xFF9AA3A0, WARN = 0xFFE0A030;
    static final String A_REMIND = "io.github.nurlyan.ritm.REMIND", A_SNOOZE = "io.github.nurlyan.ritm.SNOOZE",
        A_CARD = "io.github.nurlyan.ritm.CARD", A_CARD_DISMISSED = "io.github.nurlyan.ritm.CARD_DISMISSED",
        A_OPEN = "io.github.nurlyan.ritm.OPEN";
    static final String[][] CHANNELS = {
        {"day", "Текущий день", "Постоянная карточка с текущим и следующим занятием", "2"},
        {"activities", "Занятия", "Начало и окончание занятий", "4"},
        {"breaks", "Перерывы", "Перерыв и возвращение к работе", "3"},
        {"diaries", "Дневники", "Напоминания дополнить дневник и оценить эффект", "3"},
        {"sleep", "Сон", "Подготовка ко сну", "3"},
        {"wake", "Подъём", "Закреплённое утреннее напоминание отметить подъём", "4"},
        {"proposals", "Предложения", "Предложения активностей и ответы на них", "4"},
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
        // No data URI here: Capacitor reloads the page for launch intents that carry one, which would drop the action.
        Intent i = new Intent(c, MainActivity.class).setAction(A_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("ritmAction", action.toString());
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void show(Context c, JSONObject r) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return;
        ensureChannels(c);
        String key = r.optString("key");
        // Reminders sharing notifyKey replace each other (за 5 минут → за 1 минуту).
        int id = notificationId(r.optString("notifyKey", key));
        boolean sensitive = r.optBoolean("sensitive"), lockDetails = prefs(c).getBoolean("lockDetails", false);
        JSONObject action = new JSONObject();
        try { action.put("route", r.optString("route", "today")).put("key", key); } catch (JSONException ignored) {}
        // A reminder may carry its own app action and up to two extra buttons («Отметьте занятие»).
        JSONObject own = r.optJSONObject("action");
        if (own != null) action = own;
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, r.optString("channel", "activities"))
            .setSmallIcon(R.drawable.ic_stat_ritm)
            .setContentTitle(r.optString("title"))
            .setContentText(r.optString("text"))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(r.optString("text")))
            .setAutoCancel(!r.optBoolean("sticky"))
            .setOngoing(r.optBoolean("sticky"))
            .setPriority(r.optBoolean("sticky") ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(r.optInt("timeoutMin", 0) > 0 ? r.optInt("timeoutMin") * 60000L : 0)
            .setContentIntent(openApp(c, id, action))
            .setVisibility(sensitive || !lockDetails ? NotificationCompat.VISIBILITY_PRIVATE : NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(new NotificationCompat.Builder(c, r.optString("channel", "activities"))
                .setSmallIcon(R.drawable.ic_stat_ritm).setContentTitle("Ритм")
                .setContentText(sensitive ? "Пора дополнить дневник" : "Напоминание о плане дня").build());
        if (r.optBoolean("sticky")) b.addAction(0, "Отметить подъём", openApp(c, id ^ 0x2000, action));
        JSONArray buttons = r.optJSONArray("buttons");
        for (int i = 0; buttons != null && i < buttons.length() && i < 2; i++) {
            JSONObject btn = buttons.optJSONObject(i);
            if (btn != null && btn.optJSONObject("action") != null) b.addAction(0, btn.optString("title"), openApp(c, id ^ (0x3000 + i), btn.optJSONObject("action")));
        }
        if (r.optBoolean("snooze", true)) {
            int minutes = prefs(c).getInt("snoozeMinutes", 10);
            Intent s = new Intent(c, ReminderReceiver.class).setAction(A_SNOOZE).setData(Uri.parse("ritm://snooze/" + Uri.encode(key)))
                .putExtra("reminder", r.toString()).putExtra("notificationId", id);
            b.addAction(0, "Отложить на " + minutes + " мин", PendingIntent.getBroadcast(c, id, s, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        try { NotificationManagerCompat.from(c).notify(id, b.build()); } catch (SecurityException ignored) {}
    }

    static int notificationId(String key) { return key.hashCode() | 0x10000; }

    static void dismiss(Context c, String prefix) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        try {
            JSONArray list = new JSONArray(prefs(c).getString("reminders", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                String key = r.optString("key");
                if (!key.startsWith(prefix)) continue;
                nm.cancel(notificationId(r.optString("notifyKey", key)));
                nm.cancel(notificationId(key + ":snooze"));
            }
        } catch (JSONException ignored) {}
    }

    /** Removes one shown notification by its exact key, even after its reminder left the schedule. */
    static void dismissKey(Context c, String key) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        nm.cancel(notificationId(key));
        nm.cancel(notificationId(key + ":snooze"));
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

    /** Minute tick for the progress bars; a non-wakeup alarm, so it never wakes a sleeping phone. */
    private static PendingIntent cardTickIntent(Context c) {
        return PendingIntent.getBroadcast(c, 3, new Intent(c, ReminderReceiver.class).setAction(A_CARD).setData(Uri.parse("ritm://card/tick")), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void hideCard(Context c) {
        NotificationManagerCompat.from(c).cancel(CARD_ID);
        NotificationManagerCompat.from(c).cancel(DAY_ID);
        c.getSystemService(AlarmManager.class).cancel(cardRefreshIntent(c));
        c.getSystemService(AlarmManager.class).cancel(cardTickIntent(c));
    }

    static int kindColor(String kind) {
        switch (kind) {
            case "work": return 0xFF2F7A5F;
            case "sport": return 0xFF9A7A1F;
            case "together": return 0xFFA5566F;
            case "personal": return 0xFF4F76A3;
            case "meal": return 0xFF7D8150;
            case "call": return 0xFF2F74A6;
            case "sleep": return 0xFF6C62A6;
            default: return GAP;
        }
    }

    private static PendingIntent cardDismissIntent(Context c, String dayKey) {
        return PendingIntent.getBroadcast(c, 2, new Intent(c, ReminderReceiver.class).setAction(A_CARD_DISMISSED).putExtra("day", dayKey), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent chartDismissIntent(Context c, String dayKey) {
        return PendingIntent.getBroadcast(c, 10, new Intent(c, ReminderReceiver.class).setAction(A_CARD_DISMISSED).putExtra("day", dayKey).putExtra("chart", true), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * «Ход дня»: every block of the day as a coloured segment from 09:30 to 00:30; the bar fills with time
     * and the points mark the current activity. Android draws a limited number of segments, so the
     * shortest ones are merged into a neighbour.
     */
    private static void showDayChart(Context c, JSONObject card, JSONArray blocks, String dayKey, int m, JSONObject current) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        if (!card.optBoolean("dayChart", true) || blocks == null || blocks.length() == 0 || m < 570 || m >= 1470
            || dayKey.equals(prefs(c).getString("chartDismissed", ""))) { nm.cancel(DAY_ID); return; }
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        for (int i = 0; i < blocks.length(); i++) list.add(blocks.optJSONObject(i));
        list.sort((a, b) -> a.optInt("start") - b.optInt("start"));
        java.util.List<int[]> segs = new java.util.ArrayList<>();
        int cursor = 570, total = 0, done = 0;
        for (JSONObject x : list) {
            int s = Math.max(cursor, x.optInt("start")), e = Math.min(1470, x.optInt("end"));
            if (e <= s) continue;
            if (s > cursor) segs.add(new int[]{s - cursor, GAP});
            segs.add(new int[]{e - s, x.optBoolean("skipped") ? GAP : kindColor(x.optString("kind"))});
            cursor = e;
            if (!"sleep".equals(x.optString("kind"))) { total++; if (x.optBoolean("actualEnd")) done++; }
        }
        if (cursor < 1470) segs.add(new int[]{1470 - cursor, GAP});
        while (segs.size() > 15) {
            int k = 0;
            for (int i = 1; i < segs.size(); i++) if (segs.get(i)[0] < segs.get(k)[0]) k = i;
            int into = k == 0 ? 1 : k - 1;
            segs.get(into)[0] += segs.get(k)[0];
            segs.remove(k);
        }
        NotificationCompat.ProgressStyle ps = new NotificationCompat.ProgressStyle().setStyledByProgress(true);
        for (int[] s : segs) ps.addProgressSegment(new NotificationCompat.ProgressStyle.Segment(s[0]).setColor(s[1]));
        int progress = Math.max(0, Math.min(900, m - 570));
        ps.setProgress(progress);
        if (current != null) {
            ps.addProgressPoint(new NotificationCompat.ProgressStyle.Point(Math.max(1, Math.min(899, current.optInt("start") - 570))).setColor(kindColor(current.optString("kind"))));
            ps.addProgressPoint(new NotificationCompat.ProgressStyle.Point(Math.max(1, Math.min(899, current.optInt("end") - 570))).setColor(kindColor(current.optString("kind"))));
        }
        int left = Math.max(0, 1425 - m);
        NotificationCompat.Builder d = new NotificationCompat.Builder(c, "day")
            .setSmallIcon(R.drawable.ic_stat_ritm)
            .setContentTitle("Ход дня · " + (progress * 100 / 900) + "%")
            .setContentText("Сделано " + done + " из " + total + (left > 0 ? " · до подготовки ко сну " + (left >= 60 ? left / 60 + " ч " : "") + left % 60 + " мин" : ""))
            .setStyle(ps)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(card.optBoolean("lockDetails") ? NotificationCompat.VISIBILITY_PUBLIC : NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(new NotificationCompat.Builder(c, "day").setSmallIcon(R.drawable.ic_stat_ritm).setContentTitle("Ритм").setContentText("Ход дня").build())
            .setContentIntent(openApp(c, 9, action("open", dayKey, null)))
            .setDeleteIntent(chartDismissIntent(c, dayKey));
        if (card.optBoolean("live", true)) d.setRequestPromotedOngoing(true);
        try { nm.notify(DAY_ID, d.build()); } catch (SecurityException ignored) {}
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
            NotificationManagerCompat.from(c).cancel(DAY_ID);
            setAlarm(c, epoch(day.plusDays(1), 570), cardRefreshIntent(c));
            return;
        }
        JSONObject days = card.optJSONObject("days");
        JSONArray blocks = days == null ? null : days.optJSONArray(dayKey);
        JSONObject current = null, next = null;
        if (blocks != null) {
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject b = blocks.optJSONObject(i);
                if (b.optBoolean("actualStart") && !b.optBoolean("actualEnd") && b.optInt("start") <= m && m < b.optInt("end") + 60) { current = b; break; }
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
        String sleepOpen = card.isNull("sleepOpen") ? null : card.optString("sleepOpen", null);
        boolean sleepState = (current == null && m >= 1380) || (current != null && "sleep".equals(current.optString("kind")));
        String title, text, status;
        if (sleepState && sleepOpen != null && card.optBoolean("canEdit")) {
            status = "";
            String bed;
            try { bed = clock(java.time.Instant.parse(sleepOpen).atZone(MSK).getHour() * 60 + java.time.Instant.parse(sleepOpen).atZone(MSK).getMinute()); } catch (Exception e) { bed = ""; }
            title = "Сон с " + bed;
            text = "Утром нажмите «Проснулся»";
        } else if (current != null) {
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
            .setContentIntent(openApp(c, 2, sleepState && card.optBoolean("canEdit") ? route("sleep") : action("open", dayKey, null)))
            .setDeleteIntent(cardDismissIntent(c, dayKey));
        // Live Update (Android 16+): шкала текущего занятия с точками «за 5 и за 1 минуту до конца» и обратный отсчёт в чипе строки состояния.
        // Android закрепляет такое уведомление вверху шторки, если пользователь не выключил Live Updates для приложения.
        boolean live = card.optBoolean("live", true);
        if (live) b.setRequestPromotedOngoing(true);
        if (live && current != null && !sleepState) {
            int start = current.optInt("start"), end = current.optInt("end"), len = Math.max(1, end - start), done = Math.max(0, Math.min(len, m - start));
            NotificationCompat.ProgressStyle ps = new NotificationCompat.ProgressStyle().setStyledByProgress(true)
                .addProgressSegment(new NotificationCompat.ProgressStyle.Segment(len).setColor(kindColor(current.optString("kind"))))
                .setProgress(done);
            if (len > 10) ps.addProgressPoint(new NotificationCompat.ProgressStyle.Point(len - 5).setColor(WARN));
            if (len > 3) ps.addProgressPoint(new NotificationCompat.ProgressStyle.Point(len - 1).setColor(WARN));
            b.setStyle(ps)
                .setContentText(clock(start) + "–" + clock(end) + " · " + (done * 100 / len) + "% · " + status + (next != null ? " · далее " + next.optString("title") + " в " + clock(next.optInt("start")) : ""))
                .setWhen(epoch(day, end)).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true);
        }
        if (card.optBoolean("canEdit") && sleepOpen != null) b.addAction(0, "Проснулся", openApp(c, 6, route("wake")));
        else if (card.optBoolean("canEdit") && sleepState) b.addAction(0, "Лёг спать", openApp(c, 7, route("bed")));
        else if (current != null && card.optBoolean("canEdit") && !current.optBoolean("locked")) {
            if (!current.optBoolean("actualStart")) b.addAction(0, "Начать", openApp(c, 3, action("start", dayKey, current.optString("id"))));
            else if (!current.optBoolean("actualEnd")) b.addAction(0, "Завершить", openApp(c, 4, action("finish", dayKey, current.optString("id"))));
        }
        b.addAction(0, "Открыть", openApp(c, 5, action("open", dayKey, null)));
        try { NotificationManagerCompat.from(c).notify(CARD_ID, b.build()); } catch (SecurityException ignored) {}
        showDayChart(c, card, blocks, dayKey, m, sleepState ? null : current);
        if ((live || card.optBoolean("dayChart", true)) && m >= 570 && m < 1470) {
            long nextMinute = (System.currentTimeMillis() / 60000 + 1) * 60000;
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (exactAllowed(c)) am.setExact(AlarmManager.RTC, nextMinute, cardTickIntent(c));
            else am.set(AlarmManager.RTC, nextMinute, cardTickIntent(c));
        }

        int boundary = 1470 + 570; // tomorrow 09:30 by default
        if (blocks != null) for (int i = 0; i < blocks.length(); i++) {
            JSONObject x = blocks.optJSONObject(i);
            for (int t : new int[]{x.optInt("start"), x.optInt("end")}) if (t > m && t < boundary) boundary = t;
        }
        if (boundary == 2040) setAlarm(c, epoch(day.plusDays(1), 570), cardRefreshIntent(c));
        else setAlarm(c, epoch(day, boundary), cardRefreshIntent(c));
    }

    private static JSONObject route(String name) {
        JSONObject a = new JSONObject();
        try { a.put("route", name); } catch (JSONException ignored) {}
        return a;
    }

    private static JSONObject action(String op, String day, String id) {
        JSONObject a = new JSONObject();
        try { a.put("route", "block").put("op", op).put("date", day); if (id != null) a.put("id", id); } catch (JSONException ignored) {}
        return a;
    }
}
