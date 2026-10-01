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

    // limitler
    public double weeklyLossLimit = 0.15;
    public double chaseCooldownHours = 48;

    // zamanlama
    public int runHour = 6;
    public int runMinute = 0;

    /** "temkinli", "yuksek" ya da "ozel" (kullanıcı Kelly/üst sınırı elle değiştirdi). */
    public String profile = "temkinli";

    /**
     * Risk profilleri: {ad, başlık, Kelly çarpanı, kupon başına en fazla kasa}.
     * "yuksek", simülasyonda (README) en yüksek tipik aylık getiriyi veren ayardır; daha büyük
     * bahis tipik getiriyi artırmaz, düşürür.
     */
    public static final Object[][] PROFILES = {
        {"temkinli", "Temkinli", 0.25, 0.03},
        {"yuksek", "En yüksek getiri", 0.50, 0.10},
    };

    public void applyProfile(String name) {
        for (Object[] p : PROFILES) {
            if (p[0].equals(name)) {
                profile = name;
                kellyMultiplier = (Double) p[2];
                maxStakeFraction = (Double) p[3];
                return;
            }
        }
        throw new IllegalArgumentException("Bilinmeyen profil: " + name);
    }

    /** Kelly/üst sınır bir profile birebir uyuyorsa onun adı, yoksa "ozel". */
    public String detectProfile() {
        for (Object[] p : PROFILES) {
            if (Math.abs(kellyMultiplier - (Double) p[2]) < 1e-9 && Math.abs(maxStakeFraction - (Double) p[3]) < 1e-9) {
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
        m.put("weeklyLossLimit", weeklyLossLimit);
        m.put("chaseCooldownHours", chaseCooldownHours);
        m.put("runHour", (long) runHour);
        m.put("runMinute", (long) runMinute);
        m.put("profile", detectProfile());
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
        s.weeklyLossLimit = Json.dbl(m, "weeklyLossLimit", s.weeklyLossLimit);
        s.chaseCooldownHours = Json.dbl(m, "chaseCooldownHours", s.chaseCooldownHours);
        s.runHour = (int) Json.lng(m, "runHour", s.runHour);
        s.runMinute = (int) Json.lng(m, "runMinute", s.runMinute);
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
        return null;
    }
}
