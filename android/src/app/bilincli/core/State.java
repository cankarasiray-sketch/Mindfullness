package app.bilincli.core;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Ledger.Run;
import app.bilincli.core.Ledger.Stats;
import app.bilincli.core.Ledger.Tx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Arayüze (WebView) gönderilen durum JSON'u. Biçimlendirme JavaScript tarafında yapılır. */
public final class State {
    private State() {}

    public static Map<String, Object> build(Ledger ledger, boolean demo, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("demo", demo);
        m.put("now", ledger.now().toString());
        String today = Fmt.dayKey(ledger.now());
        m.put("today", today);
        Stats s = ledger.stats();
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("balance", s.balance);
        st.put("deposited", s.deposited);
        st.put("withdrawn", s.withdrawn);
        st.put("bettingPl", s.bettingPl());
        st.put("roi", s.roi());
        st.put("played", (long) s.played);
        st.put("won", (long) s.won);
        st.put("lost", (long) s.lost);
        st.put("voided", (long) s.voided);
        st.put("expectedWins", s.expectedWins);
        st.put("openStake", s.openStake);
        st.put("suggestedSettled", (long) s.suggestedSettled);
        st.put("suggestedWon", (long) s.suggestedWon);
        m.put("stats", st);
        m.put("todayRun", runMap(ledger.runFor(today)));
        List<Object> coupons = new ArrayList<>();
        for (Coupon c : ledger.coupons()) {
            if (coupons.size() >= 80) break;
            coupons.add(Ledger.couponMap(c));
        }
        m.put("coupons", coupons);
        List<Object> runs = new ArrayList<>();
        for (Run r : ledger.runs(30)) runs.add(runMap(r));
        m.put("runs", runs);
        List<Object> series = new ArrayList<>();
        for (Object[] p : ledger.balanceSeries()) {
            List<Object> point = new ArrayList<>();
            point.add(p[0]);
            point.add(p[1]);
            series.add(point);
        }
        m.put("series", series);
        List<Object> txs = new ArrayList<>();
        List<Tx> all = ledger.transactions();
        for (int i = all.size() - 1; i >= 0 && txs.size() < 60; i--) {
            Tx t = all.get(i);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("ts", t.ts);
            o.put("kind", t.kind);
            o.put("amount", t.amount);
            o.put("note", t.note);
            txs.add(o);
        }
        m.put("transactions", txs);
        m.put("settings", ledger.settings().toMap());
        List<Object> leagues = new ArrayList<>();
        for (String[] l : Settings.KNOWN_LEAGUES) {
            List<Object> pair = new ArrayList<>();
            pair.add(l[0]);
            pair.add(l[1]);
            leagues.add(pair);
        }
        m.put("knownLeagues", leagues);
        if (extra != null) m.putAll(extra);
        return m;
    }

    private static Map<String, Object> runMap(Run r) {
        if (r == null) return null;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("day", r.day);
        o.put("decision", r.decision);
        o.put("reason", r.reason);
        o.put("summary", r.summary);
        o.put("couponId", r.couponId);
        return o;
    }
}
