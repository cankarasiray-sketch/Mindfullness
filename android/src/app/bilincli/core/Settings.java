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
        {"soccer_uefa_europa_conference_league", "UEFA Konferans Ligi"},
        {"soccer_efl_champ", "İngiltere Championship"},
        {"soccer_belgium_first_div", "Belçika Pro Lig"},
        {"soccer_spl", "İskoçya Premiership"},
        {"soccer_germany_bundesliga2", "Almanya 2. Bundesliga"},
        {"soccer_spain_segunda_division", "İspanya La Liga 2"},
        {"soccer_italy_serie_b", "İtalya Serie B"},
        {"soccer_france_ligue_two", "Fransa Ligue 2"},
        {"soccer_greece_super_league", "Yunanistan Süper Lig"},
        {"soccer_austria_bundesliga", "Avusturya Bundesliga"},
        {"soccer_switzerland_superleague", "İsviçre Süper Lig"},
        {"soccer_denmark_superliga", "Danimarka Superliga"},
        {"soccer_poland_ekstraklasa", "Polonya Ekstraklasa"},
        {"soccer_sweden_allsvenskan", "İsveç Allsvenskan"},
        {"soccer_norway_eliteserien", "Norveç Eliteserien"},
        {"soccer_brazil_campeonato", "Brezilya Série A"},
        {"soccer_argentina_primera_division", "Arjantin Primera División"},
        {"soccer_usa_mls", "ABD MLS"},
        {"soccer_japan_j_league", "Japonya J1 Lig"},
        {"basketball_euroleague", "EuroLeague (basketbol)"},
        {"basketball_nba", "NBA (basketbol)"},
    };

    /** 1.9: basketbol ligleri (maç sonucu; Alt/Üst açıksa toplam sayı). */
    static final List<String> BASKETBALL_LEAGUES = Arrays.asList("basketball_euroleague", "basketball_nba");

    public static boolean isBasketball(String league) {
        return league != null && league.startsWith("basketball_");
    }

    /**
     * Ligin oran sorgusundaki pazarlar: maç sonucu, Alt/Üst açıksa toplam (futbolda 2,5;
     * basketbolda toplam sayı, Pinnacle'ın ana çizgisi).
     */
    public static String markets(String league, boolean totals) {
        return totals ? "h2h,totals" : "h2h";
    }

    /** Ligin tarama başına kredi maliyeti (pazar sayısı; bölge "eu"). */
    public static int scanCost(String league, boolean totals) {
        return totals ? 2 : 1;
    }

    /** 1.7.2 öncesi varsayılan ligler (taşıma için). */
    static final List<String> OLD_DEFAULT_LEAGUES = Arrays.asList(
            "soccer_turkey_super_league", "soccer_epl", "soccer_spain_la_liga",
            "soccer_italy_serie_a", "soccer_germany_bundesliga", "soccer_france_ligue_one");

    /**
     * Bir ligin herhangi bir günde karar penceresinde maçı olma payı (yaklaşık). Maçı olmayan lig
     * için oran çekilmediğinden (ücretsiz maç listesi) kredi tahminleri bununla yapılır.
     */
    public static final double ACTIVE_SHARE = 0.4;

    // kaynaklar
    /**
     * The Odds API anahtarları, satır satır (eski sürümlerde tek anahtar). Birden fazla anahtar
     * varsa her sorguda kredisi en çok kalan kullanılır (OddsApi).
     */
    public String oddsApiKey = "";

    /** En fazla bu kadar anahtar. */
    public static final int MAX_KEYS = 5;

    /** Girilen anahtarlar (boşluk, satır, virgül ya da noktalı virgülle ayrılmış; tekrarlar atılır). */
    public List<String> apiKeys() {
        List<String> out = new ArrayList<>();
        if (oddsApiKey == null) return out;
        for (String k : oddsApiKey.trim().split("[\\s,;]+")) if (!k.isEmpty() && !out.contains(k)) out.add(k);
        return out;
    }

    /** Kalıcı kayıtlarda anahtarın yerine geçen kimlik (anahtarın kendisi ikinci bir yere yazılmaz). */
    public static String keyId(String key) {
        return Integer.toHexString(key.hashCode());
    }

    /** Arayüzde ve raporlarda gösterilen kısa ad: son 4 karakter. */
    public static String keyLabel(String key) {
        return "…" + (key.length() > 4 ? key.substring(key.length() - 4) : key);
    }
    /**
     * Varsayılan ligler: büyük 6 lig + Avrupa kupaları (hafta içi) + Hollanda ve Portekiz. Maçı
     * olmayan lig kredi harcamadığı için ligi seçili tutmak yalnızca oynadığı gün kredi harcar.
     * (Masaüstü Python sürümü maç listesine bakmadığı için orada 6 lig varsayılandır.)
     */
    public List<String> leagues = new ArrayList<>(Arrays.asList(
            "soccer_turkey_super_league", "soccer_epl", "soccer_spain_la_liga",
            "soccer_italy_serie_a", "soccer_germany_bundesliga", "soccer_france_ligue_one",
            "soccer_uefa_champs_league", "soccer_uefa_europa_league", "soccer_uefa_europa_conference_league",
            "soccer_netherlands_eredivisie", "soccer_portugal_primeira_liga",
            "basketball_euroleague", "basketball_nba"));
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
    /** Günde en fazla kaç kupon (ortak Kelly ile birlikte tutarlandırılır). */
    public int maxCouponsPerDay = 5;
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
    /**
     * Kanıt koruması: kapanış oranı ölçümü, gerçekleşen avantajın öngörülenin yarısından az
     * olduğunu gösterirse olasılıklar iddaa fiyatına doğru küçültülür (EdgeCalibration).
     */
    public boolean edgeGuard = true;
    /**
     * Milli maçlar: The Odds API'de o an aktif milli takım turnuvaları (Uluslar Ligi, Dünya Kupası
     * elemeleri, hazırlık maçları...) taramaya kendiliğinden eklenir. Maçı olmayan turnuva kredi
     * harcamaz.
     */
    public boolean internationals = true;

    /** Keşfedilen turnuvaların görünen adları (kod -> ad); süreç boyu önbellek. */
    public static final Map<String, String> EXTRA_NAMES = new java.util.concurrent.ConcurrentHashMap<>();
    /** Kredi planlayıcı: kalan krediye göre lig/pazar/radar kapsamını otomatik daraltır. */
    public boolean creditAuto = true;
    /** The Odds API kredisinin yenilendiği ayın günü (1-28). */
    public int creditResetDay = 1;
    /**
     * Kredi bolsa (bütçe tahmini giderin çok üstündeyse) seçili olmayan ligler de o gün taranır:
     * bugün maçı olan, geçmişte en çok değerli seçim çıkaran önce. Küçük kotada hiçbir şey eklenmez.
     */
    public boolean creditExpand = true;

    /**
     * Kapanış oranı ölçümünden pazar bazında avantaj oranları (EdgeCalibration). Kalıcı değildir;
     * her karar öncesi defterden hesaplanır. null = kalibrasyon yok.
     */
    public transient java.util.Map<String, Double> edgeRatios;

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
        int perScan = 0;
        for (String l : leagues) perScan += scanCost(l, totals);
        return (int) Math.round(30 * perScan * ACTIVE_SHARE * (1 + radarScans)) + 30 * kgEvents + 240;
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
        {"temkinli", "Temkinli", 0.25, 0.03, 5, 0.09},
        {"yuksek", "En yüksek getiri", 0.50, 0.10, 5, 0.20},
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
        m.put("creditExpand", creditExpand);
        m.put("creditResetDay", (long) creditResetDay);
        m.put("kgEvents", (long) kgEvents);
        m.put("weeklyLossLimit", weeklyLossLimit);
        m.put("chaseCooldownHours", chaseCooldownHours);
        m.put("runHour", (long) runHour);
        m.put("runMinute", (long) runMinute);
        m.put("edgeGuard", edgeGuard);
        m.put("internationals", internationals);
        m.put("profile", detectProfile());
        m.put("v", 4L);
        m.put("radarScans", (long) radarScans);
        m.put("estimatedCredits", (long) estimatedMonthlyCredits());
        return m;
    }

    public static Settings fromMap(Map<String, Object> m) {
        Settings s = new Settings();
        if (m == null) return s;
        String key = Json.str(m, "oddsApiKey");
        s.oddsApiKey = key == null ? "" : key.trim();
        s.oddsApiKey = String.join("\n", s.apiKeys()); // tek biçim: satır başına bir anahtar
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
        s.creditExpand = Json.bool(m, "creditExpand", s.creditExpand);
        s.creditResetDay = (int) Json.lng(m, "creditResetDay", s.creditResetDay);
        s.kgEvents = (int) Json.lng(m, "kgEvents", s.kgEvents);
        s.weeklyLossLimit = Json.dbl(m, "weeklyLossLimit", s.weeklyLossLimit);
        s.chaseCooldownHours = Json.dbl(m, "chaseCooldownHours", s.chaseCooldownHours);
        s.runHour = (int) Json.lng(m, "runHour", s.runHour);
        s.runMinute = (int) Json.lng(m, "runMinute", s.runMinute);
        s.radarScans = (int) Json.lng(m, "radarScans", s.radarScans);
        s.edgeGuard = Json.bool(m, "edgeGuard", s.edgeGuard);
        s.internationals = Json.bool(m, "internationals", s.internationals);
        if (Json.lng(m, "v", 1) < 2 && s.maxCouponsPerDay == 3) {
            // 1.4 → 1.5: profiller günde 5 kupona çıktı (ortak Kelly); profil kullanıcısını taşı
            s.maxCouponsPerDay = 5;
            if ("ozel".equals(s.detectProfile())) s.maxCouponsPerDay = 3;
        }
        if (Json.lng(m, "v", 1) < 3 && new java.util.HashSet<>(s.leagues).equals(new java.util.HashSet<>(OLD_DEFAULT_LEAGUES))) {
            // 1.7.2: eski varsayılan lig listesi değiştirilmemişse yeni varsayılana (Avrupa kupaları dahil)
            s.leagues = new Settings().leagues;
        }
        if (Json.lng(m, "v", 1) < 4) {
            // 1.9: basketbol (EuroLeague, NBA) eklendi; maçı olmayan gün kredi harcamaz
            for (String l : BASKETBALL_LEAGUES) if (!s.leagues.contains(l)) s.leagues.add(l);
        }
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
        if (apiKeys().size() > MAX_KEYS) return "En fazla " + MAX_KEYS + " API anahtarı girilebilir";
        if (maxCouponsPerDay < 1 || maxCouponsPerDay > 5) return "Günlük kupon sayısı 1 ile 5 arasında olmalı";
        if (!(maxDailyExposure >= maxStakeFraction && maxDailyExposure <= 0.30)) {
            return "Günlük toplam üst sınır, kupon başına sınırdan küçük olamaz ve en fazla %30 olabilir";
        }
        if (creditResetDay < 1 || creditResetDay > 28) return "Kredi yenilenme günü 1 ile 28 arasında olmalı";
        if (kgEvents < 0 || kgEvents > 20) return "Karşılıklı Gol maç sayısı 0 ile 20 arasında olmalı";
        if (radarScans != 0 && radarScans != 1 && radarScans != 2 && radarScans != 4) return "Radar sıklığı 0, 1, 2 ya da 4 olmalı";
        return null;
    }
}
