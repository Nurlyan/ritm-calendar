package io.github.nurlyan.ritm;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Periodic background check for activity proposals (owner) and decisions on them (viewer).
 * Android runs it about every 15 minutes when the network is available; it is not real-time.
 * One tiny RPC per run; nothing else is downloaded.
 */
public class InboxWorker extends Worker {
    static final String NAME = "ritm-inbox";

    public InboxWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    static void start(Context c) {
        Constraints net = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(InboxWorker.class, 15, TimeUnit.MINUTES).setConstraints(net).build();
        // UPDATE, not KEEP: seen on Android 17 — after an app update the scheduled job was gone while the work stayed recorded as enqueued; updating re-schedules it.
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req);
        // One immediate check on app start; periodic runs continue about every 15 minutes.
        androidx.work.OneTimeWorkRequest now = new androidx.work.OneTimeWorkRequest.Builder(InboxWorker.class).setConstraints(net).build();
        WorkManager.getInstance(c).enqueueUniqueWork(NAME + "-now", androidx.work.ExistingWorkPolicy.REPLACE, now);
    }

    static void stop(Context c) {
        WorkManager.getInstance(c).cancelUniqueWork(NAME);
        WorkManager.getInstance(c).cancelUniqueWork(NAME + "-now");
        Notifier.prefs(c).edit().remove("inboxUrl").remove("inboxKey").remove("inboxSince").apply();
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        SharedPreferences p = Notifier.prefs(c);
        String url = p.getString("inboxUrl", null), key = p.getString("inboxKey", null), since = p.getString("inboxSince", null);
        String session = SecureStore.get(c, "ritm-session-v2");
        if (url == null || key == null || since == null || session == null) { status(p, "нет входа или настроек"); return Result.success(); }
        try {
            String token = new JSONObject(session).getString("token");
            JSONObject body = new JSONObject().put("p_token", token).put("p_since", since);
            HttpURLConnection http = (HttpURLConnection) new URL(url + "/rest/v1/rpc/ritm_inbox").openConnection();
            http.setRequestMethod("POST");
            http.setConnectTimeout(15000);
            http.setReadTimeout(15000);
            http.setDoOutput(true);
            http.setRequestProperty("apikey", key);
            http.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = http.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = http.getResponseCode();
            if (code >= 400) { status(p, "ответ сервера " + code); http.disconnect(); return code >= 500 ? Result.retry() : Result.success(); }
            String text;
            try (InputStream in = http.getInputStream(); java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                text = buf.toString("UTF-8");
            }
            http.disconnect();
            JSONObject res = new JSONObject(text);
            boolean owner = "owner".equals(res.optString("role"));
            JSONArray events = res.optJSONArray("events");
            for (int i = 0; events != null && i < events.length(); i++) notifyEvent(c, owner, events.getJSONObject(i));
            // The cursor only moves forward, so events already seen in the open app are not repeated.
            String now = res.optString("now", null);
            String current = p.getString("inboxSince", "");
            if (now != null && now.compareTo(current) > 0) p.edit().putString("inboxSince", now).apply();
            status(p, "ok · событий: " + (events == null ? 0 : events.length()));
            return Result.success();
        } catch (java.io.IOException e) {
            status(p, "нет сети");
            return Result.retry();
        } catch (Exception e) {
            status(p, "ошибка " + e.getClass().getSimpleName());
            return Result.success();
        }
    }

    /** Last run result for the settings screen; never contains tokens or content. */
    private static void status(SharedPreferences p, String s) {
        p.edit().putString("inboxStatus", s).putLong("inboxAt", System.currentTimeMillis()).apply();
    }

    private static void notifyEvent(Context c, boolean owner, JSONObject e) throws Exception {
        String when = "";
        try {
            LocalDate d = LocalDate.parse(e.optString("day"));
            when = d.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("ru")) + ", " + d.getDayOfMonth() + "." + String.format(Locale.ROOT, "%02d", d.getMonthValue());
            if (!e.isNull("start")) { int m = e.optInt("start"); when += String.format(Locale.ROOT, " · %02d:%02d", (m / 60) % 24, m % 60); }
        } catch (Exception ignored) {}
        String title, text;
        if (owner) {
            title = "Новое предложение: " + e.optString("title");
            text = when + " · откройте, чтобы принять или отклонить";
        } else {
            boolean ok = "accepted".equals(e.optString("status"));
            title = (ok ? "Принято: " : "Отклонено: ") + e.optString("title");
            String reply = e.optString("reply", "");
            text = when + (reply.isEmpty() ? "" : " · «" + reply + "»");
        }
        JSONObject r = new JSONObject().put("key", "proposal:" + e.optString("id") + ":" + e.optString("status"))
            .put("channel", "proposals").put("title", title).put("text", text).put("route", "proposals").put("snooze", false);
        Notifier.show(c, r);
    }
}
