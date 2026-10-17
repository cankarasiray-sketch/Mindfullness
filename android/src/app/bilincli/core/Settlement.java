package app.bilincli.core;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Ledger.Leg;
import app.bilincli.core.Models.ScoreResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maç sonuçlarını çekip açık kuponları sonuçlandırma (Python: bilincli/settlement.py). */
public final class Settlement {
    private Settlement() {}

    public interface ScoreFetcher {
        Map<String, ScoreResult> fetch(Set<String> sportKeys) throws Exception;

        /** Yalnızca süren maçlar için (geçmiş günler gerekmez; The Odds API'de daha ucuz). */
        default Map<String, ScoreResult> fetchLive(Set<String> sportKeys) throws Exception {
            return fetch(sportKeys);
        }
    }

    /** Oynanan, maç sürerken kesinleşebilen bacak varken canlı skor bu aralıkla sorulur (2.16.4). */
    public static final long LIVE_CHECK_S = 15 * 60;

    /**
     * Maç sürerken kesinleşen sonuç (WON / LOST; kesinleşmediyse null). Gol (basketbolda sayı) yalnızca artar:
     * çizgi aşılınca Üst tutmuş (2.16.4), Alt kaybetmiştir (2.16.5); iki takım da gol atınca KG Var tutmuş,
     * KG Yok kaybetmiştir. Maçın kalanı bu sonucu değiştiremez.
     */
    public static String liveResult(String market, String outcome, int home, int away) {
        if ("KG".equals(market)) {
            if (home == 0 || away == 0) return null;
            return "VAR".equals(outcome) ? Models.WON : "YOK".equals(outcome) ? Models.LOST : null;
        }
        boolean passed;
        if ("AU25".equals(market)) passed = home + away >= 3;
        else if (market.startsWith("AU@") || market.startsWith("BT@")) passed = home + away > Models.line(market);
        else if (market.startsWith("EVG@")) passed = home > Models.line(market);
        else if (market.startsWith("DEPG@")) passed = away > Models.line(market);
        else return null;
        if (!passed) return null;
        return "UST".equals(outcome) ? Models.WON : "ALT".equals(outcome) ? Models.LOST : null;
    }

    /** Maç sürerken kesinleşen kazanç (2.16.4). */
    public static boolean liveWon(String market, String outcome, int home, int away) {
        return Models.WON.equals(liveResult(market, outcome, home, away));
    }

    /** Maç sürerken kesinleşip son skorla henüz doğrulanmamış bacak var mı. */
    public static boolean hasEarly(Ledger ledger) {
        for (Coupon c : ledger.coupons()) for (Leg l : c.legs) if (l.early) return true;
        return false;
    }

    /** Maç sürerken kesinleşebilen pazar mı: Üst ve Alt türleri (2.16.5: Alt da), KG Var / Yok. */
    static boolean liveCapable(Leg l) {
        String m = l.market;
        if ("UST".equals(l.outcome) || "ALT".equals(l.outcome)) {
            return "AU25".equals(m) || m.startsWith("AU@") || m.startsWith("BT@") || m.startsWith("EVG@") || m.startsWith("DEPG@");
        }
        return "KG".equals(m) && ("VAR".equals(l.outcome) || "YOK".equals(l.outcome));
    }

    /** Bacak canlıda kazanarak mı (Üst, KG Var) yoksa kaybederek mi (Alt, KG Yok) kesinleşir. */
    static boolean winsLive(Leg l) {
        return "UST".equals(l.outcome) || "VAR".equals(l.outcome);
    }

    /** Oynanan kuponda maçı başlamış, sonucu henüz alınamayan ve canlı kesinleşebilen bacak. */
    static boolean inPlay(Coupon c, Leg l, Instant now) {
        return c.played && l.result == null && l.sportKey != null && liveCapable(l)
                && !Instant.parse(l.kickoff).isAfter(now) && resultDue(l.kickoff, l.sportKey).isAfter(now);
    }

    /** Maç bitince sonuç bu süre boyunca 15 dakikada bir sorulur (2.16.6); sonra olağan kontroller. */
    static final long FINAL_WINDOW_S = 3 * 3600;

