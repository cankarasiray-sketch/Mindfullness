package app.bilincli.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import app.bilincli.core.Daily;
import app.bilincli.core.Engine;
import app.bilincli.core.Fmt;
import app.bilincli.core.Json;
import app.bilincli.core.Ledger;
import app.bilincli.core.Matching;
import app.bilincli.core.Models;
import app.bilincli.core.Recheck;
import app.bilincli.core.Settings;
import app.bilincli.core.State;
import app.bilincli.core.Texts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JavaScript köprüsü. state() anlık durumu verir; act() işlemleri arka planda
 * yürütür ve sonucu window.onActResult(id, sonuç) ile geri bildirir.
 */
public final class Bridge {
    private final Activity activity;
    private final WebView web;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    Bridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    private Repo repo() {
        return Repo.get(activity);
    }

    /** Uzun işlemler LOCK'u tutarken arayüz donmasın diye kilit almaz; Ledger kendi içinde senkronizedir. */
    @JavascriptInterface
    public String state() {
        Repo repo = repo();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("notifications", Notifier.allowed(activity));
        extra.put("exactAlarm", Scheduler.canExact(activity));
        extra.put("nextRun", Scheduler.nextRun(repo.real().settings(), Instant.now()).toString());
        extra.put("demoSummary", repo.isDemo() ? repo.demoSummary : null);
        String version = "?";
        try {
            version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // kendi paketimiz her zaman bulunur
        }
        extra.put("version", version);
        return Json.write(State.build(repo.visible(), repo.isDemo(), extra));
    }

