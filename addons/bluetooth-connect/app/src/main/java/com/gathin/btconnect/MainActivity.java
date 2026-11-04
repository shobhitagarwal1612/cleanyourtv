package com.gathin.btconnect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Translucent top-right frosted-glass Headphones popup opened from the top-bar Headphones pill:
 * lists paired AirPods, headphones, and speakers (connected first with battery %), and toggles
 * connect/disconnect in one click.
 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    private boolean proxyRequested;
    private boolean askingPermission;
    private float dp;
    /** Addresses asked to change a moment ago, shown as "Connecting…"/"Disconnecting…" until the state change arrives. */
    private final Set<String> pending = new HashSet<>();
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_DISCONNECTED) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) pending.remove(device.getAddress());
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) pending.remove(device.getAddress());
            }
            refresh();
        }
    };

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        dp = getResources().getDisplayMetrics().density;

        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x55000000);
        scrim.setFocusable(false);
        scrim.setOnClickListener(v -> finish());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable glass = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF0181B26, 0xF210121A});
        glass.setCornerRadius(22f * dp);
        glass.setStroke(Math.max(1, Math.round(dp)), 0x38FFFFFF);
        panel.setBackground(glass);
        int padH = Math.round(20 * dp), padV = Math.round(18 * dp);
        panel.setPadding(padH, padV, padH, padV);
        panel.setElevation(18f * dp);

        TextView title = new TextView(this);
        title.setText("Headphones & Audio");
        title.setTextSize(17.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xF5FFFFFF);
        status = new TextView(this);
        status.setTextSize(12.5f);
        status.setTextColor(0x99FFFFFF);
        status.setPadding(0, Math.round(2 * dp), 0, Math.round(12 * dp));
        panel.addView(title);
        panel.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                Math.round(390 * dp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        panelLp.topMargin = Math.round(86 * dp);
        panelLp.setMarginEnd(Math.round(48 * dp));
        panelLp.bottomMargin = Math.round(36 * dp);
        scrim.addView(panel, panelLp);
        setContentView(scrim);

        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm != null ? bm.getAdapter() : null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            if (!askingPermission) {
                askingPermission = true;
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            }
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        askingPermission = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else {
            status.setText("Needs the Nearby devices permission: run install.sh once, or allow it in the app's settings.");
            showFallbackSettingsRow();
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void start() {
        if (adapter == null || !adapter.isEnabled()) {
            status.setText("Bluetooth is off. Turn it on in the TV's settings.");
            showFallbackSettingsRow();
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(changed, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(changed, filter);
        }
        if (a2dp != null) {
            refresh();
            return;
        }
        if (proxyRequested) return;
        proxyRequested = true;
        adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                proxyRequested = false;
                if (isDestroyed() || isFinishing()) {
                    adapter.closeProfileProxy(profile, proxy);
                    return;
                }
                a2dp = (BluetoothA2dp) proxy;
                refresh();
            }
            @Override public void onServiceDisconnected(int profile) {
                a2dp = null;
            }
        }, BluetoothProfile.A2DP);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onPause() {
        try {
            unregisterReceiver(changed);
        } catch (IllegalArgumentException notRegistered) {
        }
        main.removeCallbacksAndMessages(null);
        super.onPause();
        if (!askingPermission && !isChangingConfigurations()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        if (a2dp != null && adapter != null) {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            a2dp = null;
        }
        super.onDestroy();
    }

    /** Only runs after onResume has checked Nearby devices on Android 12+. */
    @SuppressLint("MissingPermission")
    private void refresh() {
        if (a2dp == null || adapter == null) return;
        int focus = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocused()) focus = i;
        list.removeAllViews();
        try {
            Set<String> on = new HashSet<>();
            for (BluetoothDevice d : a2dp.getConnectedDevices()) on.add(d.getAddress());

            List<BluetoothDevice> audioDevices = new ArrayList<>();
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (!Link.isAudio(device.getBluetoothClass())) continue;
                audioDevices.add(device);
            }
            Collections.sort(audioDevices, (a, b) -> {
                boolean ca = on.contains(a.getAddress());
                boolean cb = on.contains(b.getAddress());
                return Boolean.compare(cb, ca);
            });

            for (BluetoothDevice device : audioDevices) {
                String address = device.getAddress();
                boolean connected = on.contains(address);
                boolean isPending = pending.contains(address);
                int battery = Link.batteryLevel(device);
                String sub;
                if (isPending) {
                    sub = connected ? "Disconnecting…" : "Connecting…";
                } else if (connected) {
                    sub = battery >= 0 ? ("● Connected · " + battery + "% battery") : "● Connected";
                } else {
                    sub = battery >= 0 ? ("Paired · " + battery + "%") : "Paired · Tap OK to connect";
                }
                String rawName = device.getName();
                String label = (rawName == null || rawName.trim().isEmpty()) ? address : rawName.trim();
                Row row = new Row(this, label, sub, connected && !isPending, () -> toggle(device, connected));
                row.setValue(isPending ? "…" : (connected ? "Disconnect" : "Connect"));
                addRowView(row);
            }
        } catch (SecurityException ignored) {
        }
        if (list.getChildCount() == 0) {
            status.setText("No paired headphones. Pair one in Bluetooth settings first.");
            showFallbackSettingsRow();
        } else {
            status.setText("Press OK to connect or disconnect audio.");
            list.getChildAt(Math.min(focus, list.getChildCount() - 1)).requestFocus();
        }
    }

    private void addRowView(Row row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (list.getChildCount() > 0) lp.topMargin = Math.round(4 * dp);
        list.addView(row, lp);
    }

    private void showFallbackSettingsRow() {
        list.removeAllViews();
        Row row = new Row(this, "Bluetooth settings", "Open TV settings to pair or manage audio devices",
                false, this::openBluetoothSettings);
        row.setValue("Open");
        addRowView(row);
        row.requestFocus();
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        finish();
    }

    private void toggle(BluetoothDevice device, boolean connected) {
        if (a2dp == null || pending.contains(device.getAddress())) return;
        if (!Link.set(a2dp, device, !connected)) {
            status.setText("Android refused direct switch. Opening TV Bluetooth settings…");
            openBluetoothSettings();
            return;
        }
        pending.add(device.getAddress());
        refresh();
        // A state change normally clears it; if none arrives (a speaker that is off), stop saying Working.
        main.postDelayed(() -> {
            pending.remove(device.getAddress());
            refresh();
        }, 12000);
    }
}
