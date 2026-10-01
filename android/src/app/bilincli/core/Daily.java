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
            ledger.recordRun(r.day, "koruma", blocked, null, "");
            return r;
        }
        List<BookEvent> book;
        List<SharpEvent> sharp;
        try {
            book = src.book();
            sharp = src.sharp();
        } catch (Http.ProviderException e) {
            r.error = e.getMessage();
            return r; // hata günü kaydedilmez; sonraki çalıştırma yeniden dener
        }
        if (radar != null) radar.update(book, sharp, now, cfg, true);
        Decision d = Engine.decide(book, sharp, now, cfg);
        r.decision = d;
        String summary = Texts.statsLine(d.stats);
        if (d.isPass()) {
            ledger.recordRun(r.day, "pas", d.reason, null, summary);
            return r;
        }
        String[] note = new String[1];
        long stake = computeStake(ledger.balance(), d.proposal, cfg, note)[0];
        long cid = ledger.addCoupon(d.proposal, r.day, stake);
        r.couponId = cid;
        r.stake = stake;
        r.stakeNote = note[0];
        if (autoPlay && stake > 0) ledger.markPlayed(cid, stake, null);
        ledger.recordRun(r.day, "kupon", note[0] == null ? "" : note[0], cid, summary);
        return r;
    }

    /** Gün içi tarama sonucu. */
    public static final class Intraday {
        public Long newCouponId;
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
        if (today != null && (today.played || today.result != null)) return out; // oynanmış kuponu değiştirme
        String blocked = Guard.check(ledger, cfg, now);
        if (blocked != null) {
            out.blocked = blocked;
            return out;
        }
        Decision d = Engine.decide(book, sharp, now, cfg);
        if (d.isPass()) return out;
        if (today != null && sameSelections(today, d.proposal)) return out;
        String[] note = new String[1];
        long stake = computeStake(ledger.balance(), d.proposal, cfg, note)[0];
        long cid = ledger.addCoupon(d.proposal, day, stake);
        ledger.recordRun(day, "kupon", "Gün içi taramada güncel oranlarla bulundu."
                + (note[0] == null ? "" : " " + note[0]), cid, Texts.statsLine(d.stats));
        out.newCouponId = cid;
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
