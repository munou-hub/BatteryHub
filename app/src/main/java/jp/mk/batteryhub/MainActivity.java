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
        });

        btnStop.setOnClickListener(v -> {
            saveFromUi(Boolean.FALSE);
            stopService(new Intent(this, BatteryService.class));
            statusNote = "サービスを停止しました";
            refreshStatus();
        });

        btnBatteryOpt.setOnClickListener(v -> requestBatteryOptimizationExemption());

        refreshStatus();
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

    private void requestBatteryOptimizationExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "既に電池最適化から除外されています", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void refreshStatus() {
        String role = prefs.isHost() ? "ホスト" : "クライアント";
        String state = prefs.isServiceEnabled() ? "● 実行中" : "○ 停止中";
        String extra = statusNote.isEmpty() ? "" : ("\n" + statusNote);
        String bleState = prefs.isBleEnabled() ? "BLE併用: ON" : "BLE併用: OFF";
        tvStatus.setText("サービス: " + state + "（" + role + "）"
                + "\n警告条件: 15%未満で通知、20%以上で再アーム"
                + "\n送信: 変化時（15秒スロットル）＋60秒ハートビート"
                + "\n" + bleState + "（近距離のみ、Wi-Fiと並行）"
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
