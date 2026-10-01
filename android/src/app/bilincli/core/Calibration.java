package app.bilincli.core;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.Pair;
import app.bilincli.core.Models.SharpEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Veri doğrulama: iddaa bülteni Pinnacle'la karşılaştırılarak okunur.
 * <ul>
 *   <li>Maç Sonucu seçeneklerinin sırası (1/X/2) doğrulanır; tutarsızsa düzeltilir ya da kapatılır.</li>
 *   <li>İki seçenekli aday pazarlar arasından 2,5 Alt/Üst ve Karşılıklı Gol'e karşılık geleni,
 *       olasılıkları Pinnacle'la en iyi örtüşen pazar kodu ve yön olarak bulunur. Belirsizse
 *       pazar kullanılmaz (yanlış eşleşme sahte "değer" üretip para kaybettirir).</li>
 *   <li>Mantıksız yüksek avantajlar (çoğunlukla veri ya da eşleştirme hatası) ayıklanır.</li>
 * </ul>
 */
public final class Calibration {
    private Calibration() {}

    public static final String RAW_AU25 = "AU25#";
    public static final String RAW_KG = "KG#";
    public static final String RAW_CS = "CS#";
    static final String[] CS_KEYS = {"1X", "12", "X2"};
    /** Doğru eşleşmede iddaa ile Pinnacle olasılıklarının ortalama farkı bunun altında kalır. */
    static final double MAX_MAD = 0.06;
    static final int MIN_N = 4;
    /** Bundan büyük "avantaj" gerçek olamayacak kadar iyidir; veri hatası sayılır. */
    public static final double MAX_PLAUSIBLE_EV = 0.25;

    private static final String[][] MS_PERMS = {
        {"1", "X", "2"}, {"1", "2", "X"}, {"X", "1", "2"}, {"X", "2", "1"}, {"2", "1", "X"}, {"2", "X", "1"},
    };

    static double[] devig(double a, double b) {
        double s = 1 / a + 1 / b;
        return new double[] {(1 / a) / s, (1 / b) / s};
    }

    /** Maç Sonucu: okunan sıra perm[i] -> gerçek sonuç; ortalama mutlak olasılık farkı ve örnek sayısı. */
    static double[] msFit(List<Pair> pairs, String[] perm) {
        double sum = 0;
        int n = 0;
        for (Pair p : pairs) {
            Map<String, Double> o = p.book.odds.get("MS"), f = p.sharp.fair.get("MS");
            if (o == null || f == null || o.size() != 3 || f.size() != 3) continue;
            String[] keys = {"1", "X", "2"};
            double s = 0;
            for (String k : keys) s += 1 / o.get(k);
            for (int i = 0; i < 3; i++) sum += Math.abs((1 / o.get(keys[i])) / s - f.get(perm[i]));
            n++;
        }
        return new double[] {n == 0 ? 1 : sum / (3.0 * n), n};
    }

    public static Map<String, Object> apply(List<BookEvent> book, List<SharpEvent> sharp) {
        return apply(book, sharp, new LinkedHashMap<String, Object>());
    }

    /**
     * memory: önceki güvenilir eşlemeler (pazar kodu ve yön). Veri karar vermeye yetmediğinde
     * (ör. yalnızca kupondaki ligler çekildiğinde) kullanılır; güvenilir yeni kararla güncellenir.
     */
    public static Map<String, Object> apply(List<BookEvent> book, List<SharpEvent> sharp, Map<String, Object> memory) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        List<Pair> pairs = Matching.match(book, sharp);

