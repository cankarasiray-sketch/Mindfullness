package app.bilincli.core;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.ScoreResult;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sentetik veri (Python: providers/demo.py). Takımlar hayalidir; bu dünyada bazı
 * seçimlerin avantajlı olması tasarım gereğidir.
 */
public final class Demo {
    static final String[] TEAMS = {
        "Anadolu FK", "Boğaziçi SK", "Ege Gücü", "Karadeniz Yıldızı", "Toros Spor",
        "Kapadokya FK", "Marmara Birlik", "Fırat Spor", "Trakya FK", "Akdeniz Güneşi",
        "Erciyes SK", "Kaçkar Spor", "Uludağ FK", "Munzur Spor", "Pamukkale FK",
        "Efes Spor", "Truva FK", "Nemrut SK", "Göbeklitepe FK", "Sümela Spor",
        "Kızılırmak FK", "Ağrı Dağı SK", "Palandöken Spor", "Harran FK",
        "Datça Spor", "Assos FK", "Bafa SK", "Zigana Spor", "Salda FK", "Hasankeyf SK",
        "Olimpos FK", "Likya Spor",
    };
    static final String SPORT_KEY = "demo_lig";
    private static final int[] HOURS = {13, 15, 16, 18, 19, 20, 21, 22};
    private static final int[] MINUTES = {0, 30, 45};

    private static final class Match {
        BookEvent book;
        SharpEvent sharp;
        int home, away;
    }

    private final long seed;
    private final double margin = 0.08, priceNoise = 0.07, sharpNoise;
    private final int perDay = 16;
    private final Map<String, List<Match>> days = new HashMap<>();
    /** sharpRef -> gerçek olasılıklar (kapanış oranı yerine; demo'da kapanış = gerçek). */
    private final Map<String, Map<String, Double>> truth = new HashMap<>();
    public Instant now = Instant.now();

    public Demo(long seed) {
        this(seed, 0.02);
    }

    /** sharpNoise: keskin piyasanın gerçek olasılıktan sapması (model hatası; simülasyon için). */
    Demo(long seed, double sharpNoise) {
        this.seed = seed;
        this.sharpNoise = sharpNoise;
    }

    /** Maçın gerçekleşen skoru (simülasyon için): {ev, deplasman}; bilinmiyorsa null. */
    int[] score(String bookRef) {
        for (List<Match> ms : days.values()) {
            for (Match m : ms) if (m.book.ref.equals(bookRef)) return new int[] {m.home, m.away};
        }
        return null;
    }

