"""Kasa defteri: para hareketleri, kuponlar ve bacaklar (SQLite).

Tutarlar kuruş cinsinden tam sayı tutulur; kayan nokta yuvarlama hatası olmaz.
Kasa bakiyesi her zaman hareketlerin toplamıdır, ayrıca saklanmaz.
"""

from __future__ import annotations

import math
import re
import sqlite3
from collections.abc import Callable
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from .models import LOST, VOID, WON, Proposal

SCHEMA = """
CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY,
    ts TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('deposit', 'withdraw', 'stake', 'payout')),
    amount INTEGER NOT NULL,
    coupon_id INTEGER REFERENCES coupons(id),
    note TEXT
);
CREATE TABLE IF NOT EXISTS coupons (
    id INTEGER PRIMARY KEY,
    created_at TEXT NOT NULL,
    day TEXT NOT NULL,
    played INTEGER NOT NULL DEFAULT 0,
    played_at TEXT,
    stake INTEGER NOT NULL DEFAULT 0,
    suggested_stake INTEGER NOT NULL DEFAULT 0,
    total_odds REAL NOT NULL,
    win_prob REAL NOT NULL,
    result TEXT CHECK (result IN ('kazandi', 'kaybetti', 'iade')),
    payout INTEGER,
    settled_at TEXT
);
CREATE TABLE IF NOT EXISTS legs (
    id INTEGER PRIMARY KEY,
    coupon_id INTEGER NOT NULL REFERENCES coupons(id),
    position INTEGER NOT NULL,
    book_ref TEXT,
    book_code TEXT,
    sharp_ref TEXT,
    sport_key TEXT,
    home TEXT NOT NULL,
    away TEXT NOT NULL,
    kickoff TEXT NOT NULL,
    league TEXT,
    market TEXT NOT NULL,
    outcome TEXT NOT NULL,
    odds REAL NOT NULL,
    fair_prob REAL NOT NULL,
    mbs INTEGER NOT NULL,
    result TEXT CHECK (result IN ('kazandi', 'kaybetti', 'iade')),
    score TEXT
);
CREATE TABLE IF NOT EXISTS runs (
    day TEXT PRIMARY KEY,
    ts TEXT NOT NULL,
    decision TEXT NOT NULL,
    reason TEXT,
    coupon_id INTEGER REFERENCES coupons(id),
    summary TEXT
);
"""


class LedgerError(Exception):
    pass


def to_kurus(tl: float) -> int:
    return int(round(tl * 100))


def parse_tl(text: str) -> int:
    """'1500', '1500.50', '1.500', '1.500,50' veya '1500,5' -> kuruş.

    Türkçe yazımda nokta binlik ayracıdır: '5.000' beş bin TL demektir.
    """
    s = text.strip().replace(" ", "").replace("TL", "").replace("₺", "")
    if "," in s:
        s = s.replace(".", "").replace(",", ".")
    elif re.fullmatch(r"\d{1,3}(\.\d{3})+", s):
        s = s.replace(".", "")
    try:
        value = float(s)
    except ValueError as exc:
        raise LedgerError(f"tutar anlaşılamadı: {text!r}") from exc
    if not math.isfinite(value) or value <= 0:
        raise LedgerError("tutar pozitif olmalı")
    return to_kurus(value)


def fmt_tl(kurus: int | None) -> str:
    if kurus is None:
        return "-"
    sign = "-" if kurus < 0 else ""
    whole, frac = divmod(abs(kurus), 100)
    return f"{sign}{whole:,}".replace(",", ".") + f",{frac:02d} TL"


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


@dataclass
class Stats:
    balance: int
    deposited: int
    withdrawn: int
    staked: int
    returned: int
    open_stake: int
    played: int
    won: int
    lost: int
    voided: int
    expected_wins: float
    suggested_settled: int
    suggested_won: int

    @property
    def betting_pl(self) -> int:
        """Sonuçlanmış kuponların net kâr/zararı (açık kupon bahsi hariç)."""
        return self.returned - (self.staked - self.open_stake)

    @property
    def roi(self) -> float | None:
        settled = self.staked - self.open_stake
        return self.betting_pl / settled if settled else None


