package app.bilincli.core;

import app.bilincli.core.Engine.Decision;
import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.Proposal;
import app.bilincli.core.Models.ScoreResult;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Günlük akış (Python: bilincli/app.py): sonuçlandır, karar üret, kaydet. */
public final class Daily {
    /** Veri kaynakları: gerçek (Nesine + The Odds API) ya da demo. */
    public interface Sources {
        List<BookEvent> book() throws Http.ProviderException;

        List<SharpEvent> sharp() throws Http.ProviderException;

        Map<String, ScoreResult> scores(Set<String> sportKeys) throws Http.ProviderException;
    }

    public static final class Result {
        public String day;
        public Decision decision;
        public Long couponId;
        public List<Long> couponIds = new ArrayList<>();
        public Map<String, Object> calibration;
        public long stake;
        public String stakeNote, blocked, error;
        public boolean skipped;
        /** Bu kararda kullanılan bülten ve keskin piyasa (tahmin defteri için). */
        public List<BookEvent> book;
        public List<SharpEvent> sharp;
        /** Bugün oynanmış kupon vardı: plan yenilenmedi, yalnızca kalan sınır içinde ek kupon denendi. */
        public boolean besidePlayed;
        public List<String> settleMessages = new ArrayList<>();

        public String headline() {
            if (error != null) return "HATA";
            if (blocked != null) return "KORUMA";
            if (decision == null || decision.isPass()) return "PAS";
            return "KUPON";
        }
    }

    /** Uzun işlemlerde arayüze ilerleme metni. */
    public interface Progress {
        void step(String text);
    }

    public static final class LiveSources implements Sources {
        private final Http http;
        private final Settings cfg;
        private OddsApi api;
        /** null olabilir. */
        public Progress progress;
        /** Canlı bülten okunamazsa kullanılabilecek en eski yedek (sn); 0 = yalnızca canlı. */
        public long bookCacheMaxAgeS;
        /** Yedek bülten kullanıldıysa açıklaması. */
        public String bookNote;
        /** Bugün öğrenilmiş pencere maç sayıları (lig -> sayı); null olabilir. */
        public Map<String, Integer> knownActive;

        /** Liglerin karar penceresindeki maç sayıları (kota harcamaz). */
        public Map<String, Integer> activeCounts(java.util.Collection<String> leagues, Instant now) throws Http.ProviderException {
            return api().activeCounts(leagues, now);
        }

        void step(String text) {
            if (progress != null) progress.step(text);
        }
        /** Calibration'ın son güvenilir pazar eşlemeleri (kalıcı saklamak çağıranın işi). */
        public Map<String, Object> memory = new java.util.LinkedHashMap<>();

        public LiveSources(Http http, Settings cfg) {
            this.http = http;
            this.cfg = cfg;
        }

        /** Önceki çalışmalardan anahtar başına {kalan, kullanılan, zaman}; anahtar seçimi için. */
        public Map<String, long[]> knownKeyCredits;

        /** Pinnacle oranlarında aynı anda çekilen lig sayısı (2.16; telefonda 4, testlerde 1). */
        public int threads = 1;

        private OddsApi api() throws Http.ProviderException {
            if (api == null) {
                api = new OddsApi(http, cfg);
                api.threads = threads;
                api.progress = progress;
                api.knownInWindow = knownActive;
                if (knownKeyCredits != null) api.keyCredits.putAll(knownKeyCredits);
            }
            return api;
        }

        /** Bu çalışmadan sonra anahtar başına kredi (kalıcı saklamak için; çağrı yapılmadıysa boş). */
        public Map<String, long[]> keyCredits() {
            return api == null ? new java.util.LinkedHashMap<String, long[]>() : api.keyCredits;
        }

        /** Bu çalışmada kullanılamayan anahtarlar (kısa ad -> neden). */
        public Map<String, String> keyProblems() {
            return api == null ? new java.util.LinkedHashMap<String, String>() : api.keyProblems;
        }

        public String keySummary() {
            return api == null ? "" : api.keySummary();
        }

        /** Bu kaynağın kapsamı (kredi planına göre daraltılmış olabilir). */
        public Settings settings() {
            return cfg;
        }

        public String remainingCredits() {
            return api == null ? null : api.remaining;
        }

        public String usedCredits() {
            return api == null ? null : api.used;
        }

