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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kupon motoru (Python: bilincli/engine.py). Avantajlı seçimleri bulur, MBS
 * kuralına uyan ve kasanın beklenen log büyümesini en çok artıran kuponu seçer.
 * Eşikleri geçen kupon yoksa karar PAS'tır.
 */
public final class Engine {
    private Engine() {}

    public static final class Decision {
        public final Proposal proposal;
        public final List<Proposal> alternatives;
        public final String reason;
        public final Map<String, Object> stats;
        /** Eşikleri geçen seçimler (ortak Kelly bunlardan kupon kurar). */
        public List<Candidate> candidates = new ArrayList<>();
        /** Karar penceresindeki eşleşen maçların başlama saatleri (radar zamanlaması için). */
        public List<Instant> kickoffs = new ArrayList<>();
        /** kickoffs ile paralel: maçın The Odds API lig kodu (kadro saati taraması için). */
        public List<String> kickoffLeagues = new ArrayList<>();

        Decision(Proposal proposal, List<Proposal> alternatives, String reason, Map<String, Object> stats) {
            this.proposal = proposal;
            this.alternatives = alternatives;
            this.reason = reason;
            this.stats = stats;
        }

        public boolean isPass() {
            return proposal == null;
        }
    }

    /** [0] = filtreyi geçen adaylar (avantaja göre azalan), [1] = karşılaştırılan tüm seçimler. */
    static List<List<Candidate>> buildCandidates(List<Pair> pairs, Instant now, Settings cfg) {
        Instant earliest = now.plusSeconds(Math.round(cfg.minLeadMinutes * 60));
        Instant latest = now.plusSeconds(Math.round(cfg.windowHours * 3600));
        List<Candidate> all = new ArrayList<>(), good = new ArrayList<>();
        for (Pair p : pairs) {
            if (p.book.kickoff.isBefore(earliest) || p.book.kickoff.isAfter(latest)) continue;
            for (Map.Entry<String, Map<String, Double>> m : p.book.odds.entrySet()) {
                Map<String, Double> fair = Models.fair(p.sharp, m.getKey()); // basketbol Alt/Üst: iddaa çizgisine
                if (fair == null || fair.isEmpty()) continue;
                for (Map.Entry<String, Double> o : m.getValue().entrySet()) {
                    Double prob = fair.get(o.getKey());
                    double odds = o.getValue();
                    if (prob == null || odds <= 1.0) continue;
                    double used = cfg.edgeRatios == null ? prob
                            : EdgeCalibration.adjust(prob, odds, EdgeCalibration.ratio(cfg.edgeRatios, m.getKey()));
                    Candidate c = new Candidate(p.book, p.sharp, m.getKey(), o.getKey(), odds, used, prob);
                    all.add(c);
                    if (odds >= cfg.minLegOdds && odds <= cfg.maxLegOdds && c.ev() >= cfg.minLegEv) good.add(c);
                }
            }
        }
        // Python'daki sort(key=ev, reverse=True) kararlı sıralamadır; Collections.sort da öyle.
        Collections.sort(good, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Double.compare(b.ev(), a.ev());
            }
        });
        List<List<Candidate>> out = new ArrayList<>();
        out.add(good);
        out.add(all);
        return out;
    }

    static double stakeFraction(double prob, double odds, Settings cfg) {
        return Math.min(cfg.kellyMultiplier * OddsMath.kellyFraction(prob, odds), cfg.maxStakeFraction);
    }

    static List<Proposal> bestProposals(List<Candidate> cands, Settings cfg, int top) {
        List<Candidate> pool = cands.subList(0, Math.min(cands.size(), cfg.maxCandidates));
        List<Proposal> found = new ArrayList<>();
        for (int k = 1; k <= cfg.maxLegs; k++) {
            List<Candidate> sub = k <= 4 ? pool : pool.subList(0, Math.min(pool.size(), 20));
            if (sub.size() < k) continue;
            int[] idx = new int[k];
            for (int i = 0; i < k; i++) idx[i] = i;
            while (true) {
                consider(sub, idx, cfg, found);
                // sonraki kombinasyon (itertools.combinations sırası)
                int i = k - 1;
                while (i >= 0 && idx[i] == sub.size() - k + i) i--;
                if (i < 0) break;
                idx[i]++;
                for (int j = i + 1; j < k; j++) idx[j] = idx[j - 1] + 1;
            }
        }
        // Python sort(key=growth, reverse=True): kararlı, eşitlerde üretim sırası korunur.
        Collections.sort(found, new Comparator<Proposal>() {
            @Override
            public int compare(Proposal a, Proposal b) {
                return Double.compare(b.growth, a.growth);
            }
        });
        List<Proposal> chosen = new ArrayList<>();
        for (Proposal p : found) {
            Set<String> refs = new HashSet<>();
            for (Candidate c : p.legs) refs.add(c.book.ref);
            boolean overlaps = false;
            for (Proposal c : chosen) {
                for (Candidate leg : c.legs) {
                    if (refs.contains(leg.book.ref)) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) break;
            }
            if (overlaps) continue;
            chosen.add(p);
            if (chosen.size() == top) break;
        }
        return chosen;
    }

    private static void consider(List<Candidate> sub, int[] idx, Settings cfg, List<Proposal> found) {
        int k = idx.length;
        List<Candidate> legs = new ArrayList<>(k);
        Set<String> refs = new HashSet<>();
        int maxMbs = 0;
        double prob = 1.0, odds = 1.0;
        for (int i : idx) {
            Candidate c = sub.get(i);
            legs.add(c);
            refs.add(c.book.ref);
            maxMbs = Math.max(maxMbs, c.mbs());
            prob *= c.prob;
            odds *= c.odds;
        }
        if (refs.size() < k) return; // aynı maçtan iki seçim olmaz
        if (maxMbs > k) return; // MBS kuralı
        if (odds < cfg.minCouponOdds || prob < cfg.minWinProb) return;
        if (prob * odds - 1.0 < cfg.minCouponEv) return;
        double f = stakeFraction(prob, odds, cfg);
        if (f <= 0) return;
        found.add(new Proposal(legs, odds, prob, f, OddsMath.logGrowth(prob, odds, f)));
    }

    public static Map<String, Double> marketMargins(List<BookEvent> book) {
        Map<String, double[]> acc = new LinkedHashMap<>();
        for (BookEvent ev : book) {
            for (Map.Entry<String, Map<String, Double>> m : ev.odds.entrySet()) {
                if ("CS".equals(m.getKey())) continue; // Çifte Şans'ta olasılıklar toplamı 2; marj ayrı ölçülmez
                String family = Models.family(m.getKey()); // basketbol Alt/Üst'ün her çizgisi tek "BT" marjında
                int expected = "MS".equals(m.getKey()) ? 3 : 2;
                Map<String, Double> o = m.getValue();
                if (o.size() != expected) continue;
                double[] arr = new double[o.size()];
                int i = 0;
                boolean ok = true;
                for (double v : o.values()) {
                    if (v <= 1) ok = false;
                    arr[i++] = v;
                }
                if (!ok) continue;
                double[] a = acc.get(family);
                if (a == null) acc.put(family, a = new double[2]);
                a[0] += OddsMath.margin(arr);
                a[1] += 1;
            }
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : acc.entrySet()) out.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
        return out;
    }

    public static Decision decide(List<BookEvent> book, List<SharpEvent> sharp, Instant now, Settings cfg) {
        List<Pair> pairs = Matching.match(book, sharp);
        List<Instant> kos = new ArrayList<>();
        List<String> kls = new ArrayList<>();
        Instant from = now.plusSeconds(Math.round(cfg.minLeadMinutes * 60)), to = now.plusSeconds(Math.round(cfg.windowHours * 3600));
        for (Pair p : pairs) {
            if (p.book.kickoff.isBefore(from) || p.book.kickoff.isAfter(to)) continue;
            kos.add(p.book.kickoff);
            kls.add(p.sharp.sportKey);
        }
        List<List<Candidate>> built = buildCandidates(pairs, now, cfg);
        List<Candidate> cands = built.get(0), all = built.get(1);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("iddaa_mac", (long) book.size());
        stats.put("keskin_mac", (long) sharp.size());
        stats.put("eslesen", (long) pairs.size());
        stats.put("karsilastirilan_secim", (long) all.size());
        stats.put("avantajli_secim", (long) cands.size());
        Map<String, Object> margins = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : marketMargins(book).entrySet()) margins.put(e.getKey(), e.getValue());
        stats.put("marjlar", margins);
        List<Proposal> none = new ArrayList<>();
        if (sharp.isEmpty()) {
            return withKickoffs(kos, kls, new Decision(null, none, "Seçili liglerde karar penceresinde maç yok (keskin piyasada 0 maç). "
                    + "O gün oynayan ligleri, ör. Avrupa kupalarını, Ayarlar'dan ekleyebilirsin.", stats));
        }
        if (pairs.isEmpty()) {
            return withKickoffs(kos, kls, new Decision(null, none, "iddaa bülteni ile keskin piyasa eşleştirilemedi; "
                    + "veri kaynaklarını kontrol et.", stats));
        }
        if (cands.isEmpty()) {
            String near = nearest(all, cfg);
            if (near != null) stats.put("en_yakin", near);
            return withKickoffs(kos, kls, new Decision(null, none, all.size() + " seçim karşılaştırıldı, hiçbirinde iddaa oranı "
                    + "adil oranı yeterince geçmiyor. Bugün pas.", stats));
        }
        List<Proposal> proposals = bestProposals(cands, cfg, 3);
        if (proposals.isEmpty()) {
            return withKickoffs(kos, kls, new Decision(null, none, cands.size() + " avantajlı seçim var ama MBS ve risk "
                    + "eşiklerini birlikte sağlayan kupon kurulamadı. Bugün pas.", stats));
        }
        Decision d = new Decision(proposals.get(0), new ArrayList<>(proposals.subList(1, proposals.size())), "", stats);
        d.candidates = cands;
        return withKickoffs(kos, kls, d);
    }

    /**
     * Pas gününde avantaja en yakın seçim (oynanabilir oran aralığındakiler arasından; yoksa hepsi):
     * "03.10 20:00 Ev – Dep · MS 1 @ 2,10 (adil 2,15, −%2,3)". Kullanıcı neden pas dendiğini ve
     * fırsatın ne kadar uzak olduğunu görsün diye.
     */
    static String nearest(List<Candidate> all, Settings cfg) {
        Candidate best = null, bestAny = null;
        for (Candidate c : all) {
            if (bestAny == null || c.ev() > bestAny.ev()) bestAny = c;
            if (c.odds < cfg.minLegOdds || c.odds > cfg.maxLegOdds) continue;
            if (best == null || c.ev() > best.ev()) best = c;
        }
        Candidate c = best != null ? best : bestAny;
        if (c == null) return null;
        return Fmt.localTime(c.book.kickoff.toString()) + " " + c.book.home + " – " + c.book.away + " · "
                + Models.outcomeLabel(c.market, c.outcome) + " @ " + Fmt.odds(c.odds)
                + " (adil " + Fmt.odds(1 / c.prob) + ", " + Fmt.pct(c.ev(), true) + ")";
    }

    private static Decision withKickoffs(List<Instant> kos, List<String> leagues, Decision d) {
        d.kickoffs = kos;
        d.kickoffLeagues = leagues;
        return d;
    }
}
