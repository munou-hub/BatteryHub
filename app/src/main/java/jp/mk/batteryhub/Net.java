package jp.mk.batteryhub;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** 通信まわりの定数と小さなユーティリティ。 */
public final class Net {

    /** 電池レポートの受信ポート (UDP) */
    public static final int PORT_REPORT = 47110;
    /** ホスト自動発見ポート (UDPブロードキャスト) */
    public static final int PORT_DISCOVERY = 47111;

    private Net() {}

    public static String buildReportJson(String name, int level, boolean charging) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "report");
            o.put("name", name);
            o.put("level", level);
            o.put("charging", charging);
            o.put("ts", System.currentTimeMillis());
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    public static String buildDiscoveryJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "discover");
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    public static String buildHostAnnounceJson(String hostName) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "host");
            o.put("name", hostName);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    public static JSONObject parseJson(byte[] data, int len) {
        try {
            return new JSONObject(new String(data, 0, len, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    /** この端末のLAN内IPv4アドレス（表示用）。取れなければ null。 */
    public static String getLocalIpv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
