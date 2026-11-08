package jp.mk.batteryhub;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class BatteryHubApp extends Application {

    public static final String CHANNEL_STATUS = "status";
    public static final String CHANNEL_ALERT = "alert";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel status = new NotificationChannel(
                CHANNEL_STATUS, "常駐ステータス", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("各デバイスの電池残量を表示する常駐通知");
        nm.createNotificationChannel(status);

        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT, "バッテリー低下警告", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("いずれかのデバイスが15%を切ったときの警告");
        nm.createNotificationChannel(alert);
    }
}
