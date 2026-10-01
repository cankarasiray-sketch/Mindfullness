"""iddaa bülteni ile keskin piyasa maçlarını eşleştirme.

İki kaynakta takım adları farklı yazılır ("Bayern Münih" / "Bayern Munich",
"Beşiktaş JK" / "Besiktas"). Önce başlama saati (±15 dk) ile aday daraltılır,
sonra takım adları normalize edilip kelime bazında bulanık karşılaştırılır.
"""

from __future__ import annotations

import re
import unicodedata
from difflib import SequenceMatcher

from .models import BookEvent, SharpEvent

_TR_MAP = str.maketrans(
    {"ı": "i", "İ": "i", "ş": "s", "Ş": "s", "ğ": "g", "Ğ": "g",
     "ç": "c", "Ç": "c", "ö": "o", "Ö": "o", "ü": "u", "Ü": "u"}
)

_STOPWORDS = {
    "fc", "sk", "jk", "fk", "as", "ac", "afc", "cf", "sc", "sv", "ssc", "spor",
    "kulubu", "club", "calcio", "cd", "ud", "rc", "bk", "if", "ss", "the",
}

# Normalize edilmiş ad -> kanonik ad. Sadece kelime benzerliğinin
# yakalayamadığı kısaltma ve Türkçe karşılıklar için.
_ALIASES = {
    "paris sg": "paris saint germain",
    "psg": "paris saint germain",
    "man utd": "manchester united",
    "manchester utd": "manchester united",
    "man united": "manchester united",
    "man city": "manchester city",
    "atl madrid": "atletico madrid",
    "a madrid": "atletico madrid",
    "spurs": "tottenham hotspur",
    "wolves": "wolverhampton wanderers",
    "m gladbach": "borussia monchengladbach",
    "gladbach": "borussia monchengladbach",
    "b monchengladbach": "borussia monchengladbach",
    "bayern munih": "bayern munich",
    "internazionale": "inter milan",
    "inter": "inter milan",
    "sporting lizbon": "sporting lisbon",
    "nottingham": "nottingham forest",
}

# Kadın, genç ve rezerv takımları ana takımla aynı adı taşır ("Arsenal (K)", "Fenerbahçe U19",
# "Barcelona B", "Jong Ajax"). İşaret iki tarafta farklıysa eşleşme yapılmaz: yanlış maçın
# oranı sahte "avantaj" üretir.
_WOMEN = {"k", "kadin", "kadinlar", "women", "w", "womens", "ladies", "fem", "femenino", "feminine", "frauen", "wfc"}
_YOUTH = {"youth", "genc", "gencler", "academy", "akademi", "primavera", "juniors", "jong", "reserves", "res", "ii", "castilla"}
_UNDER = re.compile(r"^u(1[5-9]|2[0-3])$")

TIME_TOLERANCE_MIN = 15
MIN_PAIR_SCORE = 0.62
MIN_SIDE_SCORE = 0.45


def normalize(name: str) -> str:
    s = name.translate(_TR_MAP).lower()
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch))
    s = re.sub(r"[^a-z0-9 ]+", " ", s)
    raw = s.split()
    # kadın/genç/rezerv işaretleri ad benzerliğine girmez; onları variant() ayrıca karşılaştırır
    tokens = [t for i, t in enumerate(raw)
              if t not in _STOPWORDS and not t.isdigit() and not _is_marker(t, i, len(raw))]
    s = " ".join(tokens)
    return _ALIASES.get(s, s)


def _is_marker(t: str, i: int, n: int) -> bool:
    return t in _WOMEN or t in _YOUTH or bool(_UNDER.match(t)) or (t == "b" and n > 1 and i == n - 1)


def _tokens(name: str) -> list[str]:
    s = name.translate(_TR_MAP).lower()
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch))
    s = re.sub(r"[^a-z0-9 ]+", " ", s)
    return s.split()


def variant(name: str) -> str:
    """"kadin", "uNN", "genc" ya da "" (ana takım)."""
    tokens = _tokens(name)
    for t in tokens:
        if t in _WOMEN:
            return "kadin"
    for t in tokens:
        if _UNDER.match(t):
            return t
    for t in tokens:
        if t in _YOUTH:
            return "genc"
    if len(tokens) > 1 and tokens[-1] == "b":
        return "genc"
    return ""


def _token_score(a: str, b: str) -> float:
    if a == b:
        return 1.0
    short, long_ = (a, b) if len(a) <= len(b) else (b, a)
    if len(short) >= 3 and long_.startswith(short):
        return 0.9
    return SequenceMatcher(None, a, b).ratio()


def name_similarity(a: str, b: str) -> float:
    na, nb = normalize(a), normalize(b)
    if not na or not nb:
        return 0.0
    if na == nb:
        return 1.0
    ta, tb = na.split(), nb.split()
    short, long_ = (ta, tb) if len(ta) <= len(tb) else (tb, ta)
    token = sum(max(_token_score(t, u) for u in long_) for t in short) / len(short)
    full = SequenceMatcher(None, na, nb).ratio()
    return 0.7 * token + 0.3 * full


def match_events(
    book: list[BookEvent], sharp: list[SharpEvent]
) -> list[tuple[BookEvent, SharpEvent, float]]:
    """Her iddaa maçını en fazla bir keskin piyasa maçıyla eşleştirir."""
    pairs: list[tuple[float, int, int]] = []
    for i, b in enumerate(book):
        for j, s in enumerate(sharp):
            if abs((b.kickoff - s.kickoff).total_seconds()) > TIME_TOLERANCE_MIN * 60:
                continue
            if variant(b.home) != variant(s.home) or variant(b.away) != variant(s.away):
                continue
            h = name_similarity(b.home, s.home)
            a = name_similarity(b.away, s.away)
            if min(h, a) < MIN_SIDE_SCORE:
                continue
            score = (h + a) / 2.0
            if score >= MIN_PAIR_SCORE:
                pairs.append((score, i, j))
    pairs.sort(reverse=True)
    used_b: set[int] = set()
    used_s: set[int] = set()
    result = []
    for score, i, j in pairs:
        if i in used_b or j in used_s:
            continue
        used_b.add(i)
        used_s.add(j)
        result.append((book[i], sharp[j], score))
    return result
