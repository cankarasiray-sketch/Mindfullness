package app.bilincli.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uygulama ayarları. Varsayılanlar Python sürümüyle (bilincli/config.py) aynıdır. */
public final class Settings {
    public static final String[][] KNOWN_LEAGUES = {
        {"soccer_turkey_super_league", "Türkiye Süper Lig"},
        {"soccer_epl", "İngiltere Premier Lig"},
        {"soccer_spain_la_liga", "İspanya La Liga"},
        {"soccer_italy_serie_a", "İtalya Serie A"},
        {"soccer_germany_bundesliga", "Almanya Bundesliga"},
        {"soccer_france_ligue_one", "Fransa Ligue 1"},
        {"soccer_netherlands_eredivisie", "Hollanda Eredivisie"},
        {"soccer_portugal_primeira_liga", "Portekiz Primeira Liga"},
        {"soccer_uefa_champs_league", "UEFA Şampiyonlar Ligi"},
        {"soccer_uefa_europa_league", "UEFA Avrupa Ligi"},
    };

    // kaynaklar
    public String oddsApiKey = "";
    public List<String> leagues = new ArrayList<>(Arrays.asList(
            "soccer_turkey_super_league", "soccer_epl", "soccer_spain_la_liga",
            "soccer_italy_serie_a", "soccer_germany_bundesliga", "soccer_france_ligue_one"));
    public String regions = "eu";
    public String preferredBook = "pinnacle";
    public int minBooks = 3;

    // strateji
    public double windowHours = 24;
    public double minLeadMinutes = 30;
    public double minLegEv = 0.03;
    public double minCouponEv = 0.05;
    public double minWinProb = 0.20;
    public double minLegOdds = 1.25;
    public double maxLegOdds = 3.50;
    public double minCouponOdds = 1.50;
    public int maxLegs = 4;
    public int maxCandidates = 30;
    public double kellyMultiplier = 0.25;
    public double maxStakeFraction = 0.03;
    public double minCouponAmount = 50;
    /** Günde en fazla kaç bağımsız (maç paylaşmayan) kupon. */
    public int maxCouponsPerDay = 3;
    /** Bir günde tüm kuponlara toplam en fazla kasa oranı. */
    public double maxDailyExposure = 0.09;

    // pazarlar
    /** 2,5 Alt/Üst: Pinnacle "totals" verisi gerekir, lig başına kredi iki katına çıkar. */
    public boolean totals = false;
    /** Karşılıklı Gol: maç bazında çekilir; günde en fazla bu kadar maç (maç başına 1 kredi). 0 = kapalı. */
    public int kgEvents = 0;

    // limitler
    public double weeklyLossLimit = 0.15;
    public double chaseCooldownHours = 48;

    // zamanlama
    public int runHour = 6;
    public int runMinute = 0;

    /**
     * Otomatik kasa takibi: kupon, ilk maçtan 90 dk önceki kontrolde hâlâ avantajlıysa o anki
     * güncel oranlarla oynanmış sayılır ve kasa sonuçla birlikte kendiliğinden güncellenir.
     */
    public boolean autoTrack = true;
    /** Kredi planlayıcı: kalan krediye göre lig/pazar/radar kapsamını otomatik daraltır. */
    public boolean creditAuto = true;
    /** The Odds API kredisinin yenilendiği ayın günü (1-28). */
    public int creditResetDay = 1;

    /** Gün içi radar taraması sayısı: 0 (kapalı), 1 (17:00), 2 (13:00, 18:00), 4 (10, 13, 16, 19). */
    public int radarScans = 0;

    public int[] radarHours() {
        switch (radarScans) {
            case 1: return new int[] {17};
            case 2: return new int[] {13, 18};
            case 4: return new int[] {10, 13, 16, 19};
            default: return new int[0];
        }
    }

    /** Aylık tahmini The Odds API kredisi (ücretsiz plan 500). */
    public int estimatedMonthlyCredits() {
        // lig başına günlük çekim x pazar sayısı + KG maç başına + sonuç, kontrol ve kapanış için yaklaşık pay
        return 30 * leagues.size() * (1 + radarScans) * (totals ? 2 : 1) + 30 * kgEvents + 240;
    }

    /** "temkinli", "yuksek" ya da "ozel" (kullanıcı Kelly/üst sınırı elle değiştirdi). */
    public String profile = "temkinli";

