package app.bilincli.android;

import android.content.Context;
import android.content.SharedPreferences;
import app.bilincli.core.Altili;
import app.bilincli.core.AltiliLog;
import app.bilincli.core.AtYarisi;
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
import app.bilincli.core.Pick;
import app.bilincli.core.Radar;
import app.bilincli.core.Recheck;
import app.bilincli.core.ScanPlan;
import app.bilincli.core.Settings;
import app.bilincli.core.Settlement;
import app.bilincli.core.Texts;
import app.bilincli.core.Tjk;
import app.bilincli.core.Virtual;
import app.bilincli.core.Zirve;
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
    /** Sanal takip: pas günlerinde en yakın seçimin 100 TL'lik sanal bahis sonucu (kasaya dokunmaz). */
    final Virtual virtual;
    private final Map<String, Object> memory;
    /** Bilyoner Zirve Oran: son değerlendirme ve bildirilen oranlar (zirve.json). */
    private final FileStorage zirveStore;
    private final Object zirveLock = new Object();
    /** Son altılı AGF okuması: TJK (2.19), Bilyoner ve Nesine (2.20) (agf.json). */
    private final FileStorage agfStore;
    /** 2.26: altılı defteri (öneriler, sonuçlar, öğrenme; altili_defter.json). */
    private final FileStorage altiliLogStore;
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
        zirveStore = new FileStorage(new File(app.getFilesDir(), "zirve.json"));
        agfStore = new FileStorage(new File(app.getFilesDir(), "agf.json"));
        altiliLogStore = new FileStorage(new File(app.getFilesDir(), "altili_defter.json"));
        virtual = new Virtual(new FileStorage(new File(app.getFilesDir(), "sanal.json")));
        String m = memoryStore.read();
        memory = m == null || m.trim().isEmpty() ? new java.util.LinkedHashMap<String, Object>() : Json.parseObject(m);
        loadActive();
        switchToMaxProfit();
    }

    /**
     * 2.7: kullanıcı en yüksek kazancı istedi; 2.8'de yeniden ("max kâra odaklan"). Profil "En yüksek
     * kazanç" değilse (Temkinli ya da özel) bir kez ona geçirilir (2.7'nin değerleri ayar dosyasında
     * zaten taşınır); arayüz bir hafta boyunca yeni ayarları ve nasıl geri alınacağını gösterir.
     * Sonradan elle seçilen profile dokunulmaz.
     */
    private void switchToMaxProfit() {
        if (prefs.getBoolean("maxProfit28", false)) return;
        try {
            Settings s = ledger.settings();
            if (!"yuksek".equals(s.detectProfile())) {
                s.applyProfile("yuksek");
                ledger.saveSettings(s);
            }
            prefs.edit().putLong("profileSwitchedAt", System.currentTimeMillis()).apply();
        } catch (RuntimeException ignored) {
            // ayar kaydedilemedi: kullanıcı profili elle seçebilir
        }
        prefs.edit().putBoolean("maxProfit28", true).apply();
    }

    /** Profilin otomatik geçirildiği an (ms); son 7 gün içinde değilse 0. */
    long profileSwitchedAt() {
        long at = prefs.getLong("profileSwitchedAt", 0);
        return at > 0 && System.currentTimeMillis() - at < 7 * 86400000L ? at : 0;
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

    /**
     * Oran kaynağındaki ek basketbol ligleri {kod, ad} (EuroLeague ve NBA dışında; ör. WNBA,
     * Avustralya NBL). Liste milli turnuvalarla birlikte günde bir kez ücretsiz alınır.
     */
    List<String[]> basketLeagues() {
        List<String[]> out = new ArrayList<>();
        for (String row : prefs.getString("basket", "").split(";")) {
            int i = row.indexOf('|');
            if (i > 0) out.add(new String[] {row.substring(0, i), row.substring(i + 1) + " (basketbol)"});
        }
        for (String[] r : out) Settings.EXTRA_NAMES.put(r[0], r[1]);
        return out;
    }

    /** Aktif milli turnuvaları ve ek basketbol liglerini günde bir kez (ücretsiz spor listesinden) yeniler. */
    void refreshInternationals(Daily.LiveSources src, boolean force) {
        String today = Fmt.dayKey(Instant.now());
        // ek basketbol listesi 2.5'te geldi: güncellemeden sonra o gün beklemeden alınır
        if (!force && today.equals(prefs.getString("intlDay", "")) && prefs.contains("basket")) return;
        try {
            StringBuilder b = new StringBuilder(), bb = new StringBuilder();
            for (String[] s : src.sports()) {
                String row = s[0] + "|" + s[1].replace(";", ",").replace("|", "/");
                if (OddsApi.isInternational(s[0])) {
                    if (b.length() > 0) b.append(';');
                    b.append(row);
                } else if (OddsApi.isExtraBasketball(s[0])) {
                    if (bb.length() > 0) bb.append(';');
                    bb.append(row);
                }
            }
            prefs.edit().putString("intl", b.toString()).putString("basket", bb.toString()).putString("intlDay", today).apply();
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
        if (s.basketExtras) { // 2.12: kaynakta aktif ek basketbol ligleri (NBA hazırlık, WNBA, NBL...)
            for (String[] r : basketLeagues()) if (!s.leagues.contains(r[0])) s.leagues.add(r[0]);
        }
        return s;
    }

    /** Bugünün pencere maç sayıları (ücretsiz listeden) ve öğrenildiği an. */
    private volatile Map<String, Integer> activeToday;
    private volatile Instant activeAt;

    /**
     * Pencere maç sayıları bu kadar yeniyse yeniden sorulmaz (2.8.1): kredi bolken sorgu ~40 ligi
     * kapsar ve her "Kupon üret" / "Şimdi tara"dan önce yinelenmesi işlemi dakikalarca uzatıyordu.
     */
    static final long PROBE_FRESH_S = 45 * 60;
    /** Maç listesi sorgusunda aynı anda en fazla bu kadar istek (ücretsiz uç). */
    static final int PROBE_THREADS = 4;
    /** Pinnacle oranlarında aynı anda en fazla bu kadar lig (2.16). */
    static final int ODDS_THREADS = 4;

    /** Taranacak liglerde karar penceresinde kaç maç var (kota harcamaz); planı doğru kurmak için. */
    void probeActive() {
        probeActive(false);
    }

    /** force: son sorgu yeni olsa da yeniden sor ("Kaynakları test et", "Bugün oynayanları öğren"). */
    void probeActive(boolean force) {
        final Settings s = scanSettings();
        if (s.oddsApiKey.isEmpty()) return;
        List<String> leagues = new ArrayList<>(s.leagues);
        if (roomToExpand(s)) { // kredi bol: plan ek lig seçebilsin diye onlarınki de (ücretsiz)
            for (String[] l : Settings.KNOWN_LEAGUES) if (!leagues.contains(l[0])) leagues.add(l[0]);
        }
        Map<String, Integer> known = activeToday;
        Instant at = activeAt;
        if (!force && known != null && at != null && at.isAfter(Instant.now().minusSeconds(PROBE_FRESH_S))
                && known.keySet().containsAll(leagues)) return;
        Map<String, Integer> counts = probeCounts(s, leagues);
        if (counts == null) return; // bilinmiyor: plan ortalama payla kurulur
        activeToday = counts;
        activeAt = Instant.now();
        Map<String, Object> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) raw.put(e.getKey(), (long) e.getValue());
        prefs.edit().putString("active", Json.write(raw)).putLong("activeAt", activeAt.toEpochMilli()).apply();
    }

    /** Ligleri paralel sorar (her iş parçacığı kendi kaynağıyla); hiçbiri alınamadıysa null. */
    private Map<String, Integer> probeCounts(final Settings s, final List<String> leagues) {
        final Instant now = Instant.now();
        final Thread owner = Thread.currentThread();
        final int total = leagues.size();
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        step("Bugün oynayan ligler belirleniyor (ücretsiz, " + total + " lig)…");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Math.min(PROBE_THREADS, total)));
        try {
            List<java.util.concurrent.Future<Map<String, Integer>>> parts = new ArrayList<>();
            for (final String l : leagues) {
                parts.add(pool.submit(new java.util.concurrent.Callable<Map<String, Integer>>() {
                    @Override
                    public Map<String, Integer> call() throws Exception {
                        Map<String, Integer> m = new Daily.LiveSources(new AndroidHttp(app), s)
                                .activeCounts(java.util.Collections.singletonList(l), now);
                        step(owner, "Bugün oynayan ligler belirleniyor (ücretsiz): " + done.incrementAndGet() + "/" + total);
                        return m;
                    }
                }));
            }
            Map<String, Integer> out = new LinkedHashMap<>();
            boolean any = false;
            for (int i = 0; i < parts.size(); i++) {
                Integer n = -1;
                try {
                    Map<String, Integer> m = parts.get(i).get(90, java.util.concurrent.TimeUnit.SECONDS);
                    if (m != null && m.get(leagues.get(i)) != null) n = m.get(leagues.get(i));
                } catch (Exception e) {
                    // bu lig bilinmiyor (-1): plan ortalama payla tahmin eder
                }
                if (n >= 0) any = true;
                out.put(leagues.get(i), n);
            }
            return any ? out : null;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Arayüzün beklediği işlem (Bridge) için ilerleme; null: arayüz beklemiyor. */
    private volatile Daily.Progress uiProgress;
    private volatile Thread uiThread;

    void beginUi(Daily.Progress p) {
        uiThread = Thread.currentThread();
        uiProgress = p;
    }

    void endUi() {
        uiProgress = null;
        uiThread = null;
    }

    /** Arayüzün beklediği işleme ilerleme metni (arayüz beklemiyorsa yok sayılır). */
    void step(String text) {
        step(Thread.currentThread(), text);
    }

    /**
     * origin: adımı atan işin iş parçacığı. Arayüzün işlemi değilse (arka plan taraması kilidi tutuyor),
     * kullanıcı neyin beklendiğini görsün diye öyle yazılır.
     */
    void step(Thread origin, String text) {
        Daily.Progress p = uiProgress;
        if (p == null || text == null) return;
        p.step(origin == uiThread ? text : "Arka plandaki işlem sürüyor · " + text);
    }

    private final Daily.Progress stepper = new Daily.Progress() {
        @Override
        public void step(String text) {
            Repo.this.step(text);
        }
    };

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

    /** Zirve Oran en sık bu aralıkla indirilir (zorlanmadıkça): uygulama her açıldığında siteye gidilmesin. */
    static final long ZIRVE_MIN_GAP_S = 10 * 60;

    /** Zirve işleminin ekrandaki durumu; arayüz bir işlemi bekliyorsa ilerleme olarak da gösterilir. */
    private void zirveBusy(String text) {
        zirveBusy = text;
        step(text);
    }

    /** Son yazılan Zirve durumu (arayüz her çizimde dosya okumasın). */
    private volatile String zirveRaw;

    private Map<String, Object> zirveState() {
        try {
            String raw = zirveRaw != null ? zirveRaw : zirveStore.read();
            if (raw != null && !raw.trim().isEmpty()) {
                zirveRaw = raw;
                return Json.parseObject(raw);
            }
        } catch (RuntimeException ignored) {
            // bozuk kayıt: yeniden okunur
        }
        return new LinkedHashMap<>();
    }

    private volatile String agfRaw;
    private final Object agfLock = new Object();

    private Map<String, Object> agfStored() {
        try {
            String raw = agfRaw != null ? agfRaw : agfStore.read();
            if (raw == null || raw.trim().isEmpty()) return null;
            agfRaw = raw;
            return Json.parseObject(raw);
        } catch (RuntimeException e) {
            return null; // bozuk kayıt: yeniden okunur
        }
    }

    /** Altılı girdileri (2.21): devir/havuz altılı başına ve yalnızca girildiği gün; bütçe üst sınırı kalıcı. */
    private Map<String, Object> altiliInputs() {
        try {
            Map<String, Object> m = Json.parseObject(prefs.getString("altiliInputs", "{}"));
            if (!Fmt.dayKey(Instant.now()).equals(Json.str(m, "day"))) { // dünün devri ve elle girilen ayakları geçersiz
                m.remove("byKey");
                m.remove("manual");
            }
            return m;
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** Arayüzden: birim/kesinti/vergi/otomatik (ayar), seçili altılının devri ve havuzu, bütçe sınırı, elle ayaklar. */
    void saveAltiliInputs(Map<String, Object> p) {
        Settings s = ledger.settings();
        if (p.get("unit") instanceof Number) s.altiliUnit = Json.dbl(p, "unit", s.altiliUnit);
        if (p.get("takeout") instanceof Number) s.altiliTakeout = Json.dbl(p, "takeout", s.altiliTakeout);
        if (p.get("tax") instanceof Number) s.altiliTax = Json.dbl(p, "tax", s.altiliTax);
        if (p.get("budgetShare") instanceof Number) s.altiliBudgetShare = Json.dbl(p, "budgetShare", s.altiliBudgetShare);
        if (p.get("legMin") instanceof Number) s.altiliLegMin = Json.dbl(p, "legMin", s.altiliLegMin);
        if (p.get("minCost") instanceof Number) s.altiliMinCost = Json.dbl(p, "minCost", s.altiliMinCost);
        if (p.get("cap") instanceof Number) s.altiliCap = Json.dbl(p, "cap", s.altiliCap);
        if (p.get("minHit") instanceof Number) s.altiliMinHit = Json.dbl(p, "minHit", s.altiliMinHit);
        if (p.get("minEdge") instanceof Number) s.altiliMinEdge = Json.dbl(p, "minEdge", s.altiliMinEdge);
        if (p.get("playNoEdge") instanceof Boolean) s.altiliPlayNoEdge = (Boolean) p.get("playNoEdge");
        if (p.get("minHitAuto") instanceof Boolean) s.altiliMinHitAuto = (Boolean) p.get("minHitAuto");
        if (p.get("capAuto") instanceof Boolean) s.altiliCapAuto = (Boolean) p.get("capAuto");
        if (p.get("playUnproven") instanceof Boolean) s.altiliPlayUnproven = (Boolean) p.get("playUnproven");
        if (p.get("auto") instanceof Boolean) s.altiliAuto = (Boolean) p.get("auto");
        String err = s.validate();
        if (err != null) throw new IllegalArgumentException(err);
        ledger.saveSettings(s);
        Map<String, Object> m = altiliInputs();
        m.put("day", Fmt.dayKey(Instant.now()));
        if (p.get("maxCost") instanceof Number) m.put("maxCost", Math.max(0, Json.dbl(p, "maxCost", 0)));
        String key = Json.str(p, "key");
        if (key != null && (p.get("devir") instanceof Number || p.get("pool") instanceof Number)) {
            Map<String, Object> by = Json.obj(m.get("byKey"));
            by = by == null ? new LinkedHashMap<String, Object>() : by;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("devir", Math.max(0, Json.dbl(p, "devir", 0)));
            v.put("pool", Math.max(0, Json.dbl(p, "pool", 0)));
            by.put(key, v);
            m.put("byKey", by);
        }
        if (p.get("legs") instanceof List) m.put("manual", Json.arr(p.get("legs")));
        prefs.edit().putString("altiliInputs", Json.write(m)).apply();
    }

    /**
     * Planın girdileri: birim fiyat, devir ve havuz sitede okunduysa oradan (2.22), elle girilen devir/havuz önce gelir;
     * yoksa ayardaki birim fiyat. a.inputsFrom: "okundu" / "elle" / null.
     */
    private Altili.Inputs altiliInputsFor(String key, Map<String, Object> inp, Map<String, Object> a) {
        Settings s = ledger.settings();
        Altili.Inputs in = new Altili.Inputs();
        in.unit = Json.dbl(a, "unit", s.altiliUnit);
        in.takeout = s.altiliTakeout;
        in.tax = s.altiliTax;
        in.kasa = ledger.balance() / 100.0;
        in.km = s.kellyMultiplier;
        in.maxFraction = s.altiliBudgetShare; // 2.29: kârlı kuponda da üst sınır kasanın %7'si
        in.maxCost = s.altiliCap; // 2.32.10: kupon en fazla (2.35.1: varsayılan 600 TL; her ayak sınırı sığmazsa oynanmaz; 0: kasanın payı)
        // 2.29: kupon bütçesi kasanın %7'si (kullanıcı ayarı; öğrenilen bütçe yalnızca karnede karşılaştırılır)
        in.budget = Math.max(10, Math.floor(s.altiliBudgetShare * in.kasa / 10) * 10);
        in.legMin = s.altiliLegMin; // her ayakta en az legMin tutma (2.33: varsayılan yok)
        in.minHit = s.altiliMinHit; // 2.37: 6/6 olasılığı düşük altılı oynanmaz
        if (s.altiliMinHitAuto && altiliLearn != null) in.minHit = Json.dbl(altiliLearn, "minHitUse", s.altiliMinHit); // 2.49: karnedeki en iyi eşik
        if (s.altiliCapAuto && s.altiliCap > 0 && altiliLearn != null && altiliLearn.get("capUse") instanceof Number) {
            double cu = Json.dbl(altiliLearn, "capUse", s.altiliCap); // 2.51: kupon büyüklüğü kasaya göre
            if (cu < s.altiliCap - 1e-9) {
                in.maxCost = cu;
                in.refCost = s.altiliCap; // tahmin edilebilirlik ayardaki tavanla
            }
        }
        in.minEdge = s.altiliMinEdge; // 2.45: kâr kuralı (beklenen dönüş en az %110)
        in.profitRule = !s.altiliPlayNoEdge; // 2.48: 6/6 modu açıksa kârsız günde de kurallara uyan altılı önerilir
        in.maxHit = s.altiliCap > 0; // 2.33: tavan varsa kupon tavana kadar en yüksek tutmayla (para önce zayıf ayaklara)
        in.minCost = s.altiliMinCost; // 2.31: kupon en az minCost TL (varsayılan 60); üstüne yalnızca kârlı atlar (2.32)
        Map<String, Object> learn = altiliLearn;
        if (learn != null) { // 2.26: öğrenilen AGF düzeltmesi; 2.36: önce altılının bölgesine (yerli/yabancı) ait olan
            AltiliLog.applyLearn(in, learn, AltiliLog.domestic(a)); // 2.51: favori düzeltmesi; 2.57: ikramiye modeli
        }
        Map<String, Object> by = Json.obj(Json.obj(inp.get("byKey")) == null ? null : Json.obj(inp.get("byKey")).get(key));
        if (by != null) {
            in.devir = Json.dbl(by, "devir", 0);
            in.pool = Json.dbl(by, "pool", 0);
            a.put("inputsFrom", "elle");
        } else if (a.get("devir") != null || a.get("pool") != null) {
            in.devir = Json.dbl(a, "devir", 0);
            in.pool = Json.dbl(a, "pool", 0);
            a.put("inputsFrom", "okundu");
        }
        return in;
    }

    /**
     * 2.51: kupon kasaya göre küçültüldüyse ayardaki tavanla kurulan kupon (defter ve karne bununla: eşik ve tarama aynı
     * ölçüde kalsın); küçültülmediyse null. in değişir (son kullanım).
     */
    private static Map<String, Object> refPlan(List<String> legs, Altili.Inputs in) {
        if (!(in.refCost > 0)) return null;
        in.maxCost = in.refCost;
        in.refCost = 0;
        return Altili.plan(legs, in);
    }

    /**
     * Arayüz için son altılı okuması ({at, day, altilis: [.. + plan, devir, pool], log, inputs}); elle girilen ayaklar
     * "Elle girilen" altılısı olarak eklenir. Hiç veri yoksa ya da demodaysa null. Planlar her seferinde güncel kasa ve
     * girdilerle hesaplanır.
     */
    Map<String, Object> agfView() {
        if (isDemo()) return null;
        Settings st = ledger.settings();
        String cacheKey = (agfRaw == null ? 0 : agfRaw.hashCode()) + "|" + (altiliLogRaw == null ? 0 : altiliLogRaw.hashCode()) + "|" // 2.46: büyük defter
                + prefs.getString("altiliLive", "") + "|" + prefs.getString("altiliInputs", "") + "|" + ledger.balance() + "|" + st.altiliUnit + "|"
                + st.altiliTakeout + "|" + st.altiliTax + "|" + st.altiliBudgetShare + "|" + st.altiliLegMin + "|" + st.altiliMinCost + "|" + st.altiliCap + "|" + st.altiliMinHit + "|" + st.altiliMinHitAuto + "|" + st.altiliCapAuto + "|" + st.altiliPlayUnproven + "|" + st.altiliMinEdge + "|" + st.altiliPlayNoEdge + "|" + st.kellyMultiplier + "|" + st.maxStakeFraction + "|" + Fmt.dayKey(Instant.now());
        Object[] cached = agfViewCache;
        if (cached != null && cacheKey.equals(cached[0]) && agfRaw != null) return Json.obj(cached[1]);
        Map<String, Object> v = agfViewFresh();
        if (agfRaw != null) agfViewCache = new Object[] {cacheKey, v}; // aynı veri ve girdilerle planlar yeniden aranmaz
        return v;
    }

    /** Son görünüm: {anahtar, görünüm}. */
    private volatile Object[] agfViewCache;

    /** Son analizdeki öğrenilenler (planlar bununla kurulur). */
    private volatile Map<String, Object> altiliLearn;
    private volatile String altiliLogRaw;
    private final Object altiliLogLock = new Object();

    private Map<String, Object> altiliLog() {
        try {
            String raw = altiliLogRaw != null ? altiliLogRaw : altiliLogStore.read();
            if (raw != null && !raw.trim().isEmpty()) {
                altiliLogRaw = raw;
                Map<String, Object> log = Json.parseObject(raw);
                if (AltiliLog.migrate(log)) saveAltiliLog(log); // 2.35–2.36: atlarla eşleşmiş eski sonuçlar etiketlenir
                return log;
            }
        } catch (RuntimeException ignored) {
            // bozuk defter: yeniden başlar
        }
        return new LinkedHashMap<>();
    }

    private void saveAltiliLog(Map<String, Object> log) {
        String raw = Json.write(log);
        altiliLogStore.write(raw);
        altiliLogRaw = raw;
    }

    private Map<String, Object> agfViewFresh() {
        Map<String, Object> stored = agfStored(), inp = altiliInputs();
        Settings ls = ledger.settings();
        Map<String, Object> learn = AltiliLog.analyze(altiliLog(), ls.altiliUnit, ledger.balance() / 100.0, Fmt.dayKey(Instant.now()), ls.altiliLegMin, ls.altiliMinHit, ls.altiliMinEdge,
                ls.altiliMinHitAuto, ls.altiliCap, ls.kellyMultiplier);
        altiliLearn = learn; // planlar öğrenilenlerle kurulur
        List<Object> manual = Json.arr(inp.get("manual"));
        if (stored == null && manual.size() < 6) return null;
        Map<String, Object> v = stored == null ? new LinkedHashMap<String, Object>() : stored;
        List<Object> list = new ArrayList<>();
        if (manual.size() >= 6) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("key", "manual");
            a.put("source", "Elle");
            a.put("label", "Elle girilen");
            a.put("legs", manual);
            list.add(a);
        }
        list.addAll(Json.arr(v.get("altilis")));
        Map<String, Object> byKey = Json.obj(inp.get("byKey"));
        for (Object o : list) {
            Map<String, Object> a = Json.obj(o);
            String key = Json.str(a, "key");
            List<String> legs = new ArrayList<>();
            for (Object l : Json.arr(a.get("legs"))) legs.add(String.valueOf(l));
            Altili.Inputs in = altiliInputsFor(key, inp, a);
            Map<String, Object> plan = Altili.plan(legs, in);
            Map<String, Object> ref = refPlan(legs, in); // 2.51: defter (karne) tavandaki kuponla tutulur
            if (ref != null) a.put("planRef", ref);
            // 2.42 → 2.50: karnede kuponları en iyi dönen bölge dışındaki (yerli/yabancı) altılı önerilmez
            Map<String, Object> rt = Json.obj(Json.obj(learn.get("regionTrust")) == null ? null
                    : Json.obj(learn.get("regionTrust")).get(AltiliLog.domestic(a) ? "yerli" : "yabancı"));
            if (rt != null && Json.bool(rt, "weak", false) && !"manual".equals(key) && plan.get("error") == null) {
                plan.put("regionWeak", true);
                plan.put("regionCoupons", rt.get("coupons"));
                plan.put("regionBest", learn.get("scopeUse"));
                plan.put("region", AltiliLog.domestic(a) ? "yerli" : "yabancı");
            }
            // 2.53: kupon büyüklüğü taramasında hiçbir büyüklükte kasa büyümüyor: kasa bu strateji için yetersiz, önerilmez
            // (Ayarlar'da "kasaya göre otomatik" kapalıysa kullanıcı bilerek tavandaki kuponu oynar)
            Map<String, Object> stk = Json.obj(learn.get("stakes"));
            if (stk != null && Json.bool(stk, "neg", false) && ls.altiliCapAuto && !"manual".equals(key) && plan.get("error") == null
                    && !Json.bool(plan, "play", false)) {
                plan.put("kasaLow", true);
                plan.put("kasa", stk.get("kasa"));
                for (Object o2 : Json.arr(stk.get("rows"))) {
                    Map<String, Object> row = Json.obj(o2);
                    if (Math.abs(Json.dbl(row, "cap", 0) - ls.altiliCap) < 1e-9 && row.get("kasaNeed") != null) {
                        plan.put("kasaNeed", row.get("kasaNeed"));
                        plan.put("kasaRec", row.get("kasaRec"));
                    }
                }
            }
            // 2.57: geriye dönük sınamada kaybettiren strateji önerilmez: değer kuponu (oyna) için son dönem testi, 6/6 kuralı için
            // son dönem sınaması (Ayarlar'da "kanıtlanmamış stratejiyle de öner" açıksa önerilir)
            if (!ls.altiliPlayUnproven && !"manual".equals(key) && plan.get("error") == null) {
                boolean play = Json.bool(plan, "play", false);
                Map<String, Object> vs = Json.obj(learn.get("value"));
                if (play ? vs == null || !Json.bool(vs, "proven", false) : Json.bool(learn, "ruleUnproven", false)) plan.put("unproven", play ? "value" : "rule");
            }
            a.put("plan", plan);
            a.put("devir", in.devir);
            a.put("pool", in.pool);
            a.put("unit", in.unit);
        }
        v.put("altilis", list);
        Map<String, Object> bf = altiliBackfillState(); // 2.46: geriye dönük taramanın durumu
        if (bf != null) v.put("backfill", bf);
        Map<String, Object> live = altiliLiveStored(); // 2.34: ayak sonuçları
        if (live != null && Fmt.dayKey(Instant.now()).equals(Json.str(live, "day"))) {
            Map<String, Object> byKeyLive = Json.obj(live.get("byKey"));
            for (Object o : list) {
                Map<String, Object> a = Json.obj(o);
                Object w = byKeyLive == null ? null : byKeyLive.get(Json.str(a, "key"));
                if (w != null) a.put("legWinners", w);
            }
            v.put("liveAt", live.get("at"));
            v.put("liveLog", live.get("log"));
        }
        // 2.24: günün en mantıklı kuponu (başlamış altılılar hariç)
        String clock = Instant.now().atOffset(Fmt.TR).toLocalTime().toString().substring(0, 5);
        List<Map<String, Object>> plans = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> a = Json.obj(o);
            String start = Json.str(a, "start");
            plans.add(start != null && start.compareTo(clock) <= 0 ? null : Json.obj(a.get("plan")));
        }
        // önce bugünün altılıları; bugün yoksa yarınki (TJK'da günü yazılı olanlar)
        String today = Instant.now().atOffset(Fmt.TR).toLocalDate().toString();
        List<Map<String, Object>> todays = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String d = Json.str(Json.obj(list.get(i)), "day");
            todays.add(d == null || d.equals(today) ? plans.get(i) : null);
        }
        int best = Altili.best(todays);
        if (best < 0) best = Altili.best(plans);
        // 2.28: çipler en mantıklıdan başlayarak (önce bugün, kâr ve tutma birlikte: kasanın beklenen büyümesi)
        List<Object> order = new ArrayList<>();
        for (int i : Altili.order(todays)) if (todays.get(i) != null) order.add((long) i);
        for (int i : Altili.order(plans)) if (!order.contains((long) i)) order.add((long) i);
        v.put("order", order);
        v.put("best", best < 0 ? null : Json.str(Json.obj(list.get(best)), "key"));
        v.put("learn", learn);
        v.put("settleLog", altiliLog().get("settleLog"));
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("maxCost", Json.dbl(inp, "maxCost", 0));
        shown.put("manual", manual);
        shown.put("hasByKey", byKey != null && !byKey.isEmpty());
        v.put("inputs", shown);
        return v;
    }

    /**
     * Günün altılılarının AGF'leri (ve varsa ganyan oranları): önce Bilyoner ve Nesine, sonra TJK. Hepsi saklanır
     * (kredi harcamaz); altılılar sırayla, okuma ayrıntısı kaynak kaynak.
     */
    Map<String, Object> agfRead(Daily.Progress progress) {
        synchronized (agfLock) {
            Instant now = Instant.now();
            AndroidHttp http = new AndroidHttp(app);
            Map<String, Object> sites = AtYarisi.read(http, now, progress);
            java.time.LocalDate today = now.atOffset(Fmt.TR).toLocalDate(), tomorrow = today.plusDays(1);
            Map<String, Object> tjk = Tjk.read(http, today, now, progress);
            // 2.25.2: Bilyoner yarının altılısını önceden satar; bugün koşu yoksa (ya da bittiyse) yarınki de okunur
            Map<String, Object> next = Tjk.read(http, tomorrow, now, progress);
            List<Object> altilis = new ArrayList<>(Json.arr(sites.get("altilis")));
            altilis.addAll(Json.arr(tjk.get("altilis")));
            String prefix = String.format(java.util.Locale.ROOT, "%02d.%02d · ", tomorrow.getDayOfMonth(), tomorrow.getMonthValue());
            for (Object o : Json.arr(next.get("altilis"))) {
                Map<String, Object> a = Json.obj(o);
                a.put("label", prefix + a.get("label"));
                altilis.add(a);
            }
            List<Object> log = new ArrayList<>(Json.arr(sites.get("log")));
            log.add("TJK bugün (" + today + ")");
            for (Object line : Json.arr(tjk.get("log"))) log.add("  " + line);
            log.add("TJK yarın (" + tomorrow + ")");
            for (Object line : Json.arr(next.get("log"))) log.add("  " + line);
            List<Object> keptLog = new ArrayList<>();
            if (Tjk.keepMissing(altilis, agfStored(), today.toString(), keptLog) > 0) { // 2.32.7: okunmuş altılı listeden düşmez
                log.add("Önceki okumadan korunan");
                for (Object line : keptLog) log.add("  " + line);
            }
            Map<String, Object> r = new LinkedHashMap<>(tjk);
            r.put("altilis", altilis);
            r.put("log", log);
            r.put("waiting", Json.lng(tjk, "waiting", 0) + Json.lng(next, "waiting", 0));
            r.put("app", appVersion()); // güncellemeden sonra yeni okuyucuyla hemen yeniden okunur
            String raw = Json.write(r);
            agfStore.write(raw);
            agfRaw = raw;
            recordAltili();
            return r;
        }
    }

    /** 2.26: günün altılıları (son AGF'leri ve kurulan kupon) deftere. */
    private void recordAltili() {
        Map<String, Object> v = agfView();
        if (v == null) return;
        synchronized (altiliLogLock) {
            Map<String, Object> log = altiliLog();
            AltiliLog.record(log, Fmt.dayKey(Instant.now()), Json.arr(v.get("altilis")), Instant.now()); // 2.54: başlamış altılı donar
            saveAltiliLog(log);
        }
    }

    /** Sonuçlar en fazla bu sıklıkta denenir (arka plan). */
    static final long ALTILI_SETTLE_GAP_S = 3 * 3600;

    /**
     * 2.26: sonucu bekleyen günlerin TJK sonuçları okunur, öneriler ve kurulan kuponlar sonuçlanır (kredi harcamaz).
     * force: elle (bugün de denenir, aralık beklenmez). Sonuçlanan altılı sayısı.
     */
    int altiliSettle(boolean force, Daily.Progress progress) {
        if (isDemo()) return 0;
        Instant now = Instant.now();
        long last = prefs.getLong("altiliSettleAt", 0);
        if (!force && now.toEpochMilli() - last < ALTILI_SETTLE_GAP_S * 1000) return 0;
        String today = Fmt.dayKey(now);
        Settings s = ledger.settings();
        List<String> days, again;
        synchronized (altiliLogLock) {
            days = AltiliLog.pendingDays(altiliLog(), force ? java.time.LocalDate.parse(today).plusDays(1).toString() : today);
            again = AltiliLog.recheckDays(altiliLog(), today, s.altiliUnit); // 2.39: geçmiş yeniden doğrulama
        }
        if (days.isEmpty() && again.isEmpty()) return 0;
        prefs.edit().putLong("altiliSettleAt", now.toEpochMilli()).apply();
        AndroidHttp http = new AndroidHttp(app);
        int n = 0;
        List<Object> lines = new ArrayList<>();
        for (String day : days) {
            Map<String, Object> res = Tjk.results(http, java.time.LocalDate.parse(day), progress);
            lines.add("Sonuçlar " + day);
            for (Object l : Json.arr(res.get("log"))) lines.add("  " + l);
            synchronized (altiliLogLock) {
                Map<String, Object> log = altiliLog();
                int k = AltiliLog.settle(log, day, res, s.altiliUnit, s.altiliTakeout, s.altiliTax, today, now);
                n += k;
                lines.add("  " + k + " altılı sonuçlandı");
                Map<String, Object> sl = new LinkedHashMap<>();
                sl.put("at", now.toString());
                sl.put("log", lines);
                log.put("settleLog", sl);
                saveAltiliLog(log);
            }
        }
        // 2.39: doğrulanmamış geçmiş sonuçlar yeni okuyucuyla yeniden (arka planda 3, elle 10 gün; eskiden yeniye)
        for (String day : again.subList(0, Math.min(again.size(), force ? ALTILI_RECHECK_MANUAL : ALTILI_RECHECK_BACKGROUND))) {
            java.util.Set<Integer> cities;
            synchronized (altiliLogLock) {
                cities = AltiliLog.recheckCities(altiliLog(), day, s.altiliUnit);
            }
            Map<String, Object> res = Tjk.results(http, java.time.LocalDate.parse(day), progress, cities);
            lines.add("Yeniden doğrulama " + day);
            for (Object l : Json.arr(res.get("log"))) lines.add("  " + l);
            synchronized (altiliLogLock) {
                Map<String, Object> log = altiliLog();
                int k = AltiliLog.recheck(log, day, res, s.altiliUnit, s.altiliTakeout, s.altiliTax, now);
                n += k;
                lines.add("  " + k + " altılı doğrulandı");
                Map<String, Object> sl = new LinkedHashMap<>();
                sl.put("at", now.toString());
                sl.put("log", lines);
                log.put("settleLog", sl);
                saveAltiliLog(log);
            }
        }
        return n;
    }

    /**
     * 2.43: geriye dönük tahmin: bir günün TJK AGF'si okunur, her altılıya bugünkü kurallarla kupon kurulur ve deftere
     * "geriye dönük" yazılır; o günün sonuçlarıyla (ikramiyeyle doğrulanarak) sonuçlanır ve karneyi besler. Defterde zaten
     * olan altılılara dokunulmaz. {yeni, sonuçlanan, doğrulanan}.
     */
    private int[] backtestDay(java.time.LocalDate d, AndroidHttp http, Map<String, Object> inp, Settings s, Instant now, List<Object> lines,
            Daily.Progress progress) {
        String day = d.toString(), today = now.atOffset(Fmt.TR).toLocalDate().toString();
        java.util.Set<String> have = java.util.Collections.emptySet(); // yalnızca kaydı olmayan gün işlenir
        Map<String, Object> r = Tjk.read(http, d, now, progress, false); // 2.46: koşu saatleri gerekmez
        List<Object> fresh = new ArrayList<>();
        java.util.Set<Integer> cities = new java.util.HashSet<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Object o : Json.arr(r.get("altilis"))) {
            Map<String, Object> a = Json.obj(o);
            String key = Json.str(a, "key");
            if (key == null || have.contains(key)) continue;
            List<String> legs = new ArrayList<>();
            for (Object l : Json.arr(a.get("legs"))) legs.add(String.valueOf(l));
            Altili.Inputs bin = altiliInputsFor(key, inp, a);
            Map<String, Object> ref = refPlan(legs, bin); // 2.51: geriye dönük kayıt tavandaki kuponla (karne ölçüsü)
            a.put("plan", ref != null ? ref : Altili.plan(legs, bin)); // bugünkü kurallarla
            a.put("backtest", true);
            fresh.add(a);
            keys.add(key);
            if (a.get("cityId") != null) cities.add((int) Json.lng(a, "cityId", 0));
        }
        lines.add("Geriye dönük " + day + ": " + Json.arr(r.get("altilis")).size() + " altılı okundu, " + fresh.size() + " yeni");
        if (fresh.isEmpty()) return new int[3];
        Map<String, Object> res = Tjk.results(http, d, progress, cities);
        for (Object l : Json.arr(res.get("log"))) lines.add("  " + l);
        synchronized (altiliLogLock) {
            Map<String, Object> log = altiliLog();
            AltiliLog.record(log, day, fresh);
            AltiliLog.settle(log, day, res, s.altiliUnit, s.altiliTakeout, s.altiliTax, today, now);
            int[] o = AltiliLog.outcome(log, day, keys, s.altiliUnit);
            lines.add("  " + o[0] + " sonuçlandı, " + o[1] + " ikramiyeyle doğrulandı");
            if (lines.size() > 400) lines.subList(0, lines.size() - 400).clear(); // kayıt kısa kalsın
            Map<String, Object> sl = new LinkedHashMap<>();
            sl.put("at", now.toString());
            sl.put("log", lines);
            log.put("settleLog", sl);
            saveAltiliLog(log);
            return new int[] {fresh.size(), o[0], o[1]};
        }
    }

    /** 2.46–2.47: geriye dönük tarama ne kadar geriye (1 yıl); bir seferde en çok bu kadar süre (iş sınırı). */
    static final int ALTILI_BACKFILL_DAYS = 365;
    static final long ALTILI_BACKFILL_STEP_MS = 4 * 60 * 1000;

    /** Geriye dönük taramanın durumu: {from (en yeni gün), to (en eski gün), next (sıradaki gün), days, added, settled, verified, at}. */
    Map<String, Object> altiliBackfillState() {
        try {
            String raw = prefs.getString("altiliBackfill", "");
            if (raw.isEmpty()) return null;
            Map<String, Object> st = Json.parseObject(raw);
            if (Json.lng(st, "days", 0) < ALTILI_BACKFILL_DAYS && Json.str(st, "from") != null) { // 2.47: 6 aylık tarama 1 yıla uzar, kaldığı yerden
                st.put("days", (long) ALTILI_BACKFILL_DAYS);
                st.put("to", java.time.LocalDate.parse(Json.str(st, "from")).minusDays(ALTILI_BACKFILL_DAYS - 1).toString());
                prefs.edit().putString("altiliBackfill", Json.write(st)).apply();
            }
            return st;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 2.46: geriye dönük tarama: dünden geriye `days` gün, günleri yeniden eskiye işler; kaldığı yerden devam eder. Önceki
     * günlerin kaydı olan gün atlanır. En çok budgetMs süre çalışır. Özet metni.
     */
    String altiliBackfill(boolean start, int days, long budgetMs, Daily.Progress progress) {
        if (isDemo()) return "Demoda geriye dönük tahmin yok.";
        Instant now = Instant.now();
        java.time.LocalDate today = now.atOffset(Fmt.TR).toLocalDate();
        Map<String, Object> st = altiliBackfillState();
        if (start || st == null) {
            st = new LinkedHashMap<>();
            st.put("from", today.minusDays(1).toString());
            st.put("to", today.minusDays(days).toString());
            st.put("next", today.minusDays(1).toString());
            st.put("days", (long) days);
            st.put("added", 0L);
            st.put("settled", 0L);
            st.put("verified", 0L);
        }
        String next = Json.str(st, "next"), to = Json.str(st, "to");
        if (next == null || to == null || next.compareTo(to) < 0) return "Geriye dönük tarama tamamlandı.";
        Settings s = ledger.settings();
        AndroidHttp http = new AndroidHttp(app);
        Map<String, Object> inp = altiliInputs();
        agfView(); // öğrenilen düzeltme güncel olsun
        List<Object> lines;
        synchronized (altiliLogLock) {
            Map<String, Object> sl = Json.obj(altiliLog().get("settleLog"));
            lines = sl == null ? new ArrayList<Object>() : new ArrayList<>(Json.arr(sl.get("log")));
        }
        long t0 = System.currentTimeMillis();
        int doneDays = 0;
        java.util.Set<String> covered;
        synchronized (altiliLogLock) {
            covered = AltiliLog.days(altiliLog()); // 2.47: büyük defter bir kez okunur
        }
        java.time.LocalDate d = java.time.LocalDate.parse(next);
        while (d.toString().compareTo(to) >= 0 && System.currentTimeMillis() - t0 < budgetMs) {
            if (!covered.contains(d.toString())) { // o günün kaydı varsa atla
                if (progress != null) progress.step("Geriye dönük " + d + " (" + (Json.lng(st, "days", days) - java.time.temporal.ChronoUnit.DAYS.between(d, today) + 1) + " gün kaldı)…");
                int[] o = backtestDay(d, http, inp, s, now, lines, progress);
                st.put("added", Json.lng(st, "added", 0) + o[0]);
                st.put("settled", Json.lng(st, "settled", 0) + o[1]);
                st.put("verified", Json.lng(st, "verified", 0) + o[2]);
            }
            d = d.minusDays(1);
            doneDays++;
            st.put("next", d.toString());
            st.put("at", now.toString());
            prefs.edit().putString("altiliBackfill", Json.write(st)).apply();
        }
        long left = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(to), d) + 1);
        return "Geriye dönük: " + doneDays + " gün işlendi (toplam " + Json.lng(st, "added", 0) + " altılı tahmin, " + Json.lng(st, "verified", 0)
                + " ikramiyeyle doğrulandı)" + (left > 0 ? "; kalan " + left + " gün arka planda Wi-Fi'deyken işlenir." : "; tarama tamamlandı.");
    }

    /** 2.46: arka planda sürdür: yalnızca Wi-Fi gibi ücretsiz ağda (AGF sayfaları büyük). Yaptıysa true. */
    boolean altiliBackfillStep() {
        Map<String, Object> st = altiliBackfillState();
        if (st == null || isDemo() || Json.str(st, "next") == null || Json.str(st, "next").compareTo(Json.str(st, "to")) < 0) return false;
        android.net.ConnectivityManager cm = app.getSystemService(android.net.ConnectivityManager.class);
        if (cm == null || cm.isActiveNetworkMetered()) return false;
        altiliBackfill(false, ALTILI_BACKFILL_DAYS, ALTILI_BACKFILL_STEP_MS, null);
        return true;
    }

    /** 2.39: bir seferde yeniden doğrulanacak en çok gün (arka planda az; elle "Sonuçları şimdi kontrol et"). */
    static final int ALTILI_RECHECK_BACKGROUND = 3, ALTILI_RECHECK_MANUAL = 10;

    /** Arka planda altılı okuması en fazla bu sıklıkta ve bu saatlerde (Türkiye). */
    static final long ALTILI_GAP_S = 2 * 3600;
    /** Son okumada hiç altılı yoksa (AGF henüz oluşmadıysa) bir sonraki okuma 1 saat sonra. */
    static final long ALTILI_RETRY_S = 3600;
    static final int ALTILI_FROM_HOUR = 9, ALTILI_TO_HOUR = 21;
    /** Günün en mantıklı kuponu bu saatten sonra bildirilir (AGF oturmuş olsun). */
    static final int ALTILI_DAILY_HOUR = 11;

    /** 2.21: arka planda okuma (açıksa, 2 saatte bir, 09–21); ortalamada kârlı yeni kupon varsa bildirim [başlık, metin]. */
    String[][] altiliAuto() {
        if (isDemo() || !ledger.settings().altiliAuto) return null;
        Instant now = Instant.now();
        int h = now.atOffset(Fmt.TR).getHour();
        if (h < ALTILI_FROM_HOUR || h >= ALTILI_TO_HOUR) return null;
        Map<String, Object> st = agfStored();
        String at = st == null ? null : Json.str(st, "at");
        long gap = st != null && Json.arr(st.get("altilis")).isEmpty() ? ALTILI_RETRY_S : ALTILI_GAP_S; // AGF henüz yoksa daha sık
        boolean updated = st != null && Json.lng(st, "app", 0) != appVersion(); // uygulama güncellendiyse beklemeden
        if (!updated && at != null && Instant.parse(at).plusSeconds(gap).isAfter(now)) return null;
        try {
            altiliSettle(false, null); // 2.26: önceki günlerin sonuçları (3 saatte bir)
        } catch (RuntimeException ignored) {
            // bir sonraki işte yeniden
        }
        Map<String, Object> r = agfRead(null);
        return new String[][] {altiliNotice(now), altiliHealth(r, now)};
    }

    /** Bu kadar gün üst üste altılı ya da ganyan okunamazsa bir kez uyarılır (en fazla haftada bir). */
    static final int ALTILI_BAD_DAYS = 3;

    /**
     * 2.22: okuma sağlığı. Gün içindeki okumalardan "altılı bulundu" ve "ganyanlı altılı bulundu" tutulur; gün
     * kapanınca sayaçlar güncellenir. Ekrana bakılmadığı için bozulan okuma sessiz kalmasın: 3 gün üst üste hiç altılı
     * ya da hiç ganyan yoksa bildirim [başlık, metin].
     */
    String[] altiliHealth(Map<String, Object> read, Instant now) {
        Map<String, Object> h;
        try {
            h = Json.parseObject(prefs.getString("altiliHealth", "{}"));
        } catch (RuntimeException e) {
            h = new LinkedHashMap<>();
        }
        String day = Fmt.dayKey(now), last = Json.str(h, "day");
        if (last != null && !last.equals(day)) { // önceki gün kapandı
            boolean data = Json.bool(h, "data", false), odds = Json.bool(h, "odds", false);
            h.put("noData", data ? 0L : Json.lng(h, "noData", 0) + 1);
            h.put("noOdds", !data ? Json.lng(h, "noOdds", 0) : odds ? 0L : Json.lng(h, "noOdds", 0) + 1);
            h.put("data", false);
            h.put("odds", false);
        }
        h.put("day", day);
        for (Object o : Json.arr(read == null ? null : read.get("altilis"))) {
            h.put("data", true);
            if (Json.bool(Json.obj(o), "odds", false)) h.put("odds", true);
        }
        String[] notice = null;
        String warned = Json.str(h, "warned");
        boolean quiet = warned != null && Instant.parse(warned).plusSeconds(7 * 86400).isAfter(now);
        if (!quiet && Json.lng(h, "noData", 0) >= ALTILI_BAD_DAYS) {
            notice = new String[] {"Altılı verisi okunamıyor",
                    ALTILI_BAD_DAYS + " gündür Bilyoner, Nesine ve TJK'dan altılı okunamadı; altılı bildirimi gelmez. Sitelerin biçimi"
                            + " değişmiş olabilir: uygulamayı güncellemem gerekir."};
        } else if (!quiet && Json.lng(h, "noOdds", 0) >= ALTILI_BAD_DAYS) {
            notice = new String[] {"Altılıda ganyan oranı okunamıyor",
                    ALTILI_BAD_DAYS + " gündür ganyan oranı okunamadı: AGF okunuyor ama değer (az oynanmış at) aranamıyor, kupon yalnızca"
                            + " devirde kârlı çıkar. Okuyucunun güncellenmesi gerekir."};
        }
        if (notice != null) {
            h.put("warned", now.toString());
            h.put("noData", 0L);
            h.put("noOdds", 0L);
        }
        prefs.edit().putString("altiliHealth", Json.write(h)).apply();
        return notice;
    }

    /** Oynanabilir (ortalamada kârlı, kasaya uygun, başlamamış) ve bugün bildirilmemiş altılı kuponları. */
    String[] altiliNotice(Instant now) {
        Map<String, Object> v = agfView();
        if (v == null) return null;
        String day = Fmt.dayKey(now), clock = now.atOffset(Fmt.TR).toLocalTime().toString().substring(0, 5);
        Map<String, Object> sent;
        try {
            sent = Json.parseObject(prefs.getString("altiliNotified", "{}"));
        } catch (RuntimeException e) {
            sent = new LinkedHashMap<>();
        }
        List<Object> sigs = day.equals(Json.str(sent, "day")) ? new ArrayList<>(Json.arr(sent.get("sigs"))) : new ArrayList<Object>();
        StringBuilder body = new StringBuilder();
        String title = null;
        int count = 0;
        for (Object o : Json.arr(v.get("altilis"))) {
            Map<String, Object> a = Json.obj(o), plan = Json.obj(a.get("plan"));
            if (plan == null || !Json.bool(plan, "play", false)) continue;
            String start = Json.str(a, "start");
            if (start != null && start.compareTo(clock) <= 0) continue; // 1. ayak başlamış
            String coupon = Texts.altiliCoupon(plan);
            String sig = Json.str(a, "key") + "|" + coupon + "|" + Json.lng(plan, "kat", 0);
            if (sigs.contains(sig)) continue;
            sigs.add(sig);
            count++;
            noteNotified(a, plan, day, now);
            if (title == null) title = "Altılı: OYNA · " + a.get("label");
            body.append(body.length() == 0 ? "" : "\n\n").append(a.get("label")).append(" · ").append(a.get("source"))
                    .append(start == null ? "" : " · 1. ayak " + start).append('\n').append(Texts.altiliCoupon(plan, Json.arr(a.get("races")))).append('\n')
                    .append(Texts.altiliSummary(plan));
        }
        boolean daily = !day.equals(Json.str(sent, "daily"));
        if (count == 0) { // 2.24: kârlı kupon yoksa günde bir kez günün en mantıklı kuponu (11:00'den sonra)
            String bestKey = Json.str(v, "best");
            if (!daily || bestKey == null || now.atOffset(Fmt.TR).getHour() < ALTILI_DAILY_HOUR) return null;
            if (altiliPreRaceTime() != null) return null; // 2.28: 1. ayağın saati biliniyor: kupon yarıştan 30 dk önce son AGF'yle
            for (Object o : Json.arr(v.get("altilis"))) {
                Map<String, Object> a = Json.obj(o), plan = Json.obj(a.get("plan"));
                if (!bestKey.equals(Json.str(a, "key")) || plan == null) continue;
                String start = Json.str(a, "start");
                title = "Günün altılısı · " + a.get("label");
                noteNotified(a, plan, day, now);
                body.append(a.get("label")).append(" · ").append(a.get("source")).append(start == null ? "" : " · 1. ayak " + start).append('\n')
                        .append(Texts.altiliCoupon(plan, Json.arr(a.get("races")))).append('\n').append(Texts.altiliSummary(plan))
                        .append("\nOrtalamada kâr beklenmiyor; oynayacaksan günün en mantıklı kuponu bu.");
            }
            if (title == null) return null;
            sent.put("day", day);
            sent.put("sigs", sigs);
            sent.put("daily", day);
            prefs.edit().putString("altiliNotified", Json.write(sent)).apply();
            return new String[] {title, body.toString()};
        }
        sent.put("day", day);
        sent.put("sigs", sigs);
        sent.put("daily", day); // kârlı kupon bildirildi: ayrıca günün kuponu gerekmez
        prefs.edit().putString("altiliNotified", Json.write(sent)).apply();
        if (count > 1) title = "Altılı: " + count + " kârlı kupon";
        return new String[] {title, body.append("\n1. ayak başlamadan oyna. Garanti değil: çoğu gün kaybedilir, kâr uzun vadede.").toString()};
    }

    /** Bildirilen kupon defterde dondurulur (önerilerin karnesi bununla). */
    private void noteNotified(Map<String, Object> a, Map<String, Object> plan, String today, Instant now) {
        noteNotified(a, plan, today, now, false);
    }

    private void noteNotified(Map<String, Object> a, Map<String, Object> plan, String today, Instant now, boolean replace) {
        synchronized (altiliLogLock) {
            Map<String, Object> log = altiliLog();
            String d = Json.str(a, "day") != null ? Json.str(a, "day") : today;
            AltiliLog.markNotified(log, d, Json.str(a, "key"), plan, now, replace);
            saveAltiliLog(log);
        }
    }

    /** 2.28: kupon, altılı kapanmadan (1. ayak) bu kadar önce son AGF'yle bildirilir. */
    static final long ALTILI_PRE_S = 30 * 60;

    /** Günün en mantıklı (ya da kârlı) altılısı: bugünkü, 1. ayağının saati bilinen ve henüz başlamamış olan. */
    private Map<String, Object> altiliTarget(Map<String, Object> v, Instant now) {
        if (v == null) return null;
        String today = Fmt.dayKey(now), bestKey = Json.str(v, "best");
        Map<String, Object> best = null;
        for (Object o : Json.arr(v.get("altilis"))) {
            Map<String, Object> a = Json.obj(o), plan = Json.obj(a.get("plan"));
            String day = Json.str(a, "day") != null ? Json.str(a, "day") : today;
            if (plan == null || !today.equals(day) || startOf(a, day) == null || !startOf(a, day).isAfter(now)) continue;
            if (Json.bool(plan, "play", false)) return a; // kârlı olan önce
            if (Json.str(a, "key").equals(bestKey)) best = a;
        }
        return best;
    }

    private static Instant startOf(Map<String, Object> a, String day) {
        String start = Json.str(a, "start");
        if (start == null || !start.matches("\\d{2}:\\d{2}")) return null;
        return java.time.LocalDate.parse(day).atTime(java.time.LocalTime.parse(start)).atOffset(Fmt.TR).toInstant();
    }

    /** Yarış öncesi son okumanın zamanı (1. ayaktan 30 dk önce); hedef yoksa ya da bugün yapıldıysa null. */
    Instant altiliPreRaceTime() {
        if (isDemo() || !ledger.settings().altiliAuto) return null;
        Instant now = Instant.now();
        Map<String, Object> a = altiliTarget(agfView(), now);
        if (a == null) return null;
        String done = prefs.getString("altiliFinal", "");
        if (done.equals(Fmt.dayKey(now) + "|" + Json.str(a, "key"))) return null;
        Instant start = startOf(a, Fmt.dayKey(now));
        Instant pre = start.minusSeconds(ALTILI_PRE_S);
        return pre.isBefore(now) ? now : pre;
    }

    /**
     * 2.28: 1. ayaktan 30 dk önce AGF yeniden okunur ve kupon son AGF'yle bildirilir (oynanacak kupon budur; karnede
     * önerilen kupon olarak bunun yerine geçer). Zamanı gelmediyse null.
     */
    String[] altiliPreRace() {
        Instant now = Instant.now(), pre = altiliPreRaceTime();
        if (pre == null || pre.isAfter(now.plusSeconds(60))) return null;
        String today = Fmt.dayKey(now);
        Map<String, Object> target = altiliTarget(agfView(), now);
        if (target == null) return null;
        String key = Json.str(target, "key");
        prefs.edit().putString("altiliFinal", today + "|" + key).apply(); // tekrar tekrar denenmesin
        try {
            agfRead(null);
        } catch (RuntimeException e) {
            // okunamadıysa son okumadaki kupon bildirilir
        }
        Map<String, Object> v = agfView(), a = null;
        for (Object o : Json.arr(v == null ? null : v.get("altilis"))) if (key.equals(Json.str(Json.obj(o), "key"))) a = Json.obj(o);
        if (a == null) a = target;
        Map<String, Object> plan = Json.obj(a.get("plan"));
        if (plan == null || plan.get("error") != null) return null;
        noteNotified(a, plan, today, now, true);
        boolean play = Json.bool(plan, "play", false);
        String title = (play ? "Altılı: OYNA · " : "Altılı kuponu · ") + a.get("label") + " · 1. ayak " + a.get("start");
        String body = a.get("label") + " · " + a.get("source") + " · son AGF " + now.atOffset(Fmt.TR).toLocalTime().toString().substring(0, 5) + "\n"
                + Texts.altiliCoupon(plan, Json.arr(a.get("races"))) + "\n" + Texts.altiliSummary(plan)
                + (play ? "\n1. ayak başlamadan oyna. Garanti değil." : "\nOrtalamada kâr beklenmiyor; oynayacaksan günün en mantıklı kuponu bu, 1. ayak başlamadan.");
        return new String[] {title, body};
    }

    /** 2.34: bugünün ayak sonuçları: {day, at, byKey: {anahtar: [6 kazanan ya da null]}, log}. */
    private Map<String, Object> altiliLiveStored() {
        try {
            String raw = prefs.getString("altiliLive", "");
            return raw.isEmpty() ? null : Json.parseObject(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ayak sonucu, koşu saatinden bu kadar sonra okunur (koşu ~2 dk, sonuç yayını birkaç dk). */
    static final long ALTILI_LIVE_AFTER_S = 12 * 60;
    /** Sonuç gelmeyen ayak bu kadar sonra bırakılır; okumalar arası en az bu kadar. */
    static final long ALTILI_LIVE_GIVEUP_S = 3 * 3600, ALTILI_LIVE_GAP_S = 10 * 60;

    /** k. ayağın koşu saati (Türkiye; gece yarısını geçen koşu ertesi gün). */
    private static Instant raceTime(Map<String, Object> a, int k, String day) {
        List<Object> rs = Json.arr(a.get("races"));
        Instant start = startOf(a, day);
        if (start == null || k >= rs.size()) return null;
        String t = Json.str(Json.obj(rs.get(k)), "time");
        if (t == null || !t.matches("\\d{2}:\\d{2}")) return null;
        Instant at = java.time.LocalDate.parse(day).atTime(java.time.LocalTime.parse(t)).atOffset(Fmt.TR).toInstant();
        return at.isBefore(start) ? at.plusSeconds(86400) : at;
    }

    /** 2.34: sonraki ayak sonucu okuması: bugün koşan altılılarda sonucu bilinmeyen ayakların koşusundan 12 dk sonra. */
    Instant altiliLiveTime() {
        if (isDemo() || !ledger.settings().altiliAuto) return null;
        Instant now = Instant.now();
        String today = Fmt.dayKey(now);
        Map<String, Object> v = agfView(), live = altiliLiveStored();
        Map<String, Object> byKey = live != null && today.equals(Json.str(live, "day")) ? Json.obj(live.get("byKey")) : null;
        Instant next = null;
        for (Object o : Json.arr(v == null ? null : v.get("altilis"))) {
            Map<String, Object> a = Json.obj(o);
            String day = Json.str(a, "day") != null ? Json.str(a, "day") : today;
            if (!today.equals(day)) continue;
            List<Object> known = Json.arr(byKey == null ? null : byKey.get(Json.str(a, "key")));
            for (int k = 0; k < 6; k++) {
                if (k < known.size() && known.get(k) != null) continue;
                Instant t = raceTime(a, k, day);
                if (t == null) continue;
                t = t.plusSeconds(ALTILI_LIVE_AFTER_S);
                if (t.plusSeconds(ALTILI_LIVE_GIVEUP_S).isBefore(now)) continue;
                if (next == null || t.isBefore(next)) next = t;
            }
        }
        if (next == null || next.isAfter(now)) return next;
        long last = prefs.getLong("altiliLiveAt", 0);
        Instant again = Instant.ofEpochMilli(last).plusSeconds(ALTILI_LIVE_GAP_S);
        return again.isAfter(now) ? again : now;
    }

    /**
     * 2.34: koşan altılıların ayak sonuçları TJK sonuç sayfasından (yalnızca o şehirler); tamamlanan altılı deftere
     * işlenir. force: elle ("Altılıyı şimdi oku"), zamanı beklenmez. Yeni öğrenilen ayak sayısı.
     */
    int altiliLive(boolean force, Daily.Progress progress) {
        if (isDemo()) return 0;
        Instant now = Instant.now();
        if (!force) {
            Instant due = altiliLiveTime();
            if (due == null || due.isAfter(now.plusSeconds(60))) return 0;
        }
        String today = Fmt.dayKey(now);
        Map<String, Object> v = agfView(), live = altiliLiveStored();
        if (live == null || !today.equals(Json.str(live, "day"))) {
            live = new LinkedHashMap<>();
            live.put("day", today);
        }
        Map<String, Object> byKey = Json.obj(live.get("byKey")) != null ? Json.obj(live.get("byKey")) : new LinkedHashMap<String, Object>();
        List<Map<String, Object>> running = new ArrayList<>();
        java.util.Set<Integer> cities = new java.util.HashSet<>();
        boolean all = false;
        for (Object o : Json.arr(v == null ? null : v.get("altilis"))) {
            Map<String, Object> a = Json.obj(o);
            String day = Json.str(a, "day") != null ? Json.str(a, "day") : today;
            Instant start = startOf(a, day);
            if (!today.equals(day) || "manual".equals(Json.str(a, "key")) || (start == null ? !force : start.isAfter(now))) continue;
            List<Object> known = Json.arr(byKey.get(Json.str(a, "key")));
            if (known.size() == 6 && !known.contains(null)) continue;
            running.add(a);
            if ("TJK".equals(Json.str(a, "source")) && a.get("cityId") != null) cities.add((int) Json.lng(a, "cityId", 0));
            else all = true;
        }
        prefs.edit().putLong("altiliLiveAt", now.toEpochMilli()).apply();
        if (running.isEmpty()) return 0;
        Map<String, Object> res = Tjk.results(new AndroidHttp(app), java.time.LocalDate.parse(today), progress, all ? null : cities);
        int learned = 0;
        for (Map<String, Object> a : running) {
            String key = Json.str(a, "key");
            List<Object> fresh = AltiliLog.legWinners(a, AltiliLog.cityOf(a, Json.arr(res.get("cities"))));
            List<Object> old = Json.arr(byKey.get(key)), merged = new ArrayList<>();
            for (int k = 0; k < 6; k++) {
                Object w = fresh.get(k) != null ? fresh.get(k) : k < old.size() ? old.get(k) : null;
                if (w != null && (k >= old.size() || old.get(k) == null)) learned++;
                merged.add(w);
            }
            byKey.put(key, merged);
        }
        live.put("byKey", byKey);
        live.put("at", now.toString());
        live.put("log", res.get("log"));
        prefs.edit().putString("altiliLive", Json.write(live)).apply();
        Settings s = ledger.settings();
        synchronized (altiliLogLock) { // tamamlanan altılılar karneye hemen
            Map<String, Object> log = altiliLog();
            if (AltiliLog.settle(log, today, res, s.altiliUnit, s.altiliTakeout, s.altiliTax, today, now) > 0) saveAltiliLog(log);
        }
        return learned;
    }

    /** Kurulu sürümün numarası (güncellemeden sonra Zirve kaydını yenilemek için). */
    @SuppressWarnings("deprecation")
    private long appVersion() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Süren Zirve okumasının adımı (arayüzde "okunuyor" satırı); okuma yoksa null. */
    private volatile String zirveBusy;

    /** Arayüz için son Zirve Oran değerlendirmesi (+ fetchedAt, error, busy); hiç okunmadıysa ya da demodaysa null. */
    Map<String, Object> zirveView() {
        if (isDemo()) return null;
        Map<String, Object> st = zirveState(); // kilitsiz: okuma sürerken arayüz beklemesin
        String busy = zirveBusy;
        if (st.get("fetchedAt") == null && busy == null) return null;
        Map<String, Object> v = new LinkedHashMap<>();
        Map<String, Object> view = Json.obj(st.get("view"));
        if (view != null) v.putAll(view);
        if (view != null && v.get("nearest") == null && Json.lng(view, "play", 0) == 0) {
            try { // önceki sürümün kaydı: en yakın seçim satırlardan hesaplanır
                Map<String, Object> near = Zirve.nearest(view, Daily.decisionSettings(ledger), ledger.balance());
                if (near != null) v.put("nearest", near);
            } catch (RuntimeException ignored) {
                // satır gösterilmez; bir sonraki okumada gelir
            }
        }
        v.put("fetchedAt", st.get("fetchedAt"));
        v.put("error", st.get("error"));
        v.put("busy", busy);
        return v;
    }

    /**
     * Bilyoner Zirve Oran'ı indirip son tam taramanın adil oranlarıyla değerlendirir (kredi harcamaz).
     * force değilse son okumadan 10 dk geçmeden tekrar indirilmez (eski sürümün kaydı hemen yenilenir).
     * Son taramada olmayan yakın maçların ligi hedefli taranır. Yeni değerli oran varsa bildirim
     * [başlık, metin] döner; aynı oran bir kez bildirilir.
     */
    String[] zirveCheck(boolean force) {
        if (isDemo() || !ledger.settings().zirve) return null;
        synchronized (zirveLock) {
            Map<String, Object> st = zirveState();
            Instant now = Instant.now();
            String last = Json.str(st, "fetchedAt");
            // uygulama güncellendiyse ya da kayıt eski biçimdeyse beklemeden yeniden okunur
            boolean current = Json.lng(Json.obj(st.get("view")), "v", 0) == Zirve.VIEW_VERSION && Json.lng(st, "app", 0) == appVersion();
            long gap = zirveFailed(st) ? ZIRVE_RETRY_GAP_S : ZIRVE_MIN_GAP_S; // hata sonrası kısa bekleme
            if (!force && current && last != null && Instant.parse(last).plusSeconds(gap).isAfter(now)) return null;
            st.put("fetchedAt", now.toString());
            st.put("app", appVersion());
            String[] notice = null;
            zirveBusy("Bilyoner Zirve Oran okunuyor…");
            try {
                List<Zirve.Offer> offers = Zirve.fetch(new AndroidHttp(app));
                Map<String, Object> view = zirveEvaluate(offers, now);
                Map<String, String> notes = new LinkedHashMap<>();
                zirveAnnotate(view, st, notes);
                st.put("view", view); // ilk değerlendirme hemen ekranda (hedefli tarama sürerken)
                st.remove("error");
                zirveRaw = Json.write(st);
                try {
                    if (zirveScan(offers, view, st, notes, now)) view = zirveEvaluate(offers, now); // hedefli taramayla eklenen maçlar
                    if (zirveKg(offers, view, st, now)) view = zirveEvaluate(offers, now); // eksik Karşılıklı Gol adil oranları
                } catch (RuntimeException e) {
                    st.put("error", "hedefli tarama: " + e.getMessage()); // ilk değerlendirme geçerli kalır
                }
                zirveAnnotate(view, st, notes);
                Map<String, Object> near = Json.obj(view.get("nearest"));
                if (near != null) virtual.record(Fmt.dayKey(now), Virtual.ZIRVE, Json.obj(near.get("selection")), now); // sanal takip
                List<Object> notified = new ArrayList<>(Json.arr(st.get("notified")));
                notice = Zirve.notice(view, notified);
                st.put("view", view);
                st.put("notified", notified);
            } catch (Http.ProviderException | RuntimeException e) {
                st.put("error", String.valueOf(e.getMessage())); // son değerlendirme ekranda kalır
            } finally {
                zirveBusy = null;
            }
            String raw = Json.write(st);
            zirveStore.write(raw);
            zirveRaw = raw;
            return notice;
        }
    }

    /** Zirve maçının ligi bu kadar yakın zamanda tarandıysa (tam ya da hedefli) yeniden taranmaz. */
    static final long ZIRVE_SCAN_GAP_S = 3 * 3600;
    /** Hedefli tarama yalnızca bu aralıkta başlayan Zirve maçları için (daha ilerisi maç günü taranır). */
    static final long ZIRVE_SCAN_AHEAD_S = 48 * 3600;

    private Map<String, Object> zirveEvaluate(List<Zirve.Offer> offers, Instant now) {
        Map<String, Object> rv = radar.view();
        return Zirve.evaluate(offers, Json.arr(rv.get("fairs")), Json.str(rv, "fairsAt"), Daily.decisionSettings(ledger),
                ledger.balance(), now);
    }

    private static Map<String, String> stringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Object> m = Json.obj(o);
        if (m != null) for (Map.Entry<String, Object> e : m.entrySet()) if (e.getValue() != null) out.put(e.getKey(), String.valueOf(e.getValue()));
        return out;
    }

    private static String clock(Instant t) {
        return t.atOffset(Fmt.TR).toLocalTime().toString().substring(0, 5);
    }

    /** Ligin adil oranlarının tabloya en son girdiği an (tam ya da kısmi tarama); yoksa null. */
    private Instant lastFairs(String league) {
        Map<String, Object> rv = radar.view();
        String fairsAt = Json.str(rv, "fairsAt");
        Instant last = null;
        for (Object o : Json.arr(rv.get("fairs"))) {
            Map<String, Object> r = Json.obj(o);
            if (r == null || !league.equals(Json.str(r, "sport"))) continue;
            String at = Json.str(r, "at") != null ? Json.str(r, "at") : fairsAt;
            if (at == null) continue;
            Instant t = Instant.parse(at);
            if (last == null || t.isAfter(last)) last = t;
        }
        return last;
    }

    /** Başarısız hedefli tarama bu kadar sonra yeniden denenir (bağlantı kesintisi 3 saat beklemesin). */
    static final long ZIRVE_RETRY_S = 15 * 60;
    /** Son okuma ya da hedefli tarama başarısızsa uygulama açılınca bu kadar sonra yeniden okunur. */
    static final long ZIRVE_RETRY_GAP_S = 2 * 60;

    /** Son okuma ya da hedefli tarama başarısız mıydı. */
    private static boolean zirveFailed(Map<String, Object> st) {
        if (st.get("error") != null) return true;
        for (Object o : Json.arr(Json.obj(st.get("view")) == null ? null : Json.obj(st.get("view")).get("rows"))) {
            String why = Json.str(Json.obj(o), "why");
            if (why != null && why.contains("başarısız")) return true;
        }
        return false;
    }

    /**
     * Son taramada eşleşen ama Karşılıklı Gol adil oranı olmayan yakın Zirve maçları (Zirve'de KG oranı
     * varken): Pinnacle KG oranı maç başına çekilir (1 kredi; maç başına 3 saatte bir) ve adil oran
     * tablosuna eklenir. iddaa tarafı Bilyoner'in normal KG oranları (eşleme korumasıyla).
     */
    private boolean zirveKg(List<Zirve.Offer> all, Map<String, Object> view, Map<String, Object> st, Instant now) {
        Map<String, Map<String, Object>> need = new LinkedHashMap<>(); // sref -> satır
        for (Object o : Json.arr(view.get("rows"))) {
            Map<String, Object> r = Json.obj(o);
            if (r == null || !"secim".equals(Json.str(r, "status")) || !"KG".equals(Json.str(r, "m"))) continue;
            String sref = Json.str(r, "sref"), sport = Json.str(r, "sport"), ref = Json.str(r, "ref");
            if (sref == null || sport == null || ref == null) continue;
            Instant ko = Instant.parse(Json.str(r, "kickoff"));
            if (ko.isAfter(now.plusSeconds(ZIRVE_SCAN_AHEAD_S)) || ko.isBefore(now.plusSeconds(15 * 60))) continue;
            if (!need.containsKey(sref)) need.put(sref, r);
        }
        if (need.isEmpty() || ledger.settings().oddsApiKey.isEmpty() || creditsLow()) return false;
        Map<String, String> done = stringMap(st.get("kgScans"));
        for (String k : new ArrayList<>(done.keySet())) { // eski kayıtlar atılır
            if (Instant.parse(done.get(k)).plusSeconds(2 * 86400L).isBefore(now)) done.remove(k);
        }
        for (String sref : new ArrayList<>(need.keySet())) {
            String at = done.get(sref);
            if (at != null && Instant.parse(at).plusSeconds(ZIRVE_SCAN_GAP_S).isAfter(now)) need.remove(sref);
        }
        if (need.isEmpty()) return false;
        zirveBusy("Zirve maçlarının Karşılıklı Gol adil oranı çekiliyor (" + need.size() + " maç, maç başına 1 kredi)…");
        boolean changed = false;
        synchronized (LOCK) {
            Daily.LiveSources src = live(effective());
            try {
                for (Map.Entry<String, Map<String, Object>> e : need.entrySet()) {
                    Map<String, Object> r = e.getValue();
                    Models.SharpEvent ev = new Models.SharpEvent(e.getKey(), Json.str(r, "sport"), Json.str(r, "home"),
                            Json.str(r, "away"), Instant.parse(Json.str(r, "kickoff")), new LinkedHashMap<String, Map<String, Double>>(), "pinnacle");
                    try {
                        src.enrich(ev, "btts");
                    } catch (Http.ProviderException ignored) {
                        continue; // bir sonraki okumada yeniden denenir
                    }
                    done.put(e.getKey(), now.toString());
                    if (radar.addSelections(Json.str(r, "ref"), Zirve.kgSelections(all, Json.str(r, "event"), ev))) changed = true;
                }
            } finally {
                after(src);
            }
        }
        st.put("kgScans", new LinkedHashMap<String, Object>(done));
        return changed;
    }

    /**
     * Son taramada olmayan yakın Zirve maçlarının ligleri hedefli taranır: yalnızca o liglerin Pinnacle
     * oranı (lig başına ~2 kredi; Karşılıklı Gol'de Zirve oranı varsa maç başına +1). iddaa tarafı
     * Bilyoner'in normal oranlarından gelir (Nesine bülteni gerekmez). Lig son 3 saatte tarandıysa tekrar
     * taranmaz; başarısız deneme 15 dk sonra yinelenir. Tablo güncellendiyse true; notes: maç -> açıklama.
     */
    private boolean zirveScan(List<Zirve.Offer> all, Map<String, Object> view, Map<String, Object> st, Map<String, String> notes,
                              Instant now) {
        Map<String, Map<String, Object>> events = new LinkedHashMap<>();
        Set<String> kgEvents = new LinkedHashSet<>();
        for (Object o : Json.arr(view.get("rows"))) {
            Map<String, Object> r = Json.obj(o);
            if (r == null || !"mac".equals(Json.str(r, "status"))) continue;
            String ev = Json.str(r, "event");
            Instant ko = Instant.parse(Json.str(r, "kickoff"));
            if (ko.isAfter(now.plusSeconds(ZIRVE_SCAN_AHEAD_S))) {
                notes.put(ev, "maç 2 günden ileride; maç gününe yaklaşınca taranır");
                continue;
            }
            if (!events.containsKey(ev)) events.put(ev, r);
            if ("karsilikli gol".equals(Zirve.fold(Json.str(r, "marketName")))) kgEvents.add(ev);
        }
        if (events.isEmpty()) return false;
        if (ledger.settings().oddsApiKey.isEmpty()) {
            for (String ev : events.keySet()) notes.put(ev, "The Odds API anahtarı yok");
            return false;
        }
        Map<String, String> learned = stringMap(st.get("leagues"));
        List<String[]> candidates = new ArrayList<>(java.util.Arrays.asList(Settings.KNOWN_LEAGUES));
        candidates.addAll(internationals());
        Map<String, String> scans = stringMap(st.get("scans")), fails = stringMap(st.get("scanFails"));
        Map<String, String> keyOf = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> e : events.entrySet()) {
            String league = Json.str(e.getValue(), "league");
            String key = Zirve.leagueKey(league, learned, candidates);
            if (key == null) {
                notes.put(e.getKey(), "ligi (" + league + ") oran listesinde bulunamadı");
                continue;
            }
            keyOf.put(e.getKey(), key);
            Instant recent = lastFairs(key);
            if (scans.get(key) != null) {
                Instant z = Instant.parse(scans.get(key));
                if (recent == null || z.isAfter(recent)) recent = z;
            }
            if (recent != null && recent.plusSeconds(ZIRVE_SCAN_GAP_S).isAfter(now)) {
                notes.put(e.getKey(), "ligi " + clock(recent) + "'de tarandı; Pinnacle'da bu maç yok ya da adlar eşleşmedi");
                continue;
            }
            String failed = fails.get(key);
            if (failed != null && Instant.parse(failed.substring(0, failed.indexOf('|'))).plusSeconds(ZIRVE_RETRY_S).isAfter(now)) {
                notes.put(e.getKey(), "hedefli tarama başarısız (" + failed.substring(failed.indexOf('|') + 1) + "); birkaç dakika sonra yeniden denenecek");
                continue;
            }
            keys.add(key);
        }
        if (keys.isEmpty()) return false;
        List<String> names = new ArrayList<>();
        for (String k : keys) names.add(CreditPlan.leagueName(k));
        zirveBusy("Zirve maçlarının ligi taranıyor (" + String.join(", ", names) + ")…");
        if (creditsLow()) {
            for (Map.Entry<String, String> e : keyOf.entrySet()) {
                if (keys.contains(e.getValue())) notes.put(e.getKey(), "API kredisi az; hedefli tarama yapılmadı");
            }
            return false;
        }
        Set<String> targets = new LinkedHashSet<>(), basket = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : keyOf.entrySet()) {
            if (!keys.contains(e.getValue())) continue;
            targets.add(e.getKey());
            if (Settings.isBasketball(e.getValue())) basket.add(e.getKey());
        }
        boolean merged = false;
        String failure = null;
        int spent = 0, dropped = 0;
        synchronized (LOCK) {
            Settings scope = effective();
            scope.leagues = new ArrayList<>(keys);
            Daily.LiveSources src = live(scope);
            src.knownActive = null; // pencere dışındaki (yarınki) maçlar için de oran çekilsin
            try {
                List<Models.BookEvent> book = Zirve.books(all, targets, basket);
                List<Models.SharpEvent> sharp = src.sharp(keys);
                List<Models.Pair> pairs = Matching.match(book, sharp);
                for (Models.Pair p : pairs) { // Zirve'de KG oranı varsa o maçın KG adil oranı
                    if (!kgEvents.contains(p.book.ref.substring(1))) continue;
                    try {
                        src.enrich(p.sharp, "btts");
                    } catch (Http.ProviderException ignored) {
                        // KG adil oranı alınamazsa o seçim "adil oran yok" kalır
                    }
                }
                dropped = Zirve.verify(pairs);
                radar.mergeFairs(pairs, now);
                merged = !pairs.isEmpty();
            } catch (Http.ProviderException | RuntimeException e) {
                failure = String.valueOf(e.getMessage());
            } finally {
                spent = src.spent();
                after(src);
            }
        }
        for (String k : keys) {
            if (failure == null) {
                scans.put(k, now.toString());
                fails.remove(k);
            } else {
                fails.put(k, now.toString() + "|" + failure);
            }
        }
        st.put("scans", new LinkedHashMap<String, Object>(scans));
        st.put("scanFails", new LinkedHashMap<String, Object>(fails));
        for (String ev : targets) {
            notes.put(ev, failure != null ? "hedefli tarama başarısız: " + failure + "; birkaç dakika sonra yeniden denenecek"
                    : "ligi şimdi tarandı (" + spent + " kredi); Pinnacle'da bu maç yok ya da adlar eşleşmedi"
                    + (dropped > 0 ? " (" + dropped + " pazar eşleme korumasıyla atıldı)" : ""));
        }
        return merged;
    }

    /**
     * Eşleşmeyen maçlara açıklama yazılır (yalnızca hâlâ eşleşmeyenlere); eşleşen maçların lig adı
     * öğrenilir (sonraki Zirve maçlarında lig kodu doğrudan bulunur).
     */
    private static void zirveAnnotate(Map<String, Object> view, Map<String, Object> st, Map<String, String> notes) {
        Map<String, String> learned = stringMap(st.get("leagues"));
        for (Object o : Json.arr(view.get("rows"))) {
            Map<String, Object> r = Json.obj(o);
            if (r == null) continue;
            String league = Json.str(r, "league"), sport = Json.str(r, "sport");
            if (league != null && sport != null && learned.size() < 200) learned.put(league, sport);
            String note = notes.get(Json.str(r, "event"));
            if (note == null || !"mac".equals(Json.str(r, "status"))) continue;
            String base = Json.str(r, "why");
            r.put("why", note + (base != null && base.contains("eşleşmedi") ? " (" + base + ")" : ""));
        }
        st.put("leagues", new LinkedHashMap<String, Object>(learned));
    }

    /** Elle tarama ("Şimdi tara"): tüm ligler. */
    Daily.Intraday radarScan() throws Http.ProviderException {
        return radarScan(false);
    }

    /**
     * Gün içi radar taraması: tüm ligler + iddaa bülteni; gerekirse güncel kupon önerir.
     * scheduled: zamanlanmış alarmdan. O an bir kadro saati taraması vadesindeyse ve yakında normal
     * tarama yoksa yalnızca o saatte oynayan ligler çekilir (kısmi tarama).
     */
    Daily.Intraday radarScan(boolean scheduled) throws Http.ProviderException {
        synchronized (LOCK) {
            if (ledger.settings().oddsApiKey.isEmpty()) throw new Http.ProviderException("The Odds API anahtarı yok.");
            if (creditsLow()) {
                throw new Http.ProviderException("API kredisi az kaldı (" + credits().get("remaining")
                        + "); günlük karar için saklanıyor, radar bekliyor.");
            }
            Instant now = Instant.now();
            ScanPlan.Slot slot = scheduled ? dueLineupSlot(now) : null;
            if (scheduled && slot == null && effective().radarScans == 0) return new Daily.Intraday(); // radar kapalı
            boolean partial = slot != null && !normalScanNear(now);
            if (slot != null) markLineupDone(slot);
            Daily.LiveSources src;
            if (partial) {
                Settings s = effective();
                s.leagues = new ArrayList<>(slot.leagues);
                src = live(s);
            } else {
                probeActive();
                src = live();
            }
            try {
                Daily.Fetch f = Daily.fetch(src, now);
                forecasts.record(Matching.match(f.book, f.sharp), now);
                return Daily.intraday(ledger, f.book, f.sharp, radar, src, partial);
            } finally {
                if (partial) after(src); // kısmi tarama lig verimini (tam tarama ölçüsü) değiştirmez
                else afterScan(src);
            }
        }
    }

    /** Vadesi gelmiş (alarm gecikmesine pay bırakarak) ve henüz yapılmamış kadro saati taraması. */
    private ScanPlan.Slot dueLineupSlot(Instant now) {
        if (!effective().lineupScans) return null;
        Set<String> done = lineupDone();
        for (ScanPlan.Slot s : lineupSlots(now)) {
            if (done.contains(String.valueOf(s.at.getEpochSecond()))) continue;
            if (!now.isBefore(s.at.minusSeconds(10 * 60)) && !now.isAfter(s.at.plusSeconds(30 * 60))) return s;
        }
        return null;
    }

    /** Şu ana ±10 dk yakın normal radar taraması var mı (varsa tam tarama yapılır, kadro ligleri de içinde). */
    private boolean normalScanNear(Instant now) {
        List<Instant> times = ScanPlan.times(storedKickoffs()[0], effective().radarScans, now.atOffset(Fmt.TR).toLocalDate());
        if (times == null) return false;
        for (Instant t : times) if (Math.abs(t.getEpochSecond() - now.getEpochSecond()) <= 10 * 60) return true;
        return false;
    }

    private Set<String> lineupDone() {
        Set<String> out = new LinkedHashSet<>();
        for (String p : prefs.getString("lineupDone", "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private void markLineupDone(ScanPlan.Slot s) {
        List<String> done = new ArrayList<>(lineupDone());
        done.add(String.valueOf(s.at.getEpochSecond()));
        while (done.size() > 24) done.remove(0);
        prefs.edit().putString("lineupDone", String.join(",", done)).apply();
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
        return live(effective());
    }

    /** Verilen kapsamla canlı kaynak (ör. kadro saati taramasında yalnızca birkaç lig). */
    Daily.LiveSources live(Settings scope) {
        Daily.LiveSources src = new Daily.LiveSources(new AndroidHttp(app), scope);
        src.progress = stepper; // arayüz bekliyorsa ilerleme gösterilir
        src.threads = ODDS_THREADS; // 2.16: ligler aynı anda çekilir (tarama süresi ~4'te 1)
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
            out.settleMessages = Daily.settle(ledger, src, forecasts, virtual);
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
            // 2.16.4: maç sürerken oynanan Üst / KG Var bacakları canlı skorla kontrol edilir (15 dk'da bir)
            Instant liveAt = Settlement.nextLiveCheck(ledger, now, lastLiveCheck);
            if (liveAt != null && !liveAt.isAfter(now)) {
                lastLiveCheck = now;
                List<String> msgs = Daily.settle(ledger, live(), forecasts, virtual);
                synchronized (liveMessages) {
                    liveMessages.addAll(msgs);
                }
            }
            Set<String> due = Closing.dueLeagues(ledger, now);
            if (!due.isEmpty()) {
                try {
                    Daily.LiveSources cs = live();
                    List<Models.SharpEvent> closing = cs.sharp(due);
                    Set<String> kgRefs = Closing.dueKgRefs(ledger, now); // 2.16.2: KG bacaklarının kapanışı
                    for (Models.SharpEvent ev : closing) {
                        if (!kgRefs.contains(ev.ref)) continue;
                        try {
                            cs.enrich(ev, "btts");
                        } catch (Http.ProviderException ignored) {
                            // bu maçın KG kapanışı alınamadı; diğerleri yine yazılır
                        }
                    }
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
        Instant c = Settlement.nextLiveCheck(ledger, now, lastLiveCheck); // 2.16.4: canlı skor
        if (c != null && (next == null || c.isBefore(next))) next = c;
        Instant d = altiliPreRaceTime(); // 2.28: altılı kuponu yarıştan 30 dk önce
        if (d != null && (next == null || d.isBefore(next))) next = d.isAfter(now) ? d : now.plusSeconds(30);
        Instant e = altiliLiveTime(); // 2.34: ayak sonuçları koşudan 12 dk sonra
        if (e != null && (next == null || e.isBefore(next))) next = e.isAfter(now) ? e : now.plusSeconds(30);
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
        // sanal takip: pas gününde normal oranlardaki en yakın seçim
        if (r != null && r.decision != null && r.decision.isPass() && !isDemo()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sel = r.decision.stats.get("en_yakin_secim") instanceof Map
                    ? (Map<String, Object>) r.decision.stats.get("en_yakin_secim") : null;
            virtual.record(Fmt.dayKey(Instant.now()), Virtual.NORMAL, sel, Instant.now());
        }
    }

    /** Arayüz için sanal takip (demoda yok). */
    /**
     * Günün seçimi ve tek maç listesi: her ekran yenilemesinde son taramanın tablosu, Zirve, kasa ve ayarlarla
     * baştan hesaplanır (2.11.1: kullanıcı isteğiyle önbellek yok).
     */
    private final Object pickLock = new Object();
    private Map<String, Object> pickValue;
    private List<Object> singlesValue;
    private List<Object> bandsValue;
    private List<Object> drawsValue;

    private void refreshPicks() {
        Instant now = Instant.now();
        Settings cfg = Daily.decisionSettings(ledger);
        long balance = ledger.balance();
        synchronized (pickLock) {
            List<Object> fairs = Json.arr(radar.view().get("fairs"));
            Map<String, Object> z = zirveView();
            Object[] all = Pick.all(fairs, z, cfg, balance, now); // 2.16: liste ve bantlar tek geçişte
            @SuppressWarnings("unchecked")
            Map<String, Object> pv = (Map<String, Object>) all[0];
            @SuppressWarnings("unchecked")
            List<Object> sv = (List<Object>) all[1], bv = (List<Object>) all[2];
            pickValue = pv;
            singlesValue = sv;
            bandsValue = bv;
            drawsValue = Pick.draws(fairs, z, cfg, balance, now, Json.arr(radar.view().get("moves"))); // 2.17
            // sanal takip: günün seçimi oynansın oynanmasın 100 TL'lik sanal bahis (seçim maç başlamadan değişirse yenisi)
            // "oynama" günü (beklenen kayıp sınırı aşıyor) sanal bahis de yok: strateji o gün oynamıyor
            if (pickValue != null && !Json.bool(pickValue, "skip", false)) virtual.record(Fmt.dayKey(now), Virtual.PICK, Pick.selection(pickValue), now);
        }
    }

    /**
     * Kârlı seçim bildirimi (2.15.5): tek maç listesinde adil oranı geçen ve henüz bildirilmemiş seçimler varsa
     * {başlık, metin}; yoksa null. Bildirilenler kalıcı hatırlanır (aynı seçim aynı oranla bir kez).
     */
    String[] profitCheck() {
        if (isDemo()) return null;
        try {
            Settings cfg = Daily.decisionSettings(ledger);
            List<Object> fairs = Json.arr(radar.view().get("fairs"));
            Map<String, Object> z = zirveView();
            List<Object> list = new ArrayList<>(Pick.list(fairs, z, cfg, ledger.balance(), Instant.now()));
            // 2.17: kârlı beraberlikler de bildirilir (tek maç listesinde yok: tutma %50'nin altında)
            list.addAll(Pick.draws(fairs, z, cfg, ledger.balance(), Instant.now(), Json.arr(radar.view().get("moves"))));
            synchronized (LOCK) {
                List<Object> notified = new ArrayList<>(Json.arr(memory.get("profitNotified")));
                String[] n = Pick.profitNotice(list, notified);
                if (n != null) {
                    memory.put("profitNotified", notified);
                    memoryStore.write(Json.write(memory));
                }
                return n;
            }
        } catch (RuntimeException e) {
            return null; // tablo yazılırken okunduysa bir sonraki işte
        }
    }

    /** Günün seçimi (2.9): son taramanın adil oran tablosu ve Zirve Oran'dan; demo ya da kapalıysa null. */
    Map<String, Object> pick() {
        if (isDemo()) return null;
        try {
            refreshPicks();
            synchronized (pickLock) {
                return pickValue;
            }
        } catch (RuntimeException e) {
            return null; // tablo yazılırken okunduysa bir sonraki çizimde gelir
        }
    }

    /** Ekran için günün seçimi, tek maç listesi ve denge tablosu tek hesapla: {seçim, liste, bantlar} (2.16). */
    Object[] picks() {
        if (isDemo()) return new Object[4];
        try {
            refreshPicks();
            synchronized (pickLock) {
                return new Object[] {pickValue, singlesValue, bandsValue, drawsValue};
            }
        } catch (RuntimeException e) {
            return new Object[4]; // tablo yazılırken okunduysa bir sonraki çizimde gelir
        }
    }

    /** Tutma olasılığı bantlarına göre bugünün en iyi tek maç seçimi (2.13 denge tablosu). */
    List<Object> bands() {
        if (isDemo()) return null;
        try {
            refreshPicks();
            synchronized (pickLock) {
                return bandsValue;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Tek maç fırsatları listesi (2.10): en az %50 tutan tek oynanabilen seçimler; önce kârlı, sonra denge puanı (2.15.4). */
    List<Object> singles() {
        if (isDemo()) return null;
        try {
            refreshPicks();
            synchronized (pickLock) {
                return singlesValue;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    Map<String, Object> virtualView() {
        return isDemo() ? null : virtual.view();
    }

    /** Günün karar penceresindeki maç saatleri (radar zamanlaması için) saklanır. */
    void storeKickoffs(Daily.Result r) {
        if (r == null || r.decision == null || r.decision.kickoffs.isEmpty()) return;
        StringBuilder b = new StringBuilder();
        List<Instant> kos = r.decision.kickoffs;
        List<String> leagues = r.decision.kickoffLeagues;
        for (int i = 0; i < kos.size(); i++) {
            b.append(b.length() == 0 ? "" : ",").append(kos.get(i).getEpochSecond());
            if (leagues != null && i < leagues.size() && leagues.get(i) != null) b.append(':').append(leagues.get(i));
        }
        prefs.edit().putString("kickoffs", b.toString()).apply();
    }

    /** Kayıtlı maç saatleri ve (paralel) lig kodları; eski kayıtta lig kodu yoktur (null). */
    @SuppressWarnings("unchecked")
    private List[] storedKickoffs() {
        List<Instant> kos = new ArrayList<>();
        List<String> leagues = new ArrayList<>();
        for (String p : prefs.getString("kickoffs", "").split(",")) {
            if (p.isEmpty()) continue;
            int c = p.indexOf(':');
            try {
                kos.add(Instant.ofEpochSecond(Long.parseLong(c < 0 ? p : p.substring(0, c))));
                leagues.add(c < 0 ? null : p.substring(c + 1));
            } catch (NumberFormatException ignored) {
                // bozuk kayıt: atla
            }
        }
        return new List[] {kos, leagues};
    }

    @SuppressWarnings("unchecked")
    private List<ScanPlan.Slot> lineupSlots(Instant now) {
        List[] k = storedKickoffs();
        return ScanPlan.lineupSlots(k[0], k[1], now.atOffset(Fmt.TR).toLocalDate());
    }

    /**
     * Bugünün radar saatleri: maç saatlerine göre normal taramalar ve (açıksa) kadro saati
     * taramaları; 10 dk'dan yakın saatler birleştirilir. Bilgi yoksa null (sabit saatler).
     */
    @SuppressWarnings("unchecked")
    List<Instant> radarTimes(Instant now) {
        Settings s = effective();
        List<Instant> normal = ScanPlan.times(storedKickoffs()[0], s.radarScans, now.atOffset(Fmt.TR).toLocalDate());
        if (!s.lineupScans) return normal;
        List<Instant> all = new ArrayList<>();
        if (normal != null) all.addAll(normal);
        for (ScanPlan.Slot slot : lineupSlots(now)) all.add(slot.at);
        if (all.isEmpty()) return normal;
        java.util.Collections.sort(all);
        List<Instant> out = new ArrayList<>();
        for (Instant t : all) {
            if (out.isEmpty() || t.getEpochSecond() - out.get(out.size() - 1).getEpochSecond() > 10 * 60) out.add(t);
        }
        return out;
    }

    /** Son events() çalışması (yeniden deneme aralığı için). */
    private volatile Instant lastEventRun;
    /** Son canlı skor kontrolü (2.16.4) ve onun bildirilecek sonuç mesajları. */
    private volatile Instant lastLiveCheck;
    private final List<String> liveMessages = new ArrayList<>();

    /** Canlı kontrolde sonuçlanan kuponların mesajları (bildirim için; alınınca boşalır). */
    List<String> takeLiveMessages() {
        synchronized (liveMessages) {
            List<String> out = new ArrayList<>(liveMessages);
            liveMessages.clear();
            return out;
        }
    }

    static final class BackgroundResult {
        List<String> settleMessages = new ArrayList<>();
        Daily.Result daily;
        boolean setupNeeded;
    }
}
