"""iddaa oranlarını JSON dosyasından okuma (yedek / elle giriş yolu).

Biçim:
[
  {
    "kod": "1234",
    "lig": "Süper Lig",
    "ev": "Galatasaray",
    "deplasman": "Fenerbahçe",
    "baslama": "2026-10-03T20:00:00+03:00",
    "mbs": 1,
    "oranlar": {"MS": {"1": 1.95, "X": 3.40, "2": 3.60},
                "AU25": {"ALT": 1.80, "UST": 1.90}}
  }
]
Saat dilimi yazılmazsa Türkiye saati kabul edilir.
"""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from ..models import MARKETS, TR, BookEvent
from .http import ProviderError


def parse_file_events(items: list) -> list[BookEvent]:
    if not isinstance(items, list):
        raise ProviderError("iddaa dosyası bir liste olmalı")
    events = []
    for i, it in enumerate(items, start=1):
        try:
            kickoff = datetime.fromisoformat(it["baslama"])
            if kickoff.tzinfo is None:
                kickoff = kickoff.replace(tzinfo=TR)
            odds: dict[str, dict[str, float]] = {}
            for market, outcomes in it["oranlar"].items():
                if market not in MARKETS:
                    raise ProviderError(f"bilinmeyen pazar {market!r}")
                valid = MARKETS[market]["outcomes"].keys()
                clean = {str(k): float(v) for k, v in outcomes.items() if str(k) in valid}
                if clean:
                    odds[market] = clean
            code = str(it.get("kod", ""))
            events.append(BookEvent(
                ref=f"dosya:{code or i}", home=it["ev"], away=it["deplasman"], kickoff=kickoff,
                league=it.get("lig", ""), mbs=int(it.get("mbs", 1)), odds=odds, code=code,
            ))
        except (KeyError, TypeError, ValueError) as exc:
            raise ProviderError(f"iddaa dosyası {i}. kayıt hatalı: {exc}") from exc
    return events


class FileBookProvider:
    def __init__(self, path: str | Path):
        self.path = Path(path)

    def fetch_events(self) -> list[BookEvent]:
        if not self.path.exists():
            raise ProviderError(f"iddaa dosyası bulunamadı: {self.path}")
        try:
            items = json.loads(self.path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise ProviderError(f"iddaa dosyası geçerli JSON değil: {exc}") from exc
        return parse_file_events(items)
