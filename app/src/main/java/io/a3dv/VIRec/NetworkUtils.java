package io.a3dv.VIRec;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import timber.log.Timber;

/**
 * Small networking helpers for the live-streaming feature.
 */
public final class NetworkUtils {

    private NetworkUtils() {
    }

    /**
     * Returns the phone's first non-loopback IPv4 address, or {@code null} if none is found
     * (e.g. no network connection). Deliberately avoids
     * {@code WifiManager.getConnectionInfo().getIpAddress()} (deprecated, WiFi-only, needs an
     * extra permission) in favor of {@link NetworkInterface}, which needs no extra permission
     * and works over any network type (WiFi, USB tethering, etc).
     */
    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getAddress().length == 4) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            Timber.e(e, "Failed to enumerate network interfaces");
            return null;
        }
        return null;
    }
}