        // 1) Maç Sonucu sırası
        double[] id = msFit(pairs, MS_PERMS[0]);
        if (id[1] < MIN_N) {
            int remembered = (int) Json.lng(memory, "MS", 0);
            if (remembered > 0) remapMs(book, MS_PERMS[remembered]);
            report.put("MS", "az maç, " + (remembered > 0 ? "önceki düzeltme kullanıldı" : "doğrulanamadı") + " (" + (int) id[1] + ")");
        } else if (id[0] <= MAX_MAD) {
            memory.put("MS", 0L);
            report.put("MS", "doğrulandı (" + (int) id[1] + " maç, sapma " + Fmt.pct(id[0], false) + ")");
        } else {
            int best = 0;
            double bestMad = id[0];
            for (int i = 1; i < MS_PERMS.length; i++) {
                double m = msFit(pairs, MS_PERMS[i])[0];
                if (m < bestMad) {
                    bestMad = m;
                    best = i;
                }
            }
            if (best != 0 && bestMad <= MAX_MAD && bestMad < id[0] / 2) {
                String[] perm = MS_PERMS[best];
                remapMs(book, perm);
                memory.put("MS", (long) best);
                report.put("MS", "sıra düzeltildi (" + String.join("/", perm) + ")");
                notes.add("Maç Sonucu seçenek sırası bültende farklı; Pinnacle'a göre düzeltildi.");
            } else {
                for (BookEvent b : book) b.odds.remove("MS");
                report.put("MS", "tutarsız, devre dışı (sapma " + Fmt.pct(id[0], false) + ")");
                notes.add("Maç Sonucu oranları Pinnacle'la örtüşmüyor; güvenlik için kullanılmadı.");
            }
        }

