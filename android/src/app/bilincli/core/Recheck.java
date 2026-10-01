package app.bilincli.core;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Ledger.Leg;
import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Oynamadan önce kontrol": sabah üretilen kuponu güncel iddaa ve Pinnacle oranlarıyla
 * yeniden değerlendirir. Oranlar saatler içinde değişir; avantaj kaybolduysa kupon oynanmamalı.
 */
public final class Recheck {
    private Recheck() {}

    /** Kupon oynanabilmesi için ilk maça en az bu kadar süre kalmalı. */
    static final long MIN_LEAD_S = 5 * 60;
    /** Bu süreden eski kontrol "bayat" sayılır. */
    public static final long FRESH_S = 30 * 60;

    /** Oynanmamış öneri, ilk maçtan bu kadar önce otomatik kontrol edilir ve bildirilir. */
    public static final long PRE_S = 90 * 60;

    static Instant firstKickoff(Coupon c) {
        Instant first = null;
        for (Leg l : c.legs) {
            Instant k = Instant.parse(l.kickoff);
            if (first == null || k.isBefore(first)) first = k;
        }
        return first;
    }

    /** Otomatik maç öncesi kontrol gereken kupon (yoksa null). */
    public static Coupon dueForPrecheck(Ledger ledger, Instant now) {
        for (Coupon c : ledger.openCoupons()) {
            if (c.played || c.legs.isEmpty()) continue;
            Instant first = firstKickoff(c);
            Instant at = first.minusSeconds(PRE_S);
            if (now.isBefore(at) || !first.isAfter(now.plusSeconds(MIN_LEAD_S))) continue;
            String last = c.lastCheck == null ? null : Json.str(c.lastCheck, "at");
            if (last == null || Instant.parse(last).isBefore(at)) return c;
        }
        return null;
    }

    /** Şu an otomatik kontrolü gelmiş tüm kuponlar. */
    public static List<Coupon> allDueForPrecheck(Ledger ledger, Instant now) {
        List<Coupon> out = new ArrayList<>();
        for (Coupon c : ledger.openCoupons()) {
            if (c.played || c.legs.isEmpty()) continue;
            Instant first = firstKickoff(c);
            Instant at = first.minusSeconds(PRE_S);
            if (now.isBefore(at) || !first.isAfter(now.plusSeconds(MIN_LEAD_S))) continue;
            String last = c.lastCheck == null ? null : Json.str(c.lastCheck, "at");
            if (last == null || Instant.parse(last).isBefore(at)) out.add(c);
        }
        return out;
    }

    /** Bir sonraki otomatik kontrol zamanı (yoksa null). */
    public static Instant nextPrecheckTime(Ledger ledger, Instant now) {
        Instant best = null;
        for (Coupon c : ledger.openCoupons()) {
            if (c.played || c.legs.isEmpty()) continue;
            Instant at = firstKickoff(c).minusSeconds(PRE_S);
            if (!at.isAfter(now)) continue;
            if (best == null || at.isBefore(best)) best = at;
        }
        return best;
    }

    public static boolean isFresh(Map<String, Object> check, Instant now) {
        if (check == null || Json.str(check, "at") == null) return false;
        return !Instant.parse(Json.str(check, "at")).plusSeconds(FRESH_S).isBefore(now);
    }

