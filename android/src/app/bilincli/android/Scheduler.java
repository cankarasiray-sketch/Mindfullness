package app.bilincli.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import app.bilincli.core.Fmt;
import app.bilincli.core.Settings;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Zamanlama: her gün ayarlanan saatte (varsayılan 06:00 TR) tam zamanlı alarm,
 * alarm gelince ağ bağlantısı şartlı hızlandırılmış iş. Ayrıca 3 saatte bir
 * periyodik iş: biten maçları sonuçlandırır, kaçırılan günlük çalışmayı tamamlar.
 */
final class Scheduler {
    static final int JOB_DAILY = 1;
    static final int JOB_PERIODIC = 2;
    static final String EXTRA_DAILY = "daily";

    private Scheduler() {}

    static Instant nextRun(Settings s, Instant now) {
        OffsetDateTime local = now.atOffset(Fmt.TR);
        OffsetDateTime t = local.withHour(s.runHour).withMinute(s.runMinute).withSecond(0).withNano(0);
        if (!t.isAfter(local)) t = t.plusDays(1);
        return t.toInstant();
    }

    static boolean canExact(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    static Instant ensureScheduled(Context ctx) {
        Settings s = Repo.get(ctx).real().settings();
        Instant next = nextRun(s, Instant.now());
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        Intent i = new Intent(ctx, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long at = next.toEpochMilli();
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(new JobInfo.Builder(JOB_PERIODIC, new ComponentName(ctx, DailyJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(3 * 60 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        }
        return next;
    }

    /** Günlük kararı şimdi (ağ varsa hemen) üret. */
    static void runDailyNow(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean(EXTRA_DAILY, true);
        JobInfo.Builder b = new JobInfo.Builder(JOB_DAILY, new ComponentName(ctx, DailyJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (js.schedule(b.setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS) return;
            } catch (RuntimeException ignored) {
                // hızlandırılmış iş kotası dolmuş olabilir; normal işe düş
            }
            b.setExpedited(false);
        }
        js.schedule(b.build());
    }
}
