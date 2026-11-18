package esimtile.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.quicksettings.TileService;
import android.telephony.SubscriptionManager;

/** The user's tile configuration, stored only on this device. */
final class Settings {
    private final SharedPreferences prefs;
    private final Context context;

    Settings(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    int subId() { return prefs.getInt("subId", SubscriptionManager.INVALID_SUBSCRIPTION_ID); }
    String label() { return prefs.getString("label", "eSIM"); }
    boolean keepRoamingOff() { return prefs.getBoolean("keepRoamingOff", false); }
    boolean showNumber() { return prefs.getBoolean("showNumber", false); }
    String number(int subId) { return prefs.getString("number" + subId, null); }

    void select(int subId, String label) { prefs.edit().putInt("subId", subId).putString("label", label).apply(); changed(); }
    void setKeepRoamingOff(boolean on) { prefs.edit().putBoolean("keepRoamingOff", on).apply(); changed(); }
    void setShowNumber(boolean on) { prefs.edit().putBoolean("showNumber", on).apply(); changed(); }
    void rememberNumber(int subId, String number) { prefs.edit().putString("number" + subId, number).apply(); }

    private void changed() { TileService.requestListeningState(context, new ComponentName(context, SimTile.class)); }
}
