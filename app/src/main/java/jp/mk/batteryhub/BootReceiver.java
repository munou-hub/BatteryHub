package jp.mk.batteryhub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 端末起動時に、サービスが有効だった場合のみ自動で再開する。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Prefs prefs = new Prefs(context);
        if (prefs.isServiceEnabled()) {
            Intent svc = new Intent(context, BatteryService.class);
            context.startForegroundService(svc);
        }
    }
}
