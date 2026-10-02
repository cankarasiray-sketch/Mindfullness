package app.bilincli.android;

import android.app.job.JobParameters;
import android.app.job.JobService;
import app.bilincli.core.Daily;
import app.bilincli.core.Fmt;
import app.bilincli.core.Http;
import app.bilincli.core.Ledger;
import app.bilincli.core.Texts;
import app.bilincli.core.Zirve;

/** Arka plan işi: ağ isteklerini ana iş parçacığı dışında yapar, sonucu bildirir. */
public final class DailyJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        final boolean daily = params.getExtras().getBoolean(Scheduler.EXTRA_DAILY, false);
        final boolean event = params.getExtras().getBoolean(Scheduler.EXTRA_EVENT, false);
        final boolean radar = params.getExtras().getBoolean(Scheduler.EXTRA_RADAR, false);
        final boolean zirveOnly = params.getExtras().getBoolean(Scheduler.EXTRA_ZIRVE, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Repo repo = Repo.get(DailyJob.this);
                    if (zirveOnly) {
                        if (Zirve.backgroundHour(java.time.Instant.now())) zirve(repo, false); // 10 dk sınırı geçerli
                        return;
                    }
                    if (radar) {
                        try {
                            Daily.Intraday r = repo.radarScan(true); // vadesi gelen kadro saati taraması kısmi yapılır
                            if (r.newCouponId != null) {
                                Ledger.Coupon c = repo.real().coupon(r.newCouponId);
                                Notifier.show(DailyJob.this, "kupon", 14, "Yeni fırsat · " + c.legs.size() + " maç · oran "
                                        + Fmt.odds(c.totalOdds), "Gün içi taramada güncel oranlarla bulundu.\n" + Texts.couponText(c));
                            }
                            String[] m = Texts.moves(r.moves);
                            if (m != null) Notifier.show(DailyJob.this, "kupon", 15, m[0], m[1]);
                        } catch (Http.ProviderException ignored) {
                            // tarama bir sonraki saatte tekrarlanır
                        }
                        zirve(repo, true); // taze adil oranlarla
                        return;
                    }
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
                    zirve(repo, r.daily != null); // sabah taramasından sonra hemen, diğer işlerde 10 dk aralıkla
                } catch (RuntimeException e) {
                    if (daily) Notifier.show(DailyJob.this, "kupon", 12, "Günlük çalışma başarısız", String.valueOf(e.getMessage()));
                } finally {
                    Scheduler.scheduleNextEvent(DailyJob.this);
                    if (daily) Scheduler.scheduleNextRadar(DailyJob.this); // sabah kararından sonra maç saatlerine göre
                    // Yeniden deneme yok: veri alınamadıysa gün kaydedilmez ve 3 saatlik periyodik iş tamamlar.
                    jobFinished(params, false);
                }
            }
        }, "bilincli-job").start();
        return true;
    }

    /** Bilyoner Zirve Oran kontrolü (kredi harcamaz); yeni değerli oran bildirilir. */
    private void zirve(Repo repo, boolean force) {
        try {
            String[] n = repo.zirveCheck(force);
            if (n != null) Notifier.show(this, "kupon", Notifier.ID_ZIRVE, n[0], n[1]);
        } catch (RuntimeException ignored) {
            // bir sonraki işte yeniden denenir
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // yarıda kaldıysa yeniden dene
    }
}