class Ledger:
    def __init__(self, path: str | Path, clock: Callable[[], datetime] = _utcnow):
        self.path = Path(path)
        self.clock = clock
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(self.path)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA foreign_keys = ON")
        self.conn.executescript(SCHEMA)

    def _ts(self) -> str:
        return self.clock().astimezone(timezone.utc).isoformat(timespec="seconds")

    def close(self) -> None:
        self.conn.close()

    @contextmanager
    def tx(self):
        try:
            yield self.conn
            self.conn.commit()
        except Exception:
            self.conn.rollback()
            raise

    # ---- para ----------------------------------------------------------
    def balance(self) -> int:
        row = self.conn.execute("SELECT COALESCE(SUM(amount), 0) FROM transactions").fetchone()
        return int(row[0])

    def _add_tx(self, kind: str, amount: int, coupon_id: int | None = None,
                note: str = "", ts: str | None = None) -> None:
        self.conn.execute(
            "INSERT INTO transactions (ts, kind, amount, coupon_id, note) VALUES (?, ?, ?, ?, ?)",
            (ts or self._ts(), kind, amount, coupon_id, note),
        )

    def deposit(self, amount: int, note: str = "", ts: str | None = None) -> None:
        if amount <= 0:
            raise LedgerError("yatırılan tutar pozitif olmalı")
        with self.tx():
            self._add_tx("deposit", amount, note=note, ts=ts)

    def withdraw(self, amount: int, note: str = "", ts: str | None = None) -> None:
        if amount <= 0:
            raise LedgerError("çekilen tutar pozitif olmalı")
        if amount > self.balance():
            raise LedgerError(f"kasada yeterli para yok (kasa: {fmt_tl(self.balance())})")
        with self.tx():
            self._add_tx("withdraw", -amount, note=note, ts=ts)

    def transactions(self) -> list[sqlite3.Row]:
        return self.conn.execute("SELECT * FROM transactions ORDER BY ts, id").fetchall()

    # ---- kuponlar --------------------------------------------------------
    def add_coupon(self, proposal: Proposal, day: str, suggested_stake: int,
                   ts: str | None = None) -> int:
        with self.tx():
            cur = self.conn.execute(
                "INSERT INTO coupons (created_at, day, suggested_stake, total_odds, win_prob) "
                "VALUES (?, ?, ?, ?, ?)",
                (ts or self._ts(), day, suggested_stake, proposal.odds, proposal.prob),
            )
            cid = int(cur.lastrowid)
            for i, leg in enumerate(proposal.legs, start=1):
                self.conn.execute(
                    "INSERT INTO legs (coupon_id, position, book_ref, book_code, sharp_ref, "
                    "sport_key, home, away, kickoff, league, market, outcome, odds, fair_prob, mbs) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (cid, i, leg.book.ref, leg.book.code, leg.sharp.ref, leg.sharp.sport_key,
                     leg.book.home, leg.book.away, leg.book.kickoff.isoformat(),
                     leg.book.league, leg.market, leg.outcome, leg.odds, leg.prob, leg.mbs),
                )
        return cid

    def coupon(self, coupon_id: int) -> sqlite3.Row:
        row = self.conn.execute("SELECT * FROM coupons WHERE id = ?", (coupon_id,)).fetchone()
        if row is None:
            raise LedgerError(f"{coupon_id} numaralı kupon yok")
        return row

    def legs(self, coupon_id: int) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM legs WHERE coupon_id = ? ORDER BY position", (coupon_id,)
        ).fetchall()

    def coupons(self, limit: int | None = None) -> list[sqlite3.Row]:
        sql = "SELECT * FROM coupons ORDER BY created_at DESC, id DESC"
        if limit:
            sql += f" LIMIT {int(limit)}"
        return self.conn.execute(sql).fetchall()

    def open_coupons(self) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM coupons WHERE result IS NULL ORDER BY id"
        ).fetchall()

    def mark_played(self, coupon_id: int, stake: int, leg_odds: list[float] | None = None,
                    ts: str | None = None) -> None:
        c = self.coupon(coupon_id)
        if c["played"]:
            raise LedgerError(f"{coupon_id} numaralı kupon zaten oynandı olarak işaretli")
        if c["result"] is not None:
            raise LedgerError(f"{coupon_id} numaralı kupon zaten sonuçlanmış")
        if stake <= 0:
            raise LedgerError("kupon tutarı pozitif olmalı")
        if stake > self.balance():
            raise LedgerError(f"kasada yeterli para yok (kasa: {fmt_tl(self.balance())})")
        legs = self.legs(coupon_id)
        with self.tx():
            if leg_odds is not None:
                if len(leg_odds) != len(legs):
                    raise LedgerError(f"{len(legs)} oran bekleniyordu, {len(leg_odds)} verildi")
                if any(o <= 1.0 for o in leg_odds):
                    raise LedgerError("oranlar 1'den büyük olmalı")
                for leg, o in zip(legs, leg_odds, strict=True):
                    self.conn.execute("UPDATE legs SET odds = ? WHERE id = ?", (o, leg["id"]))
                total = math.prod(leg_odds)
                self.conn.execute("UPDATE coupons SET total_odds = ? WHERE id = ?",
                                  (total, coupon_id))
            self.conn.execute(
                "UPDATE coupons SET played = 1, played_at = ?, stake = ? WHERE id = ?",
                (ts or self._ts(), stake, coupon_id),
            )
            self._add_tx("stake", -stake, coupon_id, f"Kupon #{coupon_id}", ts=ts)

    def set_leg_result(self, leg_id: int, result: str, score: str | None = None) -> None:
        if result not in (WON, LOST, VOID):
            raise LedgerError(f"geçersiz sonuç: {result}")
        with self.tx():
            self.conn.execute("UPDATE legs SET result = ?, score = ? WHERE id = ?",
                              (result, score, leg_id))

    def settle_coupon(self, coupon_id: int, ts: str | None = None) -> str | None:
        """Bacaklar yeterince sonuçlandıysa kuponu kapatır; sonucu döndürür."""
        c = self.coupon(coupon_id)
        if c["result"] is not None:
            return c["result"]
        legs = self.legs(coupon_id)
        results = [leg["result"] for leg in legs]
        if LOST in results:
            outcome, multiplier = LOST, 0.0
        elif all(r is not None for r in results):
            # iddaa kuralı: iptal/ertelenen maçın oranı 1,00 sayılır.
            multiplier = math.prod(leg["odds"] for leg in legs if leg["result"] == WON)
            outcome = VOID if all(r == VOID for r in results) else WON
        else:
            return None
        payout = int(round(c["stake"] * multiplier)) if c["played"] else 0
        with self.tx():
            self.conn.execute(
                "UPDATE coupons SET result = ?, payout = ?, settled_at = ? WHERE id = ?",
                (outcome, payout if c["played"] else None, ts or self._ts(), coupon_id),
            )
            if c["played"] and payout > 0:
                self._add_tx("payout", payout, coupon_id, f"Kupon #{coupon_id} ödeme", ts=ts)
        return outcome

    # ---- günlük çalıştırma kayıtları ----------------------------------
    def record_run(self, day: str, decision: str, reason: str, coupon_id: int | None,
                   summary: str = "", ts: str | None = None) -> None:
        with self.tx():
            self.conn.execute(
                "INSERT OR REPLACE INTO runs (day, ts, decision, reason, coupon_id, summary) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (day, ts or self._ts(), decision, reason, coupon_id, summary),
            )

    def run_for(self, day: str) -> sqlite3.Row | None:
        return self.conn.execute("SELECT * FROM runs WHERE day = ?", (day,)).fetchone()

    def runs(self, limit: int = 30) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM runs ORDER BY day DESC LIMIT ?", (limit,)
        ).fetchall()

    # ---- özet ----------------------------------------------------------
    def stats(self) -> Stats:
        q = self.conn.execute
        sums = {row["kind"]: int(row["s"]) for row in q(
            "SELECT kind, SUM(amount) AS s FROM transactions GROUP BY kind")}
        open_stake = int(q(
            "SELECT COALESCE(SUM(stake), 0) FROM coupons WHERE played = 1 AND result IS NULL"
        ).fetchone()[0])
        played = q("SELECT result, win_prob FROM coupons WHERE played = 1 AND result IS NOT NULL"
                   ).fetchall()
        suggested = q("SELECT result FROM coupons WHERE result IS NOT NULL AND result != 'iade'"
                      ).fetchall()
        return Stats(
            balance=self.balance(),
            deposited=sums.get("deposit", 0),
            withdrawn=-sums.get("withdraw", 0),
            staked=-sums.get("stake", 0),
            returned=sums.get("payout", 0),
            open_stake=open_stake,
            played=len(played),
            won=sum(1 for r in played if r["result"] == WON),
            lost=sum(1 for r in played if r["result"] == LOST),
            voided=sum(1 for r in played if r["result"] == VOID),
            expected_wins=sum(r["win_prob"] for r in played if r["result"] != VOID),
            suggested_settled=len(suggested),
            suggested_won=sum(1 for r in suggested if r["result"] == WON),
        )

    def balance_series(self) -> list[tuple[str, int]]:
        """Gerçekleşmiş kasa eğrisi (grafik için).

        Açık kuponun bahsi kasada sayılır; kupon sonuçlandığı an kâr/zarar eklenir.
        Böylece grafik "bahis düştü, ödeme geldi" iğneleri yerine gerçek seyri gösterir.
        """
        points = [(row["ts"], row["amount"]) for row in self.conn.execute(
            "SELECT ts, amount FROM transactions WHERE kind IN ('deposit', 'withdraw')")]
        points += [(row["settled_at"], (row["payout"] or 0) - row["stake"]) for row in
                   self.conn.execute("SELECT settled_at, payout, stake FROM coupons "
                                     "WHERE played = 1 AND result IS NOT NULL")]
        series = []
        running = 0
        for ts, delta in sorted(points):
            running += delta
            series.append((ts, running))
        return series
