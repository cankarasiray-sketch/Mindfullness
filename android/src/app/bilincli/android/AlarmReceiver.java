package app.bilincli.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Günlük alarm: kararı üretecek işi başlatır ve ertesi günün alarmını kurar. */
public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Scheduler.runDailyNow(ctx);
        Scheduler.ensureScheduled(ctx);
    }
}