    /**
     * Risk profilleri: {ad, başlık, Kelly çarpanı, kupon başına en fazla kasa}.
     * "yuksek", simülasyonda (README) en yüksek tipik aylık getiriyi veren ayardır; daha büyük
     * bahis tipik getiriyi artırmaz, düşürür.
     */
    public static final Object[][] PROFILES = {
        // ad, başlık, Kelly çarpanı, kupon başına üst sınır, günlük kupon, günlük toplam üst sınır
        {"temkinli", "Temkinli", 0.25, 0.03, 3, 0.09},
        {"yuksek", "En yüksek getiri", 0.50, 0.10, 3, 0.20},
    };

    public void applyProfile(String name) {
        for (Object[] p : PROFILES) {
            if (p[0].equals(name)) {
                profile = name;
                kellyMultiplier = (Double) p[2];
                maxStakeFraction = (Double) p[3];
                maxCouponsPerDay = (Integer) p[4];
                maxDailyExposure = (Double) p[5];
                return;
            }
        }
        throw new IllegalArgumentException("Bilinmeyen profil: " + name);
    }

    /** Kelly/üst sınır bir profile birebir uyuyorsa onun adı, yoksa "ozel". */
    public String detectProfile() {
        for (Object[] p : PROFILES) {
            if (Math.abs(kellyMultiplier - (Double) p[2]) < 1e-9 && Math.abs(maxStakeFraction - (Double) p[3]) < 1e-9
                    && maxCouponsPerDay == (Integer) p[4] && Math.abs(maxDailyExposure - (Double) p[5]) < 1e-9) {
                return (String) p[0];
            }
        }
        return "ozel";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oddsApiKey", oddsApiKey);
        m.put("leagues", new ArrayList<Object>(leagues));
        m.put("regions", regions);
        m.put("preferredBook", preferredBook);
        m.put("minBooks", (long) minBooks);
        m.put("windowHours", windowHours);
        m.put("minLeadMinutes", minLeadMinutes);
        m.put("minLegEv", minLegEv);
        m.put("minCouponEv", minCouponEv);
        m.put("minWinProb", minWinProb);
        m.put("minLegOdds", minLegOdds);
        m.put("maxLegOdds", maxLegOdds);
        m.put("minCouponOdds", minCouponOdds);
        m.put("maxLegs", (long) maxLegs);
        m.put("maxCandidates", (long) maxCandidates);
        m.put("kellyMultiplier", kellyMultiplier);
        m.put("maxStakeFraction", maxStakeFraction);
        m.put("minCouponAmount", minCouponAmount);
        m.put("maxCouponsPerDay", (long) maxCouponsPerDay);
        m.put("maxDailyExposure", maxDailyExposure);
        m.put("totals", totals);
        m.put("autoTrack", autoTrack);
        m.put("creditAuto", creditAuto);
        m.put("creditResetDay", (long) creditResetDay);
        m.put("kgEvents", (long) kgEvents);
        m.put("weeklyLossLimit", weeklyLossLimit);
        m.put("chaseCooldownHours", chaseCooldownHours);
        m.put("runHour", (long) runHour);
        m.put("runMinute", (long) runMinute);
        m.put("profile", detectProfile());
        m.put("radarScans", (long) radarScans);
        m.put("estimatedCredits", (long) estimatedMonthlyCredits());
        return m;
    }