        /** Son taramada maçı olmadığı için atlanan ligler. */
        public List<String> idleLeagues() {
            return api == null ? new ArrayList<String>() : api.idle;
        }

        /** Aktif futbol turnuvaları {kod, ad} (kota harcamaz). */
        public List<String[]> sports() throws Http.ProviderException {
            return api().fetchSports();
        }

        /** "Kaynakları test et" için lig bazında ayrıntılı rapor üret. */
        public void diagnose() throws Http.ProviderException {
            api().diagnose = true;
        }

        public List<String> report() {
            return api == null ? new ArrayList<String>() : api.report;
        }

        public int spent() {
            return api == null ? 0 : api.spent;
        }

        /** Atlanan ligler ve hatalar (ör. bilinmeyen lig kodu). */
        public List<String> warnings() {
            return api == null ? new ArrayList<String>() : api.warnings;
        }

        /**
         * Karşılıklı Gol için Pinnacle oranı maç bazında çekilir (maç başına 1 kredi). Yalnızca
         * iddaa'da aday KG pazarı olan, zaman penceresindeki maçlar için, en fazla cfg.kgEvents
         * maç. Önce ücretsiz model ön elemesi: modele göre güvenlik payıyla bile avantaj
         * veremeyecek maçlar sorulmaz (Calibration.kgUpside); kalanlar en umutludan sorulur.
         */
        public int enrichKg(List<BookEvent> book, List<SharpEvent> sharp, Instant now) throws Http.ProviderException {
            if (cfg.kgEvents <= 0) return 0;
            List<Models.Pair> pairs = Matching.match(book, sharp);
            List<Models.Pair> due = new ArrayList<>();
            final Map<Models.Pair, Double> upside = new java.util.IdentityHashMap<>();
            int screened = 0;
            Instant from = now.plusSeconds(Math.round(cfg.minLeadMinutes * 60)), to = now.plusSeconds(Math.round(cfg.windowHours * 3600));
            for (Models.Pair p : pairs) {
                if (p.book.kickoff.isBefore(from) || p.book.kickoff.isAfter(to) || p.sharp.fair.containsKey("KG")) continue;
                boolean hasRaw = false;
                for (String k : p.book.odds.keySet()) hasRaw |= k.startsWith(Calibration.RAW_KG);
                if (!hasRaw) continue;
                double u = Calibration.kgUpside(p, memory, cfg);
                if (!Double.isNaN(u) && u < cfg.minLegEv) {
                    screened++; // iyimser modelle bile avantaj yok: kredi harcanmaz
                    continue;
                }
                upside.put(p, u);
                due.add(p);
            }
            java.util.Collections.sort(due, new java.util.Comparator<Models.Pair>() {
                @Override
                public int compare(Models.Pair a, Models.Pair b) {
                    double ua = upside.get(a), ub = upside.get(b);
                    boolean ka = !Double.isNaN(ua), kb = !Double.isNaN(ub);
                    if (ka && kb && ua != ub) return Double.compare(ub, ua); // en umutlu önce
                    if (ka != kb) return ka ? -1 : 1; // modelin umut gördüğü, bilinmeyenden önce
                    return a.book.kickoff.compareTo(b.book.kickoff);
                }
            });
            int n = 0, withData = 0, devN = 0;
            double devSum = 0, devMax = 0;
            int total = Math.min(cfg.kgEvents, due.size());
            for (Models.Pair p : due) {
                if (n >= cfg.kgEvents) break;
                try {
                    step("Karşılıklı Gol oranı " + (n + 1) + "/" + total + ": " + p.book.home + " - " + p.book.away);
                    api().enrichEvent(p.sharp, "btts");
                    n++;
                    Map<String, Double> kg = p.sharp.fair.get("KG");
                    if (kg != null) {
                        withData++;
                        Map<String, Double> model = Calibration.modelKg(p);
                        if (model != null) { // ön elemenin güvenlik payı yerinde mi: modelin gerçek sapması
                            double d = Math.abs(model.get("VAR") - kg.get("VAR"));
                            devSum += d;
                            devMax = Math.max(devMax, d);
                            devN++;
                        }
                    }
                } catch (Http.ProviderException e) {
                    break; // bu pazar desteklenmiyor ya da kredi bitti; ana akışı durdurma
                }
            }
            api().report.add("Karşılıklı Gol: " + n + " maç soruldu (" + n + " kredi), " + withData + " maçta oran geldi"
                    + (screened > 0 ? "; " + screened + " maç model ön elemesiyle sorulmadı (avantaj ihtimali yok, kredi harcanmadı)" : "")
                    + (devN > 0 ? "; model sapması ort. " + Fmt.pct(devSum / devN, false) + ", en çok " + Fmt.pct(devMax, false)
                    + (devMax > Calibration.KG_SCREEN_MARGIN ? " (güvenlik payını aştı)" : "") : "")
                    + (due.size() == 0 && screened == 0 ? " (iddaa'da KG pazarı olan maç yok)" : ""));
            return n;
        }

