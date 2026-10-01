"""iddaa bülteni: Nesine'nin herkese açık bülten JSON'u.

UYARI: Bu uç nokta resmi/belgelenmiş bir API değildir; Nesine sitesinin kendi
kullandığı veridir ve haber verilmeden değişebilir. Ayrıştırıcı bilinen alan
adlarına göre savunmacı yazıldı; alan bulunamazsa maç atlanır ve
`bilincli kontrol` kaç maçın okunabildiğini gösterir. Bozulursa
`iddaa_kaynagi = "dosya"` ile oranları JSON dosyasından vermek her zaman mümkün.

Bilinen yapı (özet):
  {"sg": {"EA": [ {"C": maç_kodu, "HN": ev, "AN": deplasman, "D": "01.10.2026",
                    "T": "20:00", "TYPE": 1, "MBS": 1, "LN": lig,
                    "MA": [ {"MTID": 1, "MBS": 1, "OCA": [{"N": 1, "O": 1.85}, ...]} ]}]}}
  MTID 1 = Maç Sonucu, OCA içindeki N: 1 = ev, 2 = beraberlik, 3 = deplasman.
"""

from __future__ import annotations

import logging
from datetime import datetime

from ..models import TR, BookEvent
from .http import ProviderError, get_json

log = logging.getLogger(__name__)

URL = "https://bulten.nesine.com/api/bulten/getprebultenfull"
FOOTBALL = 1
MS_MARKET = 1
MS_OUTCOMES = {1: "1", 2: "X", 3: "2"}


def _first(d: dict, *keys):
    for k in keys:
        if k in d and d[k] not in (None, ""):
            return d[k]
    return None


def _kickoff(ev: dict) -> datetime | None:
    date, time = _first(ev, "D", "Date"), _first(ev, "T", "Time")
    if not date:
        return None
    try:
        if "-" in str(date):  # ISO biçimi
            dt = datetime.fromisoformat(str(date))
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=TR)
            return dt
        dt = datetime.strptime(f"{date} {time or '00:00'}", "%d.%m.%Y %H:%M")
        return dt.replace(tzinfo=TR)
    except ValueError:
        return None


def _odds(value) -> float | None:
    try:
        o = float(str(value).replace(",", "."))
    except (TypeError, ValueError):
        return None
    return o if o > 1.0 else None


def parse_bulletin(payload: dict) -> list[BookEvent]:
    if not isinstance(payload, dict):
        raise ProviderError("Nesine yanıtı beklenen biçimde değil")
    root = payload.get("sg") if isinstance(payload.get("sg"), dict) else payload
    raw_events = root.get("EA") or []
    events: list[BookEvent] = []
    skipped = 0
    for ev in raw_events:
        if not isinstance(ev, dict):
            continue
        if ev.get("TYPE", FOOTBALL) != FOOTBALL:
            continue
        home, away = _first(ev, "HN"), _first(ev, "AN")
        kickoff = _kickoff(ev)
        if not home or not away or kickoff is None:
            skipped += 1
            continue
        event_mbs = _first(ev, "MBS")
        odds: dict[str, dict[str, float]] = {}
        market_mbs: dict[str, int] = {}
        for market in ev.get("MA") or []:
            if market.get("MTID") != MS_MARKET:
                continue
            outcomes = {}
            for oc in market.get("OCA") or []:
                key = MS_OUTCOMES.get(oc.get("N"))
                price = _odds(oc.get("O"))
                if key and price:
                    outcomes[key] = price
            if len(outcomes) == 3:
                odds["MS"] = outcomes
                if market.get("MBS"):
                    market_mbs["MS"] = int(market["MBS"])
        if not odds:
            skipped += 1
            continue
        if event_mbs is None and not market_mbs:
            # MBS okunamadıysa oynanamayacak tekli kupon önermemek için temkinli davran.
            event_mbs = 3
        code = str(_first(ev, "C", "EC") or "")
        events.append(BookEvent(
            ref=f"nesine:{code or home + '-' + away}", home=str(home), away=str(away),
            kickoff=kickoff.astimezone(TR), league=str(_first(ev, "LN", "LC") or ""),
            mbs=int(event_mbs or 1), odds=odds, market_mbs=market_mbs, code=code,
        ))
    if skipped:
        log.info("Nesine: %d etkinlik ayrıştırılamadı ve atlandı", skipped)
    return events


class NesineProvider:
    def __init__(self, url: str = URL):
        self.url = url
        self.last_payload: object = None

    def fetch_events(self) -> list[BookEvent]:
        payload, _ = get_json(self.url, headers={"Referer": "https://www.nesine.com/",
                                                 "Origin": "https://www.nesine.com"})
        self.last_payload = payload
        return parse_bulletin(payload)
