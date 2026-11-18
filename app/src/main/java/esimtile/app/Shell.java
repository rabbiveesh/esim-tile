package esimtile.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import rikka.shizuku.Shizuku;

/** Connects to {@link SimService}, which Shizuku runs with shell privileges. */
final class Shell {
    private static final long BINDER_WAIT_MS = 2_000;

    interface Call { void run(ISim sim) throws RemoteException; }
    interface Failure { void report(String message); }

    private Shell() { }

    static boolean granted() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    static void run(Context context, Call call, Failure failure) {
        Handler main = new Handler(Looper.getMainLooper());
        boolean[] settled = {false};
        Shizuku.OnBinderReceivedListener[] listener = new Shizuku.OnBinderReceivedListener[1];
        listener[0] = () -> {
            if (settled[0]) return;
            settled[0] = true;
            main.post(() -> { Shizuku.removeBinderReceivedListener(listener[0]); bind(context, call, failure); });
        };
        Shizuku.addBinderReceivedListenerSticky(listener[0], main);
        main.postDelayed(() -> {
            if (settled[0]) return;
            settled[0] = true;
            Shizuku.removeBinderReceivedListener(listener[0]);
            failure.report("Start Shizuku");
        }, BINDER_WAIT_MS);
    }

    private static void bind(Context context, Call call, Failure failure) {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1);
            failure.report("Allow in Shizuku");
            return;
        }
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(context, SimService.class))
                .daemon(true).processNameSuffix("sim").debuggable(false).version(BuildConfig.VERSION_CODE);
        Shizuku.bindUserService(args, new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try { call.run(ISim.Stub.asInterface(binder)); }
                catch (RemoteException e) { failure.report("Switch failed"); }
                // Shizuku is still iterating its connections here, so unbind afterwards.
                new Handler(Looper.getMainLooper()).post(() -> Shizuku.unbindUserService(args, this, false));
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        });
    }
}