    public static Settings fromMap(Map<String, Object> m) {
        Settings s = new Settings();
        if (m == null) return s;
        String key = Json.str(m, "oddsApiKey");
        s.oddsApiKey = key == null ? "" : key.trim();
        if (m.get("leagues") instanceof List) {
            s.leagues = new ArrayList<>();
            for (Object o : Json.arr(m.get("leagues"))) if (o instanceof String) s.leagues.add((String) o);
        }
        String regions = Json.str(m, "regions"), preferred = Json.str(m, "preferredBook");
        if (regions != null && !regions.trim().isEmpty()) s.regions = regions.trim();
        if (preferred != null && !preferred.trim().isEmpty()) s.preferredBook = preferred.trim();
        s.minBooks = (int) Json.lng(m, "minBooks", s.minBooks);
        s.windowHours = Json.dbl(m, "windowHours", s.windowHours);
        s.minLeadMinutes = Json.dbl(m, "minLeadMinutes", s.minLeadMinutes);
        s.minLegEv = Json.dbl(m, "minLegEv", s.minLegEv);
        s.minCouponEv = Json.dbl(m, "minCouponEv", s.minCouponEv);
        s.minWinProb = Json.dbl(m, "minWinProb", s.minWinProb);
        s.minLegOdds = Json.dbl(m, "minLegOdds", s.minLegOdds);
        s.maxLegOdds = Json.dbl(m, "maxLegOdds", s.maxLegOdds);
        s.minCouponOdds = Json.dbl(m, "minCouponOdds", s.minCouponOdds);
        s.maxLegs = (int) Json.lng(m, "maxLegs", s.maxLegs);
        s.maxCandidates = (int) Json.lng(m, "maxCandidates", s.maxCandidates);
        s.kellyMultiplier = Json.dbl(m, "kellyMultiplier", s.kellyMultiplier);
        s.maxStakeFraction = Json.dbl(m, "maxStakeFraction", s.maxStakeFraction);
        s.minCouponAmount = Json.dbl(m, "minCouponAmount", s.minCouponAmount);
        s.maxCouponsPerDay = (int) Json.lng(m, "maxCouponsPerDay", s.maxCouponsPerDay);
        s.maxDailyExposure = Json.dbl(m, "maxDailyExposure", s.maxDailyExposure);
        s.totals = Json.bool(m, "totals", s.totals);
        s.autoTrack = Json.bool(m, "autoTrack", s.autoTrack);
        s.creditAuto = Json.bool(m, "creditAuto", s.creditAuto);
        s.creditResetDay = (int) Json.lng(m, "creditResetDay", s.creditResetDay);
        s.kgEvents = (int) Json.lng(m, "kgEvents", s.kgEvents);
        s.weeklyLossLimit = Json.dbl(m, "weeklyLossLimit", s.weeklyLossLimit);
        s.chaseCooldownHours = Json.dbl(m, "chaseCooldownHours", s.chaseCooldownHours);
        s.runHour = (int) Json.lng(m, "runHour", s.runHour);
        s.runMinute = (int) Json.lng(m, "runMinute", s.runMinute);
        s.radarScans = (int) Json.lng(m, "radarScans", s.radarScans);
        s.profile = s.detectProfile();
        return s;
    }

    /** Geçersiz bir ayar varsa Türkçe hata mesajı döndürür, yoksa null. */
    public String validate() {
        if (!(kellyMultiplier > 0 && kellyMultiplier <= 1)) return "Kelly çarpanı 0 ile 1 arasında olmalı";
        if (!(maxStakeFraction > 0 && maxStakeFraction <= 0.25)) return "Kupon başına en fazla bahis %0-25 arasında olmalı";
        if (maxLegs < 1 || maxLegs > 6) return "En fazla maç sayısı 1 ile 6 arasında olmalı";
        if (maxCandidates < 1 || maxCandidates > 60) return "Aday sayısı 1 ile 60 arasında olmalı";
        if (!(minLegOdds > 1.0 && maxLegOdds > minLegOdds)) return "Bacak oran aralığı geçersiz";
        if (minWinProb < 0 || minWinProb >= 1) return "En düşük tutma olasılığı %0-99 arasında olmalı";
        if (windowHours <= 0 || windowHours > 72) return "Zaman penceresi 1-72 saat olmalı";
        if (minCouponAmount < 0) return "Asgari kupon tutarı negatif olamaz";
        if (weeklyLossLimit < 0 || weeklyLossLimit >= 1) return "Haftalık kayıp limiti %0-99 arasında olmalı";
        if (chaseCooldownHours < 0 || chaseCooldownHours > 24 * 14) return "Kovalama beklemesi 0-336 saat olmalı";
        if (runHour < 0 || runHour > 23 || runMinute < 0 || runMinute > 59) return "Saat geçersiz";
        if (leagues.isEmpty()) return "En az bir lig seçilmeli";
        if (maxCouponsPerDay < 1 || maxCouponsPerDay > 3) return "Günlük kupon sayısı 1 ile 3 arasında olmalı";
        if (!(maxDailyExposure >= maxStakeFraction && maxDailyExposure <= 0.30)) {
            return "Günlük toplam üst sınır, kupon başına sınırdan küçük olamaz ve en fazla %30 olabilir";
        }
        if (creditResetDay < 1 || creditResetDay > 28) return "Kredi yenilenme günü 1 ile 28 arasında olmalı";
        if (kgEvents < 0 || kgEvents > 20) return "Karşılıklı Gol maç sayısı 0 ile 20 arasında olmalı";
        if (radarScans != 0 && radarScans != 1 && radarScans != 2 && radarScans != 4) return "Radar sıklığı 0, 1, 2 ya da 4 olmalı";
        return null;
    }
}
