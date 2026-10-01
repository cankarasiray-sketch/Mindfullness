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
 * Karşılıklı Gol (maç başına 1 kredi) → radar taramaları → 2,5 Alt/Üst → en az değerli fırsat
 * çıkaran lig. Kullanıcının seçmediği hiçbir şey eklenmez; plan yalnızca daraltır.
 */
public final class CreditPlan {
    private CreditPlan() {}

    public static final int DEFAULT_QUOTA = 500;
    static final int RESERVE = 15;

    public static final class Plan {
        public List<String> leagues = new ArrayList<>();
        public boolean totals;
        public int kgEvents, radarScans, daysLeft;
        public double budget, cost;
        public Long remaining;
        public boolean narrowed;
        public List<String> notes = new ArrayList<>();
        final List<String> noteKeys = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("leagues", new ArrayList<Object>(leagues));
            m.put("totals", totals);
            m.put("kgEvents", (long) kgEvents);
            m.put("radarScans", (long) radarScans);
            m.put("daysLeft", (long) daysLeft);
            m.put("budget", budget);
            m.put("cost", cost);
            m.put("remaining", remaining);
            m.put("narrowed", narrowed);
            m.put("notes", new ArrayList<Object>(notes));
            return m;
        }
    }

    /**
     * Günlük sabit gider tahmini: sonuç sorguları (lig başına 2), maç öncesi kontroller ve kapanış
     * oranları (kupon başına birkaç). Tahmin şaşsa da plan her gün gerçek kalan krediden yeniden
     * hesaplandığı için kendini düzeltir.
     */
    static int overhead(Settings cfg) {
        return 4 + 3 * cfg.maxCouponsPerDay;
    }

    static double cost(int leagues, boolean totals, int radar, int kg, int overhead) {
        return leagues * (totals ? 2 : 1) * (1 + radar) + kg + overhead;
    }

    static int daysLeft(LocalDate today, int resetDay) {
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
        Plan p = new Plan();
        p.totals = cfg.totals;
        p.kgEvents = cfg.kgEvents;
        p.radarScans = cfg.radarScans;
        p.leagues.addAll(cfg.leagues);
        p.daysLeft = daysLeft(today, cfg.creditResetDay);
        long quota = remaining != null && used != null ? remaining + used : DEFAULT_QUOTA;
        double left = remaining != null ? remaining
                : (double) quota * p.daysLeft / today.lengthOfMonth(); // ilk çalışma: ay içinde orantılı
        p.remaining = remaining;
        p.budget = Math.max(0, left - RESERVE) / p.daysLeft;
        if (!cfg.creditAuto) {
            p.cost = cost(p.leagues.size(), p.totals, p.radarScans, p.kgEvents, overhead(cfg));
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
        int oh = overhead(cfg);
        int[] radarSteps = {4, 2, 1, 0};
        int[] kgSteps = {12, 8, 4, 0};
        while (cost(p.leagues.size(), p.totals, p.radarScans, p.kgEvents, oh) > p.budget) {
            if (p.kgEvents > 0) {
                p.kgEvents = next(kgSteps, p.kgEvents);
                note(p, "kg", p.kgEvents > 0 ? "Karşılıklı Gol en fazla " + p.kgEvents + " maç" : "Karşılıklı Gol bugünlük kapatıldı");
            } else if (p.radarScans > 0) {
                p.radarScans = next(radarSteps, p.radarScans);
                note(p, "radar", p.radarScans > 0 ? "Radar günde en fazla " + p.radarScans + " tarama" : "Radar bugünlük kapatıldı");
            } else if (p.totals) {
                p.totals = false;
                note(p, "totals", "2,5 Alt/Üst bugünlük kapatıldı");
            } else if (p.leagues.size() > 1) {
                String dropped = p.leagues.remove(p.leagues.size() - 1);
                note(p, "lig:" + dropped, leagueName(dropped) + " bugünlük çıkarıldı (en az fırsat çıkaran lig)");
            } else {
                note(p, "az", "Kredi çok az: yalnızca " + leagueName(p.leagues.get(0)) + " taranıyor, yine de bütçeyi aşabilir");
                break;
            }
            p.narrowed = true;
        }
        p.cost = cost(p.leagues.size(), p.totals, p.radarScans, p.kgEvents, oh);
        // kullanıcının lig sırasını koru
        List<String> kept = new ArrayList<>();
        for (String l : cfg.leagues) if (p.leagues.contains(l)) kept.add(l);
        p.leagues = kept;
        return p;
    }

    static String leagueName(String key) {
        for (String[] l : Settings.KNOWN_LEAGUES) if (l[0].equals(key)) return l[1];
        return key;
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
