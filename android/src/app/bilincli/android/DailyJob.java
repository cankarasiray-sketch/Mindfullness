package app.bilincli.android;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Arka plan işi: ağ isteklerini ana iş parçacığı dışında yapar, sonucu bildirir. */
public final class DailyJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        final boolean daily = params.getExtras().getBoolean(Scheduler.EXTRA_DAILY, false);
        final boolean event = params.getExtras().getBoolean(Scheduler.EXTRA_EVENT, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Repo repo = Repo.get(DailyJob.this);
                    if (event || !daily) {
                        for (String[] n : repo.events()) Notifier.show(DailyJob.this, "kupon", 13, n[0], n[1]);
                    }
                    if (event) return;
                    Repo.BackgroundResult r = repo.background(daily);
                    if (r.setupNeeded && daily) {
                        Notifier.show(DailyJob.this, "kupon", 12, "Kurulumu tamamla",
                                "Günün kararını üretmek için Ayarlar'dan The Odds API anahtarını gir.");
                    }
                    Notifier.settled(DailyJob.this, r.settleMessages);
                    if (r.daily != null) {
                        Notifier.daily(DailyJob.this, Repo.get(DailyJob.this).real(), r.daily, daily);
                    }
                } catch (RuntimeException e) {
                    if (daily) Notifier.show(DailyJob.this, "kupon", 12, "Günlük çalışma başarısız", String.valueOf(e.getMessage()));
                } finally {
                    Scheduler.scheduleNextEvent(DailyJob.this);
                    // Yeniden deneme yok: veri alınamadıysa gün kaydedilmez ve 3 saatlik periyodik iş tamamlar.
                    jobFinished(params, false);
                }
            }
        }, "bilincli-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // yarıda kaldıysa yeniden dene
    }
}
