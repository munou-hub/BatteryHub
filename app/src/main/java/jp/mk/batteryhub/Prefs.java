package jp.mk.batteryhub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** 設定の永続化（役割・デバイス名・ホストIP・サービス有効フラグ）。 */
public class Prefs {

    public static final String ROLE_HOST = "host";
    public static final String ROLE_CLIENT = "client";

    private static final String FILE = "batteryhub_prefs";
    private static final String KEY_ROLE = "role";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_HOST_IP = "host_ip";
    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final String KEY_BLE_ENABLED = "ble_enabled";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check_ms";
    private static final String KEY_PENDING_DOWNLOAD = "pending_download_id";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getRole() {
        return sp.getString(KEY_ROLE, ROLE_CLIENT);
    }

    public boolean isHost() {
        return ROLE_HOST.equals(getRole());
    }

    public String getDeviceName() {
        String def = Build.MODEL != null ? Build.MODEL : "Android";
        return sp.getString(KEY_DEVICE_NAME, def);
    }

    public String getHostIp() {
        return sp.getString(KEY_HOST_IP, "");
    }

    public boolean isServiceEnabled() {
        return sp.getBoolean(KEY_SERVICE_ENABLED, false);
    }

    /** BLE（Bluetooth）併用が有効か。既定は有効。 */
    public boolean isBleEnabled() {
        return sp.getBoolean(KEY_BLE_ENABLED, true);
    }

    public void setBleEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_BLE_ENABLED, enabled).apply();
    }

    public long getLastUpdateCheckMs() {
        return sp.getLong(KEY_LAST_UPDATE_CHECK, 0);
    }

    public void setLastUpdateCheckMs(long ms) {
        sp.edit().putLong(KEY_LAST_UPDATE_CHECK, ms).apply();
    }

    public long getPendingDownloadId() {
        return sp.getLong(KEY_PENDING_DOWNLOAD, -1);
    }

    public void setPendingDownloadId(long id) {
        sp.edit().putLong(KEY_PENDING_DOWNLOAD, id).apply();
    }

    public void save(String role, String deviceName, String hostIp, Boolean serviceEnabled) {
        SharedPreferences.Editor e = sp.edit();
        if (role != null) e.putString(KEY_ROLE, role);
        if (deviceName != null) e.putString(KEY_DEVICE_NAME, deviceName);
        if (hostIp != null) e.putString(KEY_HOST_IP, hostIp);
        if (serviceEnabled != null) e.putBoolean(KEY_SERVICE_ENABLED, serviceEnabled);
        e.apply();
    }
}
