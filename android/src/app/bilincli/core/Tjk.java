package app.bilincli.core;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2.19: TJK'nın herkese açık AGF tablosu (Altılı Ganyan Favorileri: oyuncuların her ayakta atlara verdiği pay).
 * Resmi bir API değildir; sayfa biçimi değişirse okuma ayrıntısı neyin bulunamadığını gösterir. Ayrıştırma esnektir:
 * ayaklar ayrı tablolar, tek tablonun sütunları ya da satırları olabilir; hücre "5 (%23,45)" biçimindedir. Doğru
 * gruplama, her ayağın payları toplamının %100'e yakın olmasından anlaşılır.
 */
public final class Tjk {
    public static final String HOST = "https://www.tjk.org";
    /** Program sayfası şehir bağlantısı vermezse denenen yerli hipodromlar (TJK şehir numarası = dizi sırası). */
    static final String[] KNOWN = {"", "Adana", "İzmir", "İstanbul", "Bursa", "Ankara", "Şanlıurfa", "Elazığ", "Diyarbakır", "Kocaeli", "Antalya"};
    /** Bir okumada en fazla bu kadar sayfa indirilir (2.32.7: 30 yetmiyordu; yabancı hipodromlu günde akşam ABD altılıları kalıyordu). */
    static final int MAX_REQUESTS = 80;
    /** Bir ayağın payları toplamı bu aralıkta değilse tablo yanlış okunmuştur. */
    static final double MIN_SUM = 60, MAX_SUM = 130;
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
    private static final DateTimeFormatter DMY_COMPACT = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT);

    private Tjk() {}

    public static final class Horse {
        public final String no;
        /** AGF payı (yüzde). */
        public final double pct;

        Horse(String no, double pct) {
            this.no = no;
            this.pct = pct;
        }
    }

    static String programUrl(LocalDate day) {
        return HOST + "/TR/YarisSever/Info/Page/GunlukYarisProgrami?QueryParameter_Tarih=" + day.format(DMY).replace("/", "%2F");
    }

    static String agfUrl(int city, LocalDate day, int altili) {
        return HOST + "/AGFv2/" + city + "/" + day.format(DMY_COMPACT) + "/TR/" + altili + "/1";
    }

    static Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "tr-TR,tr;q=0.9");
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
        return h;
    }

    // ---- metin ----------------------------------------------------------------------------------------------

    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]{1,6}|#\\d{1,7}|[a-zA-Z]{2,8});");

    static String decode(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else if (e.equals("amp")) r = "&";
                else if (e.equals("nbsp")) r = " ";
                else if (e.equals("lt")) r = "<";
                else if (e.equals("gt")) r = ">";
                else if (e.equals("quot")) r = "\"";
                else if (e.equals("apos")) r = "'";
                else r = m.group();
            } catch (IllegalArgumentException ex) {
                r = m.group();
            }
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }

    static String stripScripts(String html) {
        return html.replaceAll("(?is)<(script|style)\\b.*?</\\1\\s*>", " ");
    }

    /** Etiketsiz, tek boşluklu metin. */
    static String text(String html) {
        String s = stripScripts(html).replaceAll("<[^>]*>", " ");
        return decode(s).replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8").trim();
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s.trim();
        }
    }

    // ---- şehirler -------------------------------------------------------------------------------------------

    private static final Pattern LINK = Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>");
    private static final Pattern CITY_ID = Pattern.compile("(?i)[?&]sehirid=(\\d{1,4})");
    private static final Pattern CITY_NAME = Pattern.compile("(?i)[?&]sehiradi=([^&#]*)");
    private static final Pattern ANY_CITY_ID = Pattern.compile("(?i)sehirid[\"'=:\\s]+(\\d{1,4})");

    /** Günün programındaki hipodromlar: şehir numarası → ad (sayfadaki sırayla). */
    static Map<Integer, String> cities(String html) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Matcher m = LINK.matcher(html);
        while (m.find()) {
            String href = decode(m.group(1));
            Matcher id = CITY_ID.matcher(href);
            if (!id.find()) continue;
            int city = Integer.parseInt(id.group(1));
            if (city <= 0 || out.containsKey(city)) continue;
            Matcher nm = CITY_NAME.matcher(href);
            String name = nm.find() ? urlDecode(nm.group(1)) : "";
            if (name.isEmpty()) name = text(m.group(2));
            out.put(city, cityName(city, name));
        }
        if (out.isEmpty()) { // bağlantı yerine veri özniteliği vb.
            Matcher a = ANY_CITY_ID.matcher(html);
            while (a.find()) {
                int city = Integer.parseInt(a.group(1));
                if (city > 0 && !out.containsKey(city)) out.put(city, cityName(city, ""));
            }
        }
        return out;
    }

    private static String cityName(int city, String name) {
        if (!name.isEmpty() && name.length() <= 40) return name;
        return city < KNOWN.length ? KNOWN[city] : "Şehir " + city;
    }

    // ---- AGF tablosu ----------------------------------------------------------------------------------------

    /** "5 (%23,45)", "5(23.45%)", "5 KARAYEL (%23,45)": at numarası ve parantez içinde pay. */
    private static final Pattern ENTRY = Pattern.compile(
            "(?<![\\d.,%])(\\d{1,2})(?:\\s+[^()%\\d]{1,40}?)?\\s*\\(\\s*%?\\s*(\\d{1,3}(?:[.,]\\d{1,3})?)\\s*%?\\s*\\)");
    private static final Pattern NUMBER_CELL = Pattern.compile("^\\d{1,2}$");
    private static final Pattern HORSE_HEADER = Pattern.compile("(?i)^(n|no|at ?no|at numarası|numara)$");
    /** Ayrı hücrede pay: "%23,45", "%23,45(2)" (parantezde sıra), "23,45%". */
    private static final Pattern PCT_CELL = Pattern.compile("^(?:%\\s*(\\d{1,3}(?:[.,]\\d{1,3})?)(?:\\s*\\(\\d{1,2}\\))?|(\\d{1,3}(?:[.,]\\d{1,3})?)\\s*%)$");
    private static final Pattern TABLE_TAG = Pattern.compile("(?i)<(/?)table\\b");
    private static final Pattern AYAK = Pattern.compile("(?i)(?<!\\d)([1-9])\\s*\\.\\s*ayak");

    /** İç içe tablolarda en içtekiler (veri tablosu), sayfadaki sırayla. */
    static List<String> innerTables(String html) {
        List<String> out = new ArrayList<>();
        for (int[] s : innerTableSpans(html)) out.add(html.substring(s[0], s[1]));
        return out;
    }

    /** En içteki tabloların yerleri {başı, sonu} (sonu: kapanış etiketinin başı). */
    static List<int[]> innerTableSpans(String html) {
        List<int[]> out = new ArrayList<>();
        Matcher m = TABLE_TAG.matcher(html);
        int open = -1;
        while (m.find()) {
            if (m.group(1).isEmpty()) open = m.start();
            else if (open >= 0) {
                out.add(new int[] {open, m.start()});
                open = -1;
            }
        }
        return out;
    }

    private static double number(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    private static void add(Map<Integer, List<Horse>> legs, int key, String no, double pct) {
        if (!(pct > 0) || pct > 100) return;
        List<Horse> leg = legs.get(key);
        if (leg == null) legs.put(key, leg = new ArrayList<>());
        leg.add(new Horse(String.valueOf(Integer.parseInt(no)), pct));
    }

    static double sum(List<Horse> leg) {
        double s = 0;
        for (Horse h : leg) s += h.pct;
        return s;
    }

    static boolean plausible(List<Horse> leg) {
        if (leg.size() < 2) return false;
        Set<String> nos = new HashSet<>();
        for (Horse h : leg) if (!nos.add(h.no)) return false;
        double s = sum(leg);
        return s >= MIN_SUM && s <= MAX_SUM;
    }

    /** Gruplama adayının iyiliği: önce akla yatkın ayak sayısı, sonra toplamların %100'e yakınlığı. */
    private static double score(List<List<Horse>> legs) {
        int ok = 0;
        double dev = 0;
        for (List<Horse> l : legs) {
            if (plausible(l)) ok++;
            dev += Math.abs(sum(l) - 100);
        }
        return ok * 1e6 - dev;
    }

    private static List<List<Horse>> atLeastTwo(Map<Integer, List<Horse>> m) {
        List<List<Horse>> out = new ArrayList<>();
        for (List<Horse> l : m.values()) if (l.size() >= 2) out.add(l);
        return out;
    }

    /**
     * Bir tablodaki ayaklar: sütunlar, satırlar, tablonun tamamı (ayrı hücrede at no ve pay) ya da tablodaki bütün
     * girdiler tek ayak (2.32.8: TJK'da sıra numarası olmayan satırların payı bir sütun solda; ayrı ayak sanılmasın) — en iyisi.
     */
    static List<List<Horse>> tableLegs(String table) {
        Map<Integer, List<Horse>> byCol = new TreeMap<>(), byRow = new TreeMap<>(), whole = new TreeMap<>(), all = new TreeMap<>();
        Set<String> inAll = new HashSet<>();
        String[] rows = table.split("(?i)<tr\\b");
        int horseCol = -1; // başlıkta "At No" / "N" sütunu varsa at numarası oradan (yoksa satırın ilk sayısı; sıra sütunu karışmasın)
        for (int r = 1; r < rows.length; r++) {
            String[] cells = rows[r].split("(?i)<t[dh]\\b");
            String horse = null;
            boolean entries = false, done = false;
            for (int c = 1; c < cells.length; c++) {
                String cell = text(cells[c].substring(cells[c].indexOf('>') + 1));
                if (HORSE_HEADER.matcher(cell).matches()) horseCol = c;
                Matcher e = ENTRY.matcher(cell);
                while (e.find()) {
                    double pct = number(e.group(2));
                    add(byCol, c, e.group(1), pct);
                    add(byRow, r, e.group(1), pct);
                    if (inAll.add(String.valueOf(Integer.parseInt(e.group(1))))) add(all, 0, e.group(1), pct);
                    entries = true;
                }
                if (entries || done) continue;
                if (horse == null) {
                    if (NUMBER_CELL.matcher(cell).matches() && (horseCol < 0 || c == horseCol)) horse = cell;
                } else {
                    Matcher p = PCT_CELL.matcher(cell);
                    if (p.matches()) {
                        add(whole, 0, horse, number(p.group(1) != null ? p.group(1) : p.group(2)));
                        done = true;
                    }
                }
            }
        }
        List<List<Horse>> best = new ArrayList<>();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map<Integer, List<Horse>> m : java.util.Arrays.asList(byCol, byRow, whole, all)) {
            List<List<Horse>> legs = atLeastTwo(m);
            if (legs.isEmpty()) continue;
            double s = score(legs);
            if (s > bestScore) {
                bestScore = s;
                best = legs;
            }
        }
        return best;
    }

    /** Tablosuz sayfa: "1. Ayak", "2. Ayak" başlıklarının arasındaki girdiler. */
    static List<List<Horse>> textLegs(String html) {
        String s = text(html);
        List<List<Horse>> out = new ArrayList<>();
        Matcher a = AYAK.matcher(s);
        List<Integer> starts = new ArrayList<>();
        while (a.find()) starts.add(a.end());
        for (int i = 0; i < starts.size(); i++) {
            String part = s.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : s.length());
            Map<Integer, List<Horse>> m = new TreeMap<>();
            Matcher e = ENTRY.matcher(part);
            while (e.find()) add(m, 0, e.group(1), number(e.group(2)));
            out.addAll(atLeastTwo(m));
        }
        return out;
    }

    /** Paya göre azalan; eşitlikte at numarası. */
    private static final java.util.Comparator<Horse> BY_PCT = new java.util.Comparator<Horse>() {
        @Override
        public int compare(Horse x, Horse y) {
            return x.pct != y.pct ? Double.compare(y.pct, x.pct) : Integer.compare(Integer.parseInt(x.no), Integer.parseInt(y.no));
        }
    };

    /** Sayfadaki tüm ayaklar (sırayla); her ayakta atlar paya göre azalan. */
    static List<List<Horse>> parseAgf(String html) {
        String clean = stripScripts(html);
        List<List<Horse>> legs = new ArrayList<>();
        for (String t : innerTables(clean)) legs.addAll(tableLegs(t));
        if (legs.isEmpty()) legs = textLegs(clean);
        for (List<Horse> l : legs) l.sort(BY_PCT);
        // sayfa aynı tabloyu iki kez içeriyorsa (ör. mobil + masaüstü görünümü) tek kopya
        if (legs.size() >= 12 && texts(legs.subList(0, 6)).equals(texts(legs.subList(6, 12)))) {
            legs = new ArrayList<>(legs.subList(0, 6));
        }
        return legs;
    }

    static String pct(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("\\.?0+$", "");
    }

    /** Arayüzün AGF alanı biçimi: "1:32.5 4:20 7:15". */
    static String legText(List<Horse> leg) {
        StringBuilder b = new StringBuilder();
        for (Horse h : leg) b.append(b.length() == 0 ? "" : " ").append(h.no).append(':').append(pct(h.pct));
        return b.toString();
    }

    static List<String> texts(List<List<Horse>> legs) {
        List<String> out = new ArrayList<>();
        for (List<Horse> l : legs) out.add(legText(l));
        return out;
    }

    /**
     * İstenen altılının 6 ayağı; bulunamazsa null ve why[0] nedeni. Sayfa birden çok altılı içeriyorsa (12+ ayak)
     * k. altılı 6(k−1)'inci ayaktan başlar.
     */
    static List<List<Horse>> six(List<List<Horse>> legs, int altili, String[] why) {
        if (legs.size() < 6) {
            why[0] = legs.isEmpty() ? "AGF tablosu bulunamadı" : "yalnızca " + legs.size() + " ayak bulundu";
            return null;
        }
        int from = legs.size() >= 6 * altili ? 6 * (altili - 1) : 0;
        List<List<Horse>> out = new ArrayList<>(legs.subList(from, from + 6));
        for (int i = 0; i < 6; i++) {
            if (!plausible(out.get(i))) {
                why[0] = (i + 1) + ". ayak okunamadı (paylar toplamı %" + pct(sum(out.get(i))) + ", " + out.get(i).size() + " at)";
                return null;
            }
        }
        return out;
    }

    static final Pattern DEVIR_TEXT = Pattern.compile("(?i)(devir|devreden)[^0-9]{0,40}?(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d+(?:,\\d{1,2})?)\\s*(?:TL|₺)");
    static final Pattern POOL_TEXT = Pattern.compile("(?i)(havuz|hasılat|hasilat|toplam bahis)[^0-9]{0,40}?(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d+(?:,\\d{1,2})?)\\s*(?:TL|₺)");

    /** Sayfa metninde "Devir: 1.234.567,89 TL" gibi açık yazılmış tutar (yoksa null). */
    static Double amountNear(String html, Pattern p) {
        Matcher m = p.matcher(text(html));
        if (!m.find()) return null;
        return AtYarisi.amount(m.group(2));
    }

    /** 2.25.3: AGF sayfası var ama paylar boş (sabah erken: yalnızca başlıklar ya da bütün atlar %0). */
    static final String WAITING = "AGF henüz oluşmadı (TJK'da paylar boş; bahisler başlayınca dolar)";

    static boolean notYet(String html) {
        String t = text(stripScripts(html));
        if (!t.toUpperCase(Locale.ROOT).contains("AGF")) return false;
        Matcher e = ENTRY.matcher(t);
        while (e.find()) if (number(e.group(2)) > 0) return false; // payı olan at var: başka bir sorun
        return true;
    }

    /** Hata ayıklama için sayfanın kısa özeti (okuma ayrıntısında paylaşılır). */
    static String excerpt(String html) {
        String clean = stripScripts(html), s = text(clean);
        int at = s.toUpperCase(Locale.ROOT).indexOf("AYAK");
        if (at < 0) at = s.indexOf("AGF");
        String part = at < 0 ? s : s.substring(Math.max(0, at - 40));
        if (part.length() > 220) part = part.substring(0, 220) + "…";
        return html.length() / 1024 + " KB, " + innerTables(clean).size() + " tablo: \"" + part + "\"";
    }

    private static String shortMessage(String m) {
        String s = String.valueOf(m);
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }

    private static void step(Daily.Progress p, String text) {
        if (p != null) p.step(text);
    }

    /**
     * Günün altılılarının AGF'leri: {at, day, altilis: [{city, cityId, no, label, legs: ["1:32 4:20", …]}], log}.
     * Önce program sayfasından o gün koşan hipodromlar, sonra her biri için 1. ve 2. altılının AGF sayfası okunur.
     */
    public static Map<String, Object> read(Http http, LocalDate day, Instant now, Daily.Progress progress) {
        List<Object> log = new ArrayList<>(), altilis = new ArrayList<>();
        int requests = 0, failures = 0, waiting = 0;
        boolean reachable = true;
        Map<Integer, String> cities = new LinkedHashMap<>();
        Map<Integer, String[]> links = new LinkedHashMap<>();
        step(progress, "TJK yarış programı okunuyor…");
        try {
            requests++;
            String html = http.get(programUrl(day), headers()).body;
            cities = cities(html);
            links = cityLinks(html, programUrl(day)); // 2.27: şehir programı (koşu saatleri)
            log.add(cities.isEmpty() ? "Program: şehir bağlantısı bulunamadı (" + excerpt(html) + ")"
                    : "Program: " + String.join(", ", cities.values()));
        } catch (Http.ProviderException e) {
            log.add("Program okunamadı: " + shortMessage(e.getMessage()));
            reachable = String.valueOf(e.getMessage()).contains("-> HTTP "); // bağlantı yoksa diğer sayfalar da açılmaz
        }
        if (reachable && cities.isEmpty()) {
            for (int i = 1; i < KNOWN.length; i++) cities.put(i, KNOWN[i]);
            log.add("Yerli hipodromlar tek tek deneniyor.");
        }
        Set<String> seen = new HashSet<>();
        Map<Integer, Object[]> programs = new LinkedHashMap<>();
        outer:
        for (Map.Entry<Integer, String> c : cities.entrySet()) {
            for (int no = 1; no <= 2; no++) {
                if (requests >= MAX_REQUESTS) {
                    log.add("Sayfa sınırına ulaşıldı (" + MAX_REQUESTS + ").");
                    break outer;
                }
                String label = c.getValue() + " " + no + ". altılı";
                step(progress, label + " AGF okunuyor…");
                List<List<Horse>> legs, parsed;
                String[] why = {null};
                String html;
                try {
                    requests++;
                    html = http.get(agfUrl(c.getKey(), day, no), headers()).body;
                    failures = 0;
                    parsed = parseAgf(html);
                    legs = six(parsed, no, why);
                    if (legs == null && notYet(html)) {
                        why[0] = WAITING;
                        waiting++;
                    } else if (legs == null) why[0] += " (" + excerpt(html) + ")";
                } catch (Http.ProviderException e) {
                    String msg = String.valueOf(e.getMessage());
                    log.add(label + ": " + shortMessage(msg));
                    if (!msg.contains("-> HTTP ") && ++failures >= 2) {
                        log.add("TJK'ya bağlanılamıyor; okuma durduruldu.");
                        break outer;
                    }
                    break;
                }
                if (legs == null) {
                    log.add(label + ": " + why[0]);
                    if (parsed.isEmpty() || WAITING.equals(why[0])) break; // 1. altılı yoksa (ya da AGF yoksa) 2. de yoktur
                    continue; // 2.32.8: 1. altılının tablosu okunamadıysa 2. yine denenir
                }
                List<String> text = texts(legs);
                if (!seen.add(String.join("|", text))) {
                    log.add(label + ": önceki altılıyla aynı tablo, atlandı");
                    break;
                }
                StringBuilder n = new StringBuilder();
                for (List<Horse> l : legs) n.append(n.length() == 0 ? "" : "-").append(l.size());
                log.add(label + ": 6 ayak okundu (at sayıları " + n + ")");
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("key", "tjk/" + day + "/" + c.getKey() + "/" + no); // 2.25.2: bugün ve yarın ayrı
                a.put("day", day.toString());
                a.put("source", "TJK");
                a.put("city", c.getValue());
                a.put("cityId", c.getKey());
                a.put("no", no);
                a.put("label", label);
                a.put("legs", new ArrayList<Object>(text));
                // 2.27: her ayağın koşu numarası ve saati (yanlış koşuya oynanmasın). Önce AGF sayfasındaki ayak başlıkları,
                // sonra şehrin programı (bağlantıdaki sayfa, olmazsa şehir sekmesinin adresi); bulunamazsa nedeni kartta.
                List<Object> races = agfLegRaces(html);
                String raceWhy = null;
                if (races == null && links.containsKey(c.getKey())) {
                    Object[] prog = programs.get(c.getKey());
                    if (prog == null) {
                        step(progress, c.getValue() + " programı okunuyor (koşu saatleri)…");
                        prog = program(http, links.get(c.getKey())[1]);
                        requests += 2;
                        programs.put(c.getKey(), prog);
                    }
                    @SuppressWarnings("unchecked")
                    List<ProgramRace> program = (List<ProgramRace>) prog[0];
                    races = legRaces(legs, program);
                    if (races == null) raceWhy = program.isEmpty() ? (String) prog[1] : matchWhy(legs, program);
                } else if (races == null) {
                    raceWhy = "şehrin program bağlantısı bulunamadı";
                }
                if (races != null) {
                    a.put("races", races);
                    String start = Json.str(Json.obj(races.get(0)), "time");
                    if (start != null) a.put("start", start);
                    log.add(label + ": koşular " + raceText(races));
                } else {
                    a.put("racesWhy", raceWhy);
                    log.add(label + ": koşu saatleri bulunamadı: " + raceWhy);
                }
                Double devir = amountNear(html, DEVIR_TEXT), pool = amountNear(html, POOL_TEXT); // sayfada yazıyorsa
                if (devir != null) a.put("devir", devir);
                if (pool != null) a.put("pool", pool);
                if (devir != null || pool != null) log.add(label + ": devir " + devir + ", havuz " + pool + " (sayfadan)");
                altilis.add(a);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", now.toString());
        out.put("day", day.toString());
        out.put("altilis", altilis);
        out.put("log", log);
        out.put("waiting", (long) waiting);
        return out;
    }

    /**
     * 2.32.7: önceki okumada bulunan, bugünün ya da sonraki günün TJK altılısı yeni okumada yoksa (sayfa sınırı, geçici hata)
     * listeden düşmez: önceki AGF'yle eklenir, "kept" alanında o AGF'nin okunduğu an. Eklenen sayısı.
     */
    public static int keepMissing(List<Object> fresh, Map<String, Object> previous, String today, List<Object> log) {
        if (previous == null) return 0;
        Set<String> keys = new HashSet<>();
        for (Object o : fresh) keys.add(Json.str(Json.obj(o), "key"));
        int n = 0;
        for (Object o : Json.arr(previous.get("altilis"))) {
            Map<String, Object> a = Json.obj(o);
            String key = Json.str(a, "key"), day = Json.str(a, "day");
            if (key == null || !key.startsWith("tjk/") || keys.contains(key) || day == null || day.compareTo(today) < 0) continue;
            Map<String, Object> k = new LinkedHashMap<>(a);
            if (Json.str(k, "kept") == null && Json.str(previous, "at") != null) k.put("kept", Json.str(previous, "at"));
            fresh.add(k);
            keys.add(key);
            n++;
            log.add(Json.str(a, "label") + ": bu okumada yok, önceki okumadaki AGF kullanılıyor");
        }
        return n;
    }

    // ---- 2.27: şehir programı: koşu numarası, saati ve atları --------------------------------------------------

    static final class ProgramRace {
        final int no;
        final String time;
        final Set<String> runners;
        /** 2.27.3: at numarası olabilecek sütunlar (başlıklı sütun önce); eşleşmede en benzeri kullanılır. */
        final List<Set<String>> candidates;

        ProgramRace(int no, String time, Set<String> runners) {
            this(no, time, java.util.Collections.singletonList(runners));
        }

        ProgramRace(int no, String time, List<Set<String>> candidates) {
            this.no = no;
            this.time = time;
            this.runners = candidates.get(0);
            this.candidates = candidates;
        }
    }

    /** Hücre başındaki at numarası: "5", "5A" (eküri), "5 SECRETARIAT (USA)", "5.". */
    private static final Pattern LEAD_NUM = Pattern.compile("^(\\d{1,2})[A-Za-z]?(?:[\\s.)\\-].*)?$");

    /** Tablodaki at numarası adayları: başlıklı sütun, sonra 1–30 arası numaralar taşıyan her sütun. */
    static List<Set<String>> runnerCandidates(String table) {
        List<Set<String>> out = new ArrayList<>();
        List<String> head = runners(table);
        if (head.size() >= 2) out.add(new LinkedHashSet<>(head));
        String[] rows = table.split("(?i)<tr\\b");
        List<List<String>> all = new ArrayList<>();
        int width = 0;
        for (int r = 1; r < rows.length; r++) {
            String[] cells = rows[r].split("(?i)<t[dh]\\b");
            List<String> ts = new ArrayList<>();
            for (int c = 1; c < cells.length; c++) ts.add(text(cells[c].substring(cells[c].indexOf('>') + 1)));
            all.add(ts);
            width = Math.max(width, ts.size());
        }
        for (int c = 0; c < width; c++) {
            Set<String> set = new LinkedHashSet<>();
            int rowsWith = 0;
            for (List<String> ts : all) {
                if (c >= ts.size()) continue;
                rowsWith++;
                Matcher m = LEAD_NUM.matcher(ts.get(c));
                if (!m.matches()) continue;
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= 30) set.add(String.valueOf(n));
            }
            if (set.size() >= 2 && set.size() >= rowsWith * 0.6 && !out.contains(set)) out.add(set);
        }
        return out;
    }

    private static final Pattern RACE_NO = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*\\.\\s*[Kk][Oo][ŞşSs][Uu]");
    private static final Pattern CLOCK_COLON = Pattern.compile("(?<![\\d:.])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])");
    private static final Pattern CLOCK_DOT = Pattern.compile("(?<![\\d:.])([01]?\\d|2[0-3])\\.([0-5]\\d)(?![\\d.])");

    private static String clock(String text) {
        Matcher c = CLOCK_COLON.matcher(text);
        if (!c.find()) {
            c = CLOCK_DOT.matcher(text);
            if (!c.find()) return null;
        }
        return String.format(Locale.ROOT, "%02d:%s", Integer.parseInt(c.group(1)), c.group(2));
    }

    /**
     * Tablodaki atlar: başlıkta "N"/"No"/"At No" sütunu; başlık yoksa (2.27.1) her hücresi farklı bir tam sayı (1–30)
     * olan en dolu sütun (programda atlar numara sırasıyla yazılır).
     */
    static List<String> runners(String table) {
        String[] rows = table.split("(?i)<tr\\b");
        int horseCol = -1;
        List<List<String>> all = new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (int r = 1; r < rows.length; r++) {
            String[] cells = rows[r].split("(?i)<t[dh]\\b");
            List<String> ts = new ArrayList<>();
            for (int c = 1; c < cells.length; c++) ts.add(text(cells[c].substring(cells[c].indexOf('>') + 1)));
            all.add(ts);
            if (horseCol < 0) {
                for (int c = 0; c < ts.size(); c++) if (HORSE_HEADER.matcher(ts.get(c)).matches()) horseCol = c;
                continue;
            }
            if (horseCol < ts.size() && NUMBER_CELL.matcher(ts.get(horseCol)).matches()) out.add(String.valueOf(Integer.parseInt(ts.get(horseCol))));
        }
        if (horseCol >= 0) return out;
        int width = 0;
        for (List<String> ts : all) width = Math.max(width, ts.size());
        List<String> best = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            List<String> col = new ArrayList<>();
            int rowsWith = 0;
            for (List<String> ts : all) {
                if (c >= ts.size()) continue;
                rowsWith++;
                if (NUMBER_CELL.matcher(ts.get(c)).matches()) col.add(String.valueOf(Integer.parseInt(ts.get(c))));
            }
            boolean distinct = new HashSet<>(col).size() == col.size();
            if (distinct && col.size() >= 2 && col.size() >= rowsWith * 0.7 && col.size() > best.size()) best = col;
        }
        return best;
    }

    /**
     * Şehir programındaki koşular: her at tablosu, ondan önceki metinde geçen son "N. Koşu" ve saatle (tablolar arasında
     * taşınır: başlık ayrı tablodaysa da).
     */
    static List<ProgramRace> programRaces(String html) {
        String clean = stripScripts(html);
        List<ProgramRace> out = new ArrayList<>();
        int prev = 0, no = 0;
        String time = null;
        for (int[] span : innerTableSpans(clean)) {
            String gap = text(clean.substring(prev, span[0]));
            Matcher m = RACE_NO.matcher(gap);
            int at = -1;
            while (m.find()) {
                no = Integer.parseInt(m.group(1));
                at = m.end();
                time = null;
            }
            String t = clock(at >= 0 ? gap.substring(at) : gap);
            if (t != null) time = t;
            String table = clean.substring(span[0], span[1]);
            List<Set<String>> rs = runnerCandidates(table);
            if (!rs.isEmpty()) {
                if (time == null) time = clock(text(table.substring(0, Math.min(table.length(), 400)))); // saat tablo başlığında
                out.add(new ProgramRace(no, time, rs));
            } else {
                String inTable = text(table);
                Matcher r = RACE_NO.matcher(inTable); // başlık tablosu: "4. Koşu 14:30"
                while (r.find()) {
                    no = Integer.parseInt(r.group(1));
                    String tt = clock(inTable.substring(r.end()));
                    time = tt;
                }
            }
            prev = span[1];
        }
        return out;
    }

    /** Programın şehir sekmesi adresi (sayfa yerine yalnızca içerik: "/Info/Page/" ↔ "/Info/Sehir/"). */
    static String programAlternative(String url) {
        if (url.contains("/Info/Page/")) return url.replace("/Info/Page/", "/Info/Sehir/");
        if (url.contains("/Info/Sehir/")) return url.replace("/Info/Sehir/", "/Info/Page/");
        return null;
    }

    /** Şehir programındaki koşular ve bulunamazsa nedeni: {List<ProgramRace>, neden}. Önce bağlantı, olmazsa diğer adres. */
    static Object[] program(Http http, String url) {
        String why = null;
        for (String u : new String[] {url, programAlternative(url)}) {
            if (u == null) continue;
            try {
                String body = http.get(u, headers()).body;
                List<ProgramRace> races = programRaces(body);
                if (!races.isEmpty()) return new Object[] {races, null};
                if (why == null) why = "programda koşu tablosu bulunamadı (" + excerpt(body) + ")";
            } catch (Http.ProviderException e) {
                if (why == null) why = "program okunamadı: " + shortMessage(e.getMessage());
            }
        }
        return new Object[] {new ArrayList<ProgramRace>(), why};
    }

    private static final Pattern AYAK_HEAD = Pattern.compile("(?i)(?<!\\d)([1-6])\\s*\\.\\s*ayak");

    /**
     * AGF sayfasının ayak başlıklarında koşu numarası ya da saati yazıyorsa ("1. AYAK (4. Koşu 14:30)"): 6 ayağın
     * {no, time}; 1. ayakta hiçbiri yoksa null.
     */
    static List<Object> agfLegRaces(String html) {
        String t = text(stripScripts(html));
        Map<Integer, Map<String, Object>> found = new TreeMap<>();
        Matcher m = AYAK_HEAD.matcher(t);
        List<int[]> heads = new ArrayList<>();
        while (m.find()) heads.add(new int[] {Integer.parseInt(m.group(1)), m.end(), m.start()});
        for (int i = 0; i < heads.size(); i++) {
            int k = heads.get(i)[0];
            if (found.containsKey(k)) continue;
            int end = Math.min(t.length(), heads.get(i)[1] + 60);
            if (i + 1 < heads.size()) end = Math.min(end, heads.get(i + 1)[2]);
            String seg = t.substring(heads.get(i)[1], end);
            Map<String, Object> r = new LinkedHashMap<>();
            Matcher no = RACE_NO.matcher(seg);
            if (no.find()) r.put("no", Long.parseLong(no.group(1)));
            String time = clock(seg);
            if (time != null) r.put("time", time);
            found.put(k, r);
        }
        Map<String, Object> first = found.get(1);
        if (first == null || first.isEmpty()) return null;
        List<Object> out = new ArrayList<>();
        for (int k = 1; k <= 6; k++) out.add(found.containsKey(k) ? found.get(k) : new LinkedHashMap<String, Object>());
        return out;
    }

    /** Ayakların koşuları ({no, time}) at kümelerinin benzerliğiyle; biri eşleşmezse null. */
    static Set<String> legSet(List<Horse> leg) {
        Set<String> nos = new LinkedHashSet<>();
        for (Horse h : leg) nos.add(h.no);
        return nos;
    }

    /** Ayak ile koşu benzerliği: koşunun at numarası adaylarından en benzeri (Jaccard). */
    static double similarity(Set<String> leg, ProgramRace r) {
        double best = 0;
        for (Set<String> c : r.candidates) {
            Set<String> u = new HashSet<>(leg), x = new HashSet<>(leg);
            u.addAll(c);
            x.retainAll(c);
            best = Math.max(best, u.isEmpty() ? 0 : (double) x.size() / u.size());
        }
        return best;
    }

    /**
     * 2.28.1: ayağın atlarının koşuda bulunma oranı (AGF payı %0 olan atlar AGF listesinde yok: ayak, koşunun alt
     * kümesidir) ve fazlalık (koşuda olup ayakta olmayan atların payı); adaylardan en iyisi {içerme, fazlalık}.
     */
    static double[] containment(Set<String> leg, ProgramRace r) {
        double[] best = {0, 1};
        for (Set<String> c : r.candidates) {
            Set<String> x = new HashSet<>(leg);
            x.retainAll(c);
            double in = leg.isEmpty() ? 0 : (double) x.size() / leg.size(), extra = c.isEmpty() ? 1 : (double) (c.size() - x.size()) / c.size();
            if (in > best[0] + 1e-9 || (Math.abs(in - best[0]) < 1e-9 && extra < best[1])) best = new double[] {in, extra};
        }
        return best;
    }

    private static List<Object> info(List<ProgramRace> six) {
        List<Object> out = new ArrayList<>();
        for (ProgramRace r : six) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (r.no > 0) m.put("no", (long) r.no);
            if (r.time != null) m.put("time", r.time);
            out.add(m);
        }
        return out;
    }

    /**
     * Ayakların koşuları ({no, time}). 2.27.3: altılı art arda 6 koşudur; programda ayaklara toplamda en çok benzeyen art
     * arda 6 koşu seçilir (yalnızca at sayısı aynı olan koşular karışmasın). Olmazsa ayak ayak en benzer koşu; biri
     * eşleşmezse null.
     */
    static List<Object> legRaces(List<List<Horse>> legs, List<ProgramRace> program) {
        int n = program.size();
        int bestO = -1;
        double bestS = 0;
        for (int o = 0; o + 6 <= n; o++) {
            double s = 0, min = 1;
            for (int k = 0; k < 6; k++) {
                double j = similarity(legSet(legs.get(k)), program.get(o + k));
                s += j;
                min = Math.min(min, j);
            }
            if (min >= 0.4 && s / 6 >= 0.6 && s > bestS + 1e-9) {
                bestS = s;
                bestO = o;
            }
        }
        if (bestO >= 0) return info(program.subList(bestO, bestO + 6));
        // 2.28.1: AGF'de payı %0 olan atlar yoksa ayaklar koşuların alt kümesidir: her ayağın atları koşuda bulunmalı
        // (en az %85), art arda 6 koşu arasından en çok içeren, eşitse fazlalığı en az olan seçilir.
        bestS = -1;
        for (int o = 0; o + 6 <= n; o++) {
            double in = 0, extra = 0, min = 1;
            for (int k = 0; k < 6; k++) {
                double[] c = containment(legSet(legs.get(k)), program.get(o + k));
                in += c[0];
                extra += c[1];
                min = Math.min(min, c[0]);
            }
            double s = in - 0.01 * extra;
            if (min >= 0.85 && s > bestS + 1e-9) {
                bestS = s;
                bestO = o;
            }
        }
        if (bestO >= 0) return info(program.subList(bestO, bestO + 6));
        List<ProgramRace> six = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (List<Horse> leg : legs) {
            int best = -1;
            double score = 0.6;
            for (int i = 0; i < n; i++) {
                if (used.contains(i)) continue;
                double j = similarity(legSet(leg), program.get(i));
                if (j >= score) {
                    score = j;
                    best = i;
                }
            }
            if (best < 0) return null;
            used.add(best);
            six.add(program.get(best));
        }
        return info(six);
    }

    private static String list(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        return String.join(",", l.subList(0, Math.min(12, l.size()))) + (l.size() > 12 ? "…" : "");
    }

    /** Eşleşmeme nedeni (düzeltmek için): ayakların ve koşuların at sayıları, 1. ayak ve 1. koşunun atları. */
    static String matchWhy(List<List<Horse>> legs, List<ProgramRace> program) {
        StringBuilder b = new StringBuilder("programdaki " + program.size() + " koşu ayaklarla eşleşmedi (ayak at sayıları ");
        for (int k = 0; k < legs.size(); k++) b.append(k == 0 ? "" : "/").append(legs.get(k).size());
        b.append("; koşular");
        for (ProgramRace r : program) b.append(' ').append(r.no > 0 ? r.no + ":" : "").append(r.runners.size()).append(r.candidates.size() > 1 ? "+" : "");
        b.append("; 1. ayak ").append(list(legSet(legs.get(0))));
        if (!program.isEmpty()) b.append("; 1. koşu ").append(list(program.get(0).runners));
        return b.append(')').toString();
    }

    static String raceText(List<Object> races) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < races.size(); k++) {
            Map<String, Object> r = Json.obj(races.get(k));
            b.append(k == 0 ? "" : ", ").append(r.get("no") == null ? "?" : r.get("no")).append(r.get("time") == null ? "" : " (" + r.get("time") + ")");
        }
        return b.toString();
    }

    // ---- 2.26: sonuçlar (önerilerin tutup tutmadığı ve öğrenme için) -----------------------------------------

    static String resultsUrl(LocalDate day) {
        return HOST + "/TR/YarisSever/Info/Page/GunlukYarisSonuclari?QueryParameter_Tarih=" + day.format(DMY).replace("/", "%2F");
    }

    /** Sonuç sayfasındaki şehir bağlantıları: şehir no → {ad, mutlak adres}. */
    static Map<Integer, String[]> cityLinks(String html, String page) {
        Map<Integer, String[]> out = new LinkedHashMap<>();
        Matcher m = LINK.matcher(html);
        while (m.find()) {
            String href = decode(m.group(1));
            Matcher id = CITY_ID.matcher(href);
            if (!id.find()) continue;
            int city = Integer.parseInt(id.group(1));
            if (city <= 0 || out.containsKey(city)) continue;
            Matcher nm = CITY_NAME.matcher(href);
            String name = nm.find() ? urlDecode(nm.group(1)) : "";
            if (name.isEmpty()) name = text(m.group(2));
            out.put(city, new String[] {cityName(city, name), AtYarisi.safeUrl(AtYarisi.absolute(page, href.trim()))});
        }
        return out;
    }

    /** "6'LI GANYAN 3/5/1/7/2/4 … 12.345,67 TL": kazanan kombinasyon ve (varsa) ikramiye, sayfadaki sırayla. */
    static final Pattern ALTILI_COMBO = Pattern.compile(
            "(?i)(?:6\\s*['’]?\\s*l[ıiİI]|alt[ıi]l[ıi])\\s*ganyan[^0-9]{0,60}?(\\d{1,2}(?:\\s*[/-]\\s*\\d{1,2}){5})(?![\\d/])(?:[^0-9]{0,40}?(\\d{1,3}(?:\\.\\d{3})*,\\d{2}))?");
    private static final Pattern POS_HEADER = Pattern.compile("(?i)^(s|sıra|sira|sn|#|yer|sonuç|sonuc)$");

    /** "(1-2-3-4-5-6. Koşular)": kazanan değil, altılının koşu numaraları. */
    private static final Pattern RACES_AFTER = Pattern.compile("(?i)^\\s*\\.?\\s*(?:ko[şs]u|ayak)");

    /** 2.37: 6 numara ardışık artıyorsa (3, 4, 5, 6, 7, 8) koşu numarasıdır, kazanan değil. */
    static boolean raceNumbers(List<Object> nos) {
        for (int k = 1; k < nos.size(); k++) if (Integer.parseInt(String.valueOf(nos.get(k))) != Integer.parseInt(String.valueOf(nos.get(k - 1))) + 1) return false;
        return true;
    }

    private static final Pattern AMOUNT = Pattern.compile("(?<![\\d.,])(\\d{1,3}(?:\\.\\d{3})*,\\d{2})(?!\\d)");
    private static final Pattern BILEN = Pattern.compile("(?i)(?<!\\d)([1-6])\\s*B[İIiı]LEN");
    private static final Pattern GANYAN = Pattern.compile("(?i)ganyan");

    /**
     * 2.41: kombinasyondan sonraki metinde 6/6 ikramiyesi (sonraki "ganyan"a ya da 160 karaktere kadar). "6 BİLEN …
     * 12.345,67" varsa o (önceden aradaki "6" rakamı yüzünden ikramiye hiç okunamıyordu); başka "N BİLEN" varsa ama 6 yoksa
     * (6 bilen çıkmadı, devir) null; hiç yoksa ilk tutar.
     */
    static Double payoutAfter(String after) {
        int end = Math.min(after.length(), 160);
        Matcher g = GANYAN.matcher(after);
        if (g.find() && g.start() < end) end = g.start();
        String w = after.substring(0, end);
        Matcher b = BILEN.matcher(w);
        List<int[]> marks = new ArrayList<>();
        while (b.find()) marks.add(new int[] {Integer.parseInt(b.group(1)), b.end()});
        if (marks.isEmpty()) {
            Matcher a = AMOUNT.matcher(w);
            return a.find() ? AtYarisi.amount(a.group(1)) : null;
        }
        for (int i = 0; i < marks.size(); i++) {
            if (marks.get(i)[0] != 6) continue;
            int stop = i + 1 < marks.size() ? marks.get(i + 1)[1] : w.length();
            Matcher a = AMOUNT.matcher(w.substring(marks.get(i)[1], Math.max(marks.get(i)[1], stop)));
            if (a.find()) return AtYarisi.amount(a.group(1));
        }
        return null;
    }

    static List<Map<String, Object>> combos(String html) {
        List<Map<String, Object>> out = new ArrayList<>();
        String t = text(html);
        Matcher m = ALTILI_COMBO.matcher(t);
        while (m.find()) {
            List<Object> nos = new ArrayList<>();
            for (String n : m.group(1).split("\\s*[/-]\\s*")) nos.add(String.valueOf(Integer.parseInt(n.trim())));
            if (raceNumbers(nos) || RACES_AFTER.matcher(t.substring(m.end(1))).find()) continue; // 2.37: koşu numaraları
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("nos", nos);
            Double payout = payoutAfter(t.substring(m.end(1)));
            if (payout != null) c.put("payout", payout);
            out.add(c);
        }
        return out;
    }

    /**
     * Koşu tabloları: {no?, runners, winner}. 2.34: koşu numarası tablodan önceki metindeki son "N. Koşu"; at no başlıktaki
     * "N"/"No"/"At No" sütunu, başlık yoksa 1–30 arası farklı numaralar taşıyan, sıra gibi artmayan (1, 2, 3…) en soldaki
     * sütun ("5A" eküri, "5 KARAYEL" de olur). Kazanan: sıra sütununda 1 olan; sıra sütunu yoksa ilk satır (sonuç tabloları
     * bitiriş sırasıyla).
     */
    static List<Map<String, Object>> raceResults(String html) {
        String clean = stripScripts(html);
        List<Map<String, Object>> out = new ArrayList<>();
        int prev = 0, no = 0;
        for (int[] span : innerTableSpans(clean)) {
            Matcher m = RACE_NO.matcher(text(clean.substring(prev, span[0])));
            while (m.find()) no = Integer.parseInt(m.group(1));
            prev = span[1];
            Map<String, Object> race = raceTable(clean.substring(span[0], span[1]));
            if (race == null) continue;
            if (no > 0) race.put("no", (long) no);
            out.add(race);
        }
        return out;
    }

    private static String leadNum(String cell) {
        Matcher m = LEAD_NUM.matcher(cell);
        if (!m.matches()) return null;
        int n = Integer.parseInt(m.group(1));
        return n >= 1 && n <= 30 ? String.valueOf(n) : null;
    }

    private static Map<String, Object> raceTable(String table) {
        String[] rows = table.split("(?i)<tr\\b");
        int horseCol = -1, posCol = -1, width = 0;
        boolean header = false;
        List<String> headCells = new ArrayList<>();
        List<List<String>> data = new ArrayList<>();
        for (int r = 1; r < rows.length; r++) {
            String[] cells = rows[r].split("(?i)<t[dh]\\b");
            List<String> ts = new ArrayList<>();
            for (int c = 1; c < cells.length; c++) ts.add(text(cells[c].substring(cells[c].indexOf('>') + 1)));
            if (!header) {
                int h = -1, p = -1;
                for (int c = 0; c < ts.size(); c++) {
                    if (h < 0 && HORSE_HEADER.matcher(ts.get(c)).matches()) h = c;
                    else if (p < 0 && POS_HEADER.matcher(ts.get(c)).matches()) p = c;
                }
                if (h >= 0 || p >= 0) { // başlık satırı (önceki satırlar başlık üstü yazı)
                    header = true;
                    headCells = ts;
                    horseCol = h;
                    posCol = p;
                    data.clear();
                    continue;
                }
            }
            data.add(ts);
            width = Math.max(width, ts.size());
        }
        String headText = header ? String.join(" | ", headCells) : "başlıksız";
        if (horseCol >= 0) headText += " → at no '" + headCells.get(horseCol) + "'";
        if (horseCol < 0) { // 2.35: başlık yoksa at no yalnızca tek ve açık aday sütundan (start, ganyan sütunu karışmasın)
            List<Integer> found = new ArrayList<>();
            for (int c = 0; c < width; c++) {
                if (c == posCol) continue;
                List<String> col = new ArrayList<>();
                int rowsWith = 0;
                for (List<String> ts : data) {
                    if (c >= ts.size()) continue;
                    rowsWith++;
                    String n = leadNum(ts.get(c));
                    if (n != null) col.add(n);
                }
                if (col.size() < 2 || col.size() < rowsWith * 0.7 || new HashSet<>(col).size() != col.size()) continue;
                boolean rising = true;
                for (int i = 0; i < col.size() && rising; i++) rising = col.get(i).equals(String.valueOf(i + 1));
                if (rising) { // 1, 2, 3…: bitiriş sırası
                    if (posCol < 0) posCol = c;
                    continue;
                }
                found.add(c);
            }
            if (found.size() != 1) return null;
            horseCol = found.get(0);
            headText += " → at no " + (horseCol + 1) + ". sütun";
        }
        if (horseCol < 0) return null;
        List<Object> runners = new ArrayList<>();
        String winner = null, first = null;
        for (List<String> ts : data) {
            if (horseCol >= ts.size()) continue;
            String no = leadNum(ts.get(horseCol));
            if (no == null) continue;
            runners.add(no);
            if (first == null) first = no;
            if (posCol >= 0 && posCol < ts.size() && ts.get(posCol).matches("0*1\\.?")) winner = no;
        }
        if (runners.size() < 2) return null;
        Map<String, Object> race = new LinkedHashMap<>();
        race.put("runners", runners);
        race.put("winner", winner != null ? winner : first);
        race.put("head", headText); // okuma ayrıntısı için: hangi sütunlar, at no nereden
        return race;
    }

    /** Okuma ayrıntısı için: "1→5, 2→7" (koşu → kazanan). */
    private static String winnersText(List<Map<String, Object>> races) {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> r : races) {
            if (r.get("winner") == null) continue;
            b.append(b.length() == 0 ? "" : ", ").append(r.get("no") != null ? r.get("no") + "→" : "").append(r.get("winner"));
        }
        return b.toString();
    }

    /**
     * Günün sonuçları: {cities: [{cityId, city, combos: [{nos, payout?}], races: [{runners, winner}]}], log}. Önce sonuç
     * sayfasından şehirler, sonra her şehrin sonuç sayfası okunur. İstisna fırlatmaz.
     */
    public static Map<String, Object> results(Http http, LocalDate day, Daily.Progress progress) {
        return results(http, day, progress, null);
    }

    /** 2.34: only boş değilse yalnızca bu şehirler (canlı ayak sonuçları için az sayfa). */
    public static Map<String, Object> results(Http http, LocalDate day, Daily.Progress progress, Set<Integer> only) {
        List<Object> log = new ArrayList<>(), cities = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day.toString());
        out.put("cities", cities);
        out.put("log", log);
        step(progress, "TJK sonuçları okunuyor (" + day + ")…");
        String page = resultsUrl(day), html;
        try {
            html = http.get(page, headers()).body;
        } catch (Http.ProviderException e) {
            log.add("Sonuç sayfası okunamadı: " + shortMessage(e.getMessage()));
            return out;
        }
        Map<Integer, String[]> links = cityLinks(html, page);
        if (links.isEmpty()) {
            log.add("Sonuç sayfasında şehir bağlantısı yok (" + excerpt(html) + ")");
            return out;
        }
        int requests = 0;
        for (Map.Entry<Integer, String[]> c : links.entrySet()) {
            if (only != null && !only.isEmpty() && !only.contains(c.getKey())) continue;
            if (++requests > MAX_REQUESTS) break;
            step(progress, c.getValue()[0] + " sonuçları okunuyor…");
            String body = null;
            List<Map<String, Object>> combos = new ArrayList<>(), races = new ArrayList<>();
            // 2.34: şehir bağlantısı sayfa iskeleti olabilir (10 KB, tablosuz): şehir sekmesinin adresi de denenir
            String[] urls = {c.getValue()[1], programAlternative(c.getValue()[1])};
            for (int i = 0; i < urls.length; i++) {
                if (urls[i] == null || !combos.isEmpty() || !races.isEmpty()) continue;
                try {
                    String b = http.get(urls[i], headers()).body;
                    if (i > 0) requests++;
                    body = b;
                    combos = combos(b);
                    races = raceResults(b);
                } catch (Http.ProviderException e) {
                    if (body == null) log.add(c.getValue()[0] + ": " + shortMessage(e.getMessage()));
                }
            }
            if (body == null) continue;
            Map<String, Object> city = new LinkedHashMap<>();
            city.put("cityId", c.getKey());
            city.put("city", c.getValue()[0]);
            city.put("combos", new ArrayList<Object>(combos));
            city.put("races", new ArrayList<Object>(races));
            cities.add(city);
            StringBuilder cs = new StringBuilder(); // 2.37: okunan 6'lı sonuçları ayrıntıda (doğrulamak için)
            for (Map<String, Object> co : combos) {
                cs.append(cs.length() == 0 ? "" : ", ").append(String.join("/", (List) co.get("nos")));
                cs.append(co.get("payout") != null ? " → " + Fmt.num((Double) co.get("payout"), 2) + " TL" : " → ikramiye yok"); // 2.41
            }
            log.add(c.getValue()[0] + ": " + combos.size() + " altılı sonucu" + (combos.isEmpty() ? "" : " (" + cs + ")") + ", " + races.size() + " koşu"
                    + (races.isEmpty() ? "" : " (kazananlar " + winnersText(races) + "; sütunlar: " + races.get(0).get("head") + ")")
                    + (combos.isEmpty() && races.isEmpty() ? " (" + excerpt(body) + ")" : ""));
        }
        return out;
    }
}
