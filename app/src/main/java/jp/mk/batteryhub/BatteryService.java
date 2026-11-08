package jp.mk.batteryhub;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 中核のフォアグラウンドサービス。
 * - クライアントモード: 自端末の電池残量をホストへUDP送信する。
 *   送信タイミングは「残量変化時（15秒スロットル）」＋「60秒ハートビート」。
 * - ホストモード: UDPでレポートを受信し、デバイス一覧を常駐通知に出す。
 *   常駐通知はリングゲージ表示（RemoteViews＋生成ビットマップ、最大5台＋残りはテキスト）。
 *   15%未満のデバイスがあれば警告通知（20%以上に回復するまで再通知しない）。
 *   ホスト自身の残量は一覧に含めない（v1.5〜）。
 */
public class BatteryService extends Service {

    private static final String TAG = "BatteryHubService";
    public static final String ACTION_STATE_CHANGED = "jp.mk.batteryhub.STATE_CHANGED";

    private static final int NOTIF_ID_ONGOING = 1001;
    private static final int NOTIF_ID_ALERT_BASE = 2000;
    /** 常駐通知にリングで表示する最大台数（残りはテキスト併記） */
    private static final int MAX_RING_DEVICES = 5;

    /** 警告を出す閾値（これ未満で警告） */
    static final int ALERT_THRESHOLD = 15;
    /** 再アーム閾値（これ以上に回復したら次回また警告できる） */
    static final int ALERT_REARM = 20;

    private static final long SEND_THROTTLE_MS = 15_000;
    private static final long HEARTBEAT_SEC = 30;
    /** ACKがこの時間なければ自動で再発見を試みる */
    private static final long REDISCOVERY_INTERVAL_MS = 120_000;
    /** この時間レポートが無いデバイスは「応答なし」表示 */
    static final long STALE_MS = 180_000;

    private Prefs prefs;
    private boolean hostMode;
    private volatile boolean running;

    private DatagramSocket reportSocket;
    private DatagramSocket discoverySocket;
    private Thread reportThread;
    private Thread discoveryThread;
    private ScheduledExecutorService scheduler;
    private BroadcastReceiver batteryReceiver;
    private volatile long lastSendMs = 0;
    private volatile int ownLevel = -1;
    private volatile boolean ownCharging = false;

    // BLE併用（v1.3〜）
    private BleTransport ble;
    private volatile int lastBleLevel = -1;
    private volatile boolean lastBleCharging = false;
    private volatile boolean bleActive = false;

    // 同期の堅牢化（v1.5〜）
    private volatile long lastAckMs = 0;
    private volatile long lastDiscoveryMs = 0;
    private volatile boolean deferredSendPending = false;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;
    private BroadcastReceiver btStateReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stopMode();
        hostMode = prefs.isHost();
        running = true;

        startForegroundWith(buildOngoingNotification());

        registerBatteryReceiver();
        scheduler = Executors.newSingleThreadScheduledExecutor();

        if (hostMode) {
            HostState.get().clear();
            startReportListener();
            startDiscoveryResponder();
            // v1.5: ホスト自身の残量は一覧・通知に入れない（MK指示）。
            // 自端末の電池監視自体もホストでは行わない。
            scheduler.scheduleAtFixedRate(this::hostPeriodic,
                    HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
        } else {
            scheduler.scheduleAtFixedRate(this::sendReportNow,
                    0, HEARTBEAT_SEC, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(this::clientPeriodic,
                    HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
        }
        acquireLocks();
        registerBtStateReceiver();
        startBle();
        broadcastStateChanged();
        return START_STICKY;
    }

    /** ホストの定期処理: 通知更新＋BLEスキャンのウォッチドッグ。 */
    private void hostPeriodic() {
        refreshHostNotification();
        if (prefs.isBleEnabled() && ble != null && !ble.isScanning()) {
            bleActive = ble.startScanning();
            Log.i(TAG, "BLE scan watchdog restart, active=" + bleActive);
        }
    }

    /** クライアントの定期処理: BLEアドバタイズのウォッチドッグ。 */
    private void clientPeriodic() {
        if (prefs.isBleEnabled() && ble != null && !ble.isAdvertiseRequested()) {
            updateBleAdvertising(true);
            Log.i(TAG, "BLE advertise watchdog restart, active=" + bleActive);
        }
    }

    // ---------- バックグラウンド維持のロック ----------

    /**
     * v1.5: Wi-Fiロックとマルチキャストロックを取得する。
     * 画面OFFやWi-Fi省電力で受信が落ちるのを防ぐ。電池は食うので常駐中のみ保持する。
     */
    private void acquireLocks() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return;
            if (wifiLock == null) {
                int mode = Build.VERSION.SDK_INT >= 27
                        ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : android.net.wifi.WifiManager.WIFI_MODE_FULL;
                wifiLock = wm.createWifiLock(mode, "BatteryHub:fg");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
            if (multicastLock == null) {
                multicastLock = wm.createMulticastLock("BatteryHub:mc");
                multicastLock.setReferenceCounted(false);
            }
            if (!multicastLock.isHeld()) multicastLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "acquireLocks failed: " + e.getMessage());
        }
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {
        }
    }

