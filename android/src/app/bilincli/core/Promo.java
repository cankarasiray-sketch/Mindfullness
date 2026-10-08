package app.bilincli.core;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.Candidate;
import app.bilincli.core.Models.Pair;
import app.bilincli.core.Models.Proposal;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Promosyon / özel oran kontrolü. Bilyoner'in "Süper Oran" gibi kampanyaları bazen adil oranın
 * üstüne çıkar; iddaa oyuncusu için gerçek avantajın en güvenilir kaynağıdır. Son tam taramanın
 * eşleşen maçları ve adil olasılıkları saklanır (kredi harcamadan kontrol için); kampanya oranı
 * girilince avantaj ve Kelly tutarı hesaplanır, oynandıysa deftere promosyon kuponu olarak yazılır.
 */
public final class Promo {
    private Promo() {}

    /**
     * Tabloda en fazla bu kadar maç (en yakın başlayanlar). 2.16: 250 -> 300, Fırsatlar 48 saate çıkınca
     * yoğun hafta sonlarında ikinci günün maçları kesilmesin.
     */
    static final int MAX_ROWS = 300;
    /** Bu kadar gün ilerisindeki maçlar alınmaz. */
    static final long HORIZON_S = 4 * 86400L;

    /**
     * Eşleşen maçlar: {ref, sref, sport, code, home, away, kickoff, league, sel: [{m, o, label, p, i}]}.
     * p: keskin piyasanın adil olasılığı (basketbol Alt/Üst iddaa çizgisine çevrilmiş), i: iddaa oranı.
     */
    public static List<Object> table(List<Pair> pairs, Instant now) {
        List<Pair> sorted = new ArrayList<>();
        for (Pair p : pairs) {
            if (p.book.kickoff.isAfter(now) && p.book.kickoff.isBefore(now.plusSeconds(HORIZON_S))) sorted.add(p);
        }
        Collections.sort(sorted, new Comparator<Pair>() {
            @Override
            public int compare(Pair a, Pair b) {
                return a.book.kickoff.compareTo(b.book.kickoff);
            }
        });
        List<Object> rows = new ArrayList<>();
        for (Pair p : sorted) {
            if (rows.size() >= MAX_ROWS) break;
            List<Object> sel = new ArrayList<>();
            for (Map.Entry<String, Map<String, Double>> m : p.book.odds.entrySet()) {
                Map<String, Double> fair = Models.fair(p.sharp, m.getKey());
                if (fair == null) continue;
                for (Map.Entry<String, Double> o : m.getValue().entrySet()) {
                    Double prob = fair.get(o.getKey());
                    if (prob == null || !(prob > 0 && prob < 1)) continue;
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("m", m.getKey());
                    s.put("o", o.getKey());
                    s.put("label", Models.outcomeLabel(m.getKey(), o.getKey()));
                    s.put("p", Math.round(prob * 10000) / 10000.0); // 4 hane yeter; tablo her taramada yazılır
                    s.put("i", o.getValue());
                    s.put("mbs", (long) p.book.mbsFor(m.getKey())); // 1 değilse tek oynanamaz
                    if (GoalModel.isModelMarket(m.getKey())) s.put("model", true); // gol modeli, payı düşülmüş
                    sel.add(s);
                }
            }
            if (sel.isEmpty()) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ref", p.book.ref);
            r.put("sref", p.sharp.ref);
            r.put("sport", p.sharp.sportKey);
            r.put("code", p.book.code);
            r.put("home", p.book.home);
            r.put("away", p.book.away);
            r.put("kickoff", p.book.kickoff.toString());
            r.put("league", Models.leagueLabel(p.book, p.sharp));
            r.put("at", now.toString()); // adil oranın alındığı an (hedefli taramalar tabloyu satır satır günceller)
            r.put("sel", sel);
            rows.add(r);
        }
        return rows;
    }

    /** Değerlendirme sonucu. */
    public static final class Check {
        public double prob, odds, ev, fraction;
        public long stake;
        public boolean play, lowProb;
        public String message;
    }