    public static Map<String, Object> run(Coupon c, List<BookEvent> book, List<SharpEvent> sharp, Instant now,
                                          Settings cfg, long balance) {
        Map<String, BookEvent> bookByRef = new LinkedHashMap<>();
        for (BookEvent b : book) bookByRef.put(b.ref, b);
        Map<String, SharpEvent> sharpByRef = new LinkedHashMap<>();
        for (SharpEvent s : sharp) sharpByRef.put(s.ref, s);

        List<Object> legs = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        double prob = 1, odds = 1;
        boolean allOk = true;
        for (Leg l : c.legs) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("position", (long) l.position);
            r.put("oddsThen", l.odds);
            String status = "ok";
            String where = l.position + ". maç (" + l.home + " - " + l.away + ")";
            BookEvent b = bookByRef.get(l.bookRef);
            SharpEvent s = sharpByRef.get(l.sharpRef);
            if (!Instant.parse(l.kickoff).isAfter(now.plusSeconds(MIN_LEAD_S))) {
                status = "started";
                problems.add(where + " başladı ya da başlamak üzere");
            } else if (b == null || b.odds.get(l.market) == null || b.odds.get(l.market).get(l.outcome) == null) {
                status = "not_found";
                problems.add(where + " iddaa bülteninde yok (kaldırılmış ya da askıda olabilir)");
            } else if (s == null || s.fair.get(l.market) == null || s.fair.get(l.market).get(l.outcome) == null) {
                status = "no_fair";
                problems.add(where + " için güncel Pinnacle oranı yok");
            } else {
                double o = b.odds.get(l.market).get(l.outcome);
                double f = s.fair.get(l.market).get(l.outcome);
                // karar kalibre olasılıkla; kayıt (fairNow) ham adil olasılıkla
                double fu = cfg.edgeRatios == null ? f
                        : EdgeCalibration.adjust(f, o, EdgeCalibration.ratio(cfg.edgeRatios, l.market));
                double ev = fu * o - 1;
                int mbs = b.mbsFor(l.market);
                r.put("oddsNow", o);
                r.put("fairNow", f);
                r.put("evNow", ev);
                r.put("mbsNow", (long) mbs);
                prob *= fu;
                odds *= o;
                if (ev < cfg.minLegEv) {
                    status = "edge_lost";
                    problems.add(where + ": avantaj kayboldu (iddaa " + Fmt.odds(l.odds) + " → " + Fmt.odds(o)
                            + ", avantaj " + Fmt.pct(ev, true) + ")");
                } else if (mbs > c.legs.size()) {
                    status = "mbs";
                    problems.add(where + ": MBS " + mbs + " oldu, bu kuponla oynanamaz");
                }
            }
            if (!"ok".equals(status)) allOk = false;
            r.put("status", status);
            legs.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", now.toString());
        out.put("legs", legs);
        boolean playable = false;
        String verdict;
        if (allOk) {
            double ev = prob * odds - 1;
            out.put("odds", odds);
            out.put("prob", prob);
            out.put("ev", ev);
            if (ev < cfg.minCouponEv) {
                verdict = "Kuponun toplam avantajı " + Fmt.pct(ev, true) + "'a düştü (eşik " + Fmt.pct(cfg.minCouponEv, true)
                        + "). Bu kuponu oynama; güncel oranlarla yeni kupon üret.";
            } else if (prob < cfg.minWinProb) {
                verdict = "Tutma olasılığı " + Fmt.pct(prob, false) + "'a düştü. Bu kuponu oynama.";
            } else {
                // Günlük üst sınır küçültmesi korunur (aynı gün birden fazla kupon)
                double fraction = Math.min(cfg.kellyMultiplier * OddsMath.kellyFraction(prob, odds), cfg.maxStakeFraction)
                        * (c.scale > 0 ? c.scale : 1.0);
                String[] note = new String[1];
                long stake = Daily.computeStake(balance, fraction, cfg, note)[0];
                out.put("stake", stake);
                playable = true;
                verdict = "Güncel oranlarla hâlâ avantajlı: oran " + Fmt.odds(odds) + ", tutma olasılığı "
                        + Fmt.pct(prob, false) + ", avantaj " + Fmt.pct(ev, true)
                        + (stake > 0 ? ". Önerilen tutar " + Fmt.tl(stake) + "." : ". " + note[0]);
            }
        } else {
            verdict = String.join("; ", problems) + ". Bu kuponu oynama; güncel oranlarla yeni kupon üret.";
        }
        out.put("playable", playable);
        out.put("verdict", verdict);
        return out;
    }

    /** Kontrol sonucundan oynama için bacak oranları ve olasılıkları. [0] oranlar, [1] olasılıklar. */
    public static List<List<Double>> checkedValues(Map<String, Object> check) {
        List<Double> odds = new ArrayList<>(), fairs = new ArrayList<>();
        for (Object o : Json.arr(check.get("legs"))) {
            Map<String, Object> l = Json.obj(o);
            odds.add(Json.dbl(l, "oddsNow", 0));
            fairs.add(Json.dbl(l, "fairNow", 0));
        }
        List<List<Double>> out = new ArrayList<>();
        out.add(odds);
        out.add(fairs);
        return out;
    }
}