        // 2) İki seçenekli pazarlar
        report.put("AU25", twoWay(book, pairs, RAW_AU25, "AU25", new String[] {"ALT", "UST"}, notes, memory));
        report.put("KG", twoWay(book, pairs, RAW_KG, "KG", new String[] {"VAR", "YOK"}, notes, memory));
        report.put("CS", doubleChance(book, pairs, notes, memory));
        for (BookEvent b : book) {
            Iterator<String> it = b.odds.keySet().iterator();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith(RAW_AU25) || k.startsWith(RAW_KG) || k.startsWith(RAW_CS)) it.remove();
            }
        }

        // 3) Mantıksız avantajlar
        int suspicious = 0;
        for (Pair p : pairs) {
            for (Map.Entry<String, Map<String, Double>> m : p.book.odds.entrySet()) {
                Map<String, Double> fair = p.sharp.fair.get(m.getKey());
                if (fair == null) continue;
                Iterator<Map.Entry<String, Double>> it = m.getValue().entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Double> o = it.next();
                    Double f = fair.get(o.getKey());
                    if (f != null && f * o.getValue() - 1 > MAX_PLAUSIBLE_EV) {
                        it.remove();
                        if (suspicious++ < 3) {
                            notes.add(p.book.home + " - " + p.book.away + " " + Models.outcomeLabel(m.getKey(), o.getKey())
                                    + ": avantaj " + Fmt.pct(f * o.getValue() - 1, true) + " gerçekçi değil, ayıklandı.");
                        }
                    }
                }
            }
        }
        report.put("suspicious", (long) suspicious);
        report.put("notes", new ArrayList<Object>(notes));
        return report;
    }

    static void remapMs(List<BookEvent> book, String[] perm) {
        for (BookEvent b : book) {
            Map<String, Double> o = b.odds.get("MS");
            if (o == null || o.size() != 3) continue;
            Map<String, Double> fixed = new LinkedHashMap<>();
            fixed.put(perm[0], o.get("1"));
            fixed.put(perm[1], o.get("X"));
            fixed.put(perm[2], o.get("2"));
            Map<String, Double> ordered = new LinkedHashMap<>();
            for (String k : new String[] {"1", "X", "2"}) ordered.put(k, fixed.get(k));
            b.odds.put("MS", ordered);
        }
    }

    /** Bir hedef pazar için en iyi örtüşen (pazar kodu, yön) bulunur ve bültene işlenir. */
    static String twoWay(List<BookEvent> book, List<Pair> pairs, String prefix, String target, String[] keys,
                         List<String> notes, Map<String, Object> memory) {
        Map<String, double[]> fit = new LinkedHashMap<>(); // rawKey|yön -> {toplam fark, n}
        for (Pair p : pairs) {
            Map<String, Double> f = p.sharp.fair.get(target);
            if (f == null) continue;
            for (Map.Entry<String, Map<String, Double>> m : p.book.odds.entrySet()) {
                if (!m.getKey().startsWith(prefix)) continue;
                double[] pr = devig(m.getValue().get("1"), m.getValue().get("2"));
                for (int dir = 0; dir < 2; dir++) {
                    String k = m.getKey() + "|" + dir;
                    double[] a = fit.get(k);
                    if (a == null) fit.put(k, a = new double[2]);
                    a[0] += Math.abs(pr[0] - f.get(keys[dir])); // dir 0: N1 = keys[0]
                    a[1]++;
                }
            }
        }
        String best = null, second = null;
        double bestMad = 9, secondMad = 9;
        for (Map.Entry<String, double[]> e : fit.entrySet()) {
            if (e.getValue()[1] < MIN_N) continue;
            double mad = e.getValue()[0] / e.getValue()[1];
            if (mad < bestMad) {
                second = best;
                secondMad = bestMad;
                best = e.getKey();
                bestMad = mad;
            } else if (mad < secondMad) {
                second = e.getKey();
                secondMad = mad;
            }
        }
        String label = "KG".equals(target) ? "Karşılıklı Gol" : "2,5 Alt/Üst";
        String remembered = Json.str(memory, target);
        if (best == null) {
            if (remembered == null) return "Pinnacle verisi yok ya da az maç";
            best = remembered; // karar için veri yetmiyor: son güvenilir eşleme
            bestMad = -1;
        } else if (bestMad > MAX_MAD) {
            memory.remove(target);
            return "eşleşen pazar bulunamadı (en iyi sapma " + Fmt.pct(bestMad, false) + ")";
        } else if (second != null && secondMad < bestMad * 1.5 + 0.01) {
            // İkinci en iyi aday (başka pazar ya da aynı pazarın ters yönü) çok yakınsa ayırt edilemez.
            notes.add(label + " için iki aday pazar birbirine çok yakın; yanlış eşleşme riskine karşı kullanılmadı.");
            return "belirsiz, kullanılmadı";
        } else {
            memory.put(target, best);
        }
        String rawKey = best.substring(0, best.indexOf('|'));
        int dir = best.endsWith("|0") ? 0 : 1;
        int adopted = 0;
        for (BookEvent b : book) {
            Map<String, Double> raw = b.odds.get(rawKey);
            if (raw == null) continue;
            Map<String, Double> m = new LinkedHashMap<>();
            m.put(keys[dir], raw.get("1"));
            m.put(keys[1 - dir], raw.get("2"));
            Map<String, Double> ordered = new LinkedHashMap<>();
            ordered.put(keys[0], m.get(keys[0]));
            ordered.put(keys[1], m.get(keys[1]));
            b.odds.put(target, ordered);
            Integer mbs = b.marketMbs.get(rawKey);
            if (mbs != null) b.marketMbs.put(target, mbs);
            adopted++;
        }
        if (bestMad < 0) return "önceki eşleme kullanıldı (pazar " + rawKey.substring(prefix.length()) + ", " + adopted + " maç)";
        return "doğrulandı (pazar " + rawKey.substring(prefix.length()) + ", " + adopted + " maç, sapma " + Fmt.pct(bestMad, false) + ")";
    }

    /**
     * Çifte Şans: üç seçenekli aday pazarlar arasından, seçenek sırası (6 olasılık) dahil, Maç
     * Sonucu'ndan türetilen adil olasılıklarla (1X, 12, X2; toplamı 2) en iyi örtüşen bulunur.
     * İlk Yarı Sonucu gibi başka üç seçenekli pazarlar bu karşılaştırmada çok uzak kalır.
     */
    static String doubleChance(List<BookEvent> book, List<Pair> pairs, List<String> notes, Map<String, Object> memory) {
        Map<String, double[]> fit = new LinkedHashMap<>();
        for (Pair p : pairs) {
            Map<String, Double> f = p.sharp.fair.get("CS");
            if (f == null) continue;
            for (Map.Entry<String, Map<String, Double>> m : p.book.odds.entrySet()) {
                if (!m.getKey().startsWith(RAW_CS)) continue;
                Map<String, Double> o = m.getValue();
                double s = 1 / o.get("1") + 1 / o.get("2") + 1 / o.get("3");
                double[] pr = {2 / o.get("1") / s, 2 / o.get("2") / s, 2 / o.get("3") / s};
                for (int pi = 0; pi < MS_PERMS.length; pi++) {
                    String k = m.getKey() + "|" + pi;
                    double[] a = fit.get(k);
                    if (a == null) fit.put(k, a = new double[2]);
                    for (int i = 0; i < 3; i++) a[0] += Math.abs(pr[i] - f.get(CS_KEYS[permIndex(MS_PERMS[pi][i])])) / 3;
                    a[1]++;
                }
            }
        }
        String best = null, second = null;
        double bestMad = 9, secondMad = 9;
        for (Map.Entry<String, double[]> e : fit.entrySet()) {
            if (e.getValue()[1] < MIN_N) continue;
            double mad = e.getValue()[0] / e.getValue()[1];
            if (mad < bestMad) {
                second = best;
                secondMad = bestMad;
                best = e.getKey();
                bestMad = mad;
            } else if (mad < secondMad) {
                second = e.getKey();
                secondMad = mad;
            }
        }
        String remembered = Json.str(memory, "CS");
        if (best == null) {
            if (remembered == null) return "Pinnacle verisi yok ya da az maç";
            best = remembered;
            bestMad = -1;
        } else if (bestMad > MAX_MAD) {
            memory.remove("CS");
            return "eşleşen pazar bulunamadı (en iyi sapma " + Fmt.pct(bestMad, false) + ")";
        } else if (second != null && secondMad < bestMad * 1.5 + 0.01) {
            notes.add("Çifte Şans için iki aday pazar birbirine çok yakın; yanlış eşleşme riskine karşı kullanılmadı.");
            return "belirsiz, kullanılmadı";
        } else {
            memory.put("CS", best);
        }
        String rawKey = best.substring(0, best.indexOf('|'));
        String[] perm = MS_PERMS[Integer.parseInt(best.substring(best.indexOf('|') + 1))];
        int adopted = 0;
        for (BookEvent b : book) {
            Map<String, Double> raw = b.odds.get(rawKey);
            if (raw == null) continue;
            Map<String, Double> m = new LinkedHashMap<>();
            String[] n = {"1", "2", "3"};
            for (int i = 0; i < 3; i++) m.put(CS_KEYS[permIndex(perm[i])], raw.get(n[i]));
            Map<String, Double> ordered = new LinkedHashMap<>();
            for (String k : CS_KEYS) ordered.put(k, m.get(k));
            b.odds.put("CS", ordered);
            Integer mbs = b.marketMbs.get(rawKey);
            if (mbs != null) b.marketMbs.put("CS", mbs);
            adopted++;
        }
        if (bestMad < 0) return "önceki eşleme kullanıldı (pazar " + rawKey.substring(RAW_CS.length()) + ", " + adopted + " maç)";
        return "doğrulandı (pazar " + rawKey.substring(RAW_CS.length()) + ", " + adopted + " maç, sapma " + Fmt.pct(bestMad, false) + ")";
    }

    /** MS_PERMS içindeki "1"/"X"/"2" etiketini Çifte Şans sırasına (0: 1X, 1: 12, 2: X2) çevirir. */
    private static int permIndex(String label) {
        return "1".equals(label) ? 0 : "X".equals(label) ? 1 : 2;
    }

    /** Kısa özet: bildirim ve durum satırı için. */
    public static String summary(Map<String, Object> r) {
        if (r == null) return "";
        StringBuilder b = new StringBuilder("veri: MS ").append(ok(r.get("MS")));
        if (String.valueOf(r.get("AU25")).startsWith("doğrulandı")) b.append(", 2,5 A/Ü ✓");
        if (String.valueOf(r.get("KG")).startsWith("doğrulandı")) b.append(", KG ✓");
        if (String.valueOf(r.get("CS")).startsWith("doğrulandı")) b.append(", ÇŞ ✓");
        long sus = r.get("suspicious") instanceof Long ? (Long) r.get("suspicious") : 0;
        if (sus > 0) b.append(", ").append(sus).append(" şüpheli oran ayıklandı");
        return b.toString();
    }

    private static String ok(Object s) {
        String t = String.valueOf(s);
        return t.startsWith("doğrulandı") || t.startsWith("sıra düzeltildi") ? "✓" : t.startsWith("az maç") ? "?" : "✗";
    }
}
