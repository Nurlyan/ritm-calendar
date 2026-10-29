package io.github.nurlyan.ritm;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;

import androidx.activity.result.ActivityResult;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Bridge between the web UI and Android: secure session, reminders, day card, files, haptics. */
@CapacitorPlugin(name = "RitmNative", permissions = {
    @Permission(alias = "notifications", strings = {Manifest.permission.POST_NOTIFICATIONS})
})
public class RitmPlugin extends Plugin {
    private String pendingFile;
    // The latest notification/card action, kept until the page takes it: on a cold start the page may load twice.
    private JSObject pendingAction;

    @Override
    public void load() {
        Context c = getContext();
        Notifier.ensureChannels(c);
        clearExports(c);
        deliver(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        deliver(intent);
    }

    /** Notification taps and card buttons arrive here. A running page gets an event; a starting page calls takeAction(). */
    private void deliver(Intent intent) {
        if (intent == null || intent.getStringExtra("ritmAction") == null) return;
        try {
            pendingAction = JSObject.fromJSONObject(new JSONObject(intent.getStringExtra("ritmAction")));
            notifyListeners("action", pendingAction, false);
        } catch (Exception ignored) {}
        intent.removeExtra("ritmAction");
    }

    @PluginMethod
    public void takeAction(PluginCall call) {
        JSObject r = new JSObject();
        if (pendingAction != null) r.put("action", pendingAction);
        pendingAction = null;
        call.resolve(r);
    }

    // ---- Secure storage ----

    @PluginMethod
    public void secureGet(PluginCall call) {
        JSObject r = new JSObject();
        r.put("value", SecureStore.get(getContext(), call.getString("key", "")));
        call.resolve(r);
    }

    @PluginMethod
    public void secureSet(PluginCall call) {
        try {
            String value = call.getString("value");
            if (value == null) SecureStore.remove(getContext(), call.getString("key", ""));
            else SecureStore.set(getContext(), call.getString("key", ""), value);
            call.resolve();
        } catch (Exception e) { call.reject("Не удалось сохранить защищённые данные"); }
    }

    // ---- Notifications ----

    @PluginMethod
    public void status(PluginCall call) {
        Context c = getContext();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        JSObject r = new JSObject();
        r.put("notifications", NotificationManagerCompat.from(c).areNotificationsEnabled());
        r.put("exactAlarms", Notifier.exactAllowed(c));
        r.put("batteryUnrestricted", c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName()));
        r.put("dnd", nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL);
        JSObject channels = new JSObject();
        if (Build.VERSION.SDK_INT >= 26) for (String[] ch : Notifier.CHANNELS) {
            android.app.NotificationChannel channel = nm.getNotificationChannel(ch[0]);
            channels.put(ch[0], channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
        }
        r.put("channels", channels);
        r.put("cardVisible", false);
        r.put("chartVisible", false);
        for (android.service.notification.StatusBarNotification n : nm.getActiveNotifications()) {
            if (n.getId() == Notifier.CARD_ID) r.put("cardVisible", true);
            if (n.getId() == Notifier.DAY_ID) r.put("chartVisible", true);
        }
        r.put("chartDismissed", Notifier.prefs(c).getString("chartDismissed", ""));
        try { r.put("scheduled", new JSONArray(Notifier.prefs(c).getString("reminders", "[]")).length()); } catch (org.json.JSONException e) { r.put("scheduled", 0); }
        r.put("sdk", Build.VERSION.SDK_INT);
        r.put("promoted", NotificationManagerCompat.from(c).canPostPromotedNotifications());
        r.put("inboxStatus", Notifier.prefs(c).getString("inboxStatus", null));
        r.put("inboxAt", Notifier.prefs(c).getLong("inboxAt", 0));
        call.resolve(r);
    }

    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) { status(call); return; }
        requestPermissionForAlias("notifications", call, "notificationsResult");
    }

    @PermissionCallback
    private void notificationsResult(PluginCall call) {
        Notifier.armAll(getContext());
        Notifier.refreshCard(getContext());
        status(call);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Context c = getContext();
        String target = call.getString("target", "app");
        Intent i;
        switch (target) {
            case "notifications":
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                break;
            case "channel":
                i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, call.getString("channel", "activities"));
                break;
            case "exactAlarms":
                i = Build.VERSION.SDK_INT >= 31 ? new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + c.getPackageName()))
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
                break;
            case "promotion":
                // Android 16+: переключатель Live Updates для приложения.
                i = new Intent(Build.VERSION.SDK_INT >= 36 ? "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS" : Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                break;
            case "battery":
                i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                break;
            default:
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
        }
        try { getActivity().startActivity(i); call.resolve(); } catch (Exception e) { call.reject("Системные настройки недоступны"); }
    }

    /** Receives the full reminder set computed by the web layer and the settings that native code needs. */
    @PluginMethod
    public void schedule(PluginCall call) {
        try {
            JSObject settings = call.getObject("settings", new JSObject());
            Notifier.prefs(getContext()).edit()
                .putInt("snoozeMinutes", settings.optInt("snoozeMinutes", 10))
                .putBoolean("lockDetails", settings.optBoolean("lockDetails", false))
                .apply();
            JSONArray list = call.getArray("reminders", new com.getcapacitor.JSArray());
            JSObject r = new JSObject();
            r.put("armed", Notifier.replaceReminders(getContext(), list));
            r.put("exact", Notifier.exactAllowed(getContext()));
            call.resolve(r);
        } catch (Exception e) { call.reject("Не удалось запланировать напоминания"); }
    }

    @PluginMethod
    public void card(PluginCall call) {
        try {
            JSObject data = call.getObject("card", new JSObject());
            android.content.SharedPreferences.Editor e = Notifier.prefs(getContext()).edit().putString("card", data.toString());
            if (call.getBoolean("restore", false)) e.remove("cardDismissed").remove("chartDismissed");
            e.apply();
            Notifier.refreshCard(getContext());
            call.resolve();
        } catch (Exception e) { call.reject("Не удалось обновить карточку"); }
    }

    @PluginMethod
    public void test(PluginCall call) {
        try {
            JSONObject r = new JSONObject().put("key", "test:" + System.currentTimeMillis()).put("channel", call.getString("channel", "activities"))
                .put("title", "Тестовое уведомление").put("text", "Так будут выглядеть напоминания «Ритма».").put("route", "settings");
            Notifier.show(getContext(), r);
            call.resolve();
        } catch (Exception e) { call.reject("Не удалось показать уведомление"); }
    }

    @PluginMethod
    public void dismiss(PluginCall call) {
        String key = call.getString("key");
        if (key != null) Notifier.dismissKey(getContext(), key);
        else Notifier.dismiss(getContext(), call.getString("prefix", "\u0000"));
        call.resolve();
    }

    // ---- Background check for proposals ----

    @PluginMethod
    public void inboxStart(PluginCall call) {
        android.content.SharedPreferences p = Notifier.prefs(getContext());
        android.content.SharedPreferences.Editor e = p.edit().putString("inboxUrl", call.getString("url")).putString("inboxKey", call.getString("key"));
        if (!p.contains("inboxSince")) e.putString("inboxSince", call.getString("since"));
        e.apply();
        InboxWorker.start(getContext());
        call.resolve();
    }

    @PluginMethod
    public void inboxStop(PluginCall call) {
        InboxWorker.stop(getContext());
        call.resolve();
    }

    /** Events the user already saw in the open app are not announced again by the background check. */
    @PluginMethod
    public void inboxSeen(PluginCall call) {
        android.content.SharedPreferences p = Notifier.prefs(getContext());
        String since = call.getString("since", "");
        if (since.compareTo(p.getString("inboxSince", "")) > 0) p.edit().putString("inboxSince", since).apply();
        call.resolve();
    }

    // ---- Files ----

    private static void clearExports(Context c) {
        File dir = new File(c.getCacheDir(), "exports");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    /** Opens the system "save as" picker; the content is written only to the place the user chose. */
    @PluginMethod
    public void saveFile(PluginCall call) {
        pendingFile = call.getString("content", "");
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(call.getString("mime", "application/octet-stream"))
            .putExtra(Intent.EXTRA_TITLE, call.getString("name", "ritm.txt"));
        startActivityForResult(call, i, "saveResult");
    }

    @ActivityCallback
    private void saveResult(PluginCall call, ActivityResult result) {
        String content = pendingFile;
        pendingFile = null;
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            JSObject r = new JSObject(); r.put("saved", false); call.resolve(r); return;
        }
        try (OutputStream out = getContext().getContentResolver().openOutputStream(result.getData().getData(), "wt")) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            JSObject r = new JSObject(); r.put("saved", true); call.resolve(r);
        } catch (Exception e) { call.reject("Не удалось записать файл"); }
    }

    /** Shares a temporary copy from the app cache; copies are removed on the next app start. */
    @PluginMethod
    public void shareFile(PluginCall call) {
        try {
            File dir = new File(getContext().getCacheDir(), "exports");
            dir.mkdirs();
            File f = new File(dir, call.getString("name", "ritm.txt").replaceAll("[\\\\/:*?\"<>|]", "_"));
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(call.getString("content", "").getBytes(StandardCharsets.UTF_8)); }
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND).setType(call.getString("mime", "text/plain")).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(Intent.createChooser(send, "Поделиться выгрузкой"));
            call.resolve();
        } catch (Exception e) { call.reject("Не удалось открыть меню «Поделиться»"); }
    }

    /**
     * Android 14 and older draw the status and navigation bars as separate strips around the page: paint them in the
     * page background so they do not stay grey or white in the dark theme. Android 15+ draws the page under the bars.
     */
    @PluginMethod
    public void barColors(PluginCall call) {
        String color = call.getString("color", "#0d1512");
        boolean dark = Boolean.TRUE.equals(call.getBoolean("dark", true));
        getActivity().runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT < 35) {
                android.view.Window w = getActivity().getWindow();
                int c;
                try { c = android.graphics.Color.parseColor(color); } catch (IllegalArgumentException e) { call.reject("color"); return; }
                w.setStatusBarColor(c);
                w.setNavigationBarColor(c);
                androidx.core.view.WindowInsetsControllerCompat ic = androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
                ic.setAppearanceLightStatusBars(!dark);
                ic.setAppearanceLightNavigationBars(!dark);
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void insets(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            float[] o = getActivity() instanceof MainActivity ? ((MainActivity) getActivity()).overlap() : new float[]{0, 0, 0, 0};
            JSObject r = new JSObject();
            r.put("top", o[0]); r.put("bottom", o[1]); r.put("left", o[2]); r.put("right", o[3]);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void haptic(PluginCall call) {
        String kind = call.getString("kind", "tick");
        int effect = kind.equals("confirm") && Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM
            : kind.equals("reject") && Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.CLOCK_TICK;
        getActivity().runOnUiThread(() -> getBridge().getWebView().performHapticFeedback(effect));
        call.resolve();
    }
}
