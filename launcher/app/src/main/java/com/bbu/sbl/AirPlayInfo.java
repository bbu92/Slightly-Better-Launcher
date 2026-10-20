package com.bbu.sbl;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * The AirPlay receiver app on the TV (jqssun's "AirPlay Receiver for Android"). It exposes nothing to other
 * apps, and touching its server socket makes it pop its own window open, so the name iPhones see is read the way
 * an iPhone reads it: from the receiver's mDNS announcement ({@code _airplay._tcp}). The announcement that comes
 * from this very TV is the one that counts. It follows renames made in the AirPlay app.
 */
final class AirPlayInfo {
    static final String PACKAGE = "io.github.jqssun.airplay";
    private static final String TYPE = "_airplay._tcp.";
    private static final String CACHE = "sbl_airplay";
    private static final long TIMEOUT_MS = 8000;

    private static boolean discovering;

    private AirPlayInfo() {
    }

    static boolean installed(Context c) {
        return launchIntent(c) != null;
    }

    static Intent launchIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(PACKAGE);
        return i != null ? i : pm.getLaunchIntentForPackage(PACKAGE);
    }

    /** The name seen on the network by an earlier look, or null when none was ever heard. */
    static String heardName(Context c) {
        String n = c.getSharedPreferences(CACHE, Context.MODE_PRIVATE).getString("name", null);
        return n != null && !n.isEmpty() ? n : null;
    }

    /** The last name seen on the network, or the TV's own name until the announcement has been heard once. */
    static String cachedName(Context c) {
        String n = c.getSharedPreferences(CACHE, Context.MODE_PRIVATE).getString("name", null);
        if (n != null && !n.isEmpty()) return n;
        String dev = Settings.Global.getString(c.getContentResolver(), "device_name");
        return dev != null && !dev.isEmpty() ? dev : Build.MODEL;
    }

    /** Looks for this TV's AirPlay announcement for a few seconds; {@code done} gets the name on the main thread, if heard. */
    static void discoverName(Context context, Consumer<String> done) {
        if (discovering) return;
        final Context app = context.getApplicationContext();
        final NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        discovering = true;
        final Handler main = new Handler(Looper.getMainLooper());
        final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
        final boolean[] resolving = {false};
        final boolean[] finished = {false};
        final NsdManager.DiscoveryListener[] listener = new NsdManager.DiscoveryListener[1];

        final Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            discovering = false;
            try {
                nsd.stopServiceDiscovery(listener[0]);
            } catch (RuntimeException ignored) {
            }
        };
        final Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (finished[0] || resolving[0]) return;
            NsdServiceInfo info = queue.poll();
            if (info == null) return;
            resolving[0] = true;
            try {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo i, int errorCode) {
                        resolving[0] = false;
                        main.post(next[0]);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo i) {
                        resolving[0] = false;
                        if (isLocal(i.getHost()) && !finished[0]) {
                            String name = i.getServiceName();
                            app.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().putString("name", name).apply();
                            main.post(() -> {
                                finish.run();
                                done.accept(name);
                            });
                        } else {
                            main.post(next[0]);
                        }
                    }
                });
            } catch (RuntimeException e) {
                resolving[0] = false;
            }
        };

        listener[0] = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> {
                    queue.add(info);
                    next[0].run();
                });
            }

            @Override
            public void onStartDiscoveryFailed(String type, int errorCode) {
                main.post(finish);
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener[0]);
        } catch (RuntimeException e) {
            discovering = false;
            return;
        }
        main.postDelayed(() -> {
            boolean gaveUp = !finished[0];
            finish.run();
            if (gaveUp) done.accept(null);   // nothing of ours was announced
        }, TIMEOUT_MS);
    }

    private static boolean isLocal(InetAddress host) {
        if (host == null) return false;
        if (host.isLoopbackAddress()) return true;
        try {
            return NetworkInterface.getByInetAddress(host) != null;
        } catch (java.net.SocketException e) {
            return false;
        }
    }
}
