package jp.mk.batteryhub;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** ホスト側で保持するデバイスごとの最新状態。 */
public class HostState {

    public static class DeviceInfo {
        public final String name;
        public volatile int level;
        public volatile boolean charging;
        public volatile long lastSeenMs;
        /** 15%未満で警告を出したかどうか（20%以上で再アーム） */
        public volatile boolean alerted;

        DeviceInfo(String name) {
            this.name = name;
        }
    }

    private static final HostState INSTANCE = new HostState();

    public static HostState get() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<String, DeviceInfo> devices = new ConcurrentHashMap<>();

    /** レポートを取り込む。新規デバイスなら true を返す。 */
    public boolean update(String name, int level, boolean charging) {
        DeviceInfo d = devices.get(name);
        boolean isNew = false;
        if (d == null) {
            d = new DeviceInfo(name);
            DeviceInfo prev = devices.putIfAbsent(name, d);
            if (prev != null) {
                d = prev;
            } else {
                isNew = true;
            }
        }
        d.level = level;
        d.charging = charging;
        d.lastSeenMs = System.currentTimeMillis();
        return isNew;
    }

    public DeviceInfo getDevice(String name) {
        return devices.get(name);
    }

    public List<DeviceInfo> snapshot() {
        List<DeviceInfo> list = new ArrayList<>(devices.values());
        list.sort(Comparator.comparing(d -> d.name));
        return list;
    }

    public void clear() {
        devices.clear();
    }
}
