package app.bilincli.android;

import android.content.Context;
import android.content.SharedPreferences;
import app.bilincli.core.Calibration;
import app.bilincli.core.Closing;
import app.bilincli.core.Daily;
import app.bilincli.core.DemoSim;
import app.bilincli.core.Fmt;
import app.bilincli.core.Http;
import app.bilincli.core.Json;
import app.bilincli.core.Ledger;
import app.bilincli.core.Models;
import app.bilincli.core.Radar;
import app.bilincli.core.Recheck;
import app.bilincli.core.Settings;
import java.io.File;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
        String m = memoryStore.read();
        memory = m == null || m.trim().isEmpty() ? new java.util.LinkedHashMap<String, Object>() : Json.parseObject(m);
    }

    /** Canlı çağrıdan sonra: doğrulama hafızasını ve kalan API kredisini sakla. */
    void after(Daily.LiveSources src) {
        memoryStore.write(Json.write(memory));
        String rem = src.remainingCredits();
        if (rem != null) prefs.edit().putString("credits", rem).putLong("creditsAt", System.currentTimeMillis()).apply();
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
            Daily.LiveSources src = live();
            try {
                Daily.Fetch f = Daily.fetch(src, Instant.now());
                return Daily.intraday(ledger, f.book, f.sharp, radar);
            } finally {
                after(src);
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
        Daily.LiveSources src = new Daily.LiveSources(new Http.UrlHttp(), ledger.settings());
        src.memory = memory;
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
            Daily.LiveSources src = live();
            out.settleMessages = Daily.settle(ledger, src);
            String today = Fmt.dayKey(Instant.now());
            boolean due = !Instant.now().isBefore(todaysRunTime()) && ledger.runFor(today) == null;
            if (dailyTrigger || due) {
                Daily.Result r = Daily.generate(ledger, src, false, false, radar);
                if (!r.skipped) out.daily = r;
            }
            after(src);
        }
        return out;
    }

    /** Kuponu güncel iddaa + Pinnacle oranlarıyla yeniden değerlendirir ve sonucu kaydeder. */
    Map<String, Object> recheck(long couponId) throws Http.ProviderException {
        synchronized (LOCK) {
            Ledger.Coupon c = ledger.coupon(couponId);
            Daily.LiveSources src = live();
            Set<String> leagues = new LinkedHashSet<>();
            for (Ledger.Leg l : c.legs) if (l.sportKey != null) leagues.add(l.sportKey);
            List<Models.BookEvent> book = src.book();
            List<Models.SharpEvent> sharp = src.sharp(leagues);
            // Karşılıklı Gol bacakları: Pinnacle oranı maç bazında çekilir
            for (Ledger.Leg l : c.legs) {
                if (!"KG".equals(l.market)) continue;
                for (Models.SharpEvent ev : sharp) {
                    if (ev.ref.equals(l.sharpRef)) {
                        try {
                            src.enrich(ev, "btts");
                        } catch (Http.ProviderException ignored) {
                            // adil oran alınamazsa bacak "adil oran yok" olarak raporlanır
                        }
                    }
                }
            }
            Calibration.apply(book, sharp, memory);
            after(src);
            Instant now = Instant.now();
            Map<String, Object> r = Recheck.run(c, book, sharp, now, ledger.settings(), ledger.balance());
            ledger.saveCheck(couponId, r);
            radar.update(book, sharp, now, ledger.settings(), false); // düşen oran radarına ek ölçüm
            Closing.capture(ledger, sharp, now);
            return r;
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
            Set<String> due = Closing.dueLeagues(ledger, now);
            if (!due.isEmpty()) {
                try {
                    Closing.capture(ledger, live().sharp(due), now);
                } catch (Http.ProviderException ignored) {
                    // kapanış alınamadı; CLV o bacak için boş kalır
                }
            }
            Ledger.Coupon c = Recheck.dueForPrecheck(ledger, now);
            if (c != null) {
                try {
                    Map<String, Object> r = recheck(c.id);
                    boolean ok = Boolean.TRUE.equals(r.get("playable"));
                    out.add(new String[] {ok ? "Kupon #" + c.id + " hâlâ oynanabilir" : "Kupon #" + c.id + ": oynama",
                            String.valueOf(r.get("verdict"))});
                } catch (Http.ProviderException e) {
                    out.add(new String[] {"Kupon #" + c.id + " kontrol edilemedi",
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
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    static final class BackgroundResult {
        List<String> settleMessages = new ArrayList<>();
        Daily.Result daily;
        boolean setupNeeded;
    }
}
