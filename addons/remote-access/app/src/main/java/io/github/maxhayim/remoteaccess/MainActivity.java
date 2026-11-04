package io.github.maxhayim.remoteaccess;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * The tile and module card open this: whether the TV can be controlled over the network, from where, and one
 * press to close it. It reads what Android already shows in Settings and changes one setting, network
 * debugging, and only to switch it off.
 */
public class MainActivity extends Activity {
    private static final long CONFIRM_MS = 5000;
    private static final String TAILSCALE = "com.tailscale.ipn";
    private static final Method SYS_PROP_GET;

    static {
        Method m = null;
        try {
            m = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        SYS_PROP_GET = m;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private Row adbRow, addressRow, tailscaleRow;
    /** OK pressed once on the debugging row: a second press within CONFIRM_MS switches it off. */
    private boolean armed;
    private final Runnable disarm = () -> {
        if (!armed) return;
        armed = false;
        updateAdbRow();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0D1117);
        page.setPadding(px(48), px(32), px(48), px(24));
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);
        title.setTextColor(0xFFFFFFFF);
        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xFF8B949E);
        status.setPadding(0, 0, 0, px(16));
        page.addView(title);
        page.addView(status);
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);

        adbRow = add(rows, new Row(this, "Network debugging (ADB)", null, this::adbPressed));
        adbRow.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && armed) {
                main.removeCallbacks(disarm);
                disarm.run();
            }
        });
        addressRow = add(rows, new Row(this, "This TV's address", "On your home network.", null));
        tailscaleRow = add(rows, new Row(this, "Tailscale", "Reach the TV from outside your home, privately.",
                this::openTailscale));
        add(rows, new Row(this, "Developer options", "Where network debugging is switched on again.",
                this::openDeveloperOptions));
        adbRow.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(disarm);
        armed = false;
        super.onPause();
    }

    private Row add(LinearLayout rows, Row r) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(4);
        rows.addView(r, lp);
        return r;
    }

    private void updateAdbRow() {
        boolean enabled = global("adb_enabled") == 1;
        boolean wireless = global("adb_wifi_enabled") == 1;
        int port = Access.port(prop("service.adb.tcp.port"));
        Access.Adb adb = Access.adb(enabled, wireless, port);
        adbRow.setValue(armed ? "OK again: off" : Access.adbValue(adb, wireless, port));
        adbRow.setValueColor(armed ? Row.VALUE_ARMED : Row.VALUE_DEFAULT);
        if (armed) {
            adbRow.setDetail("Press OK again within 5 seconds to switch debugging off.");
        } else if (adb == Access.Adb.OFF) {
            adbRow.setDetail("Nobody can control this TV over the network.");
        } else if (adb == Access.Adb.USB_ONLY) {
            adbRow.setDetail(canWrite()
                    ? "Network debugging is off; USB debugging is on. OK twice switches USB debugging off too."
                    : "Network debugging is off; USB debugging is on. Switch it off in Developer options (OK opens them).");
        } else if (!canWrite()) {
            adbRow.setDetail("Switch it off in Developer options (OK opens them). install.sh lets this button do it.");
        } else {
            adbRow.setDetail("Anyone allowed on your network can control this TV. OK twice switches it off.");
        }
        if (adb == Access.Adb.NETWORK) status.setText("Network debugging is on. Switch it off when you're done.");
        else status.setText("Network debugging is off.");
    }

    private void refresh() {
        updateAdbRow();

        String[] found = addresses();
        addressRow.setValue(found[0] != null ? found[0] : "No network");
        boolean installed = installed(TAILSCALE);
        tailscaleRow.setValue(Access.tailscale(installed, found[1]));
    }

    private void adbPressed() {
        boolean on = global("adb_enabled") == 1 || global("adb_wifi_enabled") == 1;
        if (!on) return;
        if (!canWrite()) {
            openDeveloperOptions();
            return;
        }
        if (!armed) {
            armed = true;
            main.removeCallbacks(disarm);
            main.postDelayed(disarm, CONFIRM_MS);
            updateAdbRow();
            return;
        }
        main.removeCallbacks(disarm);
        armed = false;
        try {
            Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 0);
            Settings.Global.putInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        } catch (SecurityException e) {
            openDeveloperOptions();
        }
        updateAdbRow();
    }

    private boolean canWrite() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private int global(String name) {
        return Settings.Global.getInt(getContentResolver(), name, 0);
    }

    /** android.os.SystemProperties is hidden but readable; empty when this firmware refuses it. */
    private static String prop(String name) {
        if (SYS_PROP_GET == null) return "";
        try {
            Object v = SYS_PROP_GET.invoke(null, name);
            return v == null ? "" : v.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** [0] the home network's IPv4 address, [1] the Tailscale address; null where there is none. */
    private String[] addresses() {
        String[] out = new String[2];
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return out;
        Network active = cm.getActiveNetwork();
        Network[] networks = cm.getAllNetworks();
        if (networks == null) return out;
        for (Network n : networks) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            LinkProperties lp = cm.getLinkProperties(n);
            if (caps == null || lp == null) continue;
            boolean vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress addr = la.getAddress();
                if (!(addr instanceof Inet4Address)) continue;
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                String ip = addr.getHostAddress();
                if (vpn && Access.tailscaleAddress(ip)) out[1] = ip;
                else if (!vpn && (out[0] == null || n.equals(active))) out[0] = ip;
            }
        }
        return out;
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openTailscale() {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(TAILSCALE);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(TAILSCALE);
        if (i != null) open(i);
    }

    private void openDeveloperOptions() {
        if (!open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) {
            open(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean open(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Some TVs have no screen for it.
            return false;
        }
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}

