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
        public List<String> settleMessages = new ArrayList<>();

        public String headline() {
            if (error != null) return "HATA";
            if (blocked != null) return "KORUMA";
            if (decision == null || decision.isPass()) return "PAS";
            return "KUPON";
        }
    }

    public static final class LiveSources implements Sources {
        private final Http http;
        private final Settings cfg;
        private OddsApi api;
        /** Calibration'ın son güvenilir pazar eşlemeleri (kalıcı saklamak çağıranın işi). */
        public Map<String, Object> memory = new java.util.LinkedHashMap<>();

        public LiveSources(Http http, Settings cfg) {
            this.http = http;
            this.cfg = cfg;
        }

        private OddsApi api() throws Http.ProviderException {
            if (api == null) api = new OddsApi(http, cfg);
            return api;
        }

        public String remainingCredits() {
            return api == null ? null : api.remaining;
        }

        /**
         * Karşılıklı Gol için Pinnacle oranı maç bazında çekilir (maç başına 1 kredi). Yalnızca
         * iddaa'da aday KG pazarı olan, zaman penceresindeki ilk cfg.kgEvents maç için.
         */
        public int enrichKg(List<BookEvent> book, List<SharpEvent> sharp, Instant now) throws Http.ProviderException {
            if (cfg.kgEvents <= 0) return 0;
            List<Models.Pair> pairs = Matching.match(book, sharp);
            List<Models.Pair> due = new ArrayList<>();
            Instant from = now.plusSeconds(Math.round(cfg.minLeadMinutes * 60)), to = now.plusSeconds(Math.round(cfg.windowHours * 3600));
            for (Models.Pair p : pairs) {
                if (p.book.kickoff.isBefore(from) || p.book.kickoff.isAfter(to) || p.sharp.fair.containsKey("KG")) continue;
                for (String k : p.book.odds.keySet()) {
                    if (k.startsWith(Calibration.RAW_KG)) {
                        due.add(p);
                        break;
                    }
                }
            }
            java.util.Collections.sort(due, new java.util.Comparator<Models.Pair>() {
                @Override
                public int compare(Models.Pair a, Models.Pair b) {
                    return a.book.kickoff.compareTo(b.book.kickoff);
                }
            });
            int n = 0;
            for (Models.Pair p : due) {
                if (n >= cfg.kgEvents) break;
                try {
                    api().enrichEvent(p.sharp, "btts");
                    n++;
                } catch (Http.ProviderException e) {
                    break; // bu pazar desteklenmiyor ya da kredi bitti; ana akışı durdurma
                }
            }
            return n;
        }

        @Override
        public List<BookEvent> book() throws Http.ProviderException {
            return Nesine.fetch(http);
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
     * Kararın ana kuponu ve maç paylaşmayan alternatiflerinden günlük kupon sayısı kadarını kaydeder.
     * Tutarlar Kelly'ye göre; toplamı günlük üst sınırı aşarsa orantılı küçültülür.
     */
    static List<Long> createCoupons(Ledger ledger, Decision d, String day, Settings cfg, boolean autoPlay,
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
            long id = ledger.addCoupon(p, day, stake);
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
        if (ledger.openCoupons().isEmpty()) return new ArrayList<>();
        try {
            return Settlement.settleOpen(ledger, new Settlement.ScoreFetcher() {
                @Override
                public Map<String, ScoreResult> fetch(Set<String> sportKeys) throws Exception {
                    return src.scores(sportKeys);
                }
            }, ledger.now());
        } catch (Exception e) {
            List<String> m = new ArrayList<>();
            m.add("Sonuçlar alınamadı: " + e.getMessage());
            return m;
        }
    }

    public static Result generate(Ledger ledger, Sources src, boolean force, boolean autoPlay) {
        return generate(ledger, src, force, autoPlay, null);
    }

    public static Result generate(Ledger ledger, Sources src, boolean force, boolean autoPlay, Radar radar) {
        Instant now = ledger.now();
        Settings cfg = ledger.settings();
        Result r = new Result();
        r.day = Fmt.dayKey(now);
        Ledger.Run existing = ledger.runFor(r.day);
        if (existing != null && !force) {
            r.skipped = true;
            r.couponId = existing.couponId;
            return r;
        }
        String blocked = Guard.check(ledger, cfg, now);
        if (blocked != null) {
            r.blocked = blocked;
            ledger.recordRun(r.day, "koruma", blocked, (Long) null, "");
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
        Decision d = Engine.decide(f.book, f.sharp, now, cfg);
        d.stats.put("dogrulama", Calibration.summary(f.calibration));
        r.decision = d;
        r.calibration = f.calibration;
        String summary = Texts.statsLine(d.stats);
        if (d.isPass()) {
            ledger.recordRun(r.day, "pas", d.reason, (Long) null, summary);
            return r;
        }
        String[] note = new String[1];
        r.couponIds = createCoupons(ledger, d, r.day, cfg, autoPlay, null, summary, note);
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
     * Radar taraması: piyasa verisini işler; bugün oynanmış kupon yoksa ve güncel oranlarla
     * sabahkinden farklı bir kupon kurulabiliyorsa onu bugünün kuponu yapar.
     */
    public static Intraday intraday(Ledger ledger, List<BookEvent> book, List<SharpEvent> sharp, Radar radar) {
        Instant now = ledger.now();
        Settings cfg = ledger.settings();
        Intraday out = new Intraday();
        out.moves = radar.update(book, sharp, now, cfg, true);
        Closing.capture(ledger, sharp, now);
        String day = Fmt.dayKey(now);
        Ledger.Run run = ledger.runFor(day);
        Ledger.Coupon today = run == null || run.couponId == null ? null : ledger.coupon(run.couponId);
        if (run != null) {
            for (Long id : run.ids()) {
                Ledger.Coupon c = ledger.coupon(id);
                if (c.played || c.result != null) return out; // oynanmış kuponları değiştirme
            }
        }
        String blocked = Guard.check(ledger, cfg, now);
        if (blocked != null) {
            out.blocked = blocked;
            return out;
        }
        Decision d = Engine.decide(book, sharp, now, cfg);
        if (d.isPass()) return out;
        if (today != null && sameSelections(today, d.proposal)) return out;
        String[] note = new String[1];
        List<Long> ids = createCoupons(ledger, d, day, cfg, false, "Gün içi taramada güncel oranlarla bulundu.",
                Texts.statsLine(d.stats), note);
        out.newCouponId = ids.get(0);
        out.newCouponIds = ids;
        return out;
    }

    static boolean sameSelections(Ledger.Coupon c, Proposal p) {
        if (c.legs.size() != p.legs.size()) return false;
        java.util.Set<String> a = new java.util.HashSet<>(), b = new java.util.HashSet<>();
        for (Ledger.Leg l : c.legs) a.add(l.bookRef + "|" + l.market + "|" + l.outcome);
        for (Models.Candidate l : p.legs) b.add(l.book.ref + "|" + l.market + "|" + l.outcome);
        return a.equals(b);
    }

    public static Result runDaily(Ledger ledger, Sources src, boolean force, boolean autoPlay) {
        List<String> msgs = settle(ledger, src);
        Result r = generate(ledger, src, force, autoPlay);
        r.settleMessages = msgs;
        return r;
    }
}
