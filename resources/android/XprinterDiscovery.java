package com.nembestil.pos3.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** XP0001 IPv4 administration, verified on XP-80 hardware on 2026-11-13. */
final class XprinterDiscovery implements Closeable {
    private static final byte[] FIND = "XP0001FIND".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FOUND = "XP0001FOUND".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SAVE = "XP0001SAVE".getBytes(StandardCharsets.US_ASCII);
    private final Network network;
    private final DatagramSocket socket;
    private final InetAddress broadcast;
    private WifiManager.MulticastLock wifiLock;

    static final class Printer {
        final String mac;
        final String ip;
        final int port;
        final int reportedDhcp;
        private final byte[] config;

        private Printer(byte[] packet, int offset) {
            StringBuilder text = new StringBuilder();
            for (int index = 11; index < 17; index++) {
                if (index > 11) text.append('-');
                text.append(String.format(Locale.ROOT, "%02X", packet[offset + index] & 0xff));
            }
            mac = text.toString();
            ip = (packet[offset + 19] & 0xff) + "." + (packet[offset + 20] & 0xff)
                + "." + (packet[offset + 21] & 0xff) + "." + (packet[offset + 22] & 0xff);
            port = (packet[offset + 31] & 0xff) | ((packet[offset + 32] & 0xff) << 8);
            reportedDhcp = packet[offset + 33] & 0xff;
            config = Arrays.copyOfRange(packet, offset + 11, offset + 33);
        }
    }

    static String normalizeMac(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(':', '-');
    }

    static Printer decode(byte[] packet, int offset, int length) {
        if (packet == null || offset < 0 || offset > packet.length
            || length < 34 || length > packet.length - offset) return null;
        for (int index = 0; index < FOUND.length; index++) {
            if (packet[offset + index] != FOUND[index]) return null;
        }
        if (packet[offset + 17] != 0x22 || packet[offset + 18] != 0) return null;
        boolean allZero = true;
        for (int index = 11; index < 17; index++) allZero &= packet[offset + index] == 0;
        if (allZero || (packet[offset + 11] & 1) != 0) return null;
        Printer printer = new Printer(packet, offset);
        return printer.port == 0 ? null : printer;
    }

    static byte[] autoIpRequest(Printer printer) {
        // SAVE is 33 bytes, but the protocol field inside config stays 22 00.
        return ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN)
            .put(SAVE).put(printer.config).put((byte) 1).array();
    }

    static Network findLanNetwork(Context context) throws IOException {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) throw new IOException("No LAN network is available");
        Network active = manager.getActiveNetwork();
        if (isLan(manager, active)) return active;
        for (Network candidate : manager.getAllNetworks()) {
            if (isLan(manager, candidate)) return candidate;
        }
        throw new IOException("No IPv4 Wi-Fi or Ethernet network is available");
    }

    private static boolean isLan(ConnectivityManager manager, Network network) {
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        LinkProperties properties = manager.getLinkProperties(network);
        if (capabilities == null || properties == null
            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            || (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return false;
        for (LinkAddress address : properties.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address) return true;
        }
        return false;
    }

    XprinterDiscovery(Context context, Network network) throws IOException {
        this.network = network;
        broadcast = InetAddress.getByAddress(new byte[] {-1, -1, -1, -1});
        socket = new DatagramSocket((SocketAddress) null);
        try {
            socket.setBroadcast(true);
            network.bindSocket(socket);
            // The firmware broadcasts replies to this request's source port.
            // An ephemeral port also avoids another app's discovery socket.
            socket.bind(new InetSocketAddress("0.0.0.0", 0));
            socket.setSoTimeout(200);
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createMulticastLock("NemBestil:XprinterDiscovery");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    Network network() { return network; }

    void find() throws IOException { send(FIND); }

    Printer receive() throws IOException {
        byte[] bytes = new byte[4096];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException timeout) {
            return null;
        }
        return packet.getPort() == 9000 ? decode(bytes, packet.getOffset(), packet.getLength()) : null;
    }

    Printer findMac(String mac, long deadline) throws IOException {
        long nextSend = 0;
        while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted()) {
            if (SystemClock.elapsedRealtime() >= nextSend) {
                find();
                nextSend = SystemClock.elapsedRealtime() + 1000;
            }
            Printer printer = receive();
            if (printer != null && printer.mac.equals(mac)) return printer;
        }
        return null;
    }

    void requestAutoIp(Printer printer) throws IOException { send(autoIpRequest(printer)); }

    boolean reachable(Printer printer) {
        try (Socket tcp = network.getSocketFactory().createSocket()) {
            tcp.connect(new InetSocketAddress(printer.ip, printer.port), 1000);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private void send(byte[] payload) throws IOException {
        socket.send(new DatagramPacket(payload, payload.length, broadcast, 9000));
    }

    @Override public synchronized void close() {
        socket.close();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }
}
