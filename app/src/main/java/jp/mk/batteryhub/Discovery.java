package jp.mk.batteryhub;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** クライアント側のホスト自動発見（UDPブロードキャスト）。 */
public class Discovery {

    public static class Result {
        public final String ip;
        public final String hostName;

        Result(String ip, String hostName) {
            this.ip = ip;
            this.hostName = hostName;
        }
    }

    /**
     * ブロードキャストでホストを探す。呼び出しはワーカースレッドから行うこと。
     * 見つからなければ null。
     */
    public static Result discoverHost(Context context, int timeoutMs) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(Net.PORT_DISCOVERY);
            socket.setBroadcast(true);
            socket.setSoTimeout(timeoutMs);

            byte[] payload = Net.buildDiscoveryJson().getBytes("UTF-8");

            // 全体ブロードキャストとサブネットブロードキャストの両方へ送る
            sendTo(socket, payload, InetAddress.getByName("255.255.255.255"));
            InetAddress subnet = getSubnetBroadcast(context);
            if (subnet != null) {
                sendTo(socket, payload, subnet);
            }

            byte[] buf = new byte[1024];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
                JSONObject o = Net.parseJson(p.getData(), p.getLength());
                if (o == null) continue;
                if ("host".equals(o.optString("type"))) {
                    return new Result(p.getAddress().getHostAddress(),
                            o.optString("name", ""));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) socket.close();
        }
        return null;
    }

    private static void sendTo(DatagramSocket socket, byte[] payload, InetAddress addr) {
        try {
            socket.send(new DatagramPacket(payload, payload.length, addr, Net.PORT_DISCOVERY));
        } catch (Exception ignored) {
        }
    }

    private static InetAddress getSubnetBroadcast(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null || wm.getDhcpInfo() == null) return null;
            int ip = wm.getDhcpInfo().ipAddress;
            int mask = wm.getDhcpInfo().netmask;
            if (ip == 0) return null;
            int broadcast = (ip & mask) | ~mask;
            byte[] bytes = new byte[]{
                    (byte) (broadcast & 0xff),
                    (byte) ((broadcast >> 8) & 0xff),
                    (byte) ((broadcast >> 16) & 0xff),
                    (byte) ((broadcast >> 24) & 0xff)};
            return InetAddress.getByAddress(bytes);
        } catch (Exception e) {
            return null;
        }
    }
}
