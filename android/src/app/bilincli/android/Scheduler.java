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
import app.bilincli.core.ScanPlan;
import app.bilincli.core.Settings;
import app.bilincli.core.Zirve;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Zamanlama: her gün ayarlanan saatte (varsayılan 06:00 TR) tam zamanlı alarm,
 * alarm gelince ağ bağlantısı şartlı hızlandırılmış iş. Ayrıca 3 saatte bir
 * periyodik iş: biten maçları sonuçlandırır, kaçırılan günlük çalışmayı tamamlar.
 */
final class Scheduler {
    static final int JOB_DAILY = 1;
    static final int JOB_PERIODIC = 2;
    static final int JOB_EVENT = 3;
    static final int JOB_RADAR = 4;
    static final int JOB_ZIRVE = 5;
    static final String EXTRA_ZIRVE = "zirve";
    static final String EXTRA_RADAR = "radar";
    static final String ACTION_RADAR = "app.bilincli.RADAR";
    static final String EXTRA_DAILY = "daily";
    static final String EXTRA_EVENT = "event";
    static final String ACTION_EVENT = "app.bilincli.EVENT";

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
        if (js.getPendingJob(JOB_ZIRVE) == null) {
            // Zirve Oran saatlik (kredi harcamaz): yeni artırılmış oran 3 saatlik işi beklemeden görülür
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(EXTRA_ZIRVE, true);
            js.schedule(new JobInfo.Builder(JOB_ZIRVE, new ComponentName(ctx, DailyJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(Zirve.BACKGROUND_PERIOD_MS)
                    .setPersisted(true)
                    .setExtras(extras)
                    .build());
        }
        scheduleNextEvent(ctx);
        scheduleNextRadar(ctx);
        return next;
    }

    /** Maç öncesi kontrol / kapanış oranı alarmını en yakın olaya kurar. */
    static void scheduleNextEvent(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(ACTION_EVENT);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Instant next = Repo.get(ctx).nextEventTime();
        if (next == null) {
            am.cancel(pi);
            return;
        }
        long at = next.toEpochMilli();
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Bir sonraki radar taraması (ayarlardaki saatlerden en yakını, Türkiye saati). */
    static Instant nextRadar(Settings s, Instant now) {
        return nextRadar(s, now, null);
    }

    /**
     * adaptive: bugünün maç saatlerine göre tarama saatleri (ScanPlan). Bugün için kalan varsa o,
     * yoksa sabit saatlerden yarının ilki (yarının planı sabah kararından sonra yeniden kurulur).
     */
    static Instant nextRadar(Settings s, Instant now, List<Instant> adaptive) {
        if (adaptive != null) {
            Instant t = ScanPlan.next(adaptive, now);
            if (t != null) return t;
            now = now.atOffset(Fmt.TR).toLocalDate().plusDays(1).atStartOfDay(Fmt.TR).toInstant();
        }
        Instant best = null;
        OffsetDateTime local = now.atOffset(Fmt.TR);
        for (int h : s.radarHours()) {
            OffsetDateTime t = local.withHour(h).withMinute(0).withSecond(0).withNano(0);
            if (!t.isAfter(local)) t = t.plusDays(1);
            if (best == null || t.toInstant().isBefore(best)) best = t.toInstant();
        }
        return best;
    }

    static void scheduleNextRadar(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(ACTION_RADAR);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 2, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Repo repo = Repo.get(ctx);
        Instant now = Instant.now();
        Instant next = nextRadar(repo.effective(), now, repo.radarTimes(now)); // kredi planı ve maç saatlerine göre
        if (next == null) {
            am.cancel(pi);
            return;
        }
        // Radar tam dakikada olmak zorunda değil: pil dostu, gecikmesi küçük alarm.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pi);
    }

    static void runRadarNow(Context ctx) {
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean(EXTRA_RADAR, true);
        schedule(ctx, new JobInfo.Builder(JOB_RADAR, new ComponentName(ctx, DailyJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras));
    }

    static void runEventNow(Context ctx) {
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean(EXTRA_EVENT, true);
        schedule(ctx, new JobInfo.Builder(JOB_EVENT, new ComponentName(ctx, DailyJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras));
    }

    private static void schedule(Context ctx, JobInfo.Builder b) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
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

    /** Günlük kararı şimdi (ağ varsa hemen) üret. */
    static void runDailyNow(Context ctx) {
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean(EXTRA_DAILY, true);
        schedule(ctx, new JobInfo.Builder(JOB_DAILY, new ComponentName(ctx, DailyJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras));
    }
}
