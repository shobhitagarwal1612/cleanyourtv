package com.gathin.btconnect;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;

import java.lang.reflect.Method;

/**
 * Connecting and disconnecting a paired speaker. Android's public API has neither, so this calls the audio
 * profile's own connect and disconnect, which a plain app may use with the Nearby devices permission.
 */
final class Link {
    private static final Method M_CONNECT = findMethod(BluetoothA2dp.class, "connect", BluetoothDevice.class);
    private static final Method M_DISCONNECT = findMethod(BluetoothA2dp.class, "disconnect", BluetoothDevice.class);
    private static final Method M_BATTERY = findMethod(BluetoothDevice.class, "getBatteryLevel");
    private static final Method M_IS_CONNECTED = findMethod(BluetoothDevice.class, "isConnected");

    private Link() { }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** A speaker or headphones: anything the TV can send sound to. */
    static boolean isAudio(BluetoothClass c) {
        if (c == null) return true;   // unknown class: show it rather than hide a speaker
        return c.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO
                || c.hasService(BluetoothClass.Service.AUDIO);
    }

    /** True when Android accepted the request; the connection itself changes a moment later. */
    static boolean set(BluetoothA2dp a2dp, BluetoothDevice device, boolean connect) {
        if (a2dp == null || device == null) return false;
        Method m = connect ? M_CONNECT : M_DISCONNECT;
        if (m == null) return false;
        try {
            return Boolean.TRUE.equals(m.invoke(a2dp, device));
        } catch (Throwable refused) {
            return false;
        }
    }

    /** Battery percentage 0..100 from BluetoothDevice#getBatteryLevel(), or -1 when unknown. */
    static int batteryLevel(BluetoothDevice device) {
        if (device == null || M_BATTERY == null) return -1;
        try {
            Object v = M_BATTERY.invoke(device);
            if (v instanceof Integer) {
                int pct = (Integer) v;
                return (pct >= 0 && pct <= 100) ? pct : -1;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    static boolean isDeviceConnected(BluetoothDevice device) {
        if (device == null || M_IS_CONNECTED == null) return false;
        try {
            return Boolean.TRUE.equals(M_IS_CONNECTED.invoke(device));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
