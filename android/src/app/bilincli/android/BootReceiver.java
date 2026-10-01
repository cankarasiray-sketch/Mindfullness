package app.bilincli.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Telefon yeniden başlayınca, uygulama güncellenince ya da saat değişince alarmı yeniden kurar. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Scheduler.ensureScheduled(ctx);
    }
}
