package app.bilincli.core;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bilyoner "Zirve Oran": iddaa'nın normal oranının (val) yanında Bilyoner'in artırılmış oranı (tval).
 * Herkese açık bülten adresinin Zirve sekmesinden (tabType 160) okunur; hesap ya da oturum yok, kredi
 * harcamaz. Her artırılmış oran, son tam taramanın adil oranıyla (Pinnacle, marjı arındırılmış)
 * karşılaştırılır; avantaj eşiği ve Kelly tutarı promosyon kontrolüyle (Promo.evaluate) aynıdır.
 *
 * Koruma: Bilyoner'in normal oranı (val), son taramada Nesine'den okunan iddaa oranıyla aynı olmalı
 * (iki site de resmi iddaa programını verir). Belirgin fark ya oranın o arada değiştiğini ya da
 * pazarın yanlış eşlendiğini gösterir; o seçim önerilmez.
 */
public final class Zirve {
    private Zirve() {}

    public static final String URL = "https://www.bilyoner.com/api/v3/mobile/aggregator/gamelist/all/v1?tabType=160&bulletinType=2";
    /** Maç saati farkı en fazla (Bilyoner ve Nesine aynı programı verir; pay bırakılır). */
    static final long MAX_KICKOFF_GAP_S = 20 * 60;
    static final double MIN_SIDE = 0.6, MIN_PAIR = 0.8;
    /** Bilyoner'in normal oranı son taramadaki iddaa oranından bundan çok farklıysa önerilmez. */
    static final double MAX_ODDS_GAP = 0.08;
    static final int MAX_ROWS = 150;
    static final int MAX_NOTIFIED = 300;
    /** Değerlendirme biçimi; eski sürümün kaydı görülünce okuma beklemeden yenilenir. */
    public static final int VIEW_VERSION = 3;

    /** Zirve sekmesindeki artırılmış bir oran. */
    public static final class Offer {
        public final String id, event, home, away, league, name, marketName, sov;
        public final Instant kickoff;
        public final double val, tval;
        /** Aynı pazarda "MS X" var (basketbolda normal süre üç seçenekli maç sonucu; iki seçenekliyle karıştırılmaz). */
        public final boolean threeWay;

        /** Zirve oranı normal orandan yüksek. */
        public boolean boosted() {
            return val > 1 && tval > val;
        }

        Offer(String id, String event, String home, String away, String league, Instant kickoff, String name,
              String marketName, String sov, double val, double tval, boolean threeWay) {
            this.id = id;
            this.event = event;
            this.home = home;
            this.away = away;
            this.league = league;
            this.kickoff = kickoff;
            this.name = name;
            this.marketName = marketName;
            this.sov = sov;
            this.val = val;
            this.tval = tval;
            this.threeWay = threeWay;
        }
    }