        @Override
        public List<BookEvent> book() throws Http.ProviderException {
            step("iddaa bülteni okunuyor (Nesine)…");
            String[] note = new String[1];
            List<BookEvent> out = Nesine.fetch(http, bookCacheMaxAgeS, Instant.now(), note);
            bookNote = note[0];
            return out;
        }

        @Override
        public List<SharpEvent> sharp() throws Http.ProviderException {
            return api().fetchEvents();
        }

        public List<SharpEvent> sharp(java.util.Collection<String> leagues) throws Http.ProviderException {
            return api().fetchEvents(leagues);
        }

        /** Belirli maçlar için ek pazar (ör. KG) adil olasılığı. */
        public void enrich(SharpEvent ev, String markets) throws Http.ProviderException {
            api().enrichEvent(ev, markets);
        }

        @Override
        public Map<String, ScoreResult> scores(Set<String> sportKeys) throws Http.ProviderException {
            step("Maç sonuçları alınıyor (" + sportKeys.size() + " lig)…");
            return api().fetchScores(sportKeys);
        }
    }

    public static final class DemoSources implements Sources {
        private final Demo world;

        public DemoSources(Demo world) {
            this.world = world;
        }

        @Override
        public List<BookEvent> book() {
            return world.book();
        }

        @Override
        public List<SharpEvent> sharp() {
            return world.sharp();
        }

        @Override
        public Map<String, ScoreResult> scores(Set<String> sportKeys) {
            return world.scores();
        }
    }

    private Daily() {}

    /** Doğrulanmış piyasa verisi. */
    public static final class Fetch {
        public List<BookEvent> book;
        public List<SharpEvent> sharp;
        public Map<String, Object> calibration;
    }

    /** Bülten + keskin piyasa çekilir, gerekirse KG eklenir ve Calibration'dan geçirilir. */
    public static Fetch fetch(Sources src, Instant now) throws Http.ProviderException {
        Fetch f = new Fetch();
        f.book = src.book();
        f.sharp = src.sharp();
        if (src instanceof LiveSources) ((LiveSources) src).enrichKg(f.book, f.sharp, now);
        f.calibration = Calibration.apply(f.book, f.sharp,
                src instanceof LiveSources ? ((LiveSources) src).memory : new java.util.LinkedHashMap<String, Object>());
        return f;
    }

    /**
     * Karar ayarları: kullanıcı ayarları + kapanış oranı ölçümünden kanıt koruması
     * (EdgeCalibration). Motor ve güncel kontrol aynı ayarı kullanır.
     */
    public static Settings decisionSettings(Ledger ledger) {
        Settings cfg = ledger.settings();
        if (cfg.edgeGuard) cfg.edgeRatios = EdgeCalibration.fromLedger(ledger).ratios();
        return cfg;
    }

    /** Kaynağın kredi planı günlük kupon sayısını daralttıysa o sınır da uygulanır. */
    static Settings decisionSettings(Ledger ledger, Sources src) {
        Settings cfg = decisionSettings(ledger);
        if (src instanceof LiveSources) {
            cfg.maxCouponsPerDay = Math.min(cfg.maxCouponsPerDay, ((LiveSources) src).settings().maxCouponsPerDay);
        }
        return cfg;
    }