    private List<Match> day(LocalDate date) {
        String key = date.toString();
        List<Match> cached = days.get(key);
        if (cached != null) return cached;
        Random rng = new Random(seed * 1_000_003L + date.toEpochDay());
        List<String> teams = new ArrayList<>();
        Collections.addAll(teams, TEAMS);
        Collections.shuffle(teams, rng);
        List<Match> matches = new ArrayList<>();
        for (int n = 0; n < perDay; n++) {
            String home = teams.get((2 * n) % teams.size()), away = teams.get((2 * n + 1) % teams.size());
            Instant ko = date.atTime(HOURS[rng.nextInt(HOURS.length)], MINUTES[rng.nextInt(MINUTES.length)])
                    .atOffset(Fmt.TR).toInstant();
            double strength = 0.25 + 0.9 * rng.nextGaussian();
            double draw = Math.min(Math.max(0.27 + 0.03 * rng.nextGaussian(), 0.18), 0.33);
            double pHome = (1 - draw) / (1 + Math.exp(-strength));
            double[] truth = {pHome, draw, 1 - draw - pHome};
            double[] sharp = new double[3];
            double s = 0;
            for (int i = 0; i < 3; i++) {
                sharp[i] = truth[i] * Math.exp(sharpNoise * rng.nextGaussian());
                s += sharp[i];
            }
            String[] keys = {"1", "X", "2"};
            Map<String, Double> book = new LinkedHashMap<>(), fair = new LinkedHashMap<>();
            for (int i = 0; i < 3; i++) {
                double o = 1 / (truth[i] * (1 + margin)) * Math.exp(priceNoise * rng.nextGaussian());
                book.put(keys[i], Math.round(Math.max(1.01, o) * 100) / 100.0);
                fair.put(keys[i], sharp[i] / s);
            }
            double r = rng.nextDouble();
            String outcome = r < truth[0] ? "1" : r < truth[0] + truth[1] ? "X" : "2";
            double w = rng.nextDouble();
            int mbs = w < 0.5 ? 1 : w < 0.8 ? 2 : 3;
            int low = Math.min((int) (-Math.log(1 - rng.nextDouble()) / 1.2), 3);
            int diff = 1 + Math.min((int) (-Math.log(1 - rng.nextDouble())), 3);
            Match m = new Match();
            m.home = "1".equals(outcome) ? low + diff : low;
            m.away = "2".equals(outcome) ? low + diff : low;
            String ref = key + "-" + n;
            Map<String, Map<String, Double>> bo = new LinkedHashMap<>(), fo = new LinkedHashMap<>();
            bo.put("MS", book);
            fo.put("MS", fair);
            m.book = new BookEvent("demo:" + ref, home, away, ko, "Demo Lig", mbs, bo, String.valueOf(10000 + n));
            m.sharp = new SharpEvent("sharp:" + ref, SPORT_KEY, Matching.normalize(home), Matching.normalize(away),
                    ko, fo, "demo");
            matches.add(m);
            Map<String, Double> t = new LinkedHashMap<>();
            t.put("1", truth[0]);
            t.put("X", truth[1]);
            t.put("2", truth[2]);
            this.truth.put(m.sharp.ref, t);
        }
        days.put(key, matches);
        return matches;
    }

    private List<Match> upcoming() {
        LocalDate today = now.atOffset(Fmt.TR).toLocalDate();
        List<Match> out = new ArrayList<>();
        for (int d = 0; d < 2; d++) {
            for (Match m : day(today.plusDays(d))) if (!m.book.kickoff.isBefore(now)) out.add(m);
        }
        return out;
    }

    public Double truth(String sharpRef, String outcome) {
        Map<String, Double> t = truth.get(sharpRef);
        return t == null ? null : t.get(outcome);
    }

    public List<BookEvent> book() {
        List<BookEvent> out = new ArrayList<>();
        for (Match m : upcoming()) out.add(m.book);
        return out;
    }

    public List<SharpEvent> sharp() {
        List<SharpEvent> out = new ArrayList<>();
        for (Match m : upcoming()) out.add(m.sharp);
        return out;
    }

    public Map<String, ScoreResult> scores() {
        Map<String, ScoreResult> out = new HashMap<>();
        for (List<Match> ms : days.values()) {
            for (Match m : ms) {
                boolean done = !m.book.kickoff.plusSeconds(7200).isAfter(now);
                out.put(m.sharp.ref, done ? new ScoreResult(true, m.home, m.away) : new ScoreResult(false, null, null));
            }
        }
        return out;
    }

    /** "Banko" kıyası: günün en düşük oranlı 3 MS favorisi. [oran, tuttu(1/0)] */
    public List<double[]> favourites(LocalDate date) {
        List<double[]> picks = new ArrayList<>();
        for (Match m : day(date)) {
            Map<String, Double> o = m.book.odds.get("MS");
            String key = "1";
            for (String k : o.keySet()) if (o.get(k) < o.get(key)) key = k;
            String actual = m.home > m.away ? "1" : m.away > m.home ? "2" : "X";
            picks.add(new double[] {o.get(key), key.equals(actual) ? 1 : 0});
        }
        Collections.sort(picks, new java.util.Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(a[0], b[0]);
            }
        });
        return new ArrayList<>(picks.subList(0, Math.min(3, picks.size())));
    }
}
