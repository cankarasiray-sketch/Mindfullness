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
import app.bilincli.core.Http;
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
        extra.put("radar", repo.radarView());
        extra.put("zirve", repo.zirveView());
        extra.put("virtual", repo.virtualView());
        long switched = repo.profileSwitchedAt();
        extra.put("profileSwitchedAt", switched > 0 ? Instant.ofEpochMilli(switched).toString() : null);
        extra.put("credits", repo.credits());
        extra.put("creditPlan", repo.plan().toMap());
        extra.put("accuracy", repo.forecasts.summary());
        List<Object> intl = new ArrayList<>();
        for (String[] r : repo.internationals()) {
            List<Object> row = new ArrayList<>();
            row.add(r[0]);
            row.add(r[1]);
            intl.add(row);
        }
        extra.put("intlLeagues", intl);
        List<Object> basket = new ArrayList<>();
        for (String[] r : repo.basketLeagues()) {
            List<Object> row = new ArrayList<>();
            row.add(r[0]);
            row.add(r[1]);
            basket.add(row);
        }
        extra.put("basketLeagues", basket);
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
                Repo uiRepo = repo();
                uiRepo.beginUi(new Daily.Progress() { // arka plandaki adımlar da bekleme ekranına yazılır
                    @Override
                    public void step(String text) {
                        progress(text);
                    }
                });
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
                } catch (Throwable e) { // ör. bellek: arayüz hiçbir durumda dönen simgede kalmasın
                    result.put("ok", false);
                    result.put("message", "Beklenmeyen hata: " + e);
                } finally {
                    uiRepo.endUi();
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

    /** " (10 kredi harcandı, kalan 462)" ya da kredi harcanmadıysa boş. */
    static String creditNote(int spent, String remaining) {
        if (spent <= 0) return "";
        return " (" + spent + " kredi harcandı" + (remaining != null ? ", kalan " + remaining : "") + ")";
    }

    /** Zirve Oran'ı yeniden okur (taze adil oranlarla); " Zirve Oran: 3 maçta 90 artırılmış oran, 1 değerli." ya da boş. */
    private String zirveNote(Repo repo) {
        if (!repo.real().settings().zirve) return "";
        String[] n = repo.zirveCheck(true);
        if (n != null) Notifier.show(activity, "kupon", Notifier.ID_ZIRVE, n[0], n[1]);
        Map<String, Object> z = repo.zirveView();
        if (z == null || z.get("error") != null || z.get("offers") == null) return "";
        return " Zirve Oran: " + Json.lng(z, "events", 0) + " maçta " + Json.lng(z, "offers", 0) + " artırılmış oran, "
                + (Json.lng(z, "play", 0) > 0 ? Json.lng(z, "play", 0) + " değerli (Fırsatlar)." : "değerli yok.");
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
            case "radarScan": {
                requireReal(repo);
                Daily.Intraday r;
                repo.lastSpent = 0;
                progress("Tarama başlıyor…");
                try {
                    r = repo.radarScan();
                } catch (app.bilincli.core.Http.ProviderException e) {
                    throw new IllegalStateException("Tarama yapılamadı: " + e.getMessage());
                }
                Scheduler.scheduleNextEvent(activity);
                String credit = creditNote(repo.lastSpent, repo.lastRemaining) + zirveNote(repo);
                if (r.newCouponId != null) return "Güncel oranlarla yeni kupon bulundu: #" + r.newCouponId + "." + credit;
                if (r.blocked != null) return "Tarama tamam. Yeni kupon yok: " + r.blocked + credit;
                return "Tarama tamam. " + r.moves.size() + " yeni düşen oran fırsatı." + credit;
            }
            case "recheck": {
                requireReal(repo);
                Map<String, Object> r;
                progress("Kupon güncel oranlarla kontrol ediliyor…");
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
            case "unplay": {
                requireReal(repo);
                long id = Json.lng(p, "coupon", -1);
                synchronized (Repo.LOCK) {
                    repo.real().unmarkPlayed(id);
                }
                return "Kupon #" + id + " oynanmadı olarak düzeltildi; tutar kasaya geri eklendi.";
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
                progress("Biten maçların sonuçları kontrol ediliyor…");
                synchronized (Repo.LOCK) {
                    msgs = Daily.settle(repo.real(), repo.live(), repo.forecasts, repo.virtual);
                }
                return msgs.isEmpty() ? "Sonuçlanacak maç yok." : String.join("\n", msgs);
            }
            case "generate": {
                requireReal(repo);
                Daily.Result r;
                String credit;
                progress("Kupon için veriler hazırlanıyor…");
                synchronized (Repo.LOCK) {
                    repo.probeActive();
                    Daily.LiveSources src = repo.live();
                    r = Daily.generate(repo.real(), src, Json.bool(p, "force", false), false, repo.radar);
                    repo.storeKickoffs(r);
                    repo.recordForecasts(r);
                    if (r.error == null && r.blocked == null && !r.skipped) repo.afterScan(src);
                    else repo.after(src);
                    credit = creditNote(src.spent(), src.remainingCredits());
                }
                Scheduler.scheduleNextEvent(activity);
                Scheduler.scheduleNextRadar(activity);
                if (r.error != null) throw new IllegalStateException("Veri alınamadı: " + r.error + credit);
                if (r.skipped) return "Bugünün kararı zaten verilmiş.";
                if (r.blocked != null) return r.blocked + credit;
                if (r.besidePlayed && r.couponIds.isEmpty()) {
                    return "Oynanmış kuponlar duruyor; kalan günlük sınır içinde başka maçta yeni fırsat çıkmadı." + credit;
                }
                if (r.besidePlayed) return r.couponIds.size() + " ek kupon üretildi (oynanmış kuponlara dokunulmadı)." + credit;
                if (r.decision.isPass()) return "Bugün pas: " + r.decision.reason + credit;
                return (r.couponIds.size() > 1 ? r.couponIds.size() + " yeni kupon üretildi." : "Yeni kupon #" + r.couponId + " üretildi.") + credit;
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
            case "promoPlay": {
                requireReal(repo);
                String ref = Json.str(p, "ref"), market = Json.str(p, "m"), outcome = Json.str(p, "o");
                double odds;
                try {
                    odds = Double.parseDouble(String.valueOf(Json.str(p, "odds")).trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new Ledger.LedgerException("Kampanya oranını yaz, örn. 2,40");
                }
                if (!(odds > 1.0 && odds < 1000)) throw new Ledger.LedgerException("Geçersiz oran");
                Map<String, Object> view = repo.radar.view();
                @SuppressWarnings("unchecked")
                Map<String, Object>[] found = app.bilincli.core.Promo.find(Json.arr(view.get("fairs")), ref == null ? "" : ref,
                        market == null ? "" : market, outcome == null ? "" : outcome);
                if (found == null) throw new Ledger.LedgerException("Bu seçim son taramada yok. Fırsatlar → \"Şimdi tara\" ile güncelle.");
                long stake = Ledger.parseTl(Json.str(p, "amount"));
                long id;
                synchronized (Repo.LOCK) {
                    id = app.bilincli.core.Promo.record(repo.real(), found[0], found[1], odds, stake, Fmt.dayKey(Instant.now()));
                }
                Scheduler.scheduleNextEvent(activity);
                return "Kampanya bahsi kupon #" + id + " olarak kaydedildi: " + Fmt.tl(stake) + ". Sonuç maçtan sonra otomatik işlenir.";
            }
            case "zirveRefresh": {
                requireReal(repo);
                if (!repo.real().settings().zirve) throw new IllegalStateException("Zirve Oran kontrolü kapalı (Ayarlar → Radar).");
                String note = zirveNote(repo);
                Map<String, Object> z = repo.zirveView();
                if (z != null && z.get("error") != null) throw new IllegalStateException("Zirve Oran okunamadı: " + z.get("error"));
                return note.trim().isEmpty() ? "Zirve Oran okundu." : note.trim();
            }
            case "probe": {
                requireReal(repo);
                synchronized (Repo.LOCK) {
                    repo.probeActive(true);
                }
                Map<String, Integer> active = repo.active();
                if (active == null) throw new IllegalStateException("Maç listesi alınamadı (bağlantı ya da anahtar).");
                int playing = 0;
                for (int n : active.values()) if (n > 0) playing++;
                List<String> extra = repo.plan().expanded;
                return "Bugün maçı olan " + playing + " lig." + (extra.isEmpty() ? "" : " Kredi bol: " + extra.size() + " ek lig taranacak.")
                        + " (Kredi harcanmadı.)";
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
            case "shareText": {
                final String text = String.valueOf(Json.str(p, "text"));
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Bilinçli Kupon test çıktısı " + Fmt.dayKey(Instant.now()))
                                .putExtra(Intent.EXTRA_TEXT, text);
                        activity.startActivity(Intent.createChooser(send, "Çıktıyı paylaş"));
                    }
                });
                return "";
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

    /** Veri kaynaklarını dener, doğrulama raporunu ve pazar envanterini gösterir. */
    /** Bekleme ekranındaki ilerleme metni. */
    void progress(String text) {
        final String js = "window.onProgress && window.onProgress(" + Json.write(text) + ")";
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    /** Doğrulama sonucu: başında ✓ ya da ✗. */
    private static String mark(Object status) {
        String t = String.valueOf(status);
        boolean ok = t.startsWith("doğrulandı") || t.startsWith("sıra düzeltildi") || t.startsWith("önceki eşleme");
        return (ok ? "✓ " : "✗ ") + t;
    }

    private String check(Repo repo) {
        StringBuilder b = new StringBuilder();
        repo.refreshInternationals(new Daily.LiveSources(new AndroidHttp(activity), repo.real().settings()), true);
        progress("Bugün oynayan ligler belirleniyor (ücretsiz)…");
        repo.probeActive(true);
        Daily.LiveSources src = repo.live();
        src.progress = new Daily.Progress() {
            @Override
            public void step(String text) {
                progress(text);
            }
        };
        Daily.Fetch f;
        try {
            src.diagnose();
            f = Daily.fetch(src, Instant.now());
        } catch (Exception e) {
            b.append("Veri alınamadı: ").append(e.getMessage()).append('\n');
            return b.toString().trim();
        } finally {
            repo.after(src);
        }
        List<Models.Pair> pairs = Matching.match(f.book, f.sharp);
        // kredi harcanmış tam veri: Fırsatlar ve Zirve Oran karşılaştırması da güncellensin
        if (!repo.isDemo()) repo.radar.update(f.book, f.sharp, Instant.now(), repo.real().settings(), true);
        Map<String, Object> cal = f.calibration;
        Engine.Decision d = Engine.decide(f.book, f.sharp, Instant.now(), Daily.decisionSettings(repo.real()));
        // Özet en üstte: kaynaklar, pazar doğrulamaları, karar
        b.append("ÖZET\n");
        b.append("  Nesine: ").append(f.book.size()).append(" maç · Pinnacle: ").append(f.sharp.size())
                .append(" maç · eşleşen: ").append(pairs.size()).append('\n');
        String types = app.bilincli.core.Nesine.lastTypes;
        if (types != null && !types.isEmpty()) b.append("  Bülten: ").append(types).append('\n');
        b.append("  Kredi: bu test ").append(src.spent()).append(" · kalan ").append(src.remainingCredits()).append('\n');
        if (repo.real().settings().apiKeys().size() > 1) b.append("  Anahtarlar: ").append(src.keySummary()).append('\n');
        if (src.bookNote != null) b.append("  Not: ").append(src.bookNote).append('\n');
        b.append("  Maç Sonucu: ").append(mark(cal.get("MS"))).append('\n');
        b.append("  Çifte Şans: ").append(mark(cal.get("CS"))).append('\n');
        b.append("  2,5 Alt/Üst: ").append(mark(cal.get("AU25"))).append('\n');
        b.append("  Karşılıklı Gol: ").append(mark(cal.get("KG"))).append('\n');
        b.append("  Basketbol MS: ").append(mark(cal.get("BS"))).append('\n');
        b.append("  Basketbol Alt/Üst: ").append(mark(cal.get("BT"))).append('\n');
        b.append("  Basketbol Handikap: ").append(mark(cal.get("BH"))).append('\n');
        d.stats.put("kasa", repo.real().balance());
        b.append("  Karar: ").append(Texts.statsLine(d.stats)).append('\n');
        if (d.isPass()) b.append("  ").append(d.reason).append('\n');

        b.append("\nLig ayrıntısı\n");
        for (String line : src.report()) b.append("  ").append(line).append('\n');
        for (String w : src.warnings()) b.append("Uyarı: ").append(w).append('\n');
        b.append("\nEşleşen maçlar (ilk 5 futbol, ilk 5 basketbol)\n");
        int football = 0, basket = 0;
        for (Models.Pair x : pairs) {
            boolean isBasket = Models.BASKETBALL.equals(x.book.sport);
            if (isBasket ? basket++ >= 5 : football++ >= 5) continue;
            b.append("  ").append(isBasket ? "🏀 " : "").append(x.book.home).append(" - ").append(x.book.away).append(" ⇄ ")
                    .append(x.sharp.home).append(" - ").append(x.sharp.away).append('\n');
        }
        b.append("\nVeri doğrulama ayrıntısı\n");
        b.append("  Uyumsuz eşleşme (ayıklanan maç): ").append(cal.get("mismatched")).append('\n');
        b.append("  Ayıklanan şüpheli oran: ").append(cal.get("suspicious")).append('\n');
        for (Object n : Json.arr(cal.get("notes"))) b.append("  · ").append(n).append('\n');
        String inv = app.bilincli.core.Nesine.lastInventory;
        if (inv != null && !inv.isEmpty()) {
            String[] lines = inv.split("\n");
            b.append("\nPazar envanteri (korner gibi yeni pazarları eşlemek için; ilk 12)\n");
            for (int i = 0; i < Math.min(12, lines.length); i++) b.append(lines[i]).append('\n');
            if (lines.length > 12) b.append("(+").append(lines.length - 12).append(" pazar kodu daha)\n");
        }
        progress("Bilyoner Zirve Oran okunuyor (kredi harcamaz)…");
        b.append("\nBilyoner Zirve Oran\n");
        if (!repo.real().settings().zirve || repo.isDemo()) {
            b.append("  kapalı (Ayarlar → Radar) ya da demo modu\n");
        } else {
            String[] zn = repo.zirveCheck(true);
            if (zn != null) Notifier.show(activity, "kupon", Notifier.ID_ZIRVE, zn[0], zn[1]);
            Map<String, Object> z = repo.zirveView();
            if (z == null) b.append("  okunamadı\n");
            else if (z.get("error") != null) b.append("  okunamadı: ").append(z.get("error")).append('\n');
            else b.append(app.bilincli.core.Zirve.summary(z));
        }
        String binv = app.bilincli.core.Nesine.lastBasketInventory;
        if (binv != null && !binv.isEmpty()) {
            String[] lines = binv.split("\n");
            b.append("\nBasketbol pazar envanteri (ilk 10)\n");
            for (int i = 0; i < Math.min(10, lines.length); i++) b.append(lines[i]).append('\n');
            if (lines.length > 10) b.append("(+").append(lines.length - 10).append(" pazar kodu daha)\n");
        }
        return b.toString().trim();
    }
}
