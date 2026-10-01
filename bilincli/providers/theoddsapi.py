"""The Odds API (the-odds-api.com) üzerinden keskin piyasa oranları ve skorlar.

Varsayılan olarak Pinnacle kullanılır: dünyanın en düşük marjlı ve en "keskin"
bahis şirketi olduğundan oranları gerçek olasılığın en iyi tahminidir.
Pinnacle yoksa en az `min_books` sitenin marjdan arındırılmış ortalaması alınır.

Kredi: /odds çağrısı lig başına (pazar sayısı x bölge sayısı) kredi harcar.
/scores çağrısı (daysFrom ile) lig başına 2 kredi harcar.
"""

from __future__ import annotations

import logging
from datetime import datetime
from statistics import mean

from .. import oddsmath
from ..models import ScoreResult, SharpEvent
from .http import ProviderError, get_json

log = logging.getLogger(__name__)

BASE_URL = "https://api.the-odds-api.com/v4"


def _parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _market_probs(market: dict, home: str, away: str) -> tuple[str, dict[str, float]] | None:
    outcomes = market.get("outcomes") or []
    if market.get("key") == "h2h":
        by_name = {o.get("name"): o.get("price") for o in outcomes}
        odds = [by_name.get(home), by_name.get("Draw"), by_name.get(away)]
        if any(o is None or o <= 1.0 for o in odds):
            return None
        p = oddsmath.devig_power(odds)
        return "MS", {"1": p[0], "X": p[1], "2": p[2]}
    if market.get("key") == "totals":
        line = [o for o in outcomes if o.get("point") == 2.5]
        by_name = {o.get("name"): o.get("price") for o in line}
        odds = [by_name.get("Under"), by_name.get("Over")]
        if any(o is None or o <= 1.0 for o in odds):
            return None
        p = oddsmath.devig_power(odds)
        return "AU25", {"ALT": p[0], "UST": p[1]}
    return None


def parse_odds(payload: list, sport_key: str, preferred: str = "pinnacle",
               min_books: int = 3) -> list[SharpEvent]:
    events: list[SharpEvent] = []
    for ev in payload:
        home, away = ev.get("home_team"), ev.get("away_team")
        if not home or not away or not ev.get("commence_time"):
            continue
        per_market: dict[str, dict[str, dict[str, float]]] = {}  # pazar -> site -> olasılık
        for book in ev.get("bookmakers") or []:
            for market in book.get("markets") or []:
                parsed = _market_probs(market, home, away)
                if parsed:
                    key, probs = parsed
                    per_market.setdefault(key, {})[book.get("key", "?")] = probs
        fair: dict[str, dict[str, float]] = {}
        sources = []
        for key, books in per_market.items():
            if preferred in books:
                fair[key] = books[preferred]
                sources.append(f"{key}:{preferred}")
            elif len(books) >= min_books:
                outcomes = next(iter(books.values())).keys()
                avg = {o: mean(b[o] for b in books.values()) for o in outcomes}
                total = sum(avg.values())
                fair[key] = {o: v / total for o, v in avg.items()}
                sources.append(f"{key}:ortalama({len(books)})")
        if fair:
            events.append(SharpEvent(ref=str(ev.get("id")), sport_key=sport_key, home=home,
                                     away=away, kickoff=_parse_time(ev["commence_time"]),
                                     fair=fair, source=", ".join(sources)))
    return events


def parse_scores(payload: list) -> dict[str, ScoreResult]:
    out: dict[str, ScoreResult] = {}
    for ev in payload:
        ref = str(ev.get("id"))
        scores = {s.get("name"): s.get("score") for s in ev.get("scores") or []}
        home, away = scores.get(ev.get("home_team")), scores.get(ev.get("away_team"))
        try:
            hg = int(home) if home is not None else None
            ag = int(away) if away is not None else None
        except ValueError:
            hg = ag = None
        out[ref] = ScoreResult(completed=bool(ev.get("completed")) and hg is not None
                               and ag is not None, home_goals=hg, away_goals=ag)
    return out


class OddsApiProvider:
    def __init__(self, api_key: str, leagues: list[str], markets: list[str], regions: str = "eu",
                 preferred: str = "pinnacle", min_books: int = 3):
        if not api_key:
            raise ProviderError("The Odds API anahtarı yok. bilincli.toml içinde odds_api_key "
                                "ya da ODDS_API_KEY ortam değişkeni ayarlanmalı.")
        self.api_key = api_key
        self.leagues = leagues
        self.markets = markets
        self.regions = regions
        self.preferred = preferred
        self.min_books = min_books
        self.remaining: str | None = None

    def _get(self, path: str, params: dict) -> list:
        data, headers = get_json(f"{BASE_URL}{path}", {"apiKey": self.api_key, **params})
        self.remaining = headers.get("x-requests-remaining", self.remaining)
        if not isinstance(data, list):
            raise ProviderError(f"{path}: beklenmeyen yanıt")
        return data

    def fetch_events(self) -> list[SharpEvent]:
        events: list[SharpEvent] = []
        for league in self.leagues:
            try:
                payload = self._get(f"/sports/{league}/odds", {
                    "regions": self.regions,
                    "markets": ",".join(self.markets),
                    "oddsFormat": "decimal",
                    "dateFormat": "iso",
                })
            except ProviderError as exc:
                log.warning("%s atlandı: %s", league, exc)
                continue
            events.extend(parse_odds(payload, league, self.preferred, self.min_books))
        return events

    def fetch_scores(self, sport_keys: set[str]) -> dict[str, ScoreResult]:
        out: dict[str, ScoreResult] = {}
        for league in sorted(sport_keys):
            try:
                payload = self._get(f"/sports/{league}/scores", {"daysFrom": 3,
                                                                 "dateFormat": "iso"})
            except ProviderError as exc:
                log.warning("%s skorları alınamadı: %s", league, exc)
                continue
            out.update(parse_scores(payload))
        return out