    /**
     * Sıradaki skor kontrolü, oynanan kuponların sonuçlanmamış bacakları için 15 dakikada bir:
     * canlı kesinleşebilen bacakta (Üst/Alt, KG) maç başlamadan 15 dk sonra başlar (2.16.4); maç sonucu, çifte şans
     * gibi maç bitmeden kesinleşmeyen bacakta maç bitiminde (resultDue) başlar (2.16.6). Her bacak için maç
     * bitiminden FINAL_WINDOW_S sonrasına kadar sürer (erteleme vb.; sonra olağan kontroller). Yoksa null.
     * last: son kontrol (yoksa null).
     */
    public static Instant nextLiveCheck(Ledger ledger, Instant now, Instant last) {
        Instant best = null;
        for (Coupon c : ledger.openCoupons()) {
            if (!c.played) continue;
            for (Leg l : c.legs) {
                if (l.result != null || l.sportKey == null) continue;
                Instant end = resultDue(l.kickoff, l.sportKey);
                Instant start = liveCapable(l) ? Instant.parse(l.kickoff).plusSeconds(LIVE_CHECK_S) : end;
                if (!end.plusSeconds(FINAL_WINDOW_S).isAfter(now)) continue; // çok eski: olağan sonuç kontrolü
                Instant t = start.isAfter(now) ? start
                        : last == null || !last.plusSeconds(LIVE_CHECK_S).isAfter(now) ? now : last.plusSeconds(LIVE_CHECK_S);
                if (best == null || t.isBefore(best)) best = t;
            }
        }
        return best;
    }

    /** Basketbol: maç başlangıcından bu kadar sonra skor sorulur. */
    static final long RESULT_DELAY_S = 2 * 3600 + 15 * 60;
    /**
     * Futbol (2.15.1): 90 dk + devre arası + uzatma ≈ 1 sa 53 dk; maç biter bitmez yapılan sonuç kontrolü
     * boşa dönmesin diye 2 sa 15 dk yerine 1 sa 55 dk. Skor henüz yayımlanmadıysa sonraki kontrolde.
     */
    static final long FOOTBALL_RESULT_DELAY_S = 3600 + 55 * 60;
    static final long MANUAL_AFTER_S = 3 * 24 * 3600;

    /** Maçın skorunun sorulabileceği an. */
    public static Instant resultDue(String kickoff, String sportKey) {
        return Instant.parse(kickoff).plusSeconds(Settings.isBasketball(sportKey) ? RESULT_DELAY_S : FOOTBALL_RESULT_DELAY_S);
    }

    public static String legResult(String market, String outcome, int home, int away) {
        String actual;
        if ("MS".equals(market)) actual = home > away ? "1" : away > home ? "2" : "X";
        else if (market.startsWith("BT@")) {
            // basketbol toplam sayı (uzatmalar dahil); çizgi tam sayıysa ve tutarsa iade
            double total = home + away, line = Models.line(market);
            if (total == line) return Models.VOID;
            actual = total > line ? "UST" : "ALT";
        }
        else if (market.startsWith("BH@")) {
            // basketbol handikap (uzatmalar dahil): ev sahibinin çizgisi; tam sayı çizgide eşitlik iade
            double diff = home + Models.line(market) - away;
            if (diff == 0) return Models.VOID;
            actual = diff > 0 ? "1" : "2";
        }
        else if ("BS".equals(market)) {
            // basketbol maç sonucu uzatmalar dahildir; skor eşitse veri eksiktir, bacak iade sayılır
            if (home == away) return Models.VOID;
            actual = home > away ? "1" : "2";
        }
        else if ("AU25".equals(market)) actual = home + away >= 3 ? "UST" : "ALT";
        else if (market.startsWith("AU@") || market.startsWith("EVG@") || market.startsWith("DEPG@")) {
            // 2.10: x,5 çizgili gol Alt/Üst (toplam, ev, deplasman)
            int goals = market.startsWith("AU@") ? home + away : market.startsWith("EVG@") ? home : away;
            actual = goals < Models.line(market) ? "ALT" : "UST";
        }
        else if (market.startsWith("HMS@")) {
            // handikaplı maç sonucu: çizgi ev sahibine eklenen gol, eşitlik X
            double d = home + Models.line(market) - away;
            actual = d > 0 ? "1" : d < 0 ? "2" : "X";
        }
        else if ("KG".equals(market)) actual = home > 0 && away > 0 ? "VAR" : "YOK";
        else if ("CS".equals(market)) {
            // Çifte Şans: iki sonucu birden kapsar
            boolean won = "1X".equals(outcome) ? home >= away : "12".equals(outcome) ? home != away
                    : "X2".equals(outcome) ? away >= home : false;
            return won ? Models.WON : Models.LOST;
        }
        else throw new IllegalArgumentException("bilinmeyen pazar: " + market);
        return outcome.equals(actual) ? Models.WON : Models.LOST;
    }