    @JavascriptInterface
    public void act(final String action, final String payload, final String callbackId) {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                Map<String, Object> result = new LinkedHashMap<>();
                try {
                    Map<String, Object> p = payload == null || payload.isEmpty()
                            ? new LinkedHashMap<String, Object>() : Json.parseObject(payload);
                    result.put("ok", true);
                    result.put("message", handle(action, p, result));
                } catch (Ledger.LedgerException | IllegalArgumentException | IllegalStateException e) {
                    result.put("ok", false);
                    result.put("message", e.getMessage());
                } catch (RuntimeException e) {
                    result.put("ok", false);
                    result.put("message", "Beklenmeyen hata: " + e);
                }
                final String js = "window.onActResult(" + Json.write(callbackId) + "," + Json.write(result) + ")";
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(js, null);
                    }
                });
            }
        });
    }

    private static void requireReal(Repo repo) {
        if (repo.isDemo()) throw new Ledger.LedgerException("Demo modunda işlem yapılamaz. Önce demodan çık.");
    }

    private String handle(String action, Map<String, Object> p, Map<String, Object> result) {
        Repo repo = repo();
        switch (action) {
            case "deposit": {
                requireReal(repo);
                long amount = Ledger.parseTl(Json.str(p, "amount"));
                synchronized (Repo.LOCK) {
                    repo.real().deposit(amount, "uygulama");
                }
                return "Kasaya " + Fmt.tl(amount) + " eklendi.";
            }
            case "withdraw": {
                requireReal(repo);
                long amount = Ledger.parseTl(Json.str(p, "amount"));
                synchronized (Repo.LOCK) {
                    repo.real().withdraw(amount, "uygulama");
                }
                return "Kasadan " + Fmt.tl(amount) + " çekildi.";
            }
            case "played": {
                requireReal(repo);
                long id = Json.lng(p, "coupon", -1);
                String oddsText = Json.str(p, "odds");
                List<Double> odds = null;
                if (oddsText != null && !oddsText.trim().isEmpty()) {
                    odds = new ArrayList<>();
                    for (String part : oddsText.trim().split("[;\\s]+")) {
                        try {
                            odds.add(Double.parseDouble(part.replace(',', '.')));
                        } catch (NumberFormatException e) {
                            throw new Ledger.LedgerException("Oranları boşlukla ayırarak yaz, örn. 1,85 2,10");
                        }
                    }
                }
                long stake = Ledger.parseTl(Json.str(p, "amount"));
                synchronized (Repo.LOCK) {
                    repo.real().markPlayed(id, stake, odds);
                }
                Scheduler.scheduleNextEvent(activity);
                return "Kupon #" + id + " oynandı: " + Fmt.tl(stake) + ".";
            }
            case "recheck": {
                requireReal(repo);
                Map<String, Object> r;
                try {
                    r = repo.recheck(Json.lng(p, "coupon", -1));
                } catch (app.bilincli.core.Http.ProviderException e) {
                    throw new IllegalStateException("Güncel oranlar alınamadı: " + e.getMessage());
                }
                Scheduler.scheduleNextEvent(activity);
                return String.valueOf(r.get("verdict"));
            }
            case "playChecked": {
                requireReal(repo);
                long id = Json.lng(p, "coupon", -1);
                long stake = Ledger.parseTl(Json.str(p, "amount"));
                synchronized (Repo.LOCK) {
                    Ledger.Coupon c = repo.real().coupon(id);
                    if (!Recheck.isFresh(c.lastCheck, Instant.now())) {
                        throw new Ledger.LedgerException("Kontrol bayatladı (30 dk'dan eski). Önce tekrar kontrol et.");
                    }
                    if (!Boolean.TRUE.equals(c.lastCheck.get("playable"))) {
                        throw new Ledger.LedgerException("Son kontrole göre bu kupon oynanmamalı.");
                    }
                    List<List<Double>> v = Recheck.checkedValues(c.lastCheck);
                    repo.real().markPlayed(id, stake, v.get(0), v.get(1));
                }
                Scheduler.scheduleNextEvent(activity);
                return "Kupon #" + id + " güncel oranlarla oynandı: " + Fmt.tl(stake) + ".";
            }
            case "profile": {
                synchronized (Repo.LOCK) {
                    Settings s = repo.real().settings();
                    s.applyProfile(Json.str(p, "name"));
                    repo.real().saveSettings(s);
                }
                return "Strateji profili kaydedildi.";
            }
            case "legResult": {
                requireReal(repo);
                long id = Json.lng(p, "coupon", -1);
                synchronized (Repo.LOCK) {
                    repo.real().setLegResult(id, (int) Json.lng(p, "position", 0), Json.str(p, "result"), null);
                    String out = repo.real().settleCoupon(id);
                    return out == null ? "Kaydedildi; diğer maçlar bekleniyor." : "Kaydedildi. Kupon: " + label(out);
                }
            }
            case "settle": {
                requireReal(repo);
                List<String> msgs;
                synchronized (Repo.LOCK) {
                    msgs = Daily.settle(repo.real(), repo.live());
                }
                return msgs.isEmpty() ? "Sonuçlanacak maç yok." : String.join("\n", msgs);
            }
            case "generate": {
                requireReal(repo);
                Daily.Result r;
                synchronized (Repo.LOCK) {
                    r = Daily.generate(repo.real(), repo.live(), Json.bool(p, "force", false), false);
                }
                Scheduler.scheduleNextEvent(activity);
                if (r.error != null) throw new IllegalStateException("Veri alınamadı: " + r.error);
                if (r.skipped) return "Bugünün kararı zaten verilmiş.";
                if (r.blocked != null) return r.blocked;
                if (r.decision.isPass()) return "Bugün pas: " + r.decision.reason;
                return "Yeni kupon #" + r.couponId + " üretildi.";
            }
            case "saveSettings": {
                Settings s = Settings.fromMap(Json.obj(p.get("settings")));
                synchronized (Repo.LOCK) {
                    repo.real().saveSettings(s);
                }
                Instant next = Scheduler.ensureScheduled(activity);
                return "Ayarlar kaydedildi. Sonraki çalışma: " + next.atOffset(Fmt.TR).toLocalDateTime().toString()
                        .replace('T', ' ').substring(0, 16);
            }
            case "check":
                return check(repo);
            case "demoStart": {
                Map<String, Object> s;
                synchronized (Repo.LOCK) {
                    s = repo.startDemo();
                }
                return "Demo hazır: 60 gün, " + Fmt.tl((Long) s.get("start")) + " → " + Fmt.tl((Long) s.get("end"))
                        + ". 'Banko' 3'lü kupon: " + Fmt.tl((Long) s.get("baselineEnd")) + ".";
            }
            case "demoStop":
                synchronized (Repo.LOCK) {
                    repo.stopDemo();
                }
                return "Demodan çıkıldı.";
            case "export": {
                final String data;
                synchronized (Repo.LOCK) {
                    data = repo.real().export();
                }
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent send = new Intent(Intent.ACTION_SEND).setType("application/json")
                                .putExtra(Intent.EXTRA_SUBJECT, "Bilinçli Kupon yedeği " + Fmt.dayKey(Instant.now()))
                                .putExtra(Intent.EXTRA_TEXT, data);
                        activity.startActivity(Intent.createChooser(send, "Yedeği paylaş"));
                    }
                });
                return "Yedek paylaşım ekranı açıldı.";
            }
            case "import": {
                requireReal(repo);
                synchronized (Repo.LOCK) {
                    repo.real().replaceWith(Json.str(p, "data"));
                }
                Scheduler.ensureScheduled(activity);
                return "Yedek geri yüklendi.";
            }
            case "testNotification":
                if (!Notifier.allowed(activity)) throw new IllegalStateException("Bildirim izni yok.");
                Notifier.show(activity, "kupon", 99, "Bilinçli Kupon", "Bildirimler çalışıyor. Günün kararı her sabah buraya gelecek.");
                return "Test bildirimi gönderildi.";
            case "askNotifications":
                if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            activity.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
                        }
                    });
                } else {
                    openAppSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                }
                return "";
            case "askExactAlarm":
                if (Build.VERSION.SDK_INT >= 31) openAppSettings(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                return "";
            case "openUrl": {
                final String url = Json.str(p, "url");
                if (url == null || !url.startsWith("https://")) throw new IllegalArgumentException("Geçersiz adres");
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    }
                });
                return "";
            }
            default:
                throw new IllegalArgumentException("Bilinmeyen işlem: " + action);
        }
    }

    private void openAppSettings(final String action) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(action).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                        .setData(Uri.parse("package:" + activity.getPackageName()));
                try {
                    activity.startActivity(i);
                } catch (RuntimeException e) {
                    activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName())));
                }
            }
        });
    }

    private static String label(String result) {
        return Models.WON.equals(result) ? "TUTTU" : Models.LOST.equals(result) ? "yattı" : "iade";
    }

    /** Veri kaynaklarını dener ve eşleştirmeyi özetler (Python: `bilincli kontrol`). */
    private String check(Repo repo) {
        StringBuilder b = new StringBuilder();
        Daily.LiveSources src = repo.live();
        List<Models.BookEvent> book = new ArrayList<>();
        List<Models.SharpEvent> sharp = new ArrayList<>();
        try {
            book = src.book();
            b.append("iddaa bülteni (Nesine): ").append(book.size()).append(" maç okundu\n");
        } catch (Exception e) {
            b.append("iddaa bülteni okunamadı: ").append(e.getMessage()).append('\n');
        }
        try {
            sharp = src.sharp();
            b.append("Keskin piyasa: ").append(sharp.size()).append(" maç (kalan API kredisi: ")
                    .append(src.remainingCredits()).append(")\n");
        } catch (Exception e) {
            b.append("Keskin piyasa okunamadı: ").append(e.getMessage()).append('\n');
        }
        if (!book.isEmpty() && !sharp.isEmpty()) {
            List<Models.Pair> pairs = Matching.match(book, sharp);
            b.append("Eşleşen maç: ").append(pairs.size()).append('\n');
            for (int i = 0; i < Math.min(6, pairs.size()); i++) {
                Models.Pair x = pairs.get(i);
                b.append("  ").append(x.book.home).append(" - ").append(x.book.away).append(" ⇄ ")
                        .append(x.sharp.home).append(" - ").append(x.sharp.away).append('\n');
            }
            Engine.Decision d = Engine.decide(book, sharp, Instant.now(), repo.real().settings());
            b.append(Texts.statsLine(d.stats));
        }
        return b.toString().trim();
    }
}
