package app.bilincli.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Veri yapıları. */
public final class Models {
    private Models() {}

    public static final String WON = "kazandi";
    public static final String LOST = "kaybetti";
    public static final String VOID = "iade";

    public static final String FOOTBALL = "futbol";
    public static final String BASKETBALL = "basketbol";

    /** The Odds API spor kodundan spor ("basketball_nba" -> basketbol). */
    public static String sportOf(String sportKey) {
        return sportKey != null && sportKey.startsWith("basketball_") ? BASKETBALL : FOOTBALL;
    }

    /**
     * Basketbol toplam sayı Alt/Üst: Pinnacle tek (ana) çizgi verir, iddaa çoğu zaman başka bir
     * çizgi. Toplam sayı normal dağılımlı kabul edilir; ortalama Pinnacle'ın çizgisi ve Üst
     * olasılığından bulunur, iddaa çizgisindeki olasılık buradan okunur. Fark büyüdükçe dağılım
     * varsayımının hatası büyüdüğünden en fazla MAX_LINE_GAP sayılık fark kabul edilir.
     */
    public static final double MAX_LINE_GAP = 3.0;

    /** Maç toplam sayısının standart sapması (yaklaşık): NBA 48 dk, diğerleri 40 dk. */
    static double totalSd(String sportKey) {
        return "basketball_nba".equals(sportKey) ? 19 : 16;
    }

    /** iddaa çizgisinde Üst olasılığı; çizgi tam sayıysa (iade ihtimali) ya da çok uzaksa null. */
    public static Double overProb(SharpEvent s, double line) {
        Map<String, Double> f = s.fair.get("BT");
        if (f == null || f.get("LINE") == null || f.get("UST") == null) return null;
        if (Math.abs(line - Math.rint(line)) < 1e-9) return null;
        double base = f.get("LINE"), over = f.get("UST");
        if (Math.abs(line - base) > MAX_LINE_GAP || !(over > 0 && over < 1)) return null;
        double sd = totalSd(s.sportKey), mean = base + sd * OddsMath.normInv(over);
        return 1 - OddsMath.normCdf((line - mean) / sd);
    }

    /** Maçın sayı farkının (ev − deplasman) standart sapması (yaklaşık): NBA 48 dk, diğerleri 40 dk. */
    static double marginSd(String sportKey) {
        return "basketball_nba".equals(sportKey) ? 12.5 : 11;
    }

    /**
     * Basketbol handikap: ev sahibi homeLine handikapla kazanır mı (ev + çizgi > deplasman). Sayı farkı
     * normal dağılımlı kabul edilir; ortalama Pinnacle'ın handikap çizgisi ve olasılığından bulunur. Tam
     * sayı çizgi (iade ihtimali) ve Pinnacle'dan MAX_LINE_GAP'ten uzak çizgi için null.
     */
    public static Double coverProb(SharpEvent s, double homeLine) {
        Map<String, Double> f = s.fair.get("BH");
        if (f == null || f.get("LINE") == null || f.get("1") == null) return null;
        if (Math.abs(homeLine - Math.rint(homeLine)) < 1e-9) return null;
        double base = f.get("LINE"), p = f.get("1");
        if (Math.abs(homeLine - base) > MAX_LINE_GAP || !(p > 0 && p < 1)) return null;
        double sd = marginSd(s.sportKey), mean = sd * OddsMath.normInv(p) - base; // P(fark > −çizgi) = p
        return OddsMath.normCdf((mean + homeLine) / sd);
    }

    /** "BT@161.5" -> "BT" (pazar ailesi: kalibrasyon, marj ve analiz için). */
    public static String family(String market) {
        int i = market.indexOf('@');
        return i < 0 ? market : market.substring(0, i);
    }

    /** Pazarın çizgisi ("BT@161.5" -> 161.5); çizgisiz pazarda null. */
    public static Double line(String market) {
        int i = market.indexOf('@');
        return i < 0 ? null : Double.parseDouble(market.substring(i + 1));
    }

