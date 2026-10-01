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

    public static String outcomeLabel(String market, String outcome) {
        if ("MS".equals(market)) return "MS " + outcome;
        if ("BS".equals(market)) return "Basket MS " + outcome;
        if ("AU25".equals(market)) return "ALT".equals(outcome) ? "2,5 Alt" : "2,5 Üst";
        if ("KG".equals(market)) return "VAR".equals(outcome) ? "KG Var" : "KG Yok";
        if ("CS".equals(market)) return "ÇŞ " + outcome.charAt(0) + "-" + outcome.charAt(1);
        return market + " " + outcome;
    }

    /** Pazarın okunur adı (analiz ve özetler için). */
    public static String marketName(String market) {
        if ("MS".equals(market)) return "Maç Sonucu";
        if ("AU25".equals(market)) return "2,5 Alt/Üst";
        if ("KG".equals(market)) return "Karşılıklı Gol";
        if ("CS".equals(market)) return "Çifte Şans";
        if ("BS".equals(market)) return "Basketbol MS";
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
