package esimtile.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.UiccCardInfo;
import android.telephony.UiccPortInfo;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs as the shell user via Shizuku and exits once its work is done. */
public class SimService extends ISim.Stub {
    private static final String SHELL = "com.android.shell";
    private static final int PROFILE_CLASS_PROVISIONING = 1;
    private static final int SWITCH_WAIT_SECONDS = 45;
    private static final int ROAMING_REAPPLY_COUNT = 8;
    private static final long ROAMING_REAPPLY_MS = 2_000;

    private final AtomicInteger jobs = new AtomicInteger();
    private final Context context;

    public SimService(Context context) { this.context = context; }

    @Override public void destroy() { System.exit(0); }

    @Override public Bundle listProfiles() {
        Bundle out = new Bundle();
        try {
            Map<Integer, String> profiles = new TreeMap<>();
            for (SubscriptionInfo s : allSubscriptions()) {
                if (selectable(s)) profiles.put(s.getSubscriptionId(), String.valueOf(s.getDisplayName()));
            }
            int[] ids = new int[profiles.size()];
            String[] names = new String[profiles.size()];
            int i = 0;
            for (Map.Entry<Integer, String> e : profiles.entrySet()) { ids[i] = e.getKey(); names[i++] = e.getValue(); }
            out.putIntArray("ids", ids);
            out.putStringArray("names", names);
        } catch (Exception e) {
            out.putString("error", String.valueOf(e));
        }
        job(() -> Thread.sleep(1000)); // Let the reply reach the app before exiting.
        return out;
    }

    @Override public void setEnabled(int subId, boolean on, boolean keepRoamingOff) {
        job(() -> apply(subId, on, keepRoamingOff));
    }

    @Override public void fixRoaming(int subId) { job(() -> roamingOff(subId)); }

    /** Matches what Android's SIM settings offer: no provisioning or satellite-only profiles. */
    private static boolean selectable(SubscriptionInfo s) {
        return s.isEmbedded()
                && !Integer.valueOf(PROFILE_CLASS_PROVISIONING).equals(hiddenGetter(s, "getProfileClass"))
                && !Boolean.TRUE.equals(hiddenGetter(s, "isOnlyNonTerrestrialNetwork"));
    }

    private static Object hiddenGetter(SubscriptionInfo s, String name) {
        try { return SubscriptionInfo.class.getMethod(name).invoke(s); }
        catch (ReflectiveOperationException e) { return null; }
    }

    private interface Job { void run() throws Exception; }

    private void job(Job j) {
        jobs.incrementAndGet();
        new Thread(() -> {
            try { j.run(); } catch (Throwable t) { t.printStackTrace(); }
            if (jobs.decrementAndGet() == 0) System.exit(0);
        }).start();
    }

    private void apply(int subId, boolean on, boolean keepRoamingOff) throws Exception {
        if (on && keepRoamingOff) roamingOff(subId);
        SubscriptionInfo sub = subscription(subId);
        int[] location = on ? freePort(sub) : currentPort(sub);
        Object euicc = service("econtroller", "com.android.internal.telephony.euicc.IEuiccController");
        PendingIntent done = PendingIntent.getBroadcast(context.createPackageContext(SHELL, 0), 0,
                new Intent("esimtile.app.RESULT").setPackage(SHELL), PendingIntent.FLAG_IMMUTABLE);
        method(euicc, "switchToSubscriptionWithPort").invoke(euicc, location[0],
                on ? subId : SubscriptionManager.INVALID_SUBSCRIPTION_ID, location[1], SHELL, done);
        for (int i = 0; i < SWITCH_WAIT_SECONDS && isActive(subId) != on; i++) Thread.sleep(1000);
        if (!on || !keepRoamingOff) return;
        // Activation can restore the carrier's roaming default, so re-apply while it settles.
        for (int i = 0; i < ROAMING_REAPPLY_COUNT; i++) { roamingOff(subId); Thread.sleep(ROAMING_REAPPLY_MS); }
        roamingOff(subId);
    }

    /** Picks an eSIM port with no enabled profile, preferring ports already mapped to a modem slot. */
    private int[] freePort(SubscriptionInfo sub) throws Exception {
        UiccCardInfo card = euiccCard(sub.getCardId());
        List<Integer> used = new ArrayList<>();
        for (SubscriptionInfo s : allSubscriptions()) {
            if (s.isEmbedded() && s.getSimSlotIndex() >= 0 && s.getCardId() == card.getCardId()) used.add(s.getPortIndex());
        }
        for (boolean wantActive : new boolean[] {true, false}) {
            for (UiccPortInfo port : card.getPorts()) {
                if (port.isActive() == wantActive && !used.contains(port.getPortIndex())) {
                    return new int[] {card.getCardId(), port.getPortIndex()};
                }
            }
        }
        return new int[] {card.getCardId(), 0}; // No free port: Android replaces the profile on port 0.
    }

    private int[] currentPort(SubscriptionInfo sub) throws Exception {
        return new int[] {euiccCard(sub.getCardId()).getCardId(), Math.max(sub.getPortIndex(), 0)};
    }

    private UiccCardInfo euiccCard(int preferredCardId) throws Exception {
        Object phone = service("phone", "com.android.internal.telephony.ITelephony");
        UiccCardInfo first = null;
        for (Object o : (List<?>) method(phone, "getUiccCardsInfo").invoke(phone, SHELL)) {
            UiccCardInfo card = (UiccCardInfo) o;
            if (!card.isEuicc()) continue;
            if (card.getCardId() == preferredCardId) return card;
            if (first == null) first = card;
        }
        if (first == null) throw new IllegalStateException("no eSIM hardware found");
        return first;
    }

    private SubscriptionInfo subscription(int subId) throws Exception {
        for (SubscriptionInfo s : allSubscriptions()) if (s.getSubscriptionId() == subId) return s;
        throw new IllegalArgumentException("unknown subscription " + subId);
    }

    @SuppressWarnings("unchecked")
    private List<SubscriptionInfo> allSubscriptions() throws Exception {
        Object sub = isub();
        return (List<SubscriptionInfo>) method(sub, "getAllSubInfoList").invoke(sub, SHELL, null);
    }

    private void roamingOff(int subId) throws Exception {
        Object sub = isub();
        method(sub, "setDataRoaming").invoke(sub, SubscriptionManager.DATA_ROAMING_DISABLE, subId);
    }

    private boolean isActive(int subId) throws Exception {
        Object sub = isub();
        for (int id : (int[]) method(sub, "getActiveSubIdList").invoke(sub, false)) if (id == subId) return true;
        return false;
    }

    private Object isub() throws Exception { return service("isub", "com.android.internal.telephony.ISub"); }

    private static Object service(String name, String iface) throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        return Class.forName(iface + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    private static Method method(Object o, String name) {
        for (Method m : o.getClass().getMethods()) if (m.getName().equals(name)) return m;
        throw new IllegalStateException("missing " + name);
    }
}