    /**
     * Settled kuponlar için kullanıcıya gösterilecek mesajları döndürür. 2.16.4: maçı süren oynanan bacaklar da
     * sorulur; Üst / KG Var canlı skorla kesinleşince hemen tuttu sayılır (kupon tekliyse hemen ödenir) ve maç
     * bitince son skorla doğrulanır; tutmadıysa (gol iptali) kupon düzeltilir.
     */
    public static List<String> settleOpen(Ledger ledger, ScoreFetcher fetcher, Instant now) throws Exception {
        List<String> messages = new ArrayList<>();
        List<Coupon> coupons = ledger.openCoupons();
        List<Object[]> due = new ArrayList<>(), live = new ArrayList<>(), verify = new ArrayList<>();
        for (Coupon c : coupons) {
            for (Leg l : c.legs) {
                if (l.result != null) continue;
                if (!resultDue(l.kickoff, l.sportKey).isAfter(now)) due.add(new Object[] {c, l});
                else if (inPlay(c, l, now)) live.add(new Object[] {c, l});
            }
        }
        for (Coupon c : ledger.coupons()) {
            for (Leg l : c.legs) {
                if (l.early && !resultDue(l.kickoff, l.sportKey).isAfter(now)) verify.add(new Object[] {c, l});
            }
        }
        if (due.isEmpty() && live.isEmpty() && verify.isEmpty()) return messages;
        Set<String> sports = new LinkedHashSet<>();
        for (List<Object[]> list : java.util.Arrays.asList(due, live, verify)) {
            for (Object[] d : list) {
                String sk = ((Leg) d[1]).sportKey;
                if (sk != null) sports.add(sk);
            }
        }
        Map<String, ScoreResult> scores = sports.isEmpty() ? new java.util.HashMap<String, ScoreResult>()
                : due.isEmpty() && verify.isEmpty() ? fetcher.fetchLive(sports) : fetcher.fetch(sports);
        for (Object[] d : due) {
            Coupon c = (Coupon) d[0];
            Leg l = (Leg) d[1];
            ScoreResult s = scores.get(l.sharpRef);
            if (s != null && s.completed && s.home != null && s.away != null) {
                ledger.setLegResult(c.id, l.position, legResult(l.market, l.outcome, s.home, s.away),
                        s.home + "-" + s.away);
            } else if (s != null && s.home != null && s.away != null && c.played && liveCapable(l)
                    && liveResult(l.market, l.outcome, s.home, s.away) != null) {
                markLive(ledger, c, l, s, liveResult(l.market, l.outcome, s.home, s.away)); // uzatmalar / gecikme: maç bitmeden kesinleşti
            } else if (!Instant.parse(l.kickoff).plusSeconds(MANUAL_AFTER_S).isAfter(now)) {
                messages.add("#" + c.id + " " + l.home + " - " + l.away
                        + ": sonuç bulunamadı (ertelenmiş olabilir). Kuponda maçın sonucunu elle gir.");
            }
        }
        for (Object[] d : live) {
            Coupon c = (Coupon) d[0];
            Leg l = (Leg) d[1];
            ScoreResult s = scores.get(l.sharpRef);
            String res = s == null || s.home == null || s.away == null ? null : liveResult(l.market, l.outcome, s.home, s.away);
            if (res != null) markLive(ledger, c, l, s, res);
        }
        for (Object[] d : verify) {
            Coupon c = (Coupon) d[0];
            Leg l = (Leg) d[1];
            ScoreResult s = scores.get(l.sharpRef);
            if (s != null && s.completed && s.home != null && s.away != null) {
                String fin = legResult(l.market, l.outcome, s.home, s.away), was = l.result;
                if (fin.equals(was)) {
                    ledger.setLegResult(c.id, l.position, fin, s.home + "-" + s.away);
                    ledger.markEarly(c.id, l.position, false); // doğrulandı
                } else {
                    boolean paid = c.payout != null && c.payout > 0;
                    String outcome = ledger.correctLeg(c.id, l.position, fin, s.home + "-" + s.away);
                    messages.add("Kupon #" + c.id + " düzeltildi: " + l.home + " – " + l.away + " maç sürerken "
                            + (Models.WON.equals(was) ? "tuttu" : "yattı") + " sayılmıştı, son skor " + s.home + "-" + s.away + " (gol iptal edilmiş olabilir)"
                            + (outcome == null ? "; diğer maçlar bekleniyor."
                            : Models.LOST.equals(outcome) ? (paid ? "; kupon yattı, ödeme geri alındı." : "; kupon yattı.")
                            : Models.WON.equals(outcome) ? "; kupon tuttu, ödeme " + Fmt.tl(ledger.coupon(c.id).payout) + "." : "; kupon iade oldu."));
                }
            } else if (!Instant.parse(l.kickoff).plusSeconds(MANUAL_AFTER_S).isAfter(now)) {
                ledger.markEarly(c.id, l.position, false); // son skor yok: canlı sonuç kalır
            }
        }
        for (Coupon c : coupons) {
            String outcome = ledger.settleCoupon(c.id);
            if (outcome == null) continue;
            String kind = c.played ? "Oynanan" : "Oynanmayan öneri";
            if (Models.WON.equals(outcome)) {
                String extra = c.played ? ", ödeme " + Fmt.tl(ledger.coupon(c.id).payout) : "";
                String livePart = "";
                for (Leg l : ledger.coupon(c.id).legs) if (l.early) livePart = " Maç sürerken kesinleşti (" + l.home + " – " + l.away + ", " + l.score + ").";
                messages.add(kind + " kupon #" + c.id + " TUTTU (oran " + Fmt.odds(c.totalOdds) + extra + ")." + livePart);
            } else if (Models.LOST.equals(outcome)) {
                String livePart = "";
                for (Leg l : ledger.coupon(c.id).legs) {
                    if (l.early && Models.LOST.equals(l.result)) livePart = " Maç sürerken kesinleşti (" + l.home + " – " + l.away + ", " + l.score + ").";
                }
                messages.add(kind + " kupon #" + c.id + " yattı." + livePart);
            } else {
                messages.add(kind + " kupon #" + c.id + " iade oldu.");
            }
        }
        return messages;
    }