    /**
     * Günün kuponlarını kaydeder. Ortak Kelly (Portfolio) kuponları birlikte seçer ve tutarlandırır;
     * kurulamazsa ana kupon ve maç paylaşmayan alternatifleri, toplamı günlük üst sınıra
     * orantılı küçültülerek kullanılır.
     */
    static List<Long> createCoupons(Ledger ledger, Decision d, String day, Settings cfg, boolean autoPlay,
                                    String reasonPrefix, String summary, String[] noteOut) {
        List<Long> ids = addPortfolio(ledger, d.candidates, day, cfg, autoPlay, cfg.maxCouponsPerDay,
                cfg.maxDailyExposure, null, noteOut);
        if (!ids.isEmpty()) {
            String reason = (reasonPrefix == null ? "" : reasonPrefix) + (noteOut[0] == null ? "" : " " + noteOut[0]);
            ledger.recordRun(day, "kupon", reason.trim(), ids, summary);
            return ids;
        }
        return createIndependent(ledger, d, day, cfg, autoPlay, reasonPrefix, summary, noteOut);
    }

    /**
     * Ortak Kelly kuponlarını deftere ekler (koşuyu kaydetmez). Asgari kupon bedelinin altında
     * kalan küçük paylar atlanır; kasa boşsa kuponlar yalnızca takip için (tutarsız) kaydedilir.
     */
    static List<Long> addPortfolio(Ledger ledger, List<Models.Candidate> cands, String day, Settings cfg, boolean autoPlay,
                                   int maxBets, double capLeft, java.util.Collection<String> exclude, String[] noteOut) {
        List<Long> ids = new ArrayList<>();
        if (cands == null || cands.isEmpty()) return ids;
        Portfolio.Result res = Portfolio.optimize(cands, cfg, maxBets, capLeft, exclude);
        if (res.picks.isEmpty()) return ids;
        long balance = ledger.balance(), minimum = Math.round(cfg.minCouponAmount * 100);
        List<Portfolio.Pick> picks = new ArrayList<>();
        for (Portfolio.Pick p : res.picks) {
            if (balance <= 0 || (long) (balance * p.fraction) / 100 * 100 >= minimum) picks.add(p);
        }
        if (picks.isEmpty()) picks.add(res.picks.get(0)); // en büyüğü: asgari bedele yükseltilir ya da takip
        for (Portfolio.Pick p : picks) {
            String[] n = new String[1];
            long stake = computeStake(balance, p.fraction, cfg, n)[0];
            if (noteOut[0] == null) noteOut[0] = n[0];
            long id = ledger.addCoupon(p.proposal, day, stake, p.scale());
            if (autoPlay && stake > 0 && stake <= ledger.balance()) ledger.markPlayed(id, stake, null);
            ids.add(id);
        }
        return ids;
    }

    private static List<Long> createIndependent(Ledger ledger, Decision d, String day, Settings cfg, boolean autoPlay,
                                                String reasonPrefix, String summary, String[] noteOut) {
        List<Proposal> props = new ArrayList<>();
        props.add(d.proposal);
        props.addAll(d.alternatives);
        props = props.subList(0, Math.min(cfg.maxCouponsPerDay, props.size()));
        double total = 0;
        for (Proposal p : props) total += p.stakeFraction;
        double scale = total > cfg.maxDailyExposure ? cfg.maxDailyExposure / total : 1.0;
        long balance = ledger.balance();
        List<Long> ids = new ArrayList<>();
        String note = null;
        for (Proposal p : props) {
            String[] n = new String[1];
            long stake = computeStake(balance, p.stakeFraction * scale, cfg, n)[0];
            if (note == null) note = n[0];
            long id = ledger.addCoupon(p, day, stake, scale);
            if (autoPlay && stake > 0 && stake <= ledger.balance()) ledger.markPlayed(id, stake, null);
            ids.add(id);
        }
        noteOut[0] = note;
        String reason = (reasonPrefix == null ? "" : reasonPrefix) + (note == null ? "" : (reasonPrefix == null ? "" : " ") + note);
        ledger.recordRun(day, "kupon", reason.trim(), ids, summary);
        return ids;
    }

    public static long[] computeStake(long balance, Proposal p, Settings cfg, String[] noteOut) {
        return computeStake(balance, p.stakeFraction, cfg, noteOut);
    }

    public static long[] computeStake(long balance, double fraction, Settings cfg, String[] noteOut) {
        if (balance <= 0) {
            noteOut[0] = "Kasa boş: önce Kasa sekmesinden para yatır.";
            return new long[] {0};
        }
        long stake = (long) (balance * fraction) / 100 * 100; // tam TL
        long minimum = Math.round(cfg.minCouponAmount * 100);
        if (stake < minimum) {
            if (minimum <= balance * cfg.maxStakeFraction) {
                stake = minimum;
            } else {
                noteOut[0] = "Kasa (" + Fmt.tl(balance) + ") asgari kupon bedelini (" + Fmt.tl(minimum)
                        + ") güvenli oranla (en fazla %" + Math.round(cfg.maxStakeFraction * 100)
                        + ") karşılamıyor. Kupon yalnızca takip için kaydedildi.";
                return new long[] {0};
            }
        }
        return new long[] {stake};
    }

