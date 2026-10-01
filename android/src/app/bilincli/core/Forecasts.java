package app.bilincli.core;

import app.bilincli.core.Models.Pair;
import app.bilincli.core.Models.ScoreResult;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tahmin defteri: eşleşen her maçın Maç Sonucu olasılıkları (Pinnacle/borsa uzlaşısı = uygulamanın
 * tahmini, ve iddaa'nın marjı arındırılmış kendi olasılıkları) kaydedilir; maç bitince sonuçla
 * karşılaştırılır. Sonuçlar, kupon sonuçlandırması için zaten çekilen skorlardan gelir (ek kredi
 * yok). Ölçüler: Brier skoru ve log kayıp (küçük olan daha isabetli), olasılık dilimlerine göre
 * kalibrasyon (tahmin %40 dediğinde gerçekte ne sıklıkla oldu).
 */
public final class Forecasts {
    static final int KEEP = 3000;
    static final long UNRESOLVED_TTL_S = 5 * 86400L;
    private static final String[] KEYS = {"1", "X", "2"};

    private final Ledger.Storage storage;
    private final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

    public Forecasts(Ledger.Storage storage) {
        this.storage = storage;
        String raw = storage.read();
        if (raw == null || raw.isEmpty()) return;
        try {
            for (Object o : Json.arr(Json.parseObject(raw).get("rows"))) {
                Map<String, Object> r = Json.obj(o);
                if (r != null && Json.str(r, "ref") != null) rows.put(Json.str(r, "ref"), r);
            }
        } catch (RuntimeException e) {
            rows.clear(); // bozuk dosya: baştan başla (kasa verisi ayrı dosyada)
        }
    }

    private static List<Object> triple(Map<String, Double> m) {
        if (m == null || m.get("1") == null || m.get("X") == null || m.get("2") == null) return null;
        List<Object> out = new ArrayList<>();
        for (String k : KEYS) out.add(m.get(k));
        return out;
    }

    /** iddaa oranlarından marjı orantılı arındırılmış olasılıklar. */
    static List<Object> bookProbs(Map<String, Double> odds) {
        if (odds == null || odds.size() != 3 || odds.get("1") == null || odds.get("X") == null || odds.get("2") == null) return null;
        double s = 0;
        for (String k : KEYS) s += 1 / odds.get(k);
        List<Object> out = new ArrayList<>();
        for (String k : KEYS) out.add(1 / odds.get(k) / s);
        return out;
    }

    /** Eşleşen maçların güncel olasılıkları; maç başlayana kadar güncellenir, ilk değer saklanır. */
    public synchronized void record(List<Pair> pairs, Instant now) {
        for (Pair p : pairs) {
            if (!p.book.kickoff.isAfter(now)) continue;
            List<Object> f = triple(p.sharp.fair.get("MS"));
            if (f == null) continue;
            Map<String, Object> r = rows.get(p.sharp.ref);
            if (r == null) {
                r = new LinkedHashMap<>();
                r.put("ref", p.sharp.ref);
                r.put("sport", p.sharp.sportKey);
                r.put("home", p.book.home);
                r.put("away", p.book.away);
                r.put("kickoff", p.book.kickoff.toString());
                r.put("p0", f);
                rows.put(p.sharp.ref, r);
            }
            r.put("p", f);
            r.put("at", now.toString());
            List<Object> q = bookProbs(p.book.odds.get("MS"));
            if (q != null) {
                if (!r.containsKey("q0")) r.put("q0", q);
                r.put("q", q);
            }
        }
        prune(now);
        save();
    }

    /** Yalnızca keskin piyasa (ör. kapanış anında): kayıtlı maçların son olasılığı güncellenir. */
    public synchronized void recordSharp(Iterable<SharpEvent> sharp, Instant now) {
        boolean changed = false;
        for (SharpEvent s : sharp) {
            Map<String, Object> r = rows.get(s.ref);
            List<Object> f = triple(s.fair.get("MS"));
            if (r == null || f == null || r.containsKey("result")) continue;
            Instant ko = Instant.parse(Json.str(r, "kickoff"));
            if (!ko.isAfter(now)) continue;
            r.put("p", f);
            r.put("at", now.toString());
            changed = true;
        }
        if (changed) save();
    }

    /** Biten maçları sonuçlandırır; çözülen kayıt sayısını döndürür. */
    public synchronized int resolve(Map<String, ScoreResult> scores) {
        int n = 0;
        if (scores == null) return 0;
        for (Map.Entry<String, ScoreResult> e : scores.entrySet()) {
            Map<String, Object> r = rows.get(e.getKey());
            ScoreResult s = e.getValue();
            if (r == null || r.containsKey("result") || s == null || !s.completed || s.home == null || s.away == null) continue;
            r.put("result", s.home > s.away ? "1" : s.home < s.away ? "2" : "X");
            r.put("score", s.home + "-" + s.away);
            n++;
        }
        if (n > 0) save();
        return n;
    }

    private void prune(Instant now) {
        Iterator<Map.Entry<String, Map<String, Object>>> it = rows.entrySet().iterator();
        while (it.hasNext()) {
            Map<String, Object> r = it.next().getValue();
            if (!r.containsKey("result") && Instant.parse(Json.str(r, "kickoff")).plusSeconds(UNRESOLVED_TTL_S).isBefore(now)) {
                it.remove(); // sonucu hiç gelmedi (o ligde kupon yoktu)
            }
        }
        it = rows.entrySet().iterator();
        while (rows.size() > KEEP && it.hasNext()) {
            it.next();
            it.remove(); // en eskiler
        }
    }

    private void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", new ArrayList<Object>(rows.values()));
        storage.write(Json.write(m));
    }

    private static double[] arr(Object o) {
        List<Object> l = Json.arr(o);
        if (l == null || l.size() != 3) return null;
        return new double[] {((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(), ((Number) l.get(2)).doubleValue()};
    }

    static double brier(double[] p, int y) {
        double s = 0;
        for (int k = 0; k < 3; k++) s += Math.pow(p[k] - (k == y ? 1 : 0), 2);
        return s;
    }

    static double logLoss(double[] p, int y) {
        return -Math.log(Math.max(p[y], 1e-6));
    }

    /**
     * İsabet özeti. "model": uygulamanın tahmini (maç öncesi son), "first": ilk kayıt (çoğunlukla
     * sabah), "book": iddaa'nın kendi olasılıkları; hepsi aynı maç kümesinde. "bins": kalibrasyon.
     */
    public synchronized Map<String, Object> summary() {
        int n = 0;
        double bm = 0, lm = 0, bf = 0, lf = 0, bb = 0, lb = 0, dsum = 0, dsq = 0;
        double[][] bins = new double[10][3]; // {tahmin toplamı, gerçekleşen, adet}
        for (Map<String, Object> r : rows.values()) {
            String res = Json.str(r, "result");
            double[] p = arr(r.get("p")), p0 = arr(r.get("p0")), q = arr(r.get("q"));
            if (res == null || p == null || p0 == null || q == null) continue;
            int y = "1".equals(res) ? 0 : "X".equals(res) ? 1 : 2;
            n++;
            bm += brier(p, y);
            lm += logLoss(p, y);
            bf += brier(p0, y);
            lf += logLoss(p0, y);
            bb += brier(q, y);
            lb += logLoss(q, y);
            double d = logLoss(q, y) - logLoss(p, y); // > 0: uygulamanın tahmini iddaa'dan isabetli
            dsum += d;
            dsq += d * d;
            for (int k = 0; k < 3; k++) {
                int b = Math.min(9, (int) (p[k] * 10));
                bins[b][0] += p[k];
                bins[b][1] += k == y ? 1 : 0;
                bins[b][2]++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n", (long) n);
        out.put("pending", (long) (rows.size() - n));
        if (n == 0) return out;
        out.put("model", metric(bm / n, lm / n));
        out.put("first", metric(bf / n, lf / n));
        out.put("book", metric(bb / n, lb / n));
        // iddaa - uygulama log kaybı farkı ve standart hatası: fark 2 SH'den büyük değilse
        // hangisinin daha isabetli olduğu henüz söylenemez
        double mean = dsum / n, var = n > 1 ? (dsq - n * mean * mean) / (n - 1) : 0;
        out.put("diff", mean);
        out.put("diffSe", n > 1 ? Math.sqrt(Math.max(var, 0) / n) : null);
        List<Object> b = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            if (bins[i][2] == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", i / 10.0);
            m.put("to", (i + 1) / 10.0);
            m.put("predicted", bins[i][0] / bins[i][2]);
            m.put("actual", bins[i][1] / bins[i][2]);
            m.put("n", (long) bins[i][2]);
            b.add(m);
        }
        out.put("bins", b);
        return out;
    }

    private static Map<String, Object> metric(double brier, double logLoss) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("brier", brier);
        m.put("logLoss", logLoss);
        return m;
    }
}
