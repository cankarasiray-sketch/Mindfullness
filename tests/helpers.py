from datetime import datetime, timedelta, timezone

from bilincli.models import BookEvent, SharpEvent

NOW = datetime(2026, 10, 1, 3, 0, tzinfo=timezone.utc)  # 06:00 Türkiye


class Clock:
    def __init__(self, now=NOW):
        self.now = now

    def __call__(self):
        return self.now


def make_pair(ref, ms_odds, fair, mbs=1, hours=10, home=None, away=None):
    kickoff = NOW + timedelta(hours=hours)
    home = home or f"Ev {ref}"
    away = away or f"Dep {ref}"
    book = BookEvent(ref=f"b{ref}", home=home, away=away, kickoff=kickoff, mbs=mbs,
                     odds={"MS": dict(zip("1X2", ms_odds, strict=True))}, code=str(ref))
    sharp = SharpEvent(ref=f"s{ref}", sport_key="lig", home=home, away=away, kickoff=kickoff,
                       fair={"MS": dict(zip("1X2", fair, strict=True))})
    return book, sharp