    private static void markLive(Ledger ledger, Coupon c, Leg l, ScoreResult s, String result) {
        ledger.setLegResult(c.id, l.position, result, s.home + "-" + s.away + " (canlı)");
        ledger.markEarly(c.id, l.position, true);
    }

    /**
     * Elle sonuç kontrolü (2.15.1): oynanan açık kuponlarda sonucu henüz işlenmeyen maçlar ve nedeni
     * (maç bitmedi -> ne zaman sorulacağı; bitti ama skor yayımlanmadı -> biraz sonra yeniden dene).
     */
    public static List<String> waiting(Ledger ledger, Instant now) {
        List<String> out = new ArrayList<>();
        for (Coupon c : ledger.openCoupons()) {
            if (!c.played) continue;
            for (Leg l : c.legs) {
                if (l.result != null) continue;
                Instant due = resultDue(l.kickoff, l.sportKey);
                String head = "#" + c.id + " " + l.home + " – " + l.away + ": ";
                if (due.isAfter(now) && inPlay(c, l, now)) out.add(head + "maç sürüyor; " + Models.outcomeLabel(l.market, l.outcome)
                        + (winsLive(l) ? " tutunca hemen işlenir, değilse sonuç " : " kaybederse hemen işlenir, tutarsa sonuç ")
                        + Fmt.localTime(due.toString()) + " sonrası alınır.");
                else if (due.isAfter(now)) out.add(head + "maç bitmedi, sonuç " + Fmt.localTime(due.toString()) + " sonrası alınır.");
                else if (l.sportKey == null) out.add(head + "maçın ligi bilinmiyor; sonucu kuponda elle gir.");
                else out.add(head + "skor henüz yayımlanmadı, birkaç dakika sonra yeniden dene.");
            }
        }
        return out;
    }

    /**
     * Sonuç kontrolü düğmesinin mesajı: sonuçlananlar, skor çekilemeyen ligler ve (hiçbir şey
     * sonuçlanmadıysa) bekleyen maçların nedeni. Eskiden bu durumların hepsi "Sonuçlanacak maç yok" idi.
     */
    public static String report(List<String> settled, List<String> warnings, Ledger ledger, Instant now) {
        List<String> out = new ArrayList<>(settled);
        for (String w : warnings) out.add("Uyarı: " + w);
        if (settled.isEmpty()) out.addAll(waiting(ledger, now));
        return out.isEmpty() ? "Sonuç bekleyen oynanmış kupon yok." : String.join("\n", out);
    }
}