    /**
     * Kampanya oranı odds ile seçim: avantaj = olasılık x oran - 1 (kanıt koruması devredeyse
     * olasılık motorla aynı biçimde küçültülür). Tutar: ayarlardaki Kelly çarpanı ve kupon başına üst
     * sınır, kasa bakiyesine göre, 10 TL'ye yuvarlanmış.
     */
    public static Check evaluate(double fair, String market, double odds, Settings cfg, long balance) {
        Check c = new Check();
        c.odds = odds;
        c.prob = cfg.edgeRatios == null ? fair : EdgeCalibration.adjust(fair, odds, EdgeCalibration.ratio(cfg.edgeRatios, market));
        c.ev = c.prob * odds - 1;
        c.lowProb = fair < cfg.minLegProb;
        c.play = c.ev >= cfg.minLegEv && odds > 1 && !c.lowProb; // avantajlı ama seyrek tutan seçim önerilmez
        c.fraction = c.play ? Engine.stakeFraction(c.prob, odds, cfg) : 0;
        c.stake = balance <= 0 ? 0 : (long) (balance * c.fraction) / 1000 * 1000; // kuruş; 10 TL'ye aşağı yuvarla
        String head = "Kampanya oranı " + Fmt.odds(odds) + " · adil " + Fmt.odds(1 / fair) + " · avantaj " + Fmt.pct(c.ev, true);
        if (!c.play && c.lowProb && c.ev >= cfg.minLegEv) {
            c.message = head + ". Oynama: avantajlı ama tutma olasılığı düşük (" + Fmt.pct(fair, false) + "; ayar en az "
                    + Fmt.pct(cfg.minLegProb, false) + ").";
        } else if (!c.play) {
            c.message = head + ". Oynama: kampanya oranı adil oranı yeterince geçmiyor (eşik " + Fmt.pct(cfg.minLegEv, true) + ").";
        } else {
            c.message = head + ". Oyna: önerilen tutar " + (c.stake > 0 ? Fmt.tl(c.stake) : "kasa boş, tutar hesaplanamadı")
                    + " (kasanın " + Fmt.pct(c.fraction, false) + "'ü). Kampanyanın en yüksek bahis sınırı varsa onu aşma."
                    + (c.ev > Calibration.MAX_PLAUSIBLE_EV ? " Avantaj çok yüksek: kampanya şartlarını (tekli/kombine, maç, seçim) bir daha kontrol et." : "");
        }
        return c;
    }

    /** Tablodaki seçim (ref + pazar + sonuç); yoksa null. */
    public static Map<String, Object>[] find(List<Object> table, String ref, String market, String outcome) {
        if (table == null) return null;
        for (Object o : table) {
            Map<String, Object> r = Json.obj(o);
            if (r == null || !ref.equals(Json.str(r, "ref"))) continue;
            for (Object so : Json.arr(r.get("sel"))) {
                Map<String, Object> s = Json.obj(so);
                if (s != null && market.equals(Json.str(s, "m")) && outcome.equals(Json.str(s, "o"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object>[] out = new Map[] {r, s};
                    return out;
                }
            }
        }
        return null;
    }

    /**
     * Oynanan kampanya bahsini deftere yazar (tekli kupon, promosyon işaretli): kasa ve
     * sonuçlandırma normal kupon gibi işler; kanıt ölçümüne (kapanış avantajı) girmez, çünkü
     * kampanya oranı piyasa oranı değildir.
     */
    public static long record(Ledger ledger, Map<String, Object> row, Map<String, Object> sel, double odds, long stake, String day) {
        if (stake <= 0) throw new Ledger.LedgerException("Kupon tutarı pozitif olmalı");
        if (stake > ledger.balance()) { // önce: yetmezse oynanmamış kupon artığı kalmasın
            throw new Ledger.LedgerException("Kasada yeterli para yok (kasa: " + Fmt.tl(ledger.balance()) + ")");
        }
        String market = Json.str(sel, "m"), outcome = Json.str(sel, "o");
        double prob = Json.dbl(sel, "p", 0);
        Instant kickoff = Instant.parse(Json.str(row, "kickoff"));
        Map<String, Map<String, Double>> bookOdds = new LinkedHashMap<>();
        Map<String, Double> bo = new LinkedHashMap<>();
        bo.put(outcome, odds);
        bookOdds.put(market, bo);
        BookEvent b = new BookEvent(Json.str(row, "ref"), Json.str(row, "home"), Json.str(row, "away"), kickoff,
                Json.str(row, "league"), 1, bookOdds, Json.str(row, "code"));
        Map<String, Map<String, Double>> fair = new LinkedHashMap<>();
        Map<String, Double> fo = new LinkedHashMap<>();
        fo.put(outcome, prob);
        fair.put(market, fo);
        SharpEvent s = new SharpEvent(Json.str(row, "sref"), Json.str(row, "sport"), Json.str(row, "home"),
                Json.str(row, "away"), kickoff, fair, "pinnacle");
        List<Candidate> legs = new ArrayList<>();
        legs.add(new Candidate(b, s, market, outcome, odds, prob));
        Proposal p = new Proposal(legs, odds, prob, 0, 0);
        long id = ledger.addCoupon(p, day, stake);
        ledger.markPromo(id);
        ledger.markPlayed(id, stake, null);
        return id;
    }

    /** Günün seçimi oynandı (2.9): tekli kupon, avantaj ölçümüne girmez, "günün seçimi" olarak takip edilir. */
    public static long recordPick(Ledger ledger, Map<String, Object> row, Map<String, Object> sel, double odds, long stake, String day) {
        long id = record(ledger, row, sel, odds, stake, day);
        ledger.markPick(id);
        return id;
    }
}