    public static List<String> settle(Ledger ledger, final Sources src) {
        return settle(ledger, src, null);
    }

    /** forecasts verilirse aynı skor yanıtıyla tahmin defteri de sonuçlandırılır (ek kredi yok). */
    public static List<String> settle(Ledger ledger, final Sources src, final Forecasts forecasts) {
        return settle(ledger, src, forecasts, null);
    }

    /**
     * virtual verilirse sanal takip kayıtları da aynı skorlarla sonuçlandırılır; kuponların liginde
     * olmayan sanal kayıtlar için yalnızca o liglerin skoru ayrıca çekilir (açık kupon yokken de).
     */
    public static List<String> settle(Ledger ledger, final Sources src, final Forecasts forecasts, final Virtual virtual) {
        final Set<String> fetched = new java.util.HashSet<>();
        final Instant now = ledger.now();
        List<String> out = new ArrayList<>();
        if (!ledger.openCoupons().isEmpty()) {
            try {
                out = Settlement.settleOpen(ledger, new Settlement.ScoreFetcher() {
                    @Override
                    public Map<String, ScoreResult> fetch(Set<String> sportKeys) throws Exception {
                        Map<String, ScoreResult> scores = src.scores(sportKeys);
                        fetched.addAll(sportKeys);
                        if (forecasts != null) forecasts.resolve(scores);
                        if (virtual != null) virtual.resolve(scores, now);
                        return scores;
                    }
                }, now);
            } catch (Exception e) {
                out.add("Sonuçlar alınamadı: " + e.getMessage());
            }
        }
        if (virtual != null) {
            Set<String> need = virtual.dueSports(now);
            need.removeAll(fetched);
            if (!need.isEmpty()) {
                try {
                    Map<String, ScoreResult> scores = src.scores(need);
                    virtual.resolve(scores, now);
                    if (forecasts != null) forecasts.resolve(scores);
                } catch (Exception ignored) {
                    // bir sonraki sonuç kontrolünde yeniden denenir
                }
            }
        }
        return out;
    }

    public static Result generate(Ledger ledger, Sources src, boolean force, boolean autoPlay) {
        return generate(ledger, src, force, autoPlay, null);
    }

    public static Result generate(Ledger ledger, Sources src, boolean force, boolean autoPlay, Radar radar) {
        Instant now = ledger.now();
        Settings cfg = decisionSettings(ledger, src);
        Result r = new Result();
        r.day = Fmt.dayKey(now);
        Ledger.Run existing = ledger.runFor(r.day);
        if (existing != null && !force) {
            r.skipped = true;
            r.couponId = existing.couponId;
            return r;
        }
        boolean keep = existing != null && anyPlayed(ledger, existing); // oynanmış kuponlu koşu silinmez
        String blocked = Guard.check(ledger, cfg, now);
        if (blocked != null) {
            r.blocked = blocked;
            if (!keep) ledger.recordRun(r.day, "koruma", blocked, (Long) null, "");
            return r;
        }
        Fetch f;
        try {
            f = fetch(src, now);
        } catch (Http.ProviderException e) {
            r.error = e.getMessage();
            return r; // hata günü kaydedilmez; sonraki çalıştırma yeniden dener
        }
        if (radar != null) radar.update(f.book, f.sharp, now, cfg, true);
        r.book = f.book;
        r.sharp = f.sharp;
        Decision d = Engine.decide(f.book, f.sharp, now, cfg);
        d.stats.put("dogrulama", Calibration.summary(f.calibration));
        r.decision = d;
        r.calibration = f.calibration;
        d.stats.put("kasa", ledger.balance()); // pas gününde en yakın seçimin tutarı TL olarak yazılsın
        String summary = Texts.statsLine(d.stats);
        if (d.isPass()) {
            if (keep) {
                r.besidePlayed = true;
                return r;
            }
            ledger.recordRun(r.day, "pas", d.reason, (Long) null, summary);
            return r;
        }
        String[] note = new String[1];
        if (keep) {
            // yeniden üretim: oynanmış kuponlara dokunulmaz, kalan sınır içinde başka maçlardan ek kupon
            r.besidePlayed = true;
            r.couponIds = addBeside(ledger, d, r.day, cfg, existing, now, "Güncel oranlarla yeniden tarandı:", note);
            if (r.couponIds.isEmpty()) return r;
        } else {
            r.couponIds = createCoupons(ledger, d, r.day, cfg, autoPlay, null, summary, note);
        }
        r.couponId = r.couponIds.get(0);
        r.stake = ledger.coupon(r.couponId).suggestedStake;
        r.stakeNote = note[0];
        return r;
    }