    /** Sitenin tarayıcıya verdiği yanıtı almak için olağan tarayıcı başlıkları. */
    public static Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Accept-Language", "tr-TR,tr;q=0.9");
        h.put("Referer", "https://www.bilyoner.com/iddaa/zirve-oran");
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
        return h;
    }

    /** Zirve sekmesini indirir: maçların tüm oranları (artırılmışlar boosted()). */
    public static List<Offer> fetch(Http http) throws Http.ProviderException {
        Http.Response r = http.get(URL, headers());
        if (r.status != 200) throw new Http.ProviderException("Bilyoner Zirve Oran: HTTP " + r.status);
        try {
            return parseAll(r.body == null ? "" : r.body);
        } catch (RuntimeException e) {
            throw new Http.ProviderException("Bilyoner Zirve Oran: yanıt okunamadı (" + e.getMessage() + ")");
        }
    }

    /** Artırılmış oranlar (tval > val). Yanıt JSON değilse istisna fırlatır. */
    public static List<Offer> parse(String body) {
        List<Offer> out = new ArrayList<>();
        for (Offer o : parseAll(body)) if (o.boosted()) out.add(o);
        return out;
    }

    /**
     * Zirve maçlarının tüm oranları (artırılmamışlar da): normal oranlar (val) hedefli taramada iddaa
     * tarafı olarak kullanılır. Yanıt JSON değilse istisna fırlatır.
     */
    public static List<Offer> parseAll(String body) {
        Map<String, Object> root = Json.obj(Json.parse(body));
        List<Offer> out = new ArrayList<>();
        if (root == null) return out;
        Object ev = root.get("events");
        Iterable<?> items = ev instanceof Map ? ((Map<?, ?>) ev).values() : Json.arr(ev);
        for (Object o : items) {
            Map<String, Object> e = Json.obj(o);
            if (e == null) continue;
            Instant kickoff = kickoff(e);
            String home = Json.str(e, "htn"), away = Json.str(e, "atn");
            if (kickoff == null || home == null || away == null) continue;
            List<Map<String, Object>> odds = new ArrayList<>();
            Set<String> threeWay = new HashSet<>();
            for (Object g : Json.arr(e.get("marketGroups"))) {
                Map<String, Object> gm = Json.obj(g);
                if (gm == null) continue;
                for (Object x : Json.arr(gm.get("odds"))) {
                    Map<String, Object> om = Json.obj(x);
                    if (om == null) continue;
                    odds.add(om);
                    if ("MS X".equals(clean(Json.str(om, "n")))) threeWay.add(Json.str(om, "mrId"));
                }
            }
            for (Map<String, Object> om : odds) {
                double val = number(om.get("val")), tval = number(om.get("tval"));
                if (!(val > 1)) continue; // kapalı oran ("0")
                out.add(new Offer(Json.str(om, "id"), Json.str(e, "id"), home, away, Json.str(e, "lgn"), kickoff,
                        clean(Json.str(om, "n")), clean(Json.str(om, "mrn")), Json.str(om, "sov"), val, tval,
                        threeWay.contains(Json.str(om, "mrId"))));
            }
        }
        return out;
    }

    /** Başlama anı: esdl (epoch ms), yoksa esd (Türkiye saati). */
    static Instant kickoff(Map<String, Object> e) {
        Double ms = Json.num(e, "esdl");
        if (ms != null && ms > 0) return Instant.ofEpochMilli(ms.longValue());
        String esd = Json.str(e, "esd");
        if (esd == null) return null;
        try {
            return LocalDateTime.parse(esd).atZone(Fmt.TR).toInstant();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static double number(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v == null) return 0;
        try {
            return Double.parseDouble(String.valueOf(v).trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** Türkçe harfler sadeleştirilmiş küçük harf (Locale.ROOT). */
    public static String fold(String s) {
        StringBuilder t = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) {
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
        return t.toString().toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static final Pattern MS_NAME = Pattern.compile("^ms ?([12x])$");
    private static final String[] NOT_FULL_TIME = {"yari", "takim", "ev sahibi", "deplasman", "korner", "kart", "periyot", "ceyrek"};

    /**
     * Bilyoner pazarı -> {pazar, sonuç} (uygulamanın anahtarlarıyla); tanınmayan pazar null.
     * Yalnızca uygulamanın adil oranını bildiği maç sonu pazarları: MS, KG, ÇŞ, 2,5 Alt/Üst; basketbolda
     * uzatmalar dahil maç sonucu ve toplam sayı Alt/Üst (iddaa çizgisi).
     */
    static String[] map(Offer o, boolean basketball) {
        String m = fold(o.marketName), n = fold(o.name);
        for (String w : NOT_FULL_TIME) if (m.contains(w)) return null;
        if (m.equals("mac sonucu") || (basketball && m.startsWith("mac sonucu"))) {
            Matcher x = MS_NAME.matcher(n);
            if (!x.find()) return null;
            String out = x.group(1).toUpperCase(Locale.ROOT);
            if (!basketball) return new String[] {"MS", out};
            if (o.threeWay || "X".equals(out)) return null;
            return new String[] {"BS", out};
        }
        if (basketball) {
            if (!m.contains("alt/ust")) return null;
            Double line = line(o.sov);
            if (line == null || Math.abs(line - Math.rint(line)) < 1e-9) return null;
            String side = side(n);
            return side == null ? null : new String[] {"BT@" + line, side};
        }
        if (m.equals("karsilikli gol")) {
            if (n.endsWith("var")) return new String[] {"KG", "VAR"};
            if (n.endsWith("yok")) return new String[] {"KG", "YOK"};
            return null;
        }
        if (m.equals("cifte sans")) {
            String k = n.replaceAll("[^0-9x]", "");
            if (k.endsWith("1x")) return new String[] {"CS", "1X"};
            if (k.endsWith("x2")) return new String[] {"CS", "X2"};
            if (k.endsWith("12")) return new String[] {"CS", "12"};
            return null;
        }
        if (m.contains("gol alt/ust")) {
            Double line = line(o.sov);
            if (line == null || Math.abs(line - 2.5) > 1e-9) return null;
            String side = side(n);
            return side == null ? null : new String[] {"AU25", side};
        }
        return null;
    }

    private static String side(String n) {
        boolean alt = n.contains("alt"), ust = n.contains("ust");
        if (alt == ust) return null;
        return alt ? "ALT" : "UST";
    }

    private static Double line(String sov) {
        if (sov == null || sov.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(sov.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Aynı maç mı (Bilyoner ve Nesine aynı resmi programı verir): başlama farkı en fazla 20 dk, takım
     * varyantı (kadın/genç) aynı, iki taraf da benzer. Eşleşme puanı (0..1), eşleşmiyorsa 0.
     */
    public static double sameEvent(String home, String away, Instant kickoff, String home2, String away2, Instant kickoff2) {
        if (home == null || away == null || home2 == null || away2 == null || kickoff == null || kickoff2 == null) return 0;
        if (Math.abs(kickoff.getEpochSecond() - kickoff2.getEpochSecond()) > MAX_KICKOFF_GAP_S) return 0;
        if (!Matching.variant(home).equals(Matching.variant(home2)) || !Matching.variant(away).equals(Matching.variant(away2))) return 0;
        double h = Matching.similarity(home, home2), a = Matching.similarity(away, away2);
        if (Math.min(h, a) < MIN_SIDE || (h + a) / 2 < MIN_PAIR) return 0;
        return (h + a) / 2;
    }

    /** Son taramanın adil oran tablosunda bu maç (aynı saat, benzer adlar); yoksa null. */
    static Map<String, Object> findRow(Offer o, List<Object> fairs) {
        Map<String, Object> best = null;
        double bestScore = 0;
        for (Object x : fairs) {
            Map<String, Object> r = Json.obj(x);
            if (r == null || Json.str(r, "kickoff") == null) continue;
            double score = sameEvent(Json.str(r, "home"), Json.str(r, "away"), Instant.parse(Json.str(r, "kickoff")), o.home, o.away, o.kickoff);
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    /** Eşleşmeyen maç için açıklama: son taramada o saatte hangi maçlar vardı (ad farkını görmek için). */
    static String why(Offer o, List<Object> fairs) {
        List<String> near = new ArrayList<>();
        for (Object x : fairs) {
            Map<String, Object> r = Json.obj(x);
            if (r == null || Json.str(r, "kickoff") == null) continue;
            if (Math.abs(Instant.parse(Json.str(r, "kickoff")).getEpochSecond() - o.kickoff.getEpochSecond()) > MAX_KICKOFF_GAP_S) continue;
            String n = Json.str(r, "home") + " – " + Json.str(r, "away");
            if (!near.contains(n) && near.size() < 3) near.add(n);
        }
        return near.isEmpty() ? "son taramada bu saatte maç yok"
                : "son taramada bu saatte " + String.join(", ", near) + " var; bu maçla eşleşmedi";
    }

    /** Bilyoner'in iddaa lig adları -> The Odds API lig kodu (sadeleştirilmiş adla). */
    static final Map<String, String> LEAGUE_ALIASES = new LinkedHashMap<>();

    static {
        LEAGUE_ALIASES.put("uefa uluslar ligi", "soccer_uefa_nations_league");
        LEAGUE_ALIASES.put("uluslar ligi", "soccer_uefa_nations_league");
        LEAGUE_ALIASES.put("uefa sampiyonlar ligi", "soccer_uefa_champs_league");
        LEAGUE_ALIASES.put("sampiyonlar ligi", "soccer_uefa_champs_league");
        LEAGUE_ALIASES.put("uefa avrupa ligi", "soccer_uefa_europa_league");
        LEAGUE_ALIASES.put("avrupa ligi", "soccer_uefa_europa_league");
        LEAGUE_ALIASES.put("uefa konferans ligi", "soccer_uefa_europa_conference_league");
        LEAGUE_ALIASES.put("konferans ligi", "soccer_uefa_europa_conference_league");
        LEAGUE_ALIASES.put("super lig", "soccer_turkey_super_league");
        LEAGUE_ALIASES.put("turkiye super lig", "soccer_turkey_super_league");
        LEAGUE_ALIASES.put("trendyol super lig", "soccer_turkey_super_league");
        LEAGUE_ALIASES.put("ingiltere premier lig", "soccer_epl");
        LEAGUE_ALIASES.put("premier lig", "soccer_epl");
        LEAGUE_ALIASES.put("ispanya la liga", "soccer_spain_la_liga");
        LEAGUE_ALIASES.put("la liga", "soccer_spain_la_liga");
        LEAGUE_ALIASES.put("italya serie a", "soccer_italy_serie_a");
        LEAGUE_ALIASES.put("almanya bundesliga", "soccer_germany_bundesliga");
        LEAGUE_ALIASES.put("fransa ligue 1", "soccer_france_ligue_one");
        LEAGUE_ALIASES.put("euroleague", "basketball_euroleague");
        LEAGUE_ALIASES.put("thy euroleague", "basketball_euroleague");
        LEAGUE_ALIASES.put("nba", "basketball_nba");
    }

    /**
     * Zirve maçının ligi için oran sorgusu kodu: önce öğrenilmiş eşleme (daha önce eşleşen Zirve
     * maçlarından), sonra bilinen iddaa adları, sonra lig listesindeki adlarla benzerlik. Bulunamazsa null.
     * candidates: {kod, ad} (seçilebilir ligler ve milli turnuvalar).
     */
    public static String leagueKey(String league, Map<String, String> learned, List<String[]> candidates) {
        if (league == null || league.trim().isEmpty()) return null;
        if (learned != null && learned.get(league) != null) return learned.get(league);
        String f = fold(league).replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
        if (LEAGUE_ALIASES.containsKey(f)) return LEAGUE_ALIASES.get(f);
        String best = null;
        double bestScore = 0;
        if (candidates != null) {
            for (String[] c : candidates) {
                String name = fold(c[1].replaceAll("\\(.*?\\)", "")).replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
                double score = name.equals(f) ? 1.0 : Matching.ratio(name, f);
                if (score > bestScore) {
                    bestScore = score;
                    best = c[0];
                }
            }
        }
        return bestScore >= 0.85 ? best : null;
    }

    /** Bilyoner normal oranlarının marjı arındırılmış olasılığı adil olasılıktan bu kadar saparsa pazar atılır. */
    static final double MAX_FAIR_GAP = 0.10;

    /**
     * Zirve maçlarının iddaa tarafı, Bilyoner'in normal oranlarından (val): hedefli tarama Nesine bültenine
     * ihtiyaç duymaz (daha hızlı; Nesine'ye ulaşılamasa da çalışır). Yalnızca events'teki maçlar;
     * basketball: basketbol maçları (maç sonucu iki seçenekli, toplam sayı iddaa çizgisinde).
     */
    public static List<Models.BookEvent> books(List<Offer> all, Set<String> events, Set<String> basketball) {
        Map<String, Map<String, Map<String, Double>>> odds = new LinkedHashMap<>();
        Map<String, Offer> first = new LinkedHashMap<>();
        for (Offer o : all) {
            if (!events.contains(o.event)) continue;
            String[] mo = map(o, basketball.contains(o.event));
            if (mo == null) continue;
            if (!first.containsKey(o.event)) {
                first.put(o.event, o);
                odds.put(o.event, new LinkedHashMap<String, Map<String, Double>>());
            }
            Map<String, Double> m = odds.get(o.event).get(mo[0]);
            if (m == null) odds.get(o.event).put(mo[0], m = new LinkedHashMap<>());
            m.put(mo[1], o.val);
        }
        List<Models.BookEvent> out = new ArrayList<>();
        for (Map.Entry<String, Offer> e : first.entrySet()) {
            Offer o = e.getValue();
            Models.BookEvent b = new Models.BookEvent("z" + o.event, o.home, o.away, o.kickoff, o.league, 1, odds.get(e.getKey()), null);
            b.sport = basketball.contains(o.event) ? Models.BASKETBALL : Models.FOOTBALL;
            out.add(b);
        }
        return out;
    }

    /**
     * Eşleme koruması: her pazarda iddaa olasılıkları (marjı oransal arındırılmış) adil olasılığa yakın
     * olmalı; değilse (ör. Alt/Üst ters okunmuş) pazar atılır. En az iki sonucu karşılaştırılamayan pazar
     * da atılır. Atılan pazar sayısını döndürür.
     */
    public static int verify(List<Models.Pair> pairs) {
        int dropped = 0;
        for (Models.Pair p : pairs) {
            for (String market : new ArrayList<>(p.book.odds.keySet())) {
                Map<String, Double> odds = p.book.odds.get(market), fair = Models.fair(p.sharp, market);
                if (fair == null) continue; // adil oranı yok: tabloya zaten girmez
                double imp = 0, fsum = 0;
                int n = 0;
                for (Map.Entry<String, Double> e : odds.entrySet()) {
                    Double f = fair.get(e.getKey());
                    if (f == null || e.getValue() == null || e.getValue() <= 1) continue;
                    imp += 1 / e.getValue();
                    fsum += f;
                    n++;
                }
                boolean ok = n >= 2;
                for (Map.Entry<String, Double> e : odds.entrySet()) {
                    Double f = fair.get(e.getKey());
                    if (!ok || f == null || e.getValue() == null || e.getValue() <= 1) continue;
                    if (Math.abs(1 / e.getValue() * fsum / imp - f) > MAX_FAIR_GAP) ok = false;
                }
                if (!ok) {
                    p.book.odds.remove(market);
                    dropped++;
                }
            }
        }
        return dropped;
    }

    /**
     * Eşleşmiş bir Zirve maçına Karşılıklı Gol seçimleri: iddaa tarafı Bilyoner'in normal KG oranları,
     * adil olasılık sharp'taki KG (maç başına çekilmiş). Eşleme korumasına takılırsa ya da veri yoksa boş.
     */
    public static List<Object> kgSelections(List<Offer> all, String event, Models.SharpEvent sharp) {
        List<Object> out = new ArrayList<>();
        Map<String, Double> fair = sharp.fair.get("KG");
        if (fair == null) return out;
        Map<String, Double> odds = new LinkedHashMap<>();
        Offer any = null;
        for (Offer o : all) {
            if (!o.event.equals(event)) continue;
            String[] mo = map(o, false);
            if (mo != null && "KG".equals(mo[0])) {
                odds.put(mo[1], o.val);
                any = o;
            }
        }
        if (any == null) return out;
        Map<String, Map<String, Double>> m = new LinkedHashMap<>();
        m.put("KG", odds);
        List<Models.Pair> one = new ArrayList<>();
        one.add(new Models.Pair(new Models.BookEvent("z" + event, any.home, any.away, any.kickoff, any.league, 1, m, null), sharp, 1));
        verify(one);
        if (!m.containsKey("KG")) return out;
        for (Map.Entry<String, Double> e : odds.entrySet()) {
            Double p = fair.get(e.getKey());
            if (p == null || !(p > 0 && p < 1)) continue;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("m", "KG");
            s.put("o", e.getKey());
            s.put("label", Models.outcomeLabel("KG", e.getKey()));
            s.put("p", p);
            s.put("i", e.getValue());
            out.add(s);
        }
        return out;
    }

    /**
     * Zirve oranlarını son tam taramanın adil oranlarıyla değerlendirir. Sonuç (arayüz ve bildirim için):
     * {at, fairsAt, offers, matched, evaluated, play, rows: [...]}. Satır durumu (status): oyna, oynama,
     * degisti (iddaa oranı taramadan beri değişmiş ya da eşleme şüpheli), secim (bu seçimin adil oranı yok),
     * pazar (uygulamanın bilmediği pazar), mac (maç son taramada yok).
     */
    public static Map<String, Object> evaluate(List<Offer> offers, List<Object> fairs, String fairsAt, Settings cfg,
                                               long balance, Instant now) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> events = new HashSet<>(), matchedEvents = new HashSet<>();
        int evaluated = 0, play = 0;
        for (Offer o : offers) {
            if (!o.boosted() || !o.kickoff.isAfter(now)) continue; // artırılmamış oran ya da başlamış maç
            events.add(o.event);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", o.id);
            r.put("event", o.event);
            r.put("home", o.home);
            r.put("away", o.away);
            r.put("kickoff", o.kickoff.toString());
            r.put("league", o.league);
            r.put("name", o.name);
            r.put("marketName", o.marketName);
            r.put("val", o.val);
            r.put("tval", o.tval);
            r.put("boost", o.tval / o.val - 1);
            Map<String, Object> row = findRow(o, fairs == null ? new ArrayList<Object>() : fairs);
            String status;
            if (row == null) {
                status = "mac";
                r.put("why", why(o, fairs == null ? new ArrayList<Object>() : fairs));
            } else {
                matchedEvents.add(o.event);
                r.put("ref", Json.str(row, "ref"));
                r.put("code", Json.str(row, "code"));
                String sport = Json.str(row, "sport");
                r.put("sport", sport);
                r.put("sref", Json.str(row, "sref"));
                r.put("fairAt", row.get("at"));
                String[] mo = map(o, sport != null && sport.startsWith("basketball"));
                Map<String, Object> sel = null;
                if (mo != null) {
                    r.put("m", mo[0]);
                    r.put("o", mo[1]);
                    r.put("label", Models.outcomeLabel(mo[0], mo[1]));
                    for (Object so : Json.arr(row.get("sel"))) {
                        Map<String, Object> s = Json.obj(so);
                        if (s != null && mo[0].equals(Json.str(s, "m")) && mo[1].equals(Json.str(s, "o"))) sel = s;
                    }
                }
                if (mo == null) {
                    status = "pazar";
                } else if (sel == null) {
                    status = "secim";
                } else {
                    double p = Json.dbl(sel, "p", 0), i = Json.dbl(sel, "i", 0);
                    r.put("p", p);
                    r.put("i", i);
                    Promo.Check c = Promo.evaluate(p, mo[0], o.tval, cfg, balance);
                    r.put("ev", c.ev);
                    r.put("evNormal", p * o.val - 1);
                    if (i > 1 && Math.abs(o.val / i - 1) > MAX_ODDS_GAP) {
                        status = "degisti";
                    } else {
                        evaluated++;
                        status = c.play ? "oyna" : "oynama";
                        if (c.play) {
                            play++;
                            r.put("stake", c.stake);
                            r.put("fraction", c.fraction);
                        }
                    }
                }
            }
            r.put("status", status);
            rows.add(r);
        }
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                int k = Json.str(a, "kickoff").compareTo(Json.str(b, "kickoff"));
                if (k != 0) return k;
                k = Json.str(a, "event").compareTo(Json.str(b, "event"));
                if (k != 0) return k;
                return Double.compare(Json.dbl(b, "ev", -9), Json.dbl(a, "ev", -9));
            }
        });
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("v", (long) VIEW_VERSION);
        v.put("at", now.toString());
        v.put("fairsAt", fairsAt);
        v.put("events", (long) events.size());
        v.put("offers", (long) rows.size());
        v.put("matched", (long) matchedEvents.size());
        v.put("evaluated", (long) evaluated);
        v.put("play", (long) play);
        v.put("rows", new ArrayList<Object>(rows.subList(0, Math.min(MAX_ROWS, rows.size()))));
        return v;
    }

    /**
     * Daha önce bildirilmemiş oynanabilir Zirve oranları için bildirim [başlık, metin]; yoksa null.
     * Bildirilenler (oran kimliği + Zirve oranı) notified listesine eklenir; oran artarsa yeniden bildirilir.
     */
    public static String[] notice(Map<String, Object> view, List<Object> notified) {
        List<Map<String, Object>> fresh = new ArrayList<>();
        for (Object x : Json.arr(view == null ? null : view.get("rows"))) {
            Map<String, Object> r = Json.obj(x);
            if (r == null || !"oyna".equals(Json.str(r, "status"))) continue;
            String key = Json.str(r, "id") + "@" + Json.dbl(r, "tval", 0);
            if (notified.contains(key)) continue;
            notified.add(key);
            fresh.add(r);
        }
        while (notified.size() > MAX_NOTIFIED) notified.remove(0);
        if (fresh.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> r : fresh) b.append(line(r)).append('\n');
        b.append("Bilyoner'de Zirve Oran olarak oyna; kampanya şartlarını (tekli/kombine, üst sınır) kontrol et.");
        Map<String, Object> first = fresh.get(0);
        String title = fresh.size() == 1 ? "Zirve Oran fırsatı · " + Json.str(first, "home") + " – " + Json.str(first, "away")
                : fresh.size() + " Zirve Oran fırsatı";
        return new String[] {title, b.toString()};
    }

    /** Tek satır özet: "Belçika – Türkiye · MS 1 · Zirve 1,40 (normal 1,37) · adil 1,30 · avantaj +%7,7 · önerilen 150,00 TL". */
    static String line(Map<String, Object> r) {
        StringBuilder b = new StringBuilder();
        b.append(Json.str(r, "home")).append(" – ").append(Json.str(r, "away")).append(" · ")
                .append(r.get("label") != null ? Json.str(r, "label") : Json.str(r, "name"))
                .append(" · Zirve ").append(Fmt.odds(Json.dbl(r, "tval", 0))).append(" (normal ").append(Fmt.odds(Json.dbl(r, "val", 0))).append(')');
        if (r.get("p") != null) {
            b.append(" · adil ").append(Fmt.odds(1 / Json.dbl(r, "p", 1))).append(" · avantaj ").append(Fmt.pct(Json.dbl(r, "ev", 0), true));
        }
        if (r.get("stake") != null) b.append(" · önerilen ").append(Fmt.tl(Json.lng(r, "stake", 0)));
        return b.toString();
    }

    /** Durumun okunur açıklaması. */
    static String statusText(String status) {
        switch (status == null ? "" : status) {
            case "oyna": return "OYNA";
            case "oynama": return "değer yok";
            case "degisti": return "iddaa oranı taramadan beri değişmiş; önce \"Şimdi tara\"";
            case "secim": return "bu seçimin adil oranı yok (pazar taranmadı)";
            case "pazar": return "uygulamanın bilmediği pazar";
            case "mac": return "adil oran yok";
            default: return status;
        }
    }

    /** "Kaynakları test et" için özet. */
    public static String summary(Map<String, Object> view) {
        StringBuilder b = new StringBuilder();
        b.append("  ").append(Json.lng(view, "events", 0)).append(" maçta ").append(Json.lng(view, "offers", 0))
                .append(" artırılmış oran · son taramada eşleşen maç ").append(Json.lng(view, "matched", 0))
                .append(" · değerlendirilen ").append(Json.lng(view, "evaluated", 0)).append(" · değerli ")
                .append(Json.lng(view, "play", 0)).append('\n');
        List<Map<String, Object>> rows = best(view);
        for (Map<String, Object> r : rows.subList(0, Math.min(10, rows.size()))) {
            b.append("  ").append(line(r)).append(" → ").append(statusText(Json.str(r, "status")))
                    .append(r.get("why") != null ? " (" + Json.str(r, "why") + ")" : "").append('\n');
        }
        return b.toString();
    }

    /** Satırlar önem sırasıyla: oynanabilir, değerlendirilen (avantaja göre), diğerleri. */
    static List<Map<String, Object>> best(Map<String, Object> view) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object x : Json.arr(view == null ? null : view.get("rows"))) {
            Map<String, Object> r = Json.obj(x);
            if (r != null) rows.add(r);
        }
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                int k = Integer.compare(rank(Json.str(a, "status")), rank(Json.str(b, "status")));
                return k != 0 ? k : Double.compare(Json.dbl(b, "ev", -9), Json.dbl(a, "ev", -9));
            }
        });
        return rows;
    }

    private static int rank(String status) {
        if ("oyna".equals(status)) return 0;
        if ("oynama".equals(status)) return 1;
        if ("degisti".equals(status)) return 2;
        return 3;
    }
}
