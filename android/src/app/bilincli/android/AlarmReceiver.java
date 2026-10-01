package app.bilincli.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarmlar: günlük karar (ertesi günün alarmını da kurar) ya da maç öncesi olaylar. */
public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Scheduler.ACTION_EVENT.equals(intent.getAction())) {
            Scheduler.runEventNow(ctx); // maç öncesi kontrol / kapanış oranı
            return;
        }
        Scheduler.runDailyNow(ctx);
        Scheduler.ensureScheduled(ctx);
    }
}