    /** Kararda kullanılan adil olasılıklar; basketbol Alt/Üst'te iddaa çizgisine dönüştürülür. */
    public static Map<String, Double> fair(SharpEvent s, String market) {
        if (GoalModel.isModelMarket(market)) {
            // 2.10: Pinnacle'ın fiyat vermediği tek maç pazarı: gol modeli, güvenlik payı düşülerek
            if (market.startsWith("IYAU@")) return null; // yalnızca eşlemede ayırt etmek için
            Map<String, Double> raw = GoalModel.market(goalMatrix(s), market);
            if (raw == null) return null;
            Map<String, Double> m = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : raw.entrySet()) m.put(e.getKey(), Math.max(0.001, e.getValue() - GoalModel.MARGIN));
            return m;
        }
        if (market.startsWith("BT@")) {
            Double over = overProb(s, line(market));
            if (over == null) return null;
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("ALT", 1 - over);
            m.put("UST", over);
            return m;
        }
        if (market.startsWith("BH@")) {
            Double cover = coverProb(s, line(market));
            if (cover == null) return null;
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("1", cover);
            m.put("2", 1 - cover);
            return m;
        }
        return s.fair.get(market);
    }

    /**
     * Futbol maçının gol modeli skor matrisi (Pinnacle'ın MS ve 2,5 Alt/Üst adil olasılığından; maç
     * başına bir kez hesaplanır). Veri yoksa null.
     */
    public static double[][] goalMatrix(SharpEvent s) {
        if (s == null) return null;
        synchronized (s) {
            if (!s.goalTried) {
                s.goalTried = true;
                Map<String, Double> ms = s.fair.get("MS"), au = s.fair.get("AU25");
                if (ms != null && au != null && ms.get("1") != null && ms.get("2") != null && au.get("UST") != null) {
                    s.goalMatrix = GoalModel.matrix(ms.get("1"), ms.get("2"), au.get("UST"));
                }
            }
            return s.goalMatrix;
        }
    }

    /** "(0:1)" gibi iddaa handikap gösterimi: ev sahibine eklenen gol h. */
    static String handicapText(double h) {
        long g = Math.round(Math.abs(h));
        return h >= 0 ? "(" + g + ":0)" : "(0:" + g + ")";
    }

    public static String outcomeLabel(String market, String outcome) {
        if (market.startsWith("AU@")) return Fmt.line(line(market)) + ("ALT".equals(outcome) ? " Alt" : " Üst");
        if (market.startsWith("EVG@")) return "Ev " + Fmt.line(line(market)) + ("ALT".equals(outcome) ? " Alt" : " Üst");
        if (market.startsWith("DEPG@")) return "Dep " + Fmt.line(line(market)) + ("ALT".equals(outcome) ? " Alt" : " Üst");
        if (market.startsWith("HMS@")) return "H.MS " + handicapText(line(market)) + " " + outcome;
        if ("MS".equals(market)) return "MS " + outcome;
        if ("BS".equals(market)) return "Basket MS " + outcome;
        if (market.startsWith("BT@")) {
            return "Basket " + Fmt.line(line(market)) + ("ALT".equals(outcome) ? " Alt" : " Üst");
        }
        if (market.startsWith("BH@")) { // ev sahibinin çizgisi; deplasman ters işaretle
            double l = "1".equals(outcome) ? line(market) : -line(market);
            return "Basket H. " + ("1".equals(outcome) ? "Ev " : "Dep ") + (l > 0 ? "+" : l < 0 ? "−" : "") + Fmt.line(Math.abs(l));
        }
        if ("AU25".equals(market)) return "ALT".equals(outcome) ? "2,5 Alt" : "2,5 Üst";
        if ("KG".equals(market)) return "VAR".equals(outcome) ? "KG Var" : "KG Yok";
        if ("CS".equals(market)) return "ÇŞ " + outcome.charAt(0) + "-" + outcome.charAt(1);
        return market + " " + outcome;
    }

    /**
     * Maçın lig adı: Nesine bazı turnuvalarda (ör. milli maçlar) ad yerine sayısal kod verir ("10004");
     * o zaman oran sorgusundaki lig adı kullanılır.
     */
    public static String leagueLabel(BookEvent b, SharpEvent s) {
        String l = b.league == null ? "" : b.league.trim();
        if (!l.isEmpty() && !l.matches("\\d+")) return l;
        return s == null || s.sportKey == null ? l : CreditPlan.leagueName(s.sportKey);
    }

    /** Pazarın okunur adı (analiz ve özetler için). */
    public static String marketName(String market) {
        if (market.startsWith("AU@")) return "Alt/Üst " + Fmt.line(line(market));
        if (market.startsWith("EVG@")) return "Ev sahibi gol Alt/Üst";
        if (market.startsWith("DEPG@")) return "Deplasman gol Alt/Üst";
        if (market.startsWith("HMS@")) return "Handikaplı Maç Sonucu";
        if ("MS".equals(market)) return "Maç Sonucu";
        if ("AU25".equals(market)) return "2,5 Alt/Üst";
        if ("KG".equals(market)) return "Karşılıklı Gol";
        if ("CS".equals(market)) return "Çifte Şans";
        if ("BS".equals(market)) return "Basketbol MS";
        if ("BT".equals(family(market))) return "Basketbol Alt/Üst";
        if ("BH".equals(family(market))) return "Basketbol Handikap";
        return market;
    }

    /** iddaa bülteninden bir maç. */
    public static final class BookEvent {
        public final String ref, home, away, league, code;
        public final Instant kickoff;
        public final int mbs;
        public final Map<String, Map<String, Double>> odds;
        public final Map<String, Integer> marketMbs = new LinkedHashMap<>();
        /** FOOTBALL ya da BASKETBALL; eşleştirme yalnızca aynı spordaki maçlar arasında yapılır. */
        public String sport = FOOTBALL;

        public BookEvent(String ref, String home, String away, Instant kickoff, String league, int mbs,
                         Map<String, Map<String, Double>> odds, String code) {
            this.ref = ref;
            this.home = home;
            this.away = away;
            this.kickoff = kickoff;
            this.league = league == null ? "" : league;
            this.mbs = mbs;
            this.odds = odds;
            this.code = code == null ? "" : code;
        }

        public int mbsFor(String market) {
            Integer m = marketMbs.get(market);
            return m == null ? mbs : m;
        }
    }

    /** Keskin piyasadan marjı arındırılmış adil olasılıklar. */
    public static final class SharpEvent {
        public final String ref, sportKey, home, away, source;
        public final Instant kickoff;
        public final Map<String, Map<String, Double>> fair;
        /** Gol modeli skor matrisi (Models.goalMatrix; ilk kullanımda hesaplanır). */
        transient double[][] goalMatrix;
        transient boolean goalTried;

        public SharpEvent(String ref, String sportKey, String home, String away, Instant kickoff,
                          Map<String, Map<String, Double>> fair, String source) {
            this.ref = ref;
            this.sportKey = sportKey;
            this.home = home;
            this.away = away;
            this.kickoff = kickoff;
            this.fair = fair;
            this.source = source == null ? "" : source;
        }

        public String sport() {
            return sportOf(sportKey);
        }
    }

    public static final class ScoreResult {
        public final boolean completed;
        public final Integer home, away;

        public ScoreResult(boolean completed, Integer home, Integer away) {
            this.completed = completed;
            this.home = home;
            this.away = away;
        }
    }

    public static final class Pair {
        public final BookEvent book;
        public final SharpEvent sharp;
        public final double score;

        public Pair(BookEvent book, SharpEvent sharp, double score) {
            this.book = book;
            this.sharp = sharp;
            this.score = score;
        }
    }

    /** Kupona girebilecek tek bir seçim. */
    public static final class Candidate {
        public final BookEvent book;
        public final SharpEvent sharp;
        public final String market, outcome;
        /** prob: kararda kullanılan (kalibre) olasılık; rawProb: keskin piyasanın adil olasılığı. */
        public final double odds, prob, rawProb;

        public Candidate(BookEvent book, SharpEvent sharp, String market, String outcome, double odds,
                         double prob) {
            this(book, sharp, market, outcome, odds, prob, prob);
        }

        public Candidate(BookEvent book, SharpEvent sharp, String market, String outcome, double odds,
                         double prob, double rawProb) {
            this.book = book;
            this.sharp = sharp;
            this.market = market;
            this.outcome = outcome;
            this.odds = odds;
            this.prob = prob;
            this.rawProb = rawProb;
        }

        public double ev() {
            return prob * odds - 1.0;
        }

        public int mbs() {
            return book.mbsFor(market);
        }
    }

    public static final class Proposal {
        public final List<Candidate> legs;
        public final double odds, prob, stakeFraction, growth;

        public Proposal(List<Candidate> legs, double odds, double prob, double stakeFraction, double growth) {
            this.legs = legs;
            this.odds = odds;
            this.prob = prob;
            this.stakeFraction = stakeFraction;
            this.growth = growth;
        }

        public double ev() {
            return prob * odds - 1.0;
        }
    }
}