    /** BluetoothのON/OFFに追随する。ONに戻ったらBLEを自動で再開する。 */
    private void registerBtStateReceiver() {
        if (btStateReceiver != null) return;
        btStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                        android.bluetooth.BluetoothAdapter.ERROR);
                if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "Bluetooth ON, restarting BLE");
                    startBle();
                } else if (state == android.bluetooth.BluetoothAdapter.STATE_OFF) {
                    bleActive = false;
                }
            }
        };
        registerReceiver(btStateReceiver, new IntentFilter(
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    @Override
    public void onDestroy() {
        running = false;
        stopMode();
        super.onDestroy();
    }

    /**
     * v1.4: 最近使ったアプリからスワイプで消された場合の保険。
     * 有効設定のままなら AlarmManager 経由でサービスの再起動を試みる
     * （OSのバックグラウンド起動制限で弾かれる場合もある。その場合は START_STICKY と
     * 再起動時の自動開始が最終手段になる）。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (prefs != null && prefs.isServiceEnabled()) {
            try {
                Intent restart = new Intent(this, BatteryService.class);
                android.app.PendingIntent pi;
                if (Build.VERSION.SDK_INT >= 26) {
                    pi = android.app.PendingIntent.getForegroundService(this, 9001, restart,
                            android.app.PendingIntent.FLAG_ONE_SHOT
                                    | android.app.PendingIntent.FLAG_IMMUTABLE);
                } else {
                    pi = android.app.PendingIntent.getService(this, 9001, restart,
                            android.app.PendingIntent.FLAG_ONE_SHOT
                                    | android.app.PendingIntent.FLAG_IMMUTABLE);
                }
                android.app.AlarmManager am =
                        (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
                if (am != null) {
                    am.set(android.app.AlarmManager.ELAPSED_REALTIME,
                            android.os.SystemClock.elapsedRealtime() + 1000, pi);
                }
            } catch (Exception e) {
                Log.w(TAG, "onTaskRemoved restart scheduling failed: " + e.getMessage());
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 共通 ----------

    private void stopMode() {
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {
            }
            batteryReceiver = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (reportSocket != null) {
            reportSocket.close();
            reportSocket = null;
        }
        if (discoverySocket != null) {
            discoverySocket.close();
            discoverySocket = null;
        }
        reportThread = null;
        discoveryThread = null;
        releaseLocks();
        if (btStateReceiver != null) {
            try {
                unregisterReceiver(btStateReceiver);
            } catch (Exception ignored) {
            }
            btStateReceiver = null;
        }
        if (ble != null) {
            ble.stopAll();
            ble = null;
        }
        bleActive = false;
        lastBleLevel = -1;
    }

    // ---------- BLE併用 ----------

    /** BLEを開始する。ホスト=スキャン受信、クライアント=アドバタイズ送信。失敗してもWi-Fiは継続する。 */
    private void startBle() {
        if (!prefs.isBleEnabled()) {
            bleActive = false;
            return;
        }
        if (ble == null) {
            ble = new BleTransport(this, (name, level, charging) -> {
                if (hostMode && running) {
                    applyReport(name, level, charging);
                }
            });
        }
        if (hostMode) {
            bleActive = ble.startScanning();
        } else {
            readOwnBattery();
            updateBleAdvertising(true);
        }
        Log.i(TAG, "BLE active=" + bleActive + " host=" + hostMode);
    }

    /** クライアント用: 残量・充電状態が変わったときだけ報知データを更新する。 */
    private void updateBleAdvertising(boolean force) {
        if (ble == null || hostMode || !prefs.isBleEnabled()) return;
        if (ownLevel < 0) return;
        if (!force && ownLevel == lastBleLevel && ownCharging == lastBleCharging
                && ble.isAdvertiseRequested()) return;
        bleActive = ble.startAdvertising(prefs.getDeviceName(), ownLevel, ownCharging);
        if (bleActive) {
            lastBleLevel = ownLevel;
            lastBleCharging = ownCharging;
        }
    }

    public boolean isBleActive() {
        return bleActive;
    }

    private void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (level < 0 || scale <= 0) return;
                ownLevel = Math.round(level * 100f / scale);
                ownCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                if (!hostMode) {
                    long now = System.currentTimeMillis();
                    long elapsed = now - lastSendMs;
                    if (elapsed >= SEND_THROTTLE_MS) {
                        sendReportNow();
                    } else {
                        // v1.5: スロットル中でも変化を捨てず、期限が来たら送る
                        scheduleDeferredSend(SEND_THROTTLE_MS - elapsed);
                    }
                }
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void readOwnBattery() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level >= 0 && scale > 0) {
            ownLevel = Math.round(level * 100f / scale);
            ownCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        }
    }

    private void startForegroundWith(Notification n) {
        // 種別引数付き startForeground は API 29+、dataSync 種別は API 34+。
        // minSdk 26〜33 では種別なし版を使う（manifest の foregroundServiceType は
        // 旧OSでは無視されるため問題ない）。
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID_ONGOING, n);
        }
    }

    private void broadcastStateChanged() {
        Intent i = new Intent(ACTION_STATE_CHANGED);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    // ---------- クライアント ----------

    private void sendReportNow() {
        if (hostMode || !running) return;
        readOwnBattery();
        if (ownLevel < 0) return;
        updateBleAdvertising(false);
        lastSendMs = System.currentTimeMillis();
        new Thread(this::sendWithAckAndRecovery, "bh-send").start();
    }

    /** スロットル期限後に1回だけ送る（変化イベントを捨てないため）。 */
    private void scheduleDeferredSend(long delayMs) {
        if (deferredSendPending || scheduler == null) return;
        deferredSendPending = true;
        try {
            scheduler.schedule(() -> {
                deferredSendPending = false;
                sendReportNow();
            }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            deferredSendPending = false;
        }
    }

    /**
     * v1.5: ACK付き送信。ホストからの受領確認を最大3回まで再送して待つ。
     * 全滅したら（IPが変わった等を疑い）自動で再発見し、見つかれば送り直す。
     * ワーカースレッドから呼ぶこと。
     */
    private void sendWithAckAndRecovery() {
        if (trySendWithAck()) return;
        long now = System.currentTimeMillis();
        if (now - lastDiscoveryMs >= REDISCOVERY_INTERVAL_MS) {
            lastDiscoveryMs = now;
            Discovery.Result r = Discovery.discoverHost(this, 2500);
            if (r != null && r.ip != null && !r.ip.isEmpty()) {
                String current = prefs.getHostIp();
                if (!r.ip.equals(current)) {
                    Log.i(TAG, "host IP changed: " + current + " -> " + r.ip);
                    prefs.save(null, null, r.ip, null);
                }
                trySendWithAck();
            }
        }
        refreshOngoingNotification();
    }

    /** レポートを送り、ACKが返れば true。最大3回再送する。ワーカースレッド専用。 */
    private boolean trySendWithAck() {
        String hostIp = prefs.getHostIp();
        if (hostIp == null || hostIp.trim().isEmpty()) {
            refreshOngoingNotification();
            return false;
        }
        String json = Net.buildReportJson(prefs.getDeviceName(), ownLevel, ownCharging);
        for (int attempt = 0; attempt < 3; attempt++) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(700);
                byte[] buf = json.getBytes("UTF-8");
                DatagramPacket p = new DatagramPacket(buf, buf.length,
                        InetAddress.getByName(hostIp.trim()), Net.PORT_REPORT);
                socket.send(p);
                byte[] rbuf = new byte[512];
                DatagramPacket rp = new DatagramPacket(rbuf, rbuf.length);
                socket.receive(rp);
                JSONObject o = Net.parseJson(rp.getData(), rp.getLength());
                if (o != null && "ack".equals(o.optString("type"))) {
                    lastAckMs = System.currentTimeMillis();
                    refreshOngoingNotification();
                    return true;
                }
            } catch (Exception e) {
                // タイムアウト等は再送する
            }
        }
        return false;
    }

    // ---------- ホスト ----------

    private void startReportListener() {
        reportThread = new Thread(() -> {
            try {
                reportSocket = new DatagramSocket(Net.PORT_REPORT);
                byte[] buf = new byte[2048];
                while (running && reportSocket != null && !reportSocket.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    reportSocket.receive(p);
                    JSONObject o = Net.parseJson(p.getData(), p.getLength());
                    if (o == null) continue;
                    if (!"report".equals(o.optString("type"))) continue;
                    String name = o.optString("name", "unknown");
                    int level = o.optInt("level", -1);
                    boolean charging = o.optBoolean("charging", false);
                    if (level < 0) continue;
                    applyReport(name, level, charging);
                    // v1.5: 受領確認（ACK）を返す。クライアントはACKが無ければ再送・再発見する
                    try {
                        byte[] ack = Net.buildAckJson().getBytes("UTF-8");
                        reportSocket.send(new DatagramPacket(ack, ack.length,
                                p.getAddress(), p.getPort()));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "report listener stopped: " + e.getMessage());
            }
        }, "bh-report-listener");
        reportThread.start();
    }

    private void startDiscoveryResponder() {
        discoveryThread = new Thread(() -> {
            try {
                discoverySocket = new DatagramSocket(Net.PORT_DISCOVERY);
                byte[] buf = new byte[1024];
                while (running && discoverySocket != null && !discoverySocket.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    discoverySocket.receive(p);
                    JSONObject o = Net.parseJson(p.getData(), p.getLength());
                    if (o == null) continue;
                    if (!"discover".equals(o.optString("type"))) continue;
                    String reply = Net.buildHostAnnounceJson(prefs.getDeviceName());
                    byte[] rb = reply.getBytes("UTF-8");
                    DatagramPacket rp = new DatagramPacket(rb, rb.length,
                            p.getAddress(), p.getPort());
                    discoverySocket.send(rp);
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "discovery responder stopped: " + e.getMessage());
            }
        }, "bh-discovery-responder");
        discoveryThread.start();
    }

    /** レポートを状態へ反映し、警告判定と通知更新を行う。 */
    private void applyReport(String name, int level, boolean charging) {
        if (level < 0) return;
        HostState.get().update(name, level, charging);
        checkAlert(name, level);
        refreshHostNotification();
        broadcastStateChanged();
    }

    private void checkAlert(String name, int level) {
        HostState.DeviceInfo d = HostState.get().getDevice(name);
        if (d == null) return;
        if (level < ALERT_THRESHOLD && !d.alerted) {
            d.alerted = true;
            showAlertNotification(name, level);
        } else if (level >= ALERT_REARM && d.alerted) {
            d.alerted = false;
        }
    }

    private void showAlertNotification(String name, int level) {
        Notification n = new Notification.Builder(this, BatteryHubApp.CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_battery)
                .setContentTitle("バッテリー低下: " + name)
                .setContentText(name + " の電池が " + level + "% です")
                .setContentIntent(mainActivityIntent())
                .setAutoCancel(true)
                .build();
        getSystemService(android.app.NotificationManager.class)
                .notify(NOTIF_ID_ALERT_BASE + Math.abs(name.hashCode() % 1000), n);
    }

    // ---------- 通知 ----------

    private PendingIntent mainActivityIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildOngoingNotification() {
        Notification.Builder b = new Notification.Builder(this, BatteryHubApp.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_battery)
                .setContentIntent(mainActivityIntent())
                .setOngoing(true);
        if (hostMode) {
            List<HostState.DeviceInfo> list = HostState.get().snapshot();
            b.setContentTitle("BatteryHub（ホスト）");
            if (list.isEmpty()) {
                b.setContentText("デバイスからの報告を待っています");
            } else {
                // フォールバック用の一行サマリ（カスタムビューが使えない表示先向け）
                StringBuilder sb = new StringBuilder();
                for (HostState.DeviceInfo d : list) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(d.name).append(' ').append(d.level).append('%');
                }
                b.setContentText(sb.toString());
            }
            RemoteViews rv = buildHostRemoteViews(list);
            b.setCustomContentView(rv);
            b.setCustomBigContentView(rv);
            b.setStyle(new Notification.DecoratedCustomViewStyle());
        } else {
            b.setContentTitle("BatteryHub（クライアント）");
            String ip = prefs.getHostIp();
            String dest = (ip == null || ip.isEmpty()) ? "ホストIP未設定" : ("送信先: " + ip);
            String own = ownLevel >= 0 ? (" / 自分: " + ownLevel + "%") : "";
            String bleText = prefs.isBleEnabled() ? (bleActive ? " / BLE送信中" : " / BLE不可") : "";
            String syncText;
            if (lastAckMs > 0) {
                long ago = Math.max(0, (System.currentTimeMillis() - lastAckMs) / 1000);
                syncText = " / 最終同期: " + ago + "秒前";
            } else {
                syncText = (ip == null || ip.isEmpty()) ? "" : " / 未同期";
            }
            b.setContentText(dest + own + bleText + syncText);
        }
        return b.build();
    }

    /** ホスト常駐通知のカスタムビュー（リング横並び最大5台＋残りはテキスト）。 */
    private RemoteViews buildHostRemoteViews(List<HostState.DeviceInfo> list) {
        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.notification_host);
        long now = System.currentTimeMillis();

        // v1.2: 通知シェードの明暗に合わせて文字色とリング配色を切り替える。
        // ?attr 参照は RemoteViews では端末テーマ側で暗色に解釈され、
        // ダーク背景で文字がほぼ見えなかった（v1.1 実機報告）。
        boolean darkBg = RingGauge.isDarkBackground(this);
        int colorPrimary = darkBg ? 0xFFFFFFFF : 0xFF212121;
        int colorSecondary = darkBg ? 0xFFD6D6D6 : 0xFF4E4E4E;
        rv.setTextColor(R.id.notifMessage, colorPrimary);
        rv.setTextColor(R.id.overflowText, colorPrimary);

        if (list.isEmpty()) {
            rv.setTextViewText(R.id.notifMessage, "デバイスからの報告を待っています");
            rv.setViewVisibility(R.id.notifMessage, View.VISIBLE);
            rv.setViewVisibility(R.id.ringRow, View.GONE);
            rv.setViewVisibility(R.id.overflowText, View.GONE);
            return rv;
        }

        rv.setViewVisibility(R.id.notifMessage, View.GONE);
        rv.setViewVisibility(R.id.ringRow, View.VISIBLE);
        rv.removeAllViews(R.id.ringRow);

        int shown = Math.min(list.size(), MAX_RING_DEVICES);
        for (int i = 0; i < shown; i++) {
            HostState.DeviceInfo d = list.get(i);
            boolean stale = now - d.lastSeenMs > STALE_MS;
            RemoteViews item = new RemoteViews(getPackageName(), R.layout.notif_ring_item);
            item.setImageViewBitmap(R.id.ringIcon, RingGauge.render(d.level, stale, darkBg));
            item.setTextViewText(R.id.ringName, d.name);
            item.setTextColor(R.id.ringName, colorPrimary);
            String sub = stale ? "応答なし" : (d.charging ? "充電中" : " ");
            item.setTextViewText(R.id.ringSub, sub);
            item.setTextColor(R.id.ringSub, colorSecondary);
            rv.addView(R.id.ringRow, item);
        }

        // 6台目以降はテキスト行で併記する
        StringBuilder overflow = new StringBuilder();
        for (int i = shown; i < list.size(); i++) {
            if (overflow.length() > 0) overflow.append('\n');
            overflow.append(formatDeviceLine(list.get(i), now));
        }
        if (overflow.length() > 0) {
            rv.setTextViewText(R.id.overflowText, overflow.toString());
            rv.setViewVisibility(R.id.overflowText, View.VISIBLE);
        } else {
            rv.setViewVisibility(R.id.overflowText, View.GONE);
        }
        return rv;
    }

    static String formatDeviceLine(HostState.DeviceInfo d, long nowMs) {
        StringBuilder s = new StringBuilder();
        s.append(d.name).append(": ").append(d.level).append('%');
        if (d.charging) s.append("（充電中）");
        if (nowMs - d.lastSeenMs > STALE_MS) s.append("（応答なし）");
        return s.toString();
    }

    private void refreshOngoingNotification() {
        if (!running) return;
        getSystemService(android.app.NotificationManager.class)
                .notify(NOTIF_ID_ONGOING, buildOngoingNotification());
    }

    private void refreshHostNotification() {
        if (hostMode) refreshOngoingNotification();
    }
}
