package esimtile.app;

import android.Manifest;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQUEST_PHONE_STATE = 1, REQUEST_PHONE_NUMBERS = 2;

    private final Shizuku.OnRequestPermissionResultListener shizukuResult = (code, result) -> runOnUiThread(this::reload);
    private final Shizuku.OnBinderReceivedListener shizukuReady = () -> runOnUiThread(this::reload);
    private Settings settings;
    private TextView status;
    private Button grant;
    private RadioGroup profiles;
    private Switch showNumber;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        int gap = dp(16);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(gap, gap, gap, gap);

        TextView title = new TextView(this);
        title.setText("eSIM Tile");
        title.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large);
        column.addView(title);

        status = new TextView(this);
        status.setPadding(0, gap / 2, 0, gap / 2);
        column.addView(status);

        grant = new Button(this);
        grant.setText("Allow Shizuku access");
        grant.setOnClickListener(v -> Shizuku.requestPermission(1));
        column.addView(grant);

        profiles = new RadioGroup(this);
        profiles.setPadding(0, gap / 2, 0, gap / 2);
        column.addView(profiles);

        Switch keepRoamingOff = new Switch(this);
        keepRoamingOff.setText("Keep data roaming off for this eSIM");
        keepRoamingOff.setChecked(settings.keepRoamingOff());
        keepRoamingOff.setPadding(0, gap / 2, 0, gap / 2);
        keepRoamingOff.setOnCheckedChangeListener((v, on) -> settings.setKeepRoamingOff(on));
        column.addView(keepRoamingOff);

        showNumber = new Switch(this);
        showNumber.setText("Show phone number on the tile");
        showNumber.setChecked(settings.showNumber());
        showNumber.setPadding(0, gap / 2, 0, gap / 2);
        showNumber.setOnCheckedChangeListener((v, on) -> setShowNumber(on));
        column.addView(showNumber);

        Button addTile = new Button(this);
        addTile.setText("Add tile to Quick Settings");
        addTile.setOnClickListener(v -> getSystemService(StatusBarManager.class).requestAddTileService(
                new ComponentName(this, SimTile.class), "eSIM", Icon.createWithResource(this, R.drawable.ic_sim),
                getMainExecutor(), result -> { }));
        column.addView(addTile);

        ScrollView root = new ScrollView(this);
        root.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);

        Shizuku.addRequestPermissionResultListener(shizukuResult);
        Shizuku.addBinderReceivedListenerSticky(shizukuReady);
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_PHONE_STATE}, REQUEST_PHONE_STATE);
        }
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuResult);
        Shizuku.removeBinderReceivedListener(shizukuReady);
        super.onDestroy();
    }

    @Override protected void onResume() { super.onResume(); reload(); }

    private void reload() {
        boolean running = Shizuku.pingBinder();
        boolean allowed = Shell.granted();
        grant.setVisibility(running && !allowed ? View.VISIBLE : View.GONE);
        if (!running) { status.setText("Shizuku isn't running. Start it, then come back."); return; }
        if (!allowed) { status.setText("This app uses Shizuku to switch eSIMs."); return; }
        status.setText("Loading eSIMs…");
        Shell.run(this, sim -> {
            Bundle result = sim.listProfiles();
            runOnUiThread(() -> show(result));
        }, message -> runOnUiThread(() -> status.setText(message)));
    }

    private void show(Bundle result) {
        int[] ids = result.getIntArray("ids");
        String[] names = result.getStringArray("names");
        profiles.setOnCheckedChangeListener(null);
        profiles.removeAllViews();
        if (ids == null || names == null) { status.setText("Couldn't read eSIMs: " + result.getString("error")); return; }
        status.setText(ids.length == 0 ? "No downloaded eSIMs found." : "Choose the eSIM this tile controls:");
        for (int i = 0; i < ids.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(ids[i]);
            button.setText(names[i]);
            profiles.addView(button);
            if (ids[i] == settings.subId()) profiles.check(ids[i]);
        }
        profiles.setOnCheckedChangeListener((group, id) -> {
            RadioButton picked = group.findViewById(id);
            if (picked != null) settings.select(id, picked.getText().toString());
        });
    }

    private void setShowNumber(boolean on) {
        if (on && checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_PHONE_NUMBERS}, REQUEST_PHONE_NUMBERS);
            return;
        }
        settings.setShowNumber(on);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_PHONE_NUMBERS) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        settings.setShowNumber(granted);
        showNumber.setChecked(granted);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
