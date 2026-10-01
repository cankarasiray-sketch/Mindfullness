package app.bilincli.core;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Ledger.Tx;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Kasa koruma kuralları (Python: bilincli/guard.py): haftalık kayıp limiti ve
 * zarardayken para eklenince kovalama beklemesi.
 */
public final class Guard {
    private Guard() {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.ROOT);

    public static Instant weekStart(Instant now) {
        OffsetDateTime local = now.atOffset(Fmt.TR);
        OffsetDateTime start = local.minusDays(local.getDayOfWeek().getValue() - 1)
                .withHour(6).withMinute(0).withSecond(0).withNano(0);
        if (start.isAfter(local)) start = start.minusDays(7);
        return start.toInstant();
    }

    public static String check(Ledger ledger, Settings cfg, Instant now) {
        List<Tx> rows = ledger.transactions();
        if (cfg.weeklyLossLimit > 0) {
            Instant start = weekStart(now);
            long opening = 0, weekPl = 0, openStake = 0;
            for (Tx t : rows) {
                Instant ts = Instant.parse(t.ts);
                if (ts.isBefore(start)) opening += t.amount;
                else if ("stake".equals(t.kind) || "payout".equals(t.kind)) weekPl += t.amount;
            }
            for (Coupon c : ledger.openCoupons()) {
                if (c.played && c.playedAt != null && !Instant.parse(c.playedAt).isBefore(start)) openStake += c.stake;
            }
            long realized = weekPl + openStake;
            if (opening > 0 && -realized >= cfg.weeklyLossLimit * opening) {
                Instant resume = start.plusSeconds(7 * 24 * 3600L);
                return "Haftalık kayıp limiti doldu (" + Fmt.tl(-realized) + " / hafta başı kasa "
                        + Fmt.tl(opening) + "). Yeni kupon " + resume.atOffset(Fmt.TR).format(FMT) + "'de.";
            }
        }
        if (cfg.chaseCooldownHours > 0) {
            long cooldown = Math.round(cfg.chaseCooldownHours * 3600);
            Instant cutoff = now.minusSeconds(cooldown);
            List<Coupon> coupons = ledger.coupons();
            for (Tx t : rows) {
                if (!"deposit".equals(t.kind)) continue;
                Instant ts = Instant.parse(t.ts);
                if (ts.isBefore(cutoff)) continue;
                long pl = 0;
                for (Coupon c : coupons) {
                    if (c.played && c.result != null && !Instant.parse(c.settledAt).isAfter(ts)) {
                        pl += (c.payout == null ? 0 : c.payout) - c.stake;
                    }
                }
                if (pl < 0) {
                    Instant resume = ts.plusSeconds(cooldown);
                    return "Zarardayken kasaya para eklendi. Kovalama beklemesi "
                            + resume.atOffset(Fmt.TR).format(FMT) + "'e kadar sürüyor.";
                }
            }
        }
        return null;
    }
}
