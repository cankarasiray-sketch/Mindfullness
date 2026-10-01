package app.bilincli.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kredi planlayıcı: The Odds API kredisi ay sonuna kadar yetecek şekilde günlük kapsamı daraltır.
 *
 * Günlük bütçe = (kalan kredi - yedek) / yenilenmeye kalan gün. Tahmini günlük maliyet bütçeyi
 * aşarsa, kredi başına en az fırsat getiren kalemden başlayarak kısılır:
 * Karşılıklı Gol (maç başına 1 kredi) → radar taramaları → günlük kupon 5→3 (her kupon maç öncesi
 * kontrol ve kapanış oranı için kredi harcar; simülasyonda 3 kupon 5'in getirisinin çoğunu verdi)
 * → 2,5 Alt/Üst → en az değerli fırsat çıkaran lig → son çare kupon 3→1.
 *
 * Kredi bolsa (ör. birden fazla anahtar) ve daraltma gerekmiyorsa, creditExpand açıkken seçili
 * olmayan ligler de o gün taranır: yalnızca bugün maçı olduğu bilinenler, en çok fırsat çıkaran
 * önce, tahmini gider bütçenin %85'ini geçmeyecek kadar.
 */
public final class CreditPlan {
    private CreditPlan() {}

    public static final int DEFAULT_QUOTA = 500;
    static final int RESERVE = 15;

    public static final class Plan {
        public List<String> leagues = new ArrayList<>();
        public boolean totals;
        public int kgEvents, radarScans, coupons, daysLeft;
        public double budget, cost;
        public Long remaining;
        /** Aylık kota (kalan + kullanılan; bilinmiyorsa ücretsiz plan varsayımı). */
        public long quota;
        public boolean narrowed;
        public List<String> notes = new ArrayList<>();
        /** Kredi bol olduğu için bugün eklenen (kullanıcının seçmediği) ligler. */
        public List<String> expanded = new ArrayList<>();
        final List<String> noteKeys = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("leagues", new ArrayList<Object>(leagues));
            m.put("totals", totals);
            m.put("kgEvents", (long) kgEvents);
            m.put("radarScans", (long) radarScans);
            m.put("coupons", (long) coupons);
            m.put("daysLeft", (long) daysLeft);
            m.put("budget", budget);
            m.put("cost", cost);
            m.put("remaining", remaining);
            m.put("quota", quota);
            m.put("narrowed", narrowed);
            m.put("notes", new ArrayList<Object>(notes));
            m.put("expanded", new ArrayList<Object>(expanded));
            return m;
        }
    }

    /**
     * Günlük sabit gider tahmini: sonuç sorguları, maç öncesi kontroller ve kapanış oranları (kupon
     * başına yaklaşık 2; aynı anda vadesi gelenler tek çekimle). Tahmin şaşsa da plan her gün
     * gerçek kalan krediden yeniden hesaplandığı için kendini düzeltir.
     */
    static int overhead(int coupons) {
        return 6 + 2 * coupons;
    }

    /**
     * avgCoupons ≥ 0 ise gider gerçek kullanımdan: pas günlerinde kontrol ve kapanış kredisi
     * harcanmaz, bu yüzden her gün en fazla kupon varsaymak gereksiz daraltır.
     */
    static double overhead(int coupons, double avgCoupons) {
        if (avgCoupons < 0) return overhead(coupons);
        return 3 + 2 * Math.min(coupons, avgCoupons);
    }

    /** Ligin bugünkü ağırlığı: maçı biliniyorsa 1/0, bilinmiyorsa ortalama pay. */
    static double weight(String league, Map<String, Integer> active) {
        if (active == null || !active.containsKey(league)) return Settings.ACTIVE_SHARE;
        return active.get(league) == 0 ? 0 : 1; // -1: liste alınamadı, oran yine çekilir
    }

    static double cost(Plan p, Map<String, Integer> active, double avgCoupons) {
        double w = 0;
        int matches = 0;
        boolean known = active != null;
        for (String l : p.leagues) {
            w += weight(l, active) * Settings.scanCost(l, p.totals); // basketbol: yalnızca maç sonucu
            if (known && !Settings.isBasketball(l)) { // KG yalnızca futbolda
                Integer n = active.get(l);
                if (n == null || n < 0) known = false;
                else matches += n;
            }
        }
        int kg = known ? Math.min(p.kgEvents, matches) : p.kgEvents; // KG yalnızca penceredeki maçlar için
        return w * (1 + p.radarScans) + kg + overhead(p.coupons, avgCoupons);
    }

    /** Seçili liglerin yalnızca o gün oynayanları kredi harcar (Settings.ACTIVE_SHARE). */
    static double cost(int leagues, boolean totals, int radar, int kg, int overhead) {
        return leagues * Settings.ACTIVE_SHARE * (totals ? 2 : 1) * (1 + radar) + kg + overhead;
    }

    public static int daysLeft(LocalDate today, int resetDay) {
        LocalDate next = today.withDayOfMonth(Math.min(resetDay, today.lengthOfMonth()));
        if (!next.isAfter(today)) {
            LocalDate nm = today.plusMonths(1);
            next = nm.withDayOfMonth(Math.min(resetDay, nm.lengthOfMonth()));
        }
        return (int) Math.max(1, ChronoUnit.DAYS.between(today, next));
    }

    /**
     * remaining/used: The Odds API yanıt başlıklarından (bilinmiyorsa null).
     * yield: lig -> son taramalardaki değerli seçim sayısının hareketli ortalaması.
     */
    public static Plan plan(Settings cfg, Long remaining, Long used, LocalDate today, Map<String, Double> yield) {
        return plan(cfg, remaining, used, today, yield, null, -1);
    }

    /**
     * active: bugün karar penceresindeki maç sayısı (lig -> sayı; ücretsiz maç listesinden;
     * bilinmiyorsa null). avgCoupons: son günlerin ortalama kupon sayısı (bilinmiyorsa -1).
     */
    public static Plan plan(Settings cfg, Long remaining, Long used, LocalDate today, Map<String, Double> yield,
                            final Map<String, Integer> active, double avgCoupons) {
        Plan p = new Plan();
        p.totals = cfg.totals;
        p.kgEvents = cfg.kgEvents;
        p.radarScans = cfg.radarScans;
        p.coupons = cfg.maxCouponsPerDay;
        p.leagues.addAll(cfg.leagues);
        p.daysLeft = daysLeft(today, cfg.creditResetDay);
        long quota = remaining != null && used != null ? remaining + used : DEFAULT_QUOTA;
        double left = remaining != null ? remaining
                : (double) quota * p.daysLeft / today.lengthOfMonth(); // ilk çalışma: ay içinde orantılı
        p.remaining = remaining;
        p.quota = quota;
        p.budget = Math.max(0, left - RESERVE) / p.daysLeft;
        if (!cfg.creditAuto) {
            p.cost = cost(p, active, avgCoupons);
            return p;
        }
        // Ligler: en az fırsat çıkaran sonda (eşitlikte kullanıcının sırası korunur).
        final Map<String, Double> y = yield == null ? new LinkedHashMap<String, Double>() : yield;
        final List<String> order = new ArrayList<>(cfg.leagues);
        Collections.sort(p.leagues, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int c = Double.compare(y.containsKey(b) ? y.get(b) : 1.0, y.containsKey(a) ? y.get(a) : 1.0);
                return c != 0 ? c : Integer.compare(order.indexOf(a), order.indexOf(b));
            }
        });
        int[] radarSteps = {4, 2, 1, 0};
        int[] kgSteps = {12, 8, 4, 0};
        while (cost(p, active, avgCoupons) > p.budget) {
            int droppable = -1; // bugün maçı olan, en az fırsat çıkaran lig (maçsız lig kredi harcamaz)
            for (int i = p.leagues.size() - 1; i >= 0 && p.leagues.size() > 1; i--) {
                if (weight(p.leagues.get(i), active) > 0) {
                    droppable = i;
                    break;
                }
            }
            if (p.kgEvents > 0) {
                p.kgEvents = next(kgSteps, p.kgEvents);
                note(p, "kg", p.kgEvents > 0 ? "Karşılıklı Gol en fazla " + p.kgEvents + " maç" : "Karşılıklı Gol bugünlük kapatıldı");
            } else if (p.radarScans > 0) {
                p.radarScans = next(radarSteps, p.radarScans);
                note(p, "radar", p.radarScans > 0 ? "Radar günde en fazla " + p.radarScans + " tarama" : "Radar bugünlük kapatıldı");
            } else if (p.coupons > 3) {
                p.coupons = 3;
                note(p, "kupon", "Günde en fazla 3 kupon");
            } else if (p.totals) {
                p.totals = false;
                note(p, "totals", "2,5 Alt/Üst bugünlük kapatıldı");
            } else if (droppable >= 0) {
                String dropped = p.leagues.remove(droppable);
                note(p, "lig:" + dropped, leagueName(dropped) + " bugünlük çıkarıldı (en az fırsat çıkaran lig)");
            } else if (p.coupons > 1) {
                p.coupons--;
                note(p, "kupon", "Günde en fazla " + p.coupons + " kupon");
            } else {
                note(p, "az", "Kredi çok az: kapsam en aza indi, yine de bütçeyi aşabilir");
                break;
            }
            p.narrowed = true;
        }
        // kullanıcının lig sırasını koru
        List<String> kept = new ArrayList<>();
        for (String l : cfg.leagues) if (p.leagues.contains(l)) kept.add(l);
        p.leagues = kept;
        if (cfg.creditExpand && !p.narrowed) expand(p, active, avgCoupons, y);
        p.cost = cost(p, active, avgCoupons);
        return p;
    }

    /** Genişletme bütçenin bu payına kadar: elle tarama, kontrol ve tahmin hatası için yer kalsın. */
    static final double EXPAND_SHARE = 0.85;

    /**
     * Kredi bolsa seçili olmayan bilinen ligler eklenir: yalnızca bugün maçı olduğu (ücretsiz maç
     * listesinden) kesin bilinenler; tahminle lig eklenmez. Geçmiş taramalarda en çok değerli seçim
     * çıkaran önce (eşitlikte liste sırası). Her lig, tahmini gider bütçenin EXPAND_SHARE'ini
     * aşmadıkça eklenir.
     */
    static void expand(Plan p, Map<String, Integer> active, double avgCoupons, final Map<String, Double> yield) {
        final List<String> extra = new ArrayList<>();
        for (String[] l : Settings.KNOWN_LEAGUES) if (!p.leagues.contains(l[0])) extra.add(l[0]);
        final List<String> order = new ArrayList<>(extra);
        Collections.sort(extra, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int c = Double.compare(yield.containsKey(b) ? yield.get(b) : 1.0, yield.containsKey(a) ? yield.get(a) : 1.0);
                return c != 0 ? c : Integer.compare(order.indexOf(a), order.indexOf(b));
            }
        });
        for (String l : extra) {
            Integer n = active == null ? null : active.get(l);
            if (n == null || n <= 0) continue; // bugün maçı yok ya da bilinmiyor
            p.leagues.add(l);
            if (cost(p, active, avgCoupons) > EXPAND_SHARE * p.budget) {
                p.leagues.remove(p.leagues.size() - 1);
                continue; // daha az pazarlı ya da KG sınırına takılan bir lig yine sığabilir
            }
            p.expanded.add(l);
        }
        if (!p.expanded.isEmpty()) note(p, "genis", "Kredi bol: " + p.expanded.size() + " ek lig tarandı");
    }

    static String leagueName(String key) {
        for (String[] l : Settings.KNOWN_LEAGUES) if (l[0].equals(key)) return l[1];
        String extra = Settings.EXTRA_NAMES.get(key);
        return extra != null ? extra : key;
    }

    private static int next(int[] steps, int current) {
        for (int s : steps) if (s < current) return s;
        return 0;
    }

    private static void note(Plan p, String key, String n) {
        // aynı kalem birden fazla kısılırsa yalnızca son durumu tut
        int i = p.noteKeys.indexOf(key);
        if (i >= 0) {
            p.noteKeys.remove(i);
            p.notes.remove(i);
        }
        p.noteKeys.add(key);
        p.notes.add(n);
    }

    /** Planı ayarlara uygular: daraltılmış kopya döndürür. */
    public static Settings effective(Settings cfg, Plan p) {
        Settings s = Settings.fromMap(cfg.toMap());
        s.leagues = new ArrayList<>(p.leagues);
        s.totals = p.totals;
        s.kgEvents = p.kgEvents;
        s.radarScans = p.radarScans;
        s.maxCouponsPerDay = p.coupons;
        return s;
    }

    /** Son taramadaki değerli seçimlerden lig verimini günceller (hareketli ortalama). */
    public static void updateYield(Map<String, Object> memory, Map<String, Object> radarView, List<String> scanned) {
        Map<String, Object> y = Json.obj(memory.get("yield"));
        if (y == null) memory.put("yield", y = new LinkedHashMap<String, Object>());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String l : scanned) counts.put(l, 0);
        for (Object o : Json.arr(radarView.get("values"))) {
            String sport = Json.str(Json.obj(o), "sport");
            if (sport != null && counts.containsKey(sport)) counts.put(sport, counts.get(sport) + 1);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            double prev = Json.dbl(y, e.getKey(), 1.0);
            y.put(e.getKey(), 0.7 * prev + 0.3 * e.getValue());
        }
    }

    public static Map<String, Double> yieldOf(Map<String, Object> memory) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, Object> y = Json.obj(memory.get("yield"));
        if (y != null) for (Map.Entry<String, Object> e : y.entrySet()) out.put(e.getKey(), ((Number) e.getValue()).doubleValue());
        return out;
    }
}
