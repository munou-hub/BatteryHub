package jp.mk.batteryhub;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * BLEによる残量レポートの送受信（v1.3〜）。Wi-Fi/UDPと並行して使う近距離フォールバック。
 *
 * 方式は「接続しないビーコン型」:
 * - クライアント: 残量を BLE アドバタイズの Manufacturer Data に載せて報知する（GATT接続なし）
 * - ホスト: BLEスキャンで報知を受け、Manufacturer Data を解釈して通常のレポートとして取り込む
 *
 * ペイロード（Company ID 0xFFFF = テスト用IDのManufacturer Data内）:
 *   [0] 'B'  [1] 'H'  [2] version(=1)  [3] level(0-100)  [4] flags(bit0=充電中)
 *   [5..] デバイス名（UTF-8、最大17バイト。マルチバイト文字の途中で切らない）
 *
 * 制約（READMEにも明記）:
 * - アドバタイズ送信（ペリフェラル動作）に対応しない端末では送信できない（その場合はWi-Fiのみ）
 * - Android 12+ では BLUETOOTH_ADVERTISE（送信）／BLUETOOTH_SCAN（受信）のランタイム許可が必要
 * - Android 11以下ではスキャンに位置情報許可＋位置情報ONが必要
 * - 到達距離は同一空間レベル。壁越しは弱い
 */
public class BleTransport {

    private static final String TAG = "BatteryHubBLE";
    static final int COMPANY_ID = 0xFFFF;
    private static final int MAX_NAME_BYTES = 17;

    public interface Listener {
        void onBleReport(String name, int level, boolean charging);
    }

    private final Context ctx;
    private final Listener listener;

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertiseCallback;
    private boolean advertising;
    private volatile boolean advertiseRequested;

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private boolean scanning;

    public BleTransport(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    // ---------- パーミッション ----------

    /** ランタイム許可が必要なBLE権限がすべて許可済みか（役割に応じて判定は呼び出し側でも行う）。 */
    public static boolean hasAdvertisePermission(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasScanPermission(Context c) {
        if (Build.VERSION.SDK_INT >= 31) {
            return c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return null;
        BluetoothAdapter a = bm.getAdapter();
        if (a == null || !a.isEnabled()) return null;
        return a;
    }

    /** この端末でBLEアドバタイズ送信ができるか（ペリフェラル対応＋広告主が取れるか）。 */
    public boolean canAdvertise() {
        BluetoothAdapter a = adapter();
        if (a == null) return false;
        if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return false;
        }
        try {
            return a.getBluetoothLeAdvertiser() != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- ペイロード ----------

    static byte[] buildPayload(String name, int level, boolean charging) {
        byte[] nameBytes = encodeName(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('B');
        out.write('H');
        out.write(1); // version
        out.write(Math.max(0, Math.min(100, level)));
        out.write(charging ? 1 : 0);
        out.write(nameBytes, 0, nameBytes.length);
        return out.toByteArray();
    }

    /** UTF-8で最大 MAX_NAME_BYTES バイト。文字の途中で切らないよう1文字ずつ積む。 */
    private static byte[] encodeName(String name) {
        if (name == null) name = "";
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            int len = String.valueOf(c).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + len > MAX_NAME_BYTES) break;
            sb.append(c);
            bytes += len;
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Manufacturer Data を解釈する。BatteryHubの形式でなければ null。 */
    static Report parsePayload(byte[] data) {
        if (data == null || data.length < 5) return null;
        if (data[0] != 'B' || data[1] != 'H' || data[2] != 1) return null;
        int level = data[3] & 0xFF;
        if (level > 100) return null;
        boolean charging = (data[4] & 1) != 0;
        String name = "BLEデバイス";
        if (data.length > 5) {
            name = new String(data, 5, data.length - 5, StandardCharsets.UTF_8).trim();
            if (name.isEmpty()) name = "BLEデバイス";
        }
        return new Report(name, level, charging);
    }

    static final class Report {
        final String name;
        final int level;
        final boolean charging;

        Report(String name, int level, boolean charging) {
            this.name = name;
            this.level = level;
            this.charging = charging;
        }
    }

    // ---------- クライアント: アドバタイズ送信 ----------

    /** 報知を開始／データを更新する。開始できたら true。 */
    public synchronized boolean startAdvertising(String name, int level, boolean charging) {
        stopAdvertising();
        if (!hasAdvertisePermission(ctx)) {
            Log.w(TAG, "no BLUETOOTH_ADVERTISE permission");
            return false;
        }
        BluetoothAdapter a = adapter();
        if (a == null) return false;
        try {
            advertiser = a.getBluetoothLeAdvertiser();
        } catch (Exception e) {
            advertiser = null;
        }
        if (advertiser == null) {
            Log.w(TAG, "advertiser not available (peripheral unsupported?)");
            return false;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(COMPANY_ID, buildPayload(name, level, charging))
                .build();
        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                advertising = true;
            }

            @Override
            public void onStartFailure(int errorCode) {
                advertising = false;
                advertiseRequested = false;
                Log.w(TAG, "advertise failed: " + errorCode);
            }
        };
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
            advertiseRequested = true;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startAdvertising exception: " + e.getMessage());
            return false;
        }
    }

    public synchronized void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (Exception ignored) {
            }
        }
        advertiseCallback = null;
        advertising = false;
        advertiseRequested = false;
    }

    public boolean isAdvertising() {
        return advertising;
    }

    /** 報知開始を要求済みか（ウォッチドッグ判定用。開始コールバックは非同期のため分離） */
    public boolean isAdvertiseRequested() {
        return advertiseRequested;
    }

    public boolean isScanning() {
        return scanning;
    }

    // ---------- ホスト: スキャン受信 ----------

    public synchronized boolean startScanning() {
        if (scanning) return true;
        if (!hasScanPermission(ctx)) {
            Log.w(TAG, "no scan permission");
            return false;
        }
        BluetoothAdapter a = adapter();
        if (a == null) return false;
        try {
            scanner = a.getBluetoothLeScanner();
        } catch (Exception e) {
            scanner = null;
        }
        if (scanner == null) return false;
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleResult(result);
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                Log.w(TAG, "scan failed: " + errorCode);
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();
        // v1.4: フィルタ付きスキャンにする。フィルタなしスキャンは画面OFF時に
        // Android側で配信が止まるため、バックグラウンド受信にはフィルタが必須。
        // 先頭2バイト（マジック 'B','H'）だけをマスクで照合する。
        List<ScanFilter> filters = Collections.singletonList(
                new ScanFilter.Builder()
                        .setManufacturerData(COMPANY_ID,
                                new byte[]{'B', 'H'},
                                new byte[]{(byte) 0xFF, (byte) 0xFF})
                        .build());
        try {
            scanner.startScan(filters, settings, scanCallback);
            scanning = true;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "filtered startScan exception, fallback to unfiltered: " + e.getMessage());
        }
        try {
            // オフロードフィルタ非対応端末向けのフォールバック（画面OFF時は止まり得る）
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startScan exception: " + e.getMessage());
            return false;
        }
    }

    private void handleResult(ScanResult result) {
        if (result == null) return;
        ScanRecord rec = result.getScanRecord();
        if (rec == null) return;
        byte[] data = rec.getManufacturerSpecificData(COMPANY_ID);
        Report r = parsePayload(data);
        if (r != null && listener != null) {
            listener.onBleReport(r.name, r.level, r.charging);
        }
    }

    public synchronized void stopScanning() {
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanCallback = null;
        scanning = false;
    }

    public void stopAll() {
        stopAdvertising();
        stopScanning();
    }
}
