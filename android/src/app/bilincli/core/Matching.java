package app.bilincli.core;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.Pair;
import app.bilincli.core.Models.SharpEvent;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * iddaa bülteni ile keskin piyasa maçlarını eşleştirme. Python sürümüyle
 * (bilincli/matching.py) birebir aynı sonuç verecek şekilde yazıldı.
 */
public final class Matching {
    private Matching() {}

    static final int TIME_TOLERANCE_MIN = 15;
    static final double MIN_PAIR_SCORE = 0.62;
    static final double MIN_SIDE_SCORE = 0.45;

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "fc", "sk", "jk", "fk", "as", "ac", "afc", "cf", "sc", "sv", "ssc", "spor",
            "kulubu", "club", "calcio", "cd", "ud", "rc", "bk", "if", "ss", "the"));

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        String[][] a = {
            {"paris sg", "paris saint germain"}, {"psg", "paris saint germain"},
            {"man utd", "manchester united"}, {"manchester utd", "manchester united"},
            {"man united", "manchester united"}, {"man city", "manchester city"},
            {"atl madrid", "atletico madrid"}, {"a madrid", "atletico madrid"},
            {"spurs", "tottenham hotspur"}, {"wolves", "wolverhampton wanderers"},
            {"m gladbach", "borussia monchengladbach"}, {"gladbach", "borussia monchengladbach"},
            {"b monchengladbach", "borussia monchengladbach"}, {"bayern munih", "bayern munich"},
            {"internazionale", "inter milan"}, {"inter", "inter milan"},
            {"sporting lizbon", "sporting lisbon"}, {"nottingham", "nottingham forest"},
        };
        for (String[] p : a) ALIASES.put(p[0], p[1]);
    }

    public static String normalize(String name) {
        StringBuilder t = new StringBuilder();
        for (char c : name.toCharArray()) {
            switch (c) {
                case 'ı': case 'İ': t.append('i'); break;
                case 'ş': case 'Ş': t.append('s'); break;
                case 'ğ': case 'Ğ': t.append('g'); break;
                case 'ç': case 'Ç': t.append('c'); break;
                case 'ö': case 'Ö': t.append('o'); break;
                case 'ü': case 'Ü': t.append('u'); break;
                default: t.append(c);
            }
        }
        // Locale.ROOT şart: Türkçe telefonda "I".toLowerCase() noktasız ı verir.
        String s = Normalizer.normalize(t.toString().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        s = s.replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9 ]+", " ");
        List<String> tokens = new ArrayList<>();
        for (String tok : s.trim().split(" +")) {
            if (tok.isEmpty() || STOPWORDS.contains(tok) || tok.matches("[0-9]+")) continue;
            tokens.add(tok);
        }
        String joined = String.join(" ", tokens);
        String alias = ALIASES.get(joined);
        return alias != null ? alias : joined;
    }

    /** difflib.SequenceMatcher(None, a, b).ratio() ile aynı sonuç (200 karakter altı). */
    static double ratio(String a, String b) {
        int la = a.length(), lb = b.length();
        if (la + lb == 0) return 1.0;
        Map<Character, List<Integer>> b2j = new HashMap<>();
        for (int j = 0; j < lb; j++) {
            List<Integer> l = b2j.get(b.charAt(j));
            if (l == null) b2j.put(b.charAt(j), l = new ArrayList<>());
            l.add(j);
        }
        int matches = 0;
        List<int[]> queue = new ArrayList<>();
        queue.add(new int[] {0, la, 0, lb});
        while (!queue.isEmpty()) {
            int[] q = queue.remove(queue.size() - 1);
            int alo = q[0], ahi = q[1], blo = q[2], bhi = q[3];
            int besti = alo, bestj = blo, bestsize = 0;
            Map<Integer, Integer> j2len = new HashMap<>();
            for (int i = alo; i < ahi; i++) {
                Map<Integer, Integer> newj2len = new HashMap<>();
                List<Integer> js = b2j.get(a.charAt(i));
                if (js != null) {
                    for (int j : js) {
                        if (j < blo) continue;
                        if (j >= bhi) break;
                        Integer prev = j2len.get(j - 1);
                        int k = (prev == null ? 0 : prev) + 1;
                        newj2len.put(j, k);
                        if (k > bestsize) {
                            besti = i - k + 1;
                            bestj = j - k + 1;
                            bestsize = k;
                        }
                    }
                }
                j2len = newj2len;
            }
            if (bestsize > 0) {
                matches += bestsize;
                if (alo < besti && blo < bestj) queue.add(new int[] {alo, besti, blo, bestj});
                if (besti + bestsize < ahi && bestj + bestsize < bhi) {
                    queue.add(new int[] {besti + bestsize, ahi, bestj + bestsize, bhi});
                }
            }
        }
        return 2.0 * matches / (la + lb);
    }

    private static double tokenScore(String a, String b) {
        if (a.equals(b)) return 1.0;
        String shortS = a.length() <= b.length() ? a : b;
        String longS = a.length() <= b.length() ? b : a;
        if (shortS.length() >= 3 && longS.startsWith(shortS)) return 0.9;
        return ratio(a, b);
    }

    public static double similarity(String a, String b) {
        String na = normalize(a), nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return 0.0;
        if (na.equals(nb)) return 1.0;
        String[] ta = na.split(" "), tb = nb.split(" ");
        String[] shortT = ta.length <= tb.length ? ta : tb;
        String[] longT = ta.length <= tb.length ? tb : ta;
        double sum = 0;
        for (String t : shortT) {
            double best = 0;
            for (String u : longT) best = Math.max(best, tokenScore(t, u));
            sum += best;
        }
        double token = sum / shortT.length;
        double full = ratio(na, nb);
        return 0.7 * token + 0.3 * full;
    }

    public static List<Pair> match(List<BookEvent> book, List<SharpEvent> sharp) {
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < book.size(); i++) {
            BookEvent b = book.get(i);
            for (int j = 0; j < sharp.size(); j++) {
                SharpEvent s = sharp.get(j);
                long diff = Math.abs(b.kickoff.getEpochSecond() - s.kickoff.getEpochSecond());
                if (diff > TIME_TOLERANCE_MIN * 60L) continue;
                double h = similarity(b.home, s.home), a = similarity(b.away, s.away);
                if (Math.min(h, a) < MIN_SIDE_SCORE) continue;
                double score = (h + a) / 2.0;
                if (score >= MIN_PAIR_SCORE) pairs.add(new double[] {score, i, j});
            }
        }
        // Python: pairs.sort(reverse=True) -> skor, sonra i, sonra j azalan
        Collections.sort(pairs, new Comparator<double[]>() {
            @Override
            public int compare(double[] x, double[] y) {
                for (int k = 0; k < 3; k++) {
                    int c = Double.compare(y[k], x[k]);
                    if (c != 0) return c;
                }
                return 0;
            }
        });
        Set<Integer> usedB = new HashSet<>(), usedS = new HashSet<>();
        List<Pair> out = new ArrayList<>();
        for (double[] p : pairs) {
            int i = (int) p[1], j = (int) p[2];
            if (usedB.contains(i) || usedS.contains(j)) continue;
            usedB.add(i);
            usedS.add(j);
            out.add(new Pair(book.get(i), sharp.get(j), p[0]));
        }
        return out;
    }
}
