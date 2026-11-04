package io.github.maxhayim.remoteaccess;

/** The wording and the address checks, with no Android types, so the JVM tests cover them. */
final class Access {
    enum Adb { OFF, USB_ONLY, NETWORK }

    private Access() {
    }

    /**
     * What ADB can be reached by. adb_enabled is the developer-options switch; a TCP port (adb tcpip) or wireless
     * debugging makes it reachable over the network, which is what matters on a TV with no USB cable in.
     * Some firmware keeps wireless debugging, and its port, up with adb_enabled at 0 (Xiaomi MiTV-AFMU0).
     */
    static Adb adb(boolean enabled, boolean wireless, int tcpPort) {
        if (wireless) return Adb.NETWORK;
        if (!enabled) return Adb.OFF;
        return tcpPort > 0 ? Adb.NETWORK : Adb.USB_ONLY;
    }

    static String adbValue(Adb a, boolean wireless, int tcpPort) {
        switch (a) {
            case NETWORK:
                return tcpPort > 0 ? "On, port " + tcpPort : wireless ? "Wireless debugging" : "On";
            case USB_ONLY:
                return "USB only";
            default:
                return "Off";
        }
    }

    /** -1 for an unset, empty or odd service.adb.tcp.port value. */
    static int port(String prop) {
        if (prop == null) return -1;
        try {
            int p = Integer.parseInt(prop.trim());
            return p > 0 && p < 65536 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Tailscale gives each device an address in 100.64.0.0/10 (the carrier-grade NAT range). */
    static boolean tailscaleAddress(String ip) {
        if (ip == null) return false;
        int d1 = ip.indexOf('.');
        if (d1 <= 0) return false;
        int d2 = ip.indexOf('.', d1 + 1);
        if (d2 <= d1 + 1) return false;
        int d3 = ip.indexOf('.', d2 + 1);
        if (d3 <= d2 + 1 || d3 >= ip.length() - 1 || ip.indexOf('.', d3 + 1) != -1) return false;
        int a = octet(ip, 0, d1);
        int b = octet(ip, d1 + 1, d2);
        int c = octet(ip, d2 + 1, d3);
        int d = octet(ip, d3 + 1, ip.length());
        return a == 100 && b >= 64 && b <= 127 && c >= 0 && d >= 0;
    }

    private static int octet(String s, int start, int end) {
        if (end - start <= 0 || end - start > 3) return -1;
        int val = 0;
        for (int i = start; i < end; i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return -1;
            val = val * 10 + (ch - '0');
        }
        return val <= 255 ? val : -1;
    }

    static String tailscale(boolean installed, String address) {
        if (!installed) return "Not installed";
        return address != null ? "Connected, " + address : "Installed, not connected";
    }
}

