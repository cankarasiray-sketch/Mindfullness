"""Uygulama bileşenlerini bir araya getirir ve günlük akışı yürütür."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone
from functools import cached_property

from . import guard, render
from .config import Config
from .engine import Decision, decide
from .ledger import Ledger, fmt_tl, to_kurus
from .models import TR, Proposal
from .notify import send_telegram
from .providers.http import ProviderError
from .settlement import settle_open

log = logging.getLogger(__name__)


@dataclass
class App:
    cfg: Config
    demo_world: object | None = None  # providers.demo.DemoWorld

    @cached_property
    def ledger(self) -> Ledger:
        return Ledger(self.cfg.db_path, clock=self.now)

    @cached_property
    def book(self):
        if self.demo_world is not None:
            from .providers.demo import DemoBookProvider
            return DemoBookProvider(self.demo_world)
        if self.cfg.sources.iddaa_source == "dosya":
            from .providers.filesource import FileBookProvider
            return FileBookProvider(self.cfg.sources.iddaa_file)
        from .providers.nesine import NesineProvider
        return NesineProvider()

    @cached_property
    def sharp(self):
        if self.demo_world is not None:
            from .providers.demo import DemoSharpProvider
            return DemoSharpProvider(self.demo_world)
        from .providers.theoddsapi import OddsApiProvider
        s = self.cfg.sources
        return OddsApiProvider(s.odds_api_key, s.leagues, s.markets, s.regions,
                               s.preferred_book, s.min_books)

    def now(self) -> datetime:
        if self.demo_world is not None:
            return self.demo_world.now
        return datetime.now(timezone.utc)


@dataclass
class DailyResult:
    day: str
    decision: Decision | None = None
    coupon_id: int | None = None
    stake: int = 0
    stake_note: str | None = None
    blocked: str | None = None
    error: str | None = None
    skipped: bool = False
    settle_messages: list[str] = field(default_factory=list)

    @property
    def headline(self) -> str:
        if self.error:
            return "HATA"
        if self.blocked:
            return "KORUMA"
        if self.decision is None or self.decision.is_pass:
            return "PAS"
        return "KUPON"


def day_key(now: datetime) -> str:
    return now.astimezone(TR).strftime("%Y-%m-%d")


def compute_stake(balance: int, proposal: Proposal, cfg) -> tuple[int, str | None]:
    s = cfg.strategy
    if balance <= 0:
        return 0, "Kasa boş: önce `bilincli yatir <tutar>` ile kasa oluştur."
    stake = int(balance * proposal.stake_fraction) // 100 * 100  # tam TL
    minimum = to_kurus(s.min_coupon_amount)
    if stake < minimum:
        if minimum <= balance * s.max_stake_fraction:
            stake = minimum
        else:
            return 0, (f"Kasa ({fmt_tl(balance)}) asgari kupon bedelini ({fmt_tl(minimum)}) "
                       f"güvenli oranla (en fazla %{s.max_stake_fraction * 100:.0f}) karşılamıyor. "
                       f"Kupon yalnızca takip için kaydedildi.")
    return stake, None


def settle(app: App) -> list[str]:
    if not app.ledger.open_coupons():
        return []
    try:
        return settle_open(app.ledger, app.sharp.fetch_scores, app.now())
    except ProviderError as exc:
        return [f"Sonuçlar alınamadı: {exc}"]


def generate(app: App, force: bool = False) -> DailyResult:
    now = app.now()
    day = day_key(now)
    result = DailyResult(day=day)
    existing = app.ledger.run_for(day)
    if existing and not force:
        result.skipped = True
        result.coupon_id = existing["coupon_id"]
        return result

    blocked = guard.check_limits(app.ledger, app.cfg.limits, now)
    if blocked:
        result.blocked = blocked
        app.ledger.record_run(day, "koruma", blocked, None)
        return result

    try:
        book = app.book.fetch_events()
        sharp = app.sharp.fetch_events()
    except ProviderError as exc:
        result.error = str(exc)
        log.error("Veri alınamadı: %s", exc)
        return result  # hata günü kaydedilmez; sonraki çalıştırma yeniden dener

    decision = decide(book, sharp, now, app.cfg.strategy)
    result.decision = decision
    summary = render.stats_line(decision.stats)
    if decision.is_pass:
        app.ledger.record_run(day, "pas", decision.reason, None, summary)
        return result

    stake, note = compute_stake(app.ledger.balance(), decision.proposal, app.cfg)
    cid = app.ledger.add_coupon(decision.proposal, day, stake)
    result.coupon_id, result.stake, result.stake_note = cid, stake, note
    if app.cfg.strategy.auto_play and stake > 0:
        app.ledger.mark_played(cid, stake)
    app.ledger.record_run(day, "kupon", note or "", cid, summary)
    return result


def run_daily(app: App, force: bool = False, notify: bool = True) -> DailyResult:
    messages = settle(app)
    result = generate(app, force=force)
    result.settle_messages = messages
    write_panel(app)
    if notify and not result.skipped:
        text = render.daily_text(app.ledger, result)
        try:
            send_telegram(app.cfg.notify, text)
        except ProviderError as exc:
            log.warning("Telegram bildirimi gönderilemedi: %s", exc)
    return result


def write_panel(app: App) -> None:
    app.cfg.data_dir.mkdir(parents=True, exist_ok=True)
    html = render.dashboard(app.ledger, app.cfg, demo=app.demo_world is not None)
    app.cfg.panel_path.write_text(html, encoding="utf-8")
