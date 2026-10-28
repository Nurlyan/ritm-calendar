package io.github.nurlyan.ritm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Fires reminders, snoozes, card refreshes, and re-arms everything after reboot, update or clock changes. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        try {
            switch (a) {
                case Notifier.A_REMIND:
                    Notifier.show(c, new JSONObject(intent.getStringExtra("reminder")));
                    break;
                case Notifier.A_SNOOZE:
                    Notifier.snooze(c, new JSONObject(intent.getStringExtra("reminder")), intent.getIntExtra("notificationId", 0));
                    break;
                case Notifier.A_CARD:
                    Notifier.refreshCard(c);
                    break;
                case Notifier.A_CARD_DISMISSED:
                    // The user removed the card: respect that until the next plan day or until they bring it back in the app.
                    Notifier.prefs(c).edit().putString("cardDismissed", intent.getStringExtra("day")).apply();
                    Notifier.refreshCard(c);
                    break;
                default: // boot, package update, time / timezone change, exact alarm permission change
                    Notifier.ensureChannels(c);
                    Notifier.armAll(c);
                    Notifier.refreshCard(c);
            }
        } catch (Exception ignored) {
            // Never crash a broadcast; the next app start re-arms everything.
        }
    }
}