    /** Gün içi tarama sonucu. */
    public static final class Intraday {
        public Long newCouponId;
        public List<Long> newCouponIds = new ArrayList<>();
        public String blocked;
        public List<java.util.Map<String, Object>> moves = new ArrayList<>();
    }

    /**
     * Radar taraması: piyasa verisini işler. Bugün henüz oynanmış kupon yoksa ve güncel oranlarla
     * farklı bir kupon planı çıkıyorsa günün planı yenilenir. Oynanmış kupon varsa onlara
     * dokunulmaz; kalan günlük üst sınır ve kupon hakkı içinde, bugünkü kuponlarda olmayan
     * maçlardan ek kupon kurulur.
     */
    public static Intraday intraday(Ledger ledger, List<BookEvent> book, List<SharpEvent> sharp, Radar radar) {
        return intraday(ledger, book, sharp, radar, null);
    }

    public static Intraday intraday(Ledger ledger, List<BookEvent> book, List<SharpEvent> sharp, Radar radar, Sources src) {
        return intraday(ledger, book, sharp, radar, src, false);
    }

    /**
     * partial: yalnızca bazı ligler çekildi (kadro saati taraması). Değer listesi yenilenmez ve
     * günün planı baştan kurulmaz (diğer liglerdeki kuponlar silinmesin): bugünkü kuponlarda
     * olmayan maçlardan, kalan günlük sınır içinde ek kupon eklenir.
     */
    public static Intraday intraday(Ledger ledger, List<BookEvent> book, List<SharpEvent> sharp, Radar radar, Sources src,
                                    boolean partial) {
        Instant now = ledger.now();
        Settings cfg = decisionSettings(ledger, src);
        Intraday out = new Intraday();
        out.moves = radar.update(book, sharp, now, cfg, !partial);
        Closing.capture(ledger, sharp, now);
        String day = Fmt.dayKey(now);
        Ledger.Run run = ledger.runFor(day);
        String blocked = Guard.check(ledger, cfg, now);
        if (blocked != null) {
            out.blocked = blocked;
            return out;
        }
        Decision d = Engine.decide(book, sharp, now, cfg);
        if (d.isPass()) return out;
        List<Long> todayIds = run == null ? new ArrayList<Long>() : run.ids();
        String[] note = new String[1];
        if (run == null || (!partial && !anyPlayed(ledger, run))) {
            Portfolio.Result plan = Portfolio.optimize(d.candidates, cfg, cfg.maxCouponsPerDay, cfg.maxDailyExposure, null);
            java.util.Set<String> fresh = new java.util.HashSet<>(), current = new java.util.HashSet<>();
            if (plan.picks.isEmpty()) fresh.add(signature(d.proposal));
            for (Portfolio.Pick p : plan.picks) fresh.add(signature(p.proposal));
            for (Long id : todayIds) current.add(signature(ledger.coupon(id)));
            if (fresh.equals(current)) return out;
            List<Long> ids = createCoupons(ledger, d, day, cfg, false, "Gün içi taramada güncel oranlarla bulundu.",
                    Texts.statsLine(d.stats), note);
            out.newCouponId = ids.get(0);
            out.newCouponIds = ids;
            return out;
        }
        // Oynanmış kuponlar duruyor: kalan sınır içinde başka maçlardan ek kupon
        List<Long> ids = addBeside(ledger, d, day, cfg, run, now, "Gün içi taramada", note);
        if (ids.isEmpty()) return out;
        out.newCouponId = ids.get(0);
        out.newCouponIds = ids;
        return out;
    }

    static boolean anyPlayed(Ledger ledger, Ledger.Run run) {
        for (Long id : run.ids()) if (ledger.coupon(id).played) return true;
        return false;
    }

