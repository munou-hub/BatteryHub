package jp.mk.batteryhub;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private Prefs prefs;
    private RadioGroup roleGroup;
    private RadioButton rbHost;
    private EditText etDeviceName;
    private EditText etHostIp;
    private TextView tvOwnIp;
    private TextView tvStatus;
    private LinearLayout deviceList;
    private Switch swBle;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refreshDevices();
            uiHandler.postDelayed(this, 2000);
        }
    };

    private String statusNote = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        roleGroup = findViewById(R.id.roleGroup);
        rbHost = findViewById(R.id.rbHost);
        etDeviceName = findViewById(R.id.etDeviceName);
        etHostIp = findViewById(R.id.etHostIp);
        tvOwnIp = findViewById(R.id.tvOwnIp);
        tvStatus = findViewById(R.id.tvStatus);
        deviceList = findViewById(R.id.deviceList);
        swBle = findViewById(R.id.swBle);
        Button btnDiscover = findViewById(R.id.btnDiscover);
        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnBatteryOpt = findViewById(R.id.btnBatteryOpt);

        // 保存済み設定を反映
        rbHost.setChecked(prefs.isHost());
        if (!prefs.isHost()) {
            ((RadioButton) findViewById(R.id.rbClient)).setChecked(true);
        }
        etDeviceName.setText(prefs.getDeviceName());
        etHostIp.setText(prefs.getHostIp());
        swBle.setChecked(prefs.isBleEnabled());
        swBle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setBleEnabled(isChecked);
            statusNote = isChecked ? "BLEを有効にしました" : "BLEを無効にしました";
            if (prefs.isServiceEnabled()) {
                // 実行中サービスへ反映するため再起動する
                startForegroundService(new Intent(this, BatteryService.class));
            }
            refreshStatus();
        });

        String ownIp = Net.getLocalIpv4();
        tvOwnIp.setText(ownIp != null
                ? ("この端末のIP: " + ownIp + "（ホスト時はこれをクライアントへ）")
                : "この端末のIP: 取得できませんでした（Wi-Fi未接続？）");

        btnDiscover.setOnClickListener(v -> {
            saveFromUi(null);
            statusNote = "ホストを探索中…";
            refreshStatus();
            new Thread(() -> {
                Discovery.Result r = Discovery.discoverHost(this, 3000);
                runOnUiThread(() -> {
                    if (r != null) {
                        etHostIp.setText(r.ip);
                        saveFromUi(null);
                        statusNote = "ホストを発見: " + r.hostName + " (" + r.ip + ")";
                    } else {
                        statusNote = "ホストが見つかりませんでした。ホスト側でサービス開始済みか確認してください";
                    }
                    refreshStatus();
                });
            }, "bh-discover-ui").start();
        });

        btnStart.setOnClickListener(v -> {
            requestNotificationPermission();
            requestBlePermissions();
            saveFromUi(Boolean.TRUE);
            startForegroundService(new Intent(this, BatteryService.class));
            statusNote = "サービスを開始しました";
            refreshStatus();
            Toast.makeText(this, "BatteryHub サービス開始", Toast.LENGTH_SHORT).show();
            // v1.4: 未除外ならその場で電池最適化の除外を要求する。
            // 除外しないと Doze でフォアグラウンドサービスごと止められ得る。
            if (!isBatteryOptimizationExempted()) {
                requestBatteryOptimizationExemption();
            }
        });

        btnStop.setOnClickListener(v -> {
            saveFromUi(Boolean.FALSE);
            stopService(new Intent(this, BatteryService.class));
            statusNote = "サービスを停止しました";
            refreshStatus();
        });

        btnBatteryOpt.setOnClickListener(v -> requestBatteryOptimizationExemption());

        Button btnUpdateCheck = findViewById(R.id.btnUpdateCheck);
        btnUpdateCheck.setOnClickListener(v -> checkForUpdate(true));

        refreshStatus();

        // 起動時の自動アップデート確認（6時間に1回まで）
        long lastCheck = prefs.getLastUpdateCheckMs();
        if (System.currentTimeMillis() - lastCheck > 6 * 60 * 60 * 1000L) {
            checkForUpdate(false);
        }
        // 前回の更新ダウンロードが終わっていたらインストールを促す
        resumePendingInstall();
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.post(refreshTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(refreshTick);
        saveFromUi(null);
    }

    private void saveFromUi(Boolean serviceEnabled) {
        String role = rbHost.isChecked() ? Prefs.ROLE_HOST : Prefs.ROLE_CLIENT;
        prefs.save(role,
                etDeviceName.getText().toString().trim(),
                etHostIp.getText().toString().trim(),
                serviceEnabled);
    }

    /** BLE併用に必要なランタイム許可を要求する（役割とOSバージョンで変わる）。 */
    private void requestBlePermissions() {
        if (!prefs.isBleEnabled() && swBle != null && !swBle.isChecked()) return;
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: 役割によらず両方要求しておく（役割切替時にも使えるように）
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            // Android 11以下: BLEスキャンには位置情報許可が必要
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 2);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private boolean isBatteryOptimizationExempted() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryOptimizationExemption() {
        if (isBatteryOptimizationExempted()) {
            Toast.makeText(this, "既に電池最適化から除外されています", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    // ---------- アップデート確認（v1.6〜） ----------

    private void checkForUpdate(boolean manual) {
        if (manual) {
            Toast.makeText(this, "アップデートを確認中…", Toast.LENGTH_SHORT).show();
        }
        prefs.setLastUpdateCheckMs(System.currentTimeMillis());
        new Thread(() -> {
            UpdateChecker.Info info = UpdateChecker.check(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (info != null) {
                    showUpdateDialog(info);
                } else if (manual) {
                    String msg = UpdateChecker.lastError.isEmpty()
                            ? "最新版です（v" + UpdateChecker.installedVersionName(this) + "）"
                            : "確認できませんでした: " + UpdateChecker.lastError;
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }, "bh-update-check").start();
    }

    private void showUpdateDialog(UpdateChecker.Info info) {
        StringBuilder msg = new StringBuilder();
        msg.append("新しいバージョン v").append(info.versionName).append(" があります\n");
        msg.append("現在: v").append(UpdateChecker.installedVersionName(this));
        if (info.notes != null && !info.notes.isEmpty()) {
            msg.append("\n\n").append(info.notes);
        }
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("アップデートがあります")
                .setMessage(msg.toString())
                .setNegativeButton("あとで", null);
        if (info.apkUrl != null) {
            b.setPositiveButton("ダウンロードして更新", (d, w) -> downloadUpdate(info));
            b.setNeutralButton("GitHubを開く", (d, w) -> openUrl(info.pageUrl));
        } else {
            b.setPositiveButton("GitHubで開く", (d, w) -> openUrl(info.pageUrl));
        }
        b.show();
    }

    private void openUrl(String url) {
        if (url == null || url.isEmpty()) url = UpdateChecker.RELEASES_PAGE_URL;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadUpdate(UpdateChecker.Info info) {
        // 提供元不明アプリのインストール許可が無い場合は設定へ誘導する
        if (Build.VERSION.SDK_INT >= 26
                && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "このアプリからのインストールを許可してください",
                    Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        }
        android.app.DownloadManager dm = getSystemService(android.app.DownloadManager.class);
        if (dm == null) {
            openUrl(info.pageUrl);
            return;
        }
        // 既存のダウンロード済みファイルは消してから取り直す
        java.io.File old = UpdateProvider.apkFile(this);
        if (old.exists()) old.delete();
        android.app.DownloadManager.Request req =
                new android.app.DownloadManager.Request(Uri.parse(info.apkUrl));
        req.setTitle("BatteryHub アップデート");
        req.setDescription("v" + info.versionName + " をダウンロード中");
        req.setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalFilesDir(this,
                android.os.Environment.DIRECTORY_DOWNLOADS, UpdateProvider.APK_FILENAME);
        long id = dm.enqueue(req);
        prefs.setPendingDownloadId(id);
        Toast.makeText(this, "ダウンロードを開始しました", Toast.LENGTH_SHORT).show();
        pollDownload(id);
    }

    /** ダウンロード完了をポーリングし、終わったらインストール画面を出す。 */
    private void pollDownload(long downloadId) {
        new Thread(() -> {
            android.app.DownloadManager dm = getSystemService(android.app.DownloadManager.class);
            if (dm == null) return;
            for (int i = 0; i < 200; i++) {
                int status = queryDownloadStatus(dm, downloadId);
                if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                    prefs.setPendingDownloadId(-1);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) installUpdate();
                    });
                    return;
                }
                if (status == android.app.DownloadManager.STATUS_FAILED) {
                    prefs.setPendingDownloadId(-1);
                    runOnUiThread(() -> Toast.makeText(this,
                            "ダウンロードに失敗しました", Toast.LENGTH_LONG).show());
                    return;
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "bh-update-dl").start();
    }

    private int queryDownloadStatus(android.app.DownloadManager dm, long id) {
        android.database.Cursor c = null;
        try {
            c = dm.query(new android.app.DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                return c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private void resumePendingInstall() {
        long id = prefs.getPendingDownloadId();
        if (id < 0) return;
        android.app.DownloadManager dm = getSystemService(android.app.DownloadManager.class);
        if (dm == null) return;
        int status = queryDownloadStatus(dm, id);
        if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
            prefs.setPendingDownloadId(-1);
            installUpdate();
        } else if (status == android.app.DownloadManager.STATUS_RUNNING
                || status == android.app.DownloadManager.STATUS_PENDING
                || status == android.app.DownloadManager.STATUS_PAUSED) {
            pollDownload(id);
        } else {
            prefs.setPendingDownloadId(-1);
        }
    }

    private void installUpdate() {
        java.io.File f = UpdateProvider.apkFile(this);
        if (!f.exists()) {
            Toast.makeText(this, "更新ファイルが見つかりません", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(UpdateProvider.apkUri(getPackageName()),
                "application/vnd.android.package-archive");
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "インストーラを開けませんでした", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        String role = prefs.isHost() ? "ホスト" : "クライアント";
        String state = prefs.isServiceEnabled() ? "● 実行中" : "○ 停止中";
        String extra = statusNote.isEmpty() ? "" : ("\n" + statusNote);
        String bleState = prefs.isBleEnabled() ? "BLE併用: ON" : "BLE併用: OFF";
        String optState = isBatteryOptimizationExempted()
                ? "電池最適化: 除外済み（バックグラウンド継続可）"
                : "電池最適化: 未除外（止まる可能性あり）";
        tvStatus.setText("サービス: " + state + "（" + role + "）"
                + "\n警告条件: 15%未満で通知、20%以上で再アーム"
                + "\n送信: 変化時（15秒スロットル）＋30秒ハートビート＋ACK再送"
                + "\n" + bleState + "（近距離のみ、Wi-Fiと並行）"
                + "\n" + optState
                + extra);
    }

    private void refreshDevices() {
        refreshStatus();
        deviceList.removeAllViews();
        if (!prefs.isHost()) {
            deviceList.addView(noteText("（クライアントモードです。一覧はホスト側で表示されます）"));
            return;
        }
        List<HostState.DeviceInfo> list = HostState.get().snapshot();
        if (list.isEmpty()) {
            deviceList.addView(noteText("まだ報告がありません"));
            return;
        }
        long now = System.currentTimeMillis();
        for (HostState.DeviceInfo d : list) {
            deviceList.addView(buildDeviceRow(d, now));
        }
    }

    private TextView noteText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(0xFFB9BEC6);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        return t;
    }

    /** 一覧の1行: カード型。左にリングゲージ（暗背景パレット）、右に名前と状態。 */
    private View buildDeviceRow(HostState.DeviceInfo d, long now) {
        boolean stale = now - d.lastSeenMs > BatteryService.STALE_MS;
        long agoSec = Math.max(0, (now - d.lastSeenMs) / 1000);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(10);
        row.setLayoutParams(rp);

        ImageView iv = new ImageView(this);
        int sz = dp(60);
        iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        // アプリ内はダークテーマ固定なので暗背景パレットで描く
        iv.setImageBitmap(RingGauge.render(d.level, stale, true));
        row.addView(iv);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(14);
        texts.setLayoutParams(tp);

        TextView name = new TextView(this);
        name.setTextSize(16);
        name.setTextColor(0xFFF2F3F5);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setText(d.name);
        texts.addView(name);

        TextView sub = new TextView(this);
        sub.setTextSize(13);
        sub.setTextColor(0xFFB9BEC6);
        StringBuilder s = new StringBuilder();
        s.append(d.level).append('%');
        if (d.charging) s.append(" ・ 充電中");
        if (stale) s.append(" ・ 応答なし");
        s.append(" ・ ").append(agoSec).append("秒前");
        sub.setText(s.toString());
        texts.addView(sub);

        row.addView(texts);
        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
