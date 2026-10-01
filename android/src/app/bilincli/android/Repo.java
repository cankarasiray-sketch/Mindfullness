package app.bilincli.android;

import android.content.Context;
import android.content.SharedPreferences;
import app.bilincli.core.Calibration;
import app.bilincli.core.Closing;
import app.bilincli.core.CreditPlan;
import app.bilincli.core.Daily;
import app.bilincli.core.DemoSim;
import app.bilincli.core.Fmt;
import app.bilincli.core.Forecasts;
import app.bilincli.core.Http;
import app.bilincli.core.Json;
import app.bilincli.core.KeyCredits;
import app.bilincli.core.Ledger;
import app.bilincli.core.Matching;
import app.bilincli.core.Models;
import app.bilincli.core.OddsApi;
import app.bilincli.core.Radar;
import app.bilincli.core.Recheck;
import app.bilincli.core.ScanPlan;
import app.bilincli.core.Settings;
import java.io.File;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uygulama genelinde tek veri deposu. Arka plan işi ve arayüz aynı süreçte
 * çalıştığından tüm işlemler {@link #LOCK} ile sıraya sokulur.
 */
final class Repo {
    static final Object LOCK = new Object();
    private static Repo instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final Ledger ledger;
    final Radar radar;
    /** Veri doğrulamanın son güvenilir pazar eşlemeleri (dogrulama.json). */
    private final FileStorage memoryStore;
    /** Tahmin defteri: tahminlerin gerçek sonuçlarla isabeti. */
    final Forecasts forecasts;
    private final Map<String, Object> memory;
    private Ledger demoLedger;
    Map<String, Object> demoSummary;

    private static final Ledger.Clock SYSTEM = new Ledger.Clock() {
        @Override
        public Instant now() {
            return Instant.now();
        }
    };

    private Repo(Context ctx) {
        app = ctx.getApplicationContext();
        prefs = app.getSharedPreferences("bilincli", Context.MODE_PRIVATE);
        ledger = new Ledger(new FileStorage(new File(app.getFilesDir(), "kasa.json")), SYSTEM);
        radar = new Radar(new FileStorage(new File(app.getFilesDir(), "piyasa.json")));
        memoryStore = new FileStorage(new File(app.getFilesDir(), "dogrulama.json"));
        forecasts = new Forecasts(new FileStorage(new File(app.getFilesDir(), "tahmin.json")));
        String m = memoryStore.read();
        memory = m == null || m.trim().isEmpty() ? new java.util.LinkedHashMap<String, Object>() : Json.parseObject(m);
        loadActive();
    }

    /** Uygulama yeniden başlasa da (ör. güncelleme) son 3 saatlik maç sayıları kaybolmasın. */
    private void loadActive() {
        try {
            String raw = prefs.getString("active", null);
            long at = prefs.getLong("activeAt", 0);
            if (raw == null || at == 0) return;
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : Json.parseObject(raw).entrySet()) {
                if (e.getValue() instanceof Number) out.put(e.getKey(), ((Number) e.getValue()).intValue());
            }
            activeToday = out;
            activeAt = Instant.ofEpochMilli(at);
        } catch (RuntimeException ignored) {
            // bozuk kayıt: bir sonraki sorguda yeniden öğrenilir
        }
    }

    /** Canlı çağrıdan sonra: doğrulama hafızasını ve kalan API kredisini sakla. */
    /** Son çalışmanın harcadığı kredi (x-requests-last toplamı) ve kalan kredi; mesajlar için. */
    volatile int lastSpent;
    volatile String lastRemaining;

    void after(Daily.LiveSources src) {
        memoryStore.write(Json.write(memory));
        String rem = src.remainingCredits(), used = src.usedCredits();
        lastSpent = src.spent();
        lastRemaining = rem;
        if (rem == null) return;
        // anahtar başına ölçümler saklanır; toplam, kredi planının kotasıdır
        Map<String, long[]> merged = KeyCredits.merge(keyCredits(), src.keyCredits(), ledger.settings().apiKeys());
        long[] t = KeyCredits.totals(merged);
        if (t != null) {
            rem = String.valueOf(t[0]);
            used = String.valueOf(t[1]);
            lastRemaining = rem;
        }
        prefs.edit().putString("credits", rem).putString("creditsUsed", used).putLong("creditsAt", System.currentTimeMillis())
                .putString("keyCredits", KeyCredits.write(merged)).apply();
    }

    /** Anahtar başına son ölçümler; son kota yenilenmesinden eskiler atılır (yeniden ölçülür). */
    Map<String, long[]> keyCredits() {
        return KeyCredits.parse(prefs.getString("keyCredits", null),
                KeyCredits.lastResetMillis(Instant.now().atOffset(Fmt.TR).toLocalDate(), ledger.settings().creditResetDay));
    }

    /** Tam taramadan sonra: ayrıca lig verimini (değerli seçim sayısı) güncelle. */
    void afterScan(Daily.LiveSources src) {
        CreditPlan.updateYield(memory, radar.view(), effective().leagues);
        after(src);
    }

    private static Long parseLong(String v) {
        if (v == null) return null;
        try {
            return (long) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Bugünün kredi planı: kalan krediye göre daraltılmış kapsam. */
    /** Kayıtlı milli turnuvalar: {kod, ad}. */
    List<String[]> internationals() {
        List<String[]> out = new ArrayList<>();
        for (String row : prefs.getString("intl", "").split(";")) {
            int i = row.indexOf('|');
            if (i > 0) out.add(new String[] {row.substring(0, i), row.substring(i + 1)});
        }
        for (String[] r : out) Settings.EXTRA_NAMES.put(r[0], r[1]);
        return out;
    }

    /** Aktif milli turnuvaları günde bir kez (ücretsiz spor listesinden) yeniler. */
    void refreshInternationals(Daily.LiveSources src, boolean force) {
        String today = Fmt.dayKey(Instant.now());
        if (!force && today.equals(prefs.getString("intlDay", ""))) return;
        try {
            StringBuilder b = new StringBuilder();
            for (String[] s : src.sports()) {
                if (!OddsApi.isInternational(s[0])) continue;
                if (b.length() > 0) b.append(';');
                b.append(s[0]).append('|').append(s[1].replace(";", ",").replace("|", "/"));
            }
            prefs.edit().putString("intl", b.toString()).putString("intlDay", today).apply();
        } catch (Http.ProviderException ignored) {
            // liste alınamadı: önceki liste kullanılır, sonraki çalışmada yeniden denenir
        }
    }

    /** Taranacak kapsam: kullanıcının ligleri + (açıksa) aktif milli turnuvalar. */
    Settings scanSettings() {
        Settings s = ledger.settings();
        if (s.internationals) {
            for (String[] r : internationals()) if (!s.leagues.contains(r[0])) s.leagues.add(r[0]);
        }
        return s;
    }

    /** Bugünün pencere maç sayıları (ücretsiz listeden) ve öğrenildiği an. */
    private volatile Map<String, Integer> activeToday;
    private volatile Instant activeAt;

    /** Taranacak liglerde karar penceresinde kaç maç var (kota harcamaz); planı doğru kurmak için. */
    void probeActive() {
        Settings s = scanSettings();
        if (s.oddsApiKey.isEmpty()) return;
        try {
            Daily.LiveSources probe = new Daily.LiveSources(new AndroidHttp(app), s);
            List<String> leagues = new ArrayList<>(s.leagues);
            if (roomToExpand(s)) { // kredi bol: plan ek lig seçebilsin diye onlarınki de (ücretsiz)
                for (String[] l : Settings.KNOWN_LEAGUES) if (!leagues.contains(l[0])) leagues.add(l[0]);
            }
            Map<String, Integer> counts = probe.activeCounts(leagues, Instant.now());
            activeToday = counts;
            activeAt = Instant.now();
            Map<String, Object> raw = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : counts.entrySet()) raw.put(e.getKey(), (long) e.getValue());
            prefs.edit().putString("active", Json.write(raw)).putLong("activeAt", activeAt.toEpochMilli()).apply();
        } catch (Http.ProviderException ignored) {
            // bilinmiyor: plan ortalama payla kurulur
        }
    }

    /**
     * Genişletmeye yer var mı: günlük kredi (kalan / yenilenmeye kalan gün) seçili liglerin tipik
     * giderinin belirgin üstündeyse. Ücretsiz tek anahtarda (günde ~16) ek ligler hiç sorulmaz.
     */
    private boolean roomToExpand(Settings s) {
        if (!s.creditAuto || !s.creditExpand) return false;
        Long rem = parseLong(prefs.getString("credits", null));
        if (rem == null) return false;
        int days = CreditPlan.daysLeft(Instant.now().atOffset(Fmt.TR).toLocalDate(), s.creditResetDay);
        return rem / (double) days > 40;
    }

    /** Son 3 saatte öğrenildiyse bugünün pencere maç sayıları, yoksa null. */
    Map<String, Integer> active() {
        Instant at = activeAt;
        return at != null && at.isAfter(Instant.now().minusSeconds(3 * 3600)) ? activeToday : null;
    }

    /** Son 14 günde günlük ortalama kupon (az geçmişte 1). */
    double avgCoupons() {
        java.time.LocalDate today = Instant.now().atOffset(Fmt.TR).toLocalDate();
        String from = today.minusDays(14).toString();
        int runs = 0, coupons = 0;
        for (Ledger.Run r : ledger.runs(30)) {
            if (r.day.compareTo(from) < 0) continue;
            runs++;
            if ("kupon".equals(r.decision)) coupons += r.ids().size();
        }
        return runs < 3 ? 1.0 : (double) coupons / runs;
    }

    CreditPlan.Plan plan() {
        Map<String, Double> yield;
        try {
            yield = CreditPlan.yieldOf(memory);
        } catch (RuntimeException e) {
            // arayüz kilitsiz okur; arka plan işi hafızayı tam o an yazıyorsa nötr verimle hesapla
            yield = new LinkedHashMap<>();
        }
        return CreditPlan.plan(scanSettings(), parseLong(prefs.getString("credits", null)),
                parseLong(prefs.getString("creditsUsed", null)), Instant.now().atOffset(Fmt.TR).toLocalDate(), yield,
                active(), avgCoupons());
    }

    /** Canlı çekimlerde kullanılan ayarlar: kullanıcının ayarları, kredi planına göre daraltılmış. */
    Settings effective() {
        return CreditPlan.effective(scanSettings(), plan());
    }

    /** Son bilinen kalan kredi ve zamanı (bilinmiyorsa null). */
    Map<String, Object> credits() {
        String c = prefs.getString("credits", null);
        if (c == null) return null;
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        try {
            m.put("remaining", (long) Double.parseDouble(c));
        } catch (NumberFormatException e) {
            return null;
        }
        m.put("at", Instant.ofEpochMilli(prefs.getLong("creditsAt", 0)).toString());
        List<String> keys = ledger.settings().apiKeys();
        if (keys.size() > 1) m.put("keys", KeyCredits.view(keyCredits(), keys));
        return m;
    }

    /** Kredi bu sayının altına inince radar gibi isteğe bağlı çekimler durur; günlük karar korunur. */
    static final long LOW_CREDITS = 40;

    boolean creditsLow() {
        Map<String, Object> c = credits();
        return c != null && (Long) c.get("remaining") < LOW_CREDITS;
    }

    /** Arayüzdeki "Fırsatlar" verisi: demo modunda demo özeti, değilse radar durumu. */
    @SuppressWarnings("unchecked")
    Map<String, Object> radarView() {
        if (isDemo() && demoSummary != null && demoSummary.get("radar") instanceof Map) {
            return (Map<String, Object>) demoSummary.get("radar");
        }
        return radar.view();
    }

    /** Gün içi radar taraması: tüm ligler + iddaa bülteni; gerekirse güncel kupon önerir. */
    Daily.Intraday radarScan() throws Http.ProviderException {
        synchronized (LOCK) {
            if (ledger.settings().oddsApiKey.isEmpty()) throw new Http.ProviderException("The Odds API anahtarı yok.");
            if (creditsLow()) {
                throw new Http.ProviderException("API kredisi az kaldı (" + credits().get("remaining")
                        + "); günlük karar için saklanıyor, radar bekliyor.");
            }
            probeActive();
            Daily.LiveSources src = live();
            try {
                Daily.Fetch f = Daily.fetch(src, Instant.now());
                forecasts.record(Matching.match(f.book, f.sharp), Instant.now());
                return Daily.intraday(ledger, f.book, f.sharp, radar, src);
            } finally {
                afterScan(src);
            }
        }
    }

    /**
     * LOCK değil, ayrı bir kilit: arka plan işi ağ isteği sırasında LOCK'u tutarken
     * ana iş parçacığı burada beklerse uygulama donar (ANR).
     */
    static Repo get(Context ctx) {
        synchronized (Repo.class) {
            if (instance == null) instance = new Repo(ctx);
            return instance;
        }
    }

    /** Gerçek defter. Demo modunda bile arka plan işi bunun üzerinde çalışır. */
    Ledger real() {
        return ledger;
    }

    boolean isDemo() {
        return prefs.getBoolean("demo", false) && demoLedger() != null;
    }

    /** Arayüzün gösterdiği defter. */
    Ledger visible() {
        return isDemo() ? demoLedger() : ledger;
    }

    private Ledger demoLedger() {
        if (demoLedger == null) {
            File f = new File(app.getFilesDir(), "demo.json");
            if (!f.exists()) return null;
            demoLedger = new Ledger(new FileStorage(f), SYSTEM);
        }
        return demoLedger;
    }

    Map<String, Object> startDemo() {
        File f = new File(app.getFilesDir(), "demo.json");
        FileStorage st = new FileStorage(f);
        st.delete();
        OffsetDateTime local = Instant.now().atOffset(Fmt.TR);
        Instant today0600 = local.withHour(6).withMinute(0).withSecond(0).withNano(0).toInstant();
        if (today0600.isAfter(Instant.now())) today0600 = today0600.minusSeconds(86400);
        demoSummary = DemoSim.run(st, 60, 1000000, System.currentTimeMillis() % 1000, today0600);
        demoLedger = null;
        prefs.edit().putBoolean("demo", true).apply();
        return demoSummary;
    }

    void stopDemo() {
        prefs.edit().putBoolean("demo", false).apply();
        new FileStorage(new File(app.getFilesDir(), "demo.json")).delete();
        demoLedger = null;
        demoSummary = null;
    }

    Daily.LiveSources live() {
        Daily.LiveSources src = new Daily.LiveSources(new AndroidHttp(app), effective());
        src.memory = memory;
        src.knownActive = active(); // pencere maç sayıları zaten biliniyorsa tekrar sorulmaz
        src.knownKeyCredits = keyCredits(); // birden fazla anahtar: kredisi en çok kalan seçilir
        src.bookCacheMaxAgeS = 20 * 60; // yarıda kesilen indirmede en fazla 20 dk'lık bülten (kontrol hariç)
        return src;
    }

    /** Bugünün planlanan çalışma anı (Türkiye saati). */
    Instant todaysRunTime() {
        Settings s = ledger.settings();
        return Instant.now().atOffset(Fmt.TR).withHour(s.runHour).withMinute(s.runMinute).withSecond(0)
                .withNano(0).toInstant();
    }

    /** Arka plan: biten maçları sonuçlandır; gerekiyorsa bugünün kararını üret. */
    BackgroundResult background(boolean dailyTrigger) {
        BackgroundResult out = new BackgroundResult();
        synchronized (LOCK) {
            if (ledger.settings().oddsApiKey.isEmpty()) {
                out.setupNeeded = true; // anahtar yokken ağ isteği yapma
                return out;
            }
            refreshInternationals(new Daily.LiveSources(new AndroidHttp(app), ledger.settings()), false);
            probeActive();
            Daily.LiveSources src = live();
            out.settleMessages = Daily.settle(ledger, src, forecasts);
            String today = Fmt.dayKey(Instant.now());
            boolean due = !Instant.now().isBefore(todaysRunTime()) && ledger.runFor(today) == null;
            boolean scanned = false;
            if (dailyTrigger || due) {
                Daily.Result r = Daily.generate(ledger, src, false, false, radar);
                if (!r.skipped) out.daily = r;
                storeKickoffs(r);
                recordForecasts(r);
                scanned = !r.skipped && r.error == null && r.blocked == null;
            }
            if (scanned) afterScan(src);
            else after(src);
        }
        return out;
    }

    /** Kuponu güncel iddaa + Pinnacle oranlarıyla yeniden değerlendirir ve sonucu kaydeder. */
    Map<String, Object> recheck(long couponId) throws Http.ProviderException {
        List<Long> one = new ArrayList<>();
        one.add(couponId);
        return recheckAll(one).get(couponId);
    }

    /**
     * Kuponları tek veri çekimiyle (liglerin birleşimi) kontrol eder: aynı anda vadesi gelen
     * kuponlar için kredi bir kez harcanır. Tutarlar aynı kasadan hesaplanır (eşzamanlı Kelly).
     */
    Map<Long, Map<String, Object>> recheckAll(List<Long> ids) throws Http.ProviderException {
        synchronized (LOCK) {
            Daily.LiveSources src = live();
            Set<String> leagues = new LinkedHashSet<>(), kg = new LinkedHashSet<>();
            for (Long id : ids) {
                for (Ledger.Leg l : ledger.coupon(id).legs) {
                    if (l.sportKey != null) leagues.add(l.sportKey);
                    if ("KG".equals(l.market)) kg.add(l.sharpRef);
                }
            }
            src.bookCacheMaxAgeS = 0; // oynama kararı: yalnızca canlı bülten
            List<Models.BookEvent> book = src.book();
            List<Models.SharpEvent> sharp = src.sharp(leagues);
            // Karşılıklı Gol bacakları: Pinnacle oranı maç bazında çekilir (maç başına bir kez)
            for (Models.SharpEvent ev : sharp) {
                if (!kg.contains(ev.ref)) continue;
                try {
                    src.enrich(ev, "btts");
                } catch (Http.ProviderException ignored) {
                    // adil oran alınamazsa bacak "adil oran yok" olarak raporlanır
                }
            }
            Calibration.apply(book, sharp, memory);
            after(src);
            Instant now = Instant.now();
            Settings cfg = Daily.decisionSettings(ledger);
            long balance = ledger.balance();
            Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
            for (Long id : ids) {
                Map<String, Object> r = Recheck.run(ledger.coupon(id), book, sharp, now, cfg, balance);
                ledger.saveCheck(id, r);
                out.put(id, r);
            }
            radar.update(book, sharp, now, ledger.settings(), false); // düşen oran radarına ek ölçüm
            Closing.capture(ledger, sharp, now);
            forecasts.record(Matching.match(book, sharp), now); // maç öncesi en güncel tahmin
            return out;
        }
    }

    /**
     * Zamanlanmış olaylar: maçtan 10 dk önce kapanış oranı, ilk maçtan 90 dk önce oynanmamış
     * önerinin otomatik kontrolü. Gösterilecek bildirimleri [başlık, metin] olarak döndürür.
     */
    List<String[]> events() {
        List<String[]> out = new ArrayList<>();
        synchronized (LOCK) {
            if (ledger.settings().oddsApiKey.isEmpty()) return out;
            Instant now = Instant.now();
            lastEventRun = now;
            Set<String> due = Closing.dueLeagues(ledger, now);
            if (!due.isEmpty()) {
                try {
                    Daily.LiveSources cs = live();
                    List<Models.SharpEvent> closing = cs.sharp(due);
                    Closing.capture(ledger, closing, now);
                    forecasts.recordSharp(closing, now);
                    after(cs);
                } catch (Http.ProviderException ignored) {
                    // kapanış alınamadı; pencere içinde 10 dk sonra yeniden denenir
                }
            }
            // Aynı anda vadesi gelen tüm kuponlar tek çekimle kontrol edilir
            List<Long> dueIds = new ArrayList<>();
            for (Ledger.Coupon c : Recheck.allDueForPrecheck(ledger, now)) dueIds.add(c.id);
            if (!dueIds.isEmpty()) {
                try {
                    Map<Long, Map<String, Object>> checks = recheckAll(dueIds);
                    for (Long id : dueIds) {
                        Map<String, Object> r = checks.get(id);
                        boolean ok = Boolean.TRUE.equals(r.get("playable"));
                        long stake = Daily.applyCheck(ledger, id, r, ledger.settings());
                        if (stake > 0) {
                            Ledger.Coupon played = ledger.coupon(id);
                            StringBuilder odds = new StringBuilder();
                            for (Ledger.Leg l : played.legs) {
                                odds.append(l.home).append(" - ").append(l.away).append(": ")
                                        .append(Models.outcomeLabel(l.market, l.outcome)).append(" @ ").append(Fmt.odds(l.odds)).append('\n');
                            }
                            out.add(new String[] {"Kupon #" + id + " oyna: " + Fmt.tl(stake) + " · oran " + Fmt.odds(played.totalOdds),
                                    "Güncel oranlarla hâlâ avantajlı; kasadan düşüldü (otomatik takip). Bilyoner'de bu oranlarla oyna:\n"
                                            + odds + "Oynamadıysan uygulamada 'Oynamadım, geri al'a bas."});
                        } else {
                            out.add(new String[] {ok ? "Kupon #" + id + " hâlâ oynanabilir" : "Kupon #" + id + ": oynama",
                                    String.valueOf(r.get("verdict"))});
                        }
                    }
                } catch (Http.ProviderException e) {
                    out.add(new String[] {dueIds.size() == 1 ? "Kupon #" + dueIds.get(0) + " kontrol edilemedi" : dueIds.size() + " kupon kontrol edilemedi",
                            "Oynamadan önce uygulamada elle kontrol et. (" + e.getMessage() + ")"});
                }
            }
        }
        return out;
    }

    /** Sonraki olay zamanı: kapanış ya da maç öncesi kontrol (yoksa null). */
    Instant nextEventTime() {
        Instant now = Instant.now();
        Instant a = Closing.nextCaptureTime(ledger, now), b = Recheck.nextPrecheckTime(ledger, now);
        Instant next = a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
        if (next != null && !next.isAfter(now)) {
            // vadesi gelmiş iş: hemen. Son 10 dk içinde zaten denendiyse (ağ hatası, kapanış oranı
            // yayında yok) 10 dk sonra; böylece döngüye girip kredi harcamaz.
            Instant last = lastEventRun;
            next = last != null && last.isAfter(now.minusSeconds(600)) ? last.plusSeconds(600) : now.plusSeconds(30);
        }
        return next;
    }

    void recordForecasts(Daily.Result r) {
        if (r != null && r.book != null && r.sharp != null) forecasts.record(Matching.match(r.book, r.sharp), Instant.now());
    }

    /** Günün karar penceresindeki maç saatleri (radar zamanlaması için) saklanır. */
    void storeKickoffs(Daily.Result r) {
        if (r == null || r.decision == null || r.decision.kickoffs.isEmpty()) return;
        StringBuilder b = new StringBuilder();
        for (Instant k : r.decision.kickoffs) b.append(b.length() == 0 ? "" : ",").append(k.getEpochSecond());
        prefs.edit().putString("kickoffs", b.toString()).apply();
    }

    /** Bugünün radar saatleri: maç saatlerine göre; bilgi yoksa null (sabit saatler). */
    List<Instant> radarTimes(Instant now) {
        String raw = prefs.getString("kickoffs", "");
        List<Instant> kos = new ArrayList<>();
        for (String p : raw.split(",")) {
            try {
                if (!p.isEmpty()) kos.add(Instant.ofEpochSecond(Long.parseLong(p)));
            } catch (NumberFormatException ignored) {
                // bozuk kayıt: atla
            }
        }
        return ScanPlan.times(kos, effective().radarScans, now.atOffset(Fmt.TR).toLocalDate());
    }

    /** Son events() çalışması (yeniden deneme aralığı için). */
    private volatile Instant lastEventRun;

    static final class BackgroundResult {
        List<String> settleMessages = new ArrayList<>();
        Daily.Result daily;
        boolean setupNeeded;
    }
}
