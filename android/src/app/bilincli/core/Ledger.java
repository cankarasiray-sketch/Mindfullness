package app.bilincli.core;

import app.bilincli.core.Models.Candidate;
import app.bilincli.core.Models.Proposal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kasa defteri (Python: bilincli/ledger.py). Tutarlar kuruş cinsinden tam sayıdır.
 * Kasa bakiyesi her zaman hareketlerin toplamıdır. Veriler tek bir JSON belgesi
 * olarak saklanır; her değişiklikten sonra {@link Storage} ile diske yazılır.
 */
public final class Ledger {
    public interface Storage {
        String read();

        void write(String data);
    }

    public interface Clock {
        Instant now();
    }

    public static final class LedgerException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LedgerException(String msg) {
            super(msg);
        }
    }

    public static final class Tx {
        public long id;
        public String ts, kind, note;
        public long amount;
        public Long couponId;
    }

    public static final class Leg {
        public long id;
        public int position, mbs;
        public String bookRef, bookCode, sharpRef, sportKey, home, away, kickoff, league, market, outcome;
        public double odds, fairProb;
        public String result, score;
        /** Maç başlamadan hemen önce Pinnacle'ın adil olasılığı (kapanış). CLV ölçümü için. */
        public Double closingFair;
        public String closingAt;

        /** Kapanış avantajı: alınan oranın kapanıştaki adil orana göre değeri. null = kapanış yok. */
        public Double clv() {
            return closingFair == null ? null : odds * closingFair - 1.0;
        }
    }

    public static final class Coupon {
        public long id;
        public String createdAt, day, playedAt, result, settledAt;
        public boolean played;
        public long stake, suggestedStake;
        public double totalOdds, winProb;
        public Long payout;
        public List<Leg> legs = new ArrayList<>();
        /** Son "oynamadan önce kontrol" sonucu (Recheck.run çıktısı). */
        public Map<String, Object> lastCheck;
        /** Kelly'ye göre kasa oranı (kupon başına üst sınır uygulanmış) ve günlük üst sınır küçültmesi. */
        public double fraction, scale = 1.0;
        /** Otomatik kasa takibiyle oynandı sayıldıysa true. */
        public boolean autoPlayed;
        /**
         * Günün planı güncel oranlarla yenilendiğinde oynanmamış eski kupon. Açık kupon sayılmaz:
         * maç öncesi kontrol, otomatik oynama, kapanış ve sonuçlandırma dışında kalır.
         */
        public boolean superseded;
    }

    public static final class Run {
        public String day, ts, decision, reason, summary;
        public Long couponId;
        /** Günün tüm kuponları (ilki couponId). */
        public List<Long> couponIds = new ArrayList<>();

        public List<Long> ids() {
            if (!couponIds.isEmpty()) return couponIds;
            List<Long> one = new ArrayList<>();
            if (couponId != null) one.add(couponId);
            return one;
        }
    }

    public static final class Stats {
        public long balance, deposited, withdrawn, staked, returned, openStake;
        public int played, won, lost, voided, suggestedSettled, suggestedWon;
        public double expectedWins;

        public long bettingPl() {
            return returned - (staked - openStake);
        }

        public Double roi() {
            long settled = staked - openStake;
            return settled == 0 ? null : (double) bettingPl() / settled;
        }
    }

    private final Storage storage;
    private final Clock clock;
    private final List<Tx> txs = new ArrayList<>();
    private final List<Coupon> coupons = new ArrayList<>();
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private Settings settings = new Settings();
    private long nextId = 1;
    private long nextCouponId = 1; // kupon numaraları kullanıcıya görünür: sıralı artsın

    public Ledger(Storage storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
        String data = storage.read();
        if (data != null && !data.trim().isEmpty()) load(Json.parseObject(data));
    }

    public Instant now() {
        return clock.now();
    }

    private String ts() {
        return clock.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    // ---- kalıcılık --------------------------------------------------------
    public synchronized String export() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 1L);
        m.put("nextId", nextId);
        m.put("nextCouponId", nextCouponId);
        m.put("settings", settings.toMap());
        List<Object> t = new ArrayList<>();
        for (Tx x : txs) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", x.id);
            o.put("ts", x.ts);
            o.put("kind", x.kind);
            o.put("amount", x.amount);
            o.put("couponId", x.couponId);
            o.put("note", x.note);
            t.add(o);
        }
        m.put("transactions", t);
        List<Object> cs = new ArrayList<>();
        for (Coupon c : coupons) cs.add(couponMap(c));
        m.put("coupons", cs);
        List<Object> rs = new ArrayList<>();
        for (Run r : runs.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("day", r.day);
            o.put("ts", r.ts);
            o.put("decision", r.decision);
            o.put("reason", r.reason);
            o.put("couponId", r.couponId);
            o.put("summary", r.summary);
            o.put("couponIds", new ArrayList<Object>(r.couponIds));
            rs.add(o);
        }
        m.put("runs", rs);
        return Json.write(m);
    }

    public static Map<String, Object> couponMap(Coupon c) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", c.id);
        o.put("createdAt", c.createdAt);
        o.put("day", c.day);
        o.put("played", c.played);
        o.put("playedAt", c.playedAt);
        o.put("stake", c.stake);
        o.put("suggestedStake", c.suggestedStake);
        o.put("totalOdds", c.totalOdds);
        o.put("winProb", c.winProb);
        o.put("result", c.result);
        o.put("payout", c.payout);
        o.put("settledAt", c.settledAt);
        List<Object> legs = new ArrayList<>();
        for (Leg l : c.legs) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", l.id);
            g.put("position", (long) l.position);
            g.put("bookRef", l.bookRef);
            g.put("bookCode", l.bookCode);
            g.put("sharpRef", l.sharpRef);
            g.put("sportKey", l.sportKey);
            g.put("home", l.home);
            g.put("away", l.away);
            g.put("kickoff", l.kickoff);
            g.put("league", l.league);
            g.put("market", l.market);
            g.put("outcome", l.outcome);
            g.put("odds", l.odds);
            g.put("fairProb", l.fairProb);
            g.put("mbs", (long) l.mbs);
            g.put("result", l.result);
            g.put("score", l.score);
            g.put("closingFair", l.closingFair);
            g.put("closingAt", l.closingAt);
            legs.add(g);
        }
        o.put("legs", legs);
        o.put("lastCheck", c.lastCheck);
        o.put("fraction", c.fraction);
        o.put("scale", c.scale);
        o.put("autoPlayed", c.autoPlayed);
        o.put("superseded", c.superseded);
        return o;
    }

    private void load(Map<String, Object> m) {
        nextId = Json.lng(m, "nextId", 1);
        settings = Settings.fromMap(Json.obj(m.get("settings")));
        for (Object o : Json.arr(m.get("transactions"))) {
            Map<String, Object> x = Json.obj(o);
            Tx t = new Tx();
            t.id = Json.lng(x, "id", 0);
            t.ts = Json.str(x, "ts");
            t.kind = Json.str(x, "kind");
            t.amount = Json.lng(x, "amount", 0);
            t.couponId = x.get("couponId") == null ? null : Json.lng(x, "couponId", 0);
            t.note = Json.str(x, "note");
            txs.add(t);
        }
        for (Object o : Json.arr(m.get("coupons"))) {
            Map<String, Object> x = Json.obj(o);
            Coupon c = new Coupon();
            c.id = Json.lng(x, "id", 0);
            c.createdAt = Json.str(x, "createdAt");
            c.day = Json.str(x, "day");
            c.played = Json.bool(x, "played", false);
            c.playedAt = Json.str(x, "playedAt");
            c.stake = Json.lng(x, "stake", 0);
            c.suggestedStake = Json.lng(x, "suggestedStake", 0);
            c.totalOdds = Json.dbl(x, "totalOdds", 1);
            c.winProb = Json.dbl(x, "winProb", 0);
            c.result = Json.str(x, "result");
            c.payout = x.get("payout") == null ? null : Json.lng(x, "payout", 0);
            c.settledAt = Json.str(x, "settledAt");
            c.lastCheck = Json.obj(x.get("lastCheck"));
            c.fraction = Json.dbl(x, "fraction", 0);
            c.scale = Json.dbl(x, "scale", 1.0);
            c.autoPlayed = Json.bool(x, "autoPlayed", false);
            c.superseded = Json.bool(x, "superseded", false);
            for (Object lo : Json.arr(x.get("legs"))) {
                Map<String, Object> g = Json.obj(lo);
                Leg l = new Leg();
                l.id = Json.lng(g, "id", 0);
                l.position = (int) Json.lng(g, "position", 0);
                l.bookRef = Json.str(g, "bookRef");
                l.bookCode = Json.str(g, "bookCode");
                l.sharpRef = Json.str(g, "sharpRef");
                l.sportKey = Json.str(g, "sportKey");
                l.home = Json.str(g, "home");
                l.away = Json.str(g, "away");
                l.kickoff = Json.str(g, "kickoff");
                l.league = Json.str(g, "league");
                l.market = Json.str(g, "market");
                l.outcome = Json.str(g, "outcome");
                l.odds = Json.dbl(g, "odds", 1);
                l.fairProb = Json.dbl(g, "fairProb", 0);
                l.mbs = (int) Json.lng(g, "mbs", 1);
                l.result = Json.str(g, "result");
                l.score = Json.str(g, "score");
                l.closingFair = Json.num(g, "closingFair");
                l.closingAt = Json.str(g, "closingAt");
                c.legs.add(l);
            }
            coupons.add(c);
            nextCouponId = Math.max(nextCouponId, c.id + 1);
        }
        nextCouponId = Math.max(nextCouponId, Json.lng(m, "nextCouponId", 1));
        for (Object o : Json.arr(m.get("runs"))) {
            Map<String, Object> x = Json.obj(o);
            Run r = new Run();
            r.day = Json.str(x, "day");
            r.ts = Json.str(x, "ts");
            r.decision = Json.str(x, "decision");
            r.reason = Json.str(x, "reason");
            r.summary = Json.str(x, "summary");
            r.couponId = x.get("couponId") == null ? null : Json.lng(x, "couponId", 0);
            for (Object id : Json.arr(x.get("couponIds"))) r.couponIds.add(((Number) id).longValue());
            runs.put(r.day, r);
        }
    }

    private void save() {
        storage.write(export());
    }

    /** Yedekten geri yükleme: geçerli bir dışa aktarım ise mevcut verinin yerini alır. */
    public synchronized void replaceWith(String data) {
        Map<String, Object> m = Json.parseObject(data);
        if (!m.containsKey("transactions") || !m.containsKey("coupons")) {
            throw new LedgerException("Bu bir Bilinçli Kupon yedeği değil");
        }
        Ledger probe = new Ledger(new MemoryStorage(data), clock);
        txs.clear();
        txs.addAll(probe.txs);
        coupons.clear();
        coupons.addAll(probe.coupons);
        runs.clear();
        runs.putAll(probe.runs);
        settings = probe.settings;
        nextId = probe.nextId;
        nextCouponId = probe.nextCouponId;
        save();
    }

    public static final class MemoryStorage implements Storage {
        private String data;

        public MemoryStorage(String data) {
            this.data = data;
        }

        @Override
        public String read() {
            return data;
        }

        @Override
        public void write(String d) {
            data = d;
        }
    }

    // ---- ayarlar ----------------------------------------------------------
    public synchronized Settings settings() {
        return Settings.fromMap(settings.toMap()); // kopya
    }

    public synchronized void saveSettings(Settings s) {
        String err = s.validate();
        if (err != null) throw new LedgerException(err);
        settings = Settings.fromMap(s.toMap());
        save();
    }

    // ---- para -------------------------------------------------------------
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3}(\\.\\d{3})+");

    /** "1500", "1500.50", "1.500", "1.500,50" ya da "1500,5" -> kuruş. */
    public static long parseTl(String text) {
        String s = text == null ? "" : text.trim().replace(" ", "").replace("TL", "").replace("₺", "");
        if (s.contains(",")) s = s.replace(".", "").replace(",", ".");
        else if (THOUSANDS.matcher(s).matches()) s = s.replace(".", "");
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new LedgerException("Tutar anlaşılamadı: " + text);
        }
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) throw new LedgerException("Tutar pozitif olmalı");
        return Math.round(v * 100);
    }

    public synchronized long balance() {
        long s = 0;
        for (Tx t : txs) s += t.amount;
        return s;
    }

    private void addTx(String kind, long amount, Long couponId, String note) {
        Tx t = new Tx();
        t.id = nextId++;
        t.ts = ts();
        t.kind = kind;
        t.amount = amount;
        t.couponId = couponId;
        t.note = note;
        txs.add(t);
    }

    public synchronized void deposit(long amount, String note) {
        if (amount <= 0) throw new LedgerException("Yatırılan tutar pozitif olmalı");
        addTx("deposit", amount, null, note);
        save();
    }

    public synchronized void withdraw(long amount, String note) {
        if (amount <= 0) throw new LedgerException("Çekilen tutar pozitif olmalı");
        if (amount > balance()) throw new LedgerException("Kasada yeterli para yok (kasa: " + Fmt.tl(balance()) + ")");
        addTx("withdraw", -amount, null, note);
        save();
    }

    public synchronized List<Tx> transactions() {
        return new ArrayList<>(txs);
    }

    // ---- kuponlar ---------------------------------------------------------
    public synchronized long addCoupon(Proposal p, String day, long suggestedStake) {
        return addCoupon(p, day, suggestedStake, 1.0);
    }

    public synchronized long addCoupon(Proposal p, String day, long suggestedStake, double scale) {
        Coupon c = new Coupon();
        c.fraction = p.stakeFraction;
        c.scale = scale;
        c.id = nextCouponId++;
        c.createdAt = ts();
        c.day = day;
        c.suggestedStake = suggestedStake;
        c.totalOdds = p.odds;
        c.winProb = p.prob;
        int pos = 1;
        for (Candidate leg : p.legs) {
            Leg l = new Leg();
            l.id = nextId++;
            l.position = pos++;
            l.bookRef = leg.book.ref;
            l.bookCode = leg.book.code;
            l.sharpRef = leg.sharp.ref;
            l.sportKey = leg.sharp.sportKey;
            l.home = leg.book.home;
            l.away = leg.book.away;
            l.kickoff = leg.book.kickoff.toString();
            l.league = leg.book.league;
            l.market = leg.market;
            l.outcome = leg.outcome;
            l.odds = leg.odds;
            l.fairProb = leg.rawProb; // kapanış ölçümü kalibre edilmemiş tahminle yapılır
            l.mbs = leg.mbs();
            c.legs.add(l);
        }
        coupons.add(c);
        save();
        return c.id;
    }

    public synchronized Coupon coupon(long id) {
        for (Coupon c : coupons) if (c.id == id) return c;
        throw new LedgerException(id + " numaralı kupon yok");
    }

    /** En yeni önce. */
    public synchronized List<Coupon> coupons() {
        List<Coupon> out = new ArrayList<>(coupons);
        Collections.reverse(out);
        return out;
    }

    /** Sonuçlanmamış kuponlar (yenilenmiş plandan kalan oynanmamış kuponlar hariç). */
    public synchronized List<Coupon> openCoupons() {
        List<Coupon> out = new ArrayList<>();
        for (Coupon c : coupons) if (c.result == null && !c.superseded) out.add(c);
        return out;
    }

    public synchronized void markPlayed(long couponId, long stake, List<Double> legOdds) {
        markPlayed(couponId, stake, legOdds, null);
    }

    /**
     * Oynandı kaydı. legFairs verilirse (güncel kontrolden) bacakların adil olasılığı ve
     * kuponun tutma olasılığı oynama anındaki değerlerle güncellenir.
     */
    public synchronized void markPlayed(long couponId, long stake, List<Double> legOdds, List<Double> legFairs) {
        Coupon c = coupon(couponId);
        if (c.played) throw new LedgerException(couponId + " numaralı kupon zaten oynandı olarak işaretli");
        if (c.result != null) throw new LedgerException(couponId + " numaralı kupon zaten sonuçlanmış");
        if (stake <= 0) throw new LedgerException("Kupon tutarı pozitif olmalı");
        if (stake > balance()) throw new LedgerException("Kasada yeterli para yok (kasa: " + Fmt.tl(balance()) + ")");
        c.superseded = false; // yenilenmiş plandan da olsa gerçekten oynandıysa açık kupondur
        if (legOdds != null) {
            if (legOdds.size() != c.legs.size()) {
                throw new LedgerException(c.legs.size() + " oran bekleniyordu, " + legOdds.size() + " verildi");
            }
            double total = 1;
            for (double o : legOdds) {
                if (!(o > 1.0)) throw new LedgerException("Oranlar 1'den büyük olmalı");
                total *= o;
            }
            for (int i = 0; i < legOdds.size(); i++) c.legs.get(i).odds = legOdds.get(i);
            c.totalOdds = total;
        }
        if (legFairs != null) {
            if (legFairs.size() != c.legs.size()) throw new LedgerException("Olasılık sayısı maç sayısıyla aynı olmalı");
            double prob = 1;
            for (int i = 0; i < legFairs.size(); i++) {
                double f = legFairs.get(i);
                if (!(f > 0 && f < 1)) throw new LedgerException("Geçersiz olasılık");
                c.legs.get(i).fairProb = f;
                prob *= f;
            }
            c.winProb = prob;
        }
        c.played = true;
        c.playedAt = ts();
        c.stake = stake;
        addTx("stake", -stake, c.id, "Kupon #" + c.id);
        save();
    }

    /** Otomatik ya da yanlışlıkla "oynandı" sayılan kuponu geri alır (sonuçlanmadan önce). */
    public synchronized void unmarkPlayed(long couponId) {
        Coupon c = coupon(couponId);
        if (!c.played) throw new LedgerException("Kupon #" + couponId + " zaten oynanmamış");
        if (c.result != null) throw new LedgerException("Sonuçlanmış kupon geri alınamaz");
        java.util.Iterator<Tx> it = txs.iterator();
        while (it.hasNext()) {
            Tx t = it.next();
            if ("stake".equals(t.kind) && t.couponId != null && t.couponId == couponId) it.remove();
        }
        c.played = false;
        c.autoPlayed = false;
        c.playedAt = null;
        c.stake = 0;
        Run run = runs.get(c.day);
        if (run == null || !run.ids().contains(c.id)) c.superseded = true; // günün geçerli planında değil
        save();
    }

    public synchronized void setAutoPlayed(long couponId) {
        coupon(couponId).autoPlayed = true;
        save();
    }

    public synchronized void setLegResult(long couponId, int position, String result, String score) {
        if (!Models.WON.equals(result) && !Models.LOST.equals(result) && !Models.VOID.equals(result)) {
            throw new LedgerException("Geçersiz sonuç: " + result);
        }
        for (Leg l : coupon(couponId).legs) {
            if (l.position == position) {
                l.result = result;
                l.score = score;
                save();
                return;
            }
        }
        throw new LedgerException("Kupon #" + couponId + " içinde " + position + ". maç yok");
    }

    /** Bacaklar yeterince sonuçlandıysa kuponu kapatır; sonucu döndürür. */
    public synchronized String settleCoupon(long couponId) {
        Coupon c = coupon(couponId);
        if (c.result != null) return c.result;
        boolean anyLost = false, allDone = true, allVoid = true;
        double multiplier = 1.0;
        for (Leg l : c.legs) {
            if (Models.LOST.equals(l.result)) anyLost = true;
            if (l.result == null) allDone = false;
            if (!Models.VOID.equals(l.result)) allVoid = false;
            if (Models.WON.equals(l.result)) multiplier *= l.odds; // iade = 1,00
        }
        String outcome;
        if (anyLost) {
            outcome = Models.LOST;
            multiplier = 0;
        } else if (allDone) {
            outcome = allVoid ? Models.VOID : Models.WON;
        } else {
            return null;
        }
        long payout = c.played ? Math.round(c.stake * multiplier) : 0;
        c.result = outcome;
        c.payout = c.played ? payout : null;
        c.settledAt = ts();
        if (c.played && payout > 0) addTx("payout", payout, c.id, "Kupon #" + c.id + " ödeme");
        save();
        return outcome;
    }

    public synchronized void setClosing(long couponId, int position, double fair) {
        for (Leg l : coupon(couponId).legs) {
            if (l.position == position) {
                l.closingFair = fair;
                l.closingAt = ts();
                save();
                return;
            }
        }
        throw new LedgerException("Kupon #" + couponId + " içinde " + position + ". maç yok");
    }

    public synchronized void saveCheck(long couponId, Map<String, Object> check) {
        coupon(couponId).lastCheck = check;
        save();
    }

    /** CLV özeti: [oynanan bacak ortalaması, sayısı, tüm önerilen bacak ortalaması, sayısı]. */
    public synchronized double[] clvSummary() {
        double playedSum = 0, allSum = 0;
        int playedN = 0, allN = 0;
        for (Coupon c : coupons) {
            for (Leg l : c.legs) {
                Double v = l.clv();
                if (v == null) continue;
                allSum += v;
                allN++;
                if (c.played) {
                    playedSum += v;
                    playedN++;
                }
            }
        }
        return new double[] {playedN == 0 ? 0 : playedSum / playedN, playedN, allN == 0 ? 0 : allSum / allN, allN};
    }

    // ---- günlük çalıştırma kayıtları -----------------------------------------
    public synchronized void recordRun(String day, String decision, String reason, Long couponId, String summary) {
        List<Long> ids = new ArrayList<>();
        if (couponId != null) ids.add(couponId);
        recordRun(day, decision, reason, ids, summary);
    }

    public synchronized void recordRun(String day, String decision, String reason, List<Long> couponIds, String summary) {
        Long couponId = couponIds.isEmpty() ? null : couponIds.get(0);
        Run prev = runs.get(day);
        if (prev != null) {
            // plan yenilendi: yeni planda olmayan, oynanmamış eski kuponlar artık geçersiz
            for (Long id : prev.ids()) {
                if (couponIds.contains(id)) continue;
                for (Coupon c : coupons) {
                    if (c.id == id && !c.played && c.result == null) c.superseded = true;
                }
            }
        }
        Run r = new Run();
        r.couponIds = new ArrayList<>(couponIds);
        r.day = day;
        r.ts = ts();
        r.decision = decision;
        r.reason = reason;
        r.couponId = couponId;
        r.summary = summary;
        runs.remove(day);
        runs.put(day, r);
        save();
    }

    public synchronized Run runFor(String day) {
        return runs.get(day);
    }

    /** En yeni gün önce. */
    public synchronized List<Run> runs(int limit) {
        List<Run> out = new ArrayList<>(runs.values());
        Collections.sort(out, new Comparator<Run>() {
            @Override
            public int compare(Run a, Run b) {
                return b.day.compareTo(a.day);
            }
        });
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    // ---- özet -------------------------------------------------------------
    public synchronized Stats stats() {
        Stats s = new Stats();
        for (Tx t : txs) {
            s.balance += t.amount;
            if ("deposit".equals(t.kind)) s.deposited += t.amount;
            else if ("withdraw".equals(t.kind)) s.withdrawn -= t.amount;
            else if ("stake".equals(t.kind)) s.staked -= t.amount;
            else if ("payout".equals(t.kind)) s.returned += t.amount;
        }
        for (Coupon c : coupons) {
            if (c.played && c.result == null) s.openStake += c.stake;
            if (c.played && c.result != null) {
                s.played++;
                if (Models.WON.equals(c.result)) s.won++;
                else if (Models.LOST.equals(c.result)) s.lost++;
                else s.voided++;
                if (!Models.VOID.equals(c.result)) s.expectedWins += c.winProb;
            }
            if (c.result != null && !Models.VOID.equals(c.result)) {
                s.suggestedSettled++;
                if (Models.WON.equals(c.result)) s.suggestedWon++;
            }
        }
        return s;
    }

    /** Gerçekleşmiş kasa eğrisi: açık kuponun bahsi kasada sayılır, sonuçlanınca K/Z eklenir. */
    public synchronized List<Object[]> balanceSeries() {
        List<Object[]> points = new ArrayList<>();
        for (Tx t : txs) {
            if ("deposit".equals(t.kind) || "withdraw".equals(t.kind)) points.add(new Object[] {t.ts, t.amount});
        }
        for (Coupon c : coupons) {
            if (c.played && c.result != null) {
                points.add(new Object[] {c.settledAt, (c.payout == null ? 0 : c.payout) - c.stake});
            }
        }
        Collections.sort(points, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Instant.parse((String) a[0]).compareTo(Instant.parse((String) b[0]));
            }
        });
        List<Object[]> series = new ArrayList<>();
        long running = 0;
        for (Object[] p : points) {
            running += (Long) p[1];
            series.add(new Object[] {p[0], running});
        }
        return series;
    }
}
