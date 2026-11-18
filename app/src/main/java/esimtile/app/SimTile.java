package esimtile.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import java.util.Locale;

public class SimTile extends TileService {
    private static final long SWITCH_TIMEOUT_MS = 45_000, POLL_MS = 1_000;

    // Static: the system may recreate this service while a switch is still running.
    private static boolean target;
    private static long deadline;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;
    private final SubscriptionManager.OnSubscriptionsChangedListener subs =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override public void onSubscriptionsChanged() { refresh(); }
            };
    private Settings settings;
    private String notice;

    @Override public void onCreate() { super.onCreate(); settings = new Settings(this); }

    private SubscriptionManager sm() { return getSystemService(SubscriptionManager.class); }

    private SubscriptionInfo info() {
        try { return sm().getActiveSubscriptionInfo(settings.subId()); }
        catch (SecurityException | IllegalArgumentException e) { return null; }
    }

    private boolean configured() { return SubscriptionManager.isValidSubscriptionId(settings.subId()); }

    /** The number is often only readable while the line is active, so remember it. */
    private String number(SubscriptionInfo active) {
        int subId = settings.subId();
        if (active != null) {
            try {
                String raw = sm().getPhoneNumber(subId);
                if (raw != null && !raw.isEmpty()) {
                    String pretty = PhoneNumberUtils.formatNumber(raw, active.getCountryIso().toUpperCase(Locale.ROOT));
                    settings.rememberNumber(subId, pretty != null ? pretty : raw);
                }
            } catch (SecurityException | IllegalArgumentException ignored) { }
        }
        return settings.number(subId);
    }

    @Override public void onStartListening() {
        sm().addOnSubscriptionsChangedListener(getMainExecutor(), subs);
        refresh();
        SubscriptionInfo active = info();
        if (deadline == 0 && active != null && settings.keepRoamingOff()
                && active.getDataRoaming() != SubscriptionManager.DATA_ROAMING_DISABLE) {
            int subId = settings.subId();
            Shell.run(this, sim -> sim.fixRoaming(subId), this::say);
        }
    }

    @Override public void onStopListening() { sm().removeOnSubscriptionsChangedListener(subs); }

    @Override public void onDestroy() { main.removeCallbacks(poll); super.onDestroy(); }

    @Override public void onClick() {
        if (!configured()) { openSetup(); return; }
        refresh();
        if (deadline != 0) return;
        boolean on = info() == null;
        int subId = settings.subId();
        boolean keepRoamingOff = settings.keepRoamingOff();
        notice = null;
        Shell.run(this, sim -> {
            target = on;
            deadline = SystemClock.elapsedRealtime() + SWITCH_TIMEOUT_MS;
            refresh();
            sim.setEnabled(subId, on, keepRoamingOff);
        }, message -> { deadline = 0; say(message); });
    }

    private void openSetup() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void refresh() {
        main.removeCallbacks(poll);
        SubscriptionInfo active = info();
        boolean on = active != null;
        if (deadline != 0) {
            if (on == target) { deadline = 0; notice = null; }
            else if (SystemClock.elapsedRealtime() > deadline) { deadline = 0; notice = "Switch failed"; }
            else main.postDelayed(poll, POLL_MS);
        }
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean busy = deadline != 0;
        String state = on ? "On" : "Off";
        String number = settings.showNumber() ? number(active) : null;
        String subtitle = !configured() ? "Tap to set up"
                : busy ? (target ? "Turning on…" : "Turning off…")
                : notice != null ? notice
                : number != null ? number : state;
        tile.setLabel(settings.label());
        tile.setState(busy ? Tile.STATE_UNAVAILABLE : on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(subtitle);
        tile.setContentDescription(settings.label() + " " + state);
        tile.updateTile();
    }

    private void say(String message) { notice = message; refresh(); }
}
