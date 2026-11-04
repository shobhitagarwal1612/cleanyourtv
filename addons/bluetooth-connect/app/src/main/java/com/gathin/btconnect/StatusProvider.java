package com.gathin.btconnect;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * Read-only provider (content://com.gathin.btconnect.status/now and /connect) that reports the currently
 * connected Bluetooth headphones/speaker name + battery % to the launcher's top-bar Headphones pill,
 * and auto-connects paired headphones when Movie Night is activated.
 */
public final class StatusProvider extends ContentProvider {
    private static final String[] COLUMNS = {"name", "battery"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    @SuppressLint("MissingPermission")
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor out = new MatrixCursor(COLUMNS);
        Context ctx = getContext();
        if (ctx == null) return out;
        String caller = getCallingPackage();
        if (caller != null && !"com.gathin.tvhome".equals(caller) && !ctx.getPackageName().equals(caller)) {
            return out;
        }
        try {
            BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null || !adapter.isEnabled()) return out;
            boolean connectedFound = false;
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (!Link.isAudio(device.getBluetoothClass())) continue;
                if (Link.isDeviceConnected(device)) {
                    String name = device.getName();
                    String cleanName = (name == null || name.trim().isEmpty()) ? device.getAddress() : name.trim();
                    int battery = Link.batteryLevel(device);
                    out.addRow(new Object[]{cleanName, battery});
                    connectedFound = true;
                    break;
                }
            }
            if (!connectedFound && "/connect".equals(uri.getPath())) {
                autoConnectBondedAudio(ctx.getApplicationContext(), adapter);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    @SuppressLint("MissingPermission")
    private static void autoConnectBondedAudio(Context appCtx, BluetoothAdapter adapter) {
        adapter.getProfileProxy(appCtx, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    for (BluetoothDevice device : adapter.getBondedDevices()) {
                        if (!Link.isAudio(device.getBluetoothClass())) continue;
                        if (Link.set((BluetoothA2dp) proxy, device, true)) break;
                    }
                } catch (Throwable ignored) {
                } finally {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> adapter.closeProfileProxy(profile, proxy), 1500);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
            }
        }, BluetoothProfile.A2DP);
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.btconnect.status";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
