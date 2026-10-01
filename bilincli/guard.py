"""Kasa koruma kuralları.

İki kural var, ikisi de ayardan kapatılabilir (0 yaparak):
  * Haftalık kayıp limiti: Bu hafta (Pazartesi 06:00'dan beri) sonuçlanan
    kuponların net zararı, hafta başı kasanın belirli bir oranını geçtiyse
    hafta sonuna kadar yeni kupon üretilmez.
  * Kovalama beklemesi: Zarardayken kasaya para eklenirse, belirli bir süre
    yeni kupon üretilmez. Kaybı "geri almak" için bahsi büyütmek, kasaları en
    hızlı eriten davranıştır.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from .config import LimitConfig
from .ledger import Ledger, fmt_tl
from .models import TR


def week_start(now: datetime) -> datetime:
    local = now.astimezone(TR)
    start = (local - timedelta(days=local.weekday())).replace(hour=6, minute=0, second=0,
                                                              microsecond=0)
    if start > local:
        start -= timedelta(days=7)
    return start


def check_limits(ledger: Ledger, cfg: LimitConfig, now: datetime) -> str | None:
    """Kupon üretimini engelleyen bir durum varsa gerekçesini döndürür."""
    rows = [(datetime.fromisoformat(r["ts"]), r["kind"], r["amount"])
            for r in ledger.transactions()]

    if cfg.weekly_loss_limit > 0:
        start = week_start(now)
        opening = sum(a for ts, _, a in rows if ts < start)
        week_pl = sum(a for ts, kind, a in rows if ts >= start and kind in ("stake", "payout"))
        # Hafta içinde oynanıp henüz sonuçlanmamış kuponların bahsi zarar sayılmasın.
        open_stake = sum(
            c["stake"] for c in ledger.open_coupons()
            if c["played"] and c["played_at"] and datetime.fromisoformat(c["played_at"]) >= start
        )
        realized = week_pl + open_stake
        if opening > 0 and -realized >= cfg.weekly_loss_limit * opening:
            resume = start + timedelta(days=7)
            return (f"Haftalık kayıp limiti doldu ({fmt_tl(-realized)} / hafta başı kasa "
                    f"{fmt_tl(opening)}). Yeni kupon {resume:%d.%m %H:%M}'de.")

    if cfg.chase_cooldown_hours > 0:
        cutoff = now - timedelta(hours=cfg.chase_cooldown_hours)
        settled = [(datetime.fromisoformat(c["settled_at"]), (c["payout"] or 0) - c["stake"])
                   for c in ledger.coupons() if c["played"] and c["result"] is not None]
        for ts, kind, _amount in rows:
            if kind != "deposit" or ts < cutoff:
                continue
            if sum(pl for settled_at, pl in settled if settled_at <= ts) < 0:
                resume = (ts + timedelta(hours=cfg.chase_cooldown_hours)).astimezone(TR)
                return (f"Zarardayken kasaya para eklendi. Kovalama beklemesi "
                        f"{resume:%d.%m %H:%M}'e kadar sürüyor.")
    return None