    /**
     * Bugünün oynanmış (ve henüz başlamamış oynanmamış) kuponları dururken, kalan günlük üst sınır
     * ve kupon hakkı içinde, o kuponlarda olmayan maçlardan ek kupon kurar ve günün koşusuna ekler.
     */
    static List<Long> addBeside(Ledger ledger, Decision d, String day, Settings cfg, Ledger.Run run, Instant now,
                                String label, String[] note) {
        double used = 0;
        int active = 0;
        java.util.Set<String> exclude = new java.util.HashSet<>();
        for (Long id : run.ids()) {
            Ledger.Coupon c = ledger.coupon(id);
            boolean pending = !c.played && c.result == null && Recheck.firstKickoff(c).isAfter(now);
            if (!c.played && !pending) continue; // oynanmadan geçmiş kupon sınırı tüketmez
            used += c.fraction > 0 ? c.fraction * c.scale : cfg.maxStakeFraction;
            active++;
            for (Ledger.Leg l : c.legs) exclude.add(l.bookRef);
        }
        List<Long> ids = new ArrayList<>();
        double capLeft = cfg.maxDailyExposure - used;
        int slots = cfg.maxCouponsPerDay - active;
        if (capLeft < 0.005 || slots <= 0) return ids;
        ids = addPortfolio(ledger, d.candidates, day, cfg, false, slots, capLeft, exclude, note);
        if (ids.isEmpty()) return ids;
        List<Long> all = new ArrayList<>(run.ids());
        all.addAll(ids);
        ledger.recordRun(day, "kupon", (run.reason + " " + label + " " + ids.size() + " ek kupon.").trim(), all, run.summary);
        return ids;
    }

    static String signature(Proposal p) {
        List<String> k = new ArrayList<>();
        for (Models.Candidate l : p.legs) k.add(l.book.ref + "|" + l.market + "|" + l.outcome);
        java.util.Collections.sort(k);
        return String.join(";", k);
    }

    static String signature(Ledger.Coupon c) {
        List<String> k = new ArrayList<>();
        for (Ledger.Leg l : c.legs) k.add(l.bookRef + "|" + l.market + "|" + l.outcome);
        java.util.Collections.sort(k);
        return String.join(";", k);
    }

    static boolean sameSelections(Ledger.Coupon c, Proposal p) {
        if (c.legs.size() != p.legs.size()) return false;
        java.util.Set<String> a = new java.util.HashSet<>(), b = new java.util.HashSet<>();
        for (Ledger.Leg l : c.legs) a.add(l.bookRef + "|" + l.market + "|" + l.outcome);
        for (Models.Candidate l : p.legs) b.add(l.book.ref + "|" + l.market + "|" + l.outcome);
        return a.equals(b);
    }

    /** Güncel kasaya göre önerilen tutar (oynanmamış kupon için). */
    public static long stakeNow(Ledger ledger, Ledger.Coupon c, Settings cfg) {
        if (c.fraction <= 0) return c.suggestedStake; // eski kayıtlar
        return computeStake(ledger.balance(), c.fraction * c.scale, cfg, new String[1])[0];
    }

    /**
     * Otomatik kasa takibi: kontrol "oynanabilir" dediyse kupon o anki güncel oranlar ve güncel
     * kasaya göre tutarla oynanmış sayılır. Oynandıysa tutarı, oynanmadıysa 0 döndürür.
     */
    public static long applyCheck(Ledger ledger, long couponId, Map<String, Object> check, Settings cfg) {
        Ledger.Coupon c = ledger.coupon(couponId);
        if (!cfg.autoTrack || c.played || c.superseded || c.result != null || !Boolean.TRUE.equals(check.get("playable"))) return 0;
        long stake = Json.lng(check, "stake", 0);
        if (stake <= 0 || stake > ledger.balance()) return 0;
        List<List<Double>> v = Recheck.checkedValues(check);
        ledger.markPlayed(couponId, stake, v.get(0), v.get(1));
        ledger.setAutoPlayed(couponId);
        return stake;
    }

    public static Result runDaily(Ledger ledger, Sources src, boolean force, boolean autoPlay) {
        List<String> msgs = settle(ledger, src);
        Result r = generate(ledger, src, force, autoPlay);
        r.settleMessages = msgs;
        return r;
    }
}
