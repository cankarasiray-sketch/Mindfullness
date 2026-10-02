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
        // sanal takip: pas gününde normal oranlardaki en yakın seçim
        if (r != null && r.decision != null && r.decision.isPass() && !isDemo()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sel = r.decision.stats.get("en_yakin_secim") instanceof Map
                    ? (Map<String, Object>) r.decision.stats.get("en_yakin_secim") : null;
            virtual.record(Fmt.dayKey(Instant.now()), Virtual.NORMAL, sel, Instant.now());
        }
    }

    /** Arayüz için sanal takip (demoda yok). */
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

    static final class BackgroundResult {
        List<String> settleMessages = new ArrayList<>();
        Daily.Result daily;
        boolean setupNeeded;
    }
}
