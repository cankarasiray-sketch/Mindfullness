"""Metin özetleri (terminal / Telegram) ve HTML paneli."""

from __future__ import annotations

from datetime import datetime
from html import escape

from .ledger import Ledger, Stats, fmt_tl
from .models import LOST, TR, VOID, WON, outcome_label


# ---- biçimlendirme --------------------------------------------------------
def num(x: float, digits: int = 2) -> str:
    return f"{x:,.{digits}f}".replace(",", "X").replace(".", ",").replace("X", ".")


def pct(x: float | None, signed: bool = False, digits: int = 1) -> str:
    if x is None:
        return "-"
    sign = ("+" if x > 0 else "−" if x < 0 else "") if signed else ("−" if x < 0 else "")
    return f"{sign}%{num(abs(x) * 100, digits)}"


def local_time(iso: str, fmt: str = "%d.%m %H:%M") -> str:
    return datetime.fromisoformat(iso).astimezone(TR).strftime(fmt)


RESULT_LABEL = {WON: "Tuttu", LOST: "Yattı", VOID: "İade", None: "Bekliyor"}


def stats_line(stats: dict) -> str:
    parts = [f"Bülten {stats.get('iddaa_mac', 0)} maç",
             f"eşleşen {stats.get('eslesen', 0)}",
             f"karşılaştırılan {stats.get('karsilastirilan_secim', 0)} seçim",
             f"avantajlı {stats.get('avantajli_secim', 0)}"]
    for market, m in (stats.get("marjlar") or {}).items():
        parts.append(f"iddaa {market} marjı {pct(m)}")
    return " · ".join(parts)


# ---- metin ----------------------------------------------------------------
def coupon_text(ledger: Ledger, coupon_id: int) -> str:
    c = ledger.coupon(coupon_id)
    legs = ledger.legs(coupon_id)
    lines = [f"Kupon #{c['id']} — {len(legs)} maç"]
    for leg in legs:
        fair = 1 / leg["fair_prob"]
        ev = leg["fair_prob"] * leg["odds"] - 1
        state = f" [{RESULT_LABEL[leg['result']]} {leg['score'] or ''}]".rstrip() \
            if leg["result"] else ""
        code = f" kod {leg['book_code']}" if leg["book_code"] else ""
        lines.append(
            f"{leg['position']}) {local_time(leg['kickoff'])} {leg['home']} - {leg['away']}"
            f"{code} (MBS {leg['mbs']})\n"
            f"   → {outcome_label(leg['market'], leg['outcome'])} @ {num(leg['odds'])} "
            f"(adil {num(fair)}, avantaj {pct(ev, signed=True)}){state}"
        )
    ev = c["win_prob"] * c["total_odds"] - 1
    lines.append(f"Toplam oran {num(c['total_odds'])} · tutma olasılığı {pct(c['win_prob'])} · "
                 f"beklenen değer {pct(ev, signed=True)}")
    stake = c["stake"] if c["played"] else c["suggested_stake"]
    if stake:
        label = "Oynanan" if c["played"] else "Önerilen"
        lines.append(f"{label} tutar {fmt_tl(stake)} → tutarsa {fmt_tl(int(stake * c['total_odds']))}")
    return "\n".join(lines)


def daily_text(ledger: Ledger, result) -> str:
    when = datetime.fromisoformat(result.day).strftime("%d.%m.%Y")
    lines = [f"BİLİNÇLİ KUPON · {when}", f"Kasa: {fmt_tl(ledger.balance())}"]
    if result.settle_messages:
        lines += ["", *result.settle_messages]
    lines.append("")
    if result.error:
        lines.append(f"Veri alınamadı: {result.error}")
    elif result.blocked:
        lines.append(f"Kupon yok — {result.blocked}")
    elif result.decision is None:
        lines.append("Bugünün kararı daha önce verildi.")
    elif result.decision.is_pass:
        lines.append(f"BUGÜN PAS — {result.decision.reason}")
        lines.append(stats_line(result.decision.stats))
    else:
        lines.append("GÜNÜN KUPONU")
        lines.append(coupon_text(ledger, result.coupon_id))
        if result.stake_note:
            lines.append(result.stake_note)
        else:
            c = ledger.coupon(result.coupon_id)
            if not c["played"]:
                lines.append(f"Oynadıysan: bilincli oynadim {result.coupon_id}")
        lines.append(stats_line(result.decision.stats))
    return "\n".join(lines)


def status_text(ledger: Ledger) -> str:
    s = ledger.stats()
    lines = [
        f"Kasa              : {fmt_tl(s.balance)}",
        f"Net yatırılan     : {fmt_tl(s.deposited - s.withdrawn)}",
        f"Bahis kâr/zarar   : {fmt_tl(s.betting_pl)} (ROI {pct(s.roi, signed=True)})",
        f"Açıktaki bahis    : {fmt_tl(s.open_stake)}",
        f"Oynanan kupon     : {s.played} (tutan {s.won}, yatan {s.lost}, iade {s.voided})",
        f"Beklenen tutan    : {num(s.expected_wins, 1)} (gerçekleşen {s.won})",
    ]
    if s.suggested_settled:
        lines.append(f"Tüm öneriler      : {s.suggested_settled} sonuçlandı, {s.suggested_won} tuttu")
    opens = ledger.open_coupons()
    if opens:
        lines.append("")
        lines.append("Açık kuponlar:")
        for c in opens:
            state = "oynandı" if c["played"] else "öneri (oynanmadı)"
            lines.append(f"  #{c['id']} {c['day']} · oran {num(c['total_odds'])} · {state}")
    return "\n".join(lines)


# ---- HTML -----------------------------------------------------------------
CSS = """
:root{--bg:#f6f7f9;--card:#fff;--ink:#14171c;--muted:#5d6672;--line:#e3e6eb;
--accent:#0f6e56;--accent-soft:#e3f2ec;--bad:#b42318;--bad-soft:#fdecea;--warn:#8a5a00;
--warn-soft:#fff4dc;--chart:#0f6e56}
@media (prefers-color-scheme:dark){:root{--bg:#0f1216;--card:#171b21;--ink:#e8ebef;
--muted:#9aa3ae;--line:#2a3038;--accent:#4cc39a;--accent-soft:#16322a;--bad:#f2867c;
--bad-soft:#3a1d1b;--warn:#f0c065;--warn-soft:#33290f;--chart:#4cc39a}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,
"Segoe UI",Roboto,sans-serif}
main{max-width:1060px;margin:0 auto;padding:24px 16px 48px}
header{display:flex;align-items:baseline;justify-content:space-between;gap:12px;flex-wrap:wrap}
h1{font-size:22px;margin:0}h2{font-size:16px;margin:0 0 12px}
.muted{color:var(--muted)}.small{font-size:13px}
.badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:12px;font-weight:600;
background:var(--warn-soft);color:var(--warn)}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(135px,1fr));gap:10px;margin:20px 0}
.tile,.card{background:var(--card);border:1px solid var(--line);border-radius:12px}
.tile{padding:12px 14px}.tile .k{font-size:12px;color:var(--muted)}.tile .v{font-size:17px;
font-weight:650;margin-top:2px;font-variant-numeric:tabular-nums;white-space:nowrap}
.card{padding:18px;margin:16px 0;overflow-x:auto}
.pos{color:var(--accent)}.neg{color:var(--bad)}
table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}
th,td{text-align:left;padding:8px 6px;border-bottom:1px solid var(--line);vertical-align:top}
th{font-size:12px;color:var(--muted);font-weight:600}
td.r,th.r{text-align:right}
.pill{display:inline-block;padding:1px 8px;border-radius:999px;font-size:12px;font-weight:600}
.pill.kazandi{background:var(--accent-soft);color:var(--accent)}
.pill.kaybetti{background:var(--bad-soft);color:var(--bad)}
.pill.iade,.pill.bek{background:var(--line);color:var(--muted)}
.decision{font-size:13px;font-weight:700;letter-spacing:.04em}
.summary{display:flex;gap:20px;flex-wrap:wrap;margin-top:12px}
.summary div{min-width:120px}.summary b{display:block;font-size:18px}
.flash{padding:10px 14px;border-radius:10px;background:var(--accent-soft);color:var(--accent);
margin-top:16px}
form.inline{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:8px 0}
input{font:inherit;padding:7px 10px;border:1px solid var(--line);border-radius:8px;
background:var(--bg);color:var(--ink);width:140px}
button{font:inherit;padding:7px 14px;border-radius:8px;border:1px solid var(--accent);
background:var(--accent);color:#fff;cursor:pointer}
button.ghost{background:transparent;color:var(--accent)}
svg{width:100%;height:auto;display:block}
footer{margin-top:28px;font-size:13px;color:var(--muted)}
"""


def _tl_cls(kurus: int) -> str:
    return "pos" if kurus > 0 else "neg" if kurus < 0 else ""


def _chart(series: list[tuple[str, int]]) -> str:
    if len(series) < 2:
        return '<p class="muted small">Grafik ilk kupon sonuçlanınca oluşur.</p>'
    w, h, pad = 640, 180, 8
    times = [datetime.fromisoformat(t).timestamp() for t, _ in series]
    vals = [v / 100 for _, v in series]
    t0, t1 = min(times), max(times) or 1
    lo, hi = min(vals), max(vals)
    if hi == lo:
        hi = lo + 1
    span_t = (t1 - t0) or 1

    def xy(t, v):
        return (pad + (t - t0) / span_t * (w - 2 * pad),
                h - pad - (v - lo) / (hi - lo) * (h - 2 * pad))

    # Basamaklı çizgi: bakiye bir sonraki harekete kadar sabit kalır.
    pts = []
    for i, (t, v) in enumerate(zip(times, vals, strict=True)):
        if i:
            pts.append(xy(t, vals[i - 1]))
        pts.append(xy(t, v))
    line = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
    area = f"{pad},{h - pad} {line} {pts[-1][0]:.1f},{h - pad}"
    return (
        f'<svg viewBox="0 0 {w} {h}" role="img" aria-label="Kasa grafiği">'
        f'<polygon points="{area}" fill="var(--chart)" opacity=".12"/>'
        f'<polyline points="{line}" fill="none" stroke="var(--chart)" stroke-width="2"/>'
        f'</svg><div class="muted small" style="display:flex;justify-content:space-between">'
        f'<span>en düşük {escape(num(lo))} TL</span><span>en yüksek {escape(num(hi))} TL</span></div>'
    )


def _legs_table(ledger: Ledger, coupon_id: int) -> str:
    rows = []
    for leg in ledger.legs(coupon_id):
        ev = leg["fair_prob"] * leg["odds"] - 1
        result = leg["result"]
        pill = (f'<span class="pill {escape(result)}">{RESULT_LABEL[result]}'
                f'{" " + escape(leg["score"]) if leg["score"] else ""}</span>') if result else \
            '<span class="pill bek">Bekliyor</span>'
        code = f'<div class="muted small">kod {escape(leg["book_code"])}</div>' \
            if leg["book_code"] else ""
        rows.append(
            f"<tr><td>{escape(local_time(leg['kickoff']))}</td>"
            f"<td>{escape(leg['home'])} – {escape(leg['away'])}"
            f"<div class='muted small'>{escape(leg['league'] or '')}</div></td>"
            f"<td>{escape(outcome_label(leg['market'], leg['outcome']))}{code}</td>"
            f"<td class='r'>{num(leg['odds'])}</td><td class='r'>{num(1 / leg['fair_prob'])}</td>"
            f"<td class='r {_tl_cls(1 if ev > 0 else -1)}'>{pct(ev, signed=True)}</td>"
            f"<td class='r'>{leg['mbs']}</td><td>{pill}</td></tr>"
        )
    return ("<table><thead><tr><th>Saat</th><th>Maç</th><th>Tahmin</th><th class='r'>iddaa</th>"
            "<th class='r'>Adil</th><th class='r'>Avantaj</th><th class='r'>MBS</th><th>Durum</th>"
            "</tr></thead><tbody>" + "".join(rows) + "</tbody></table>")


def _still_relevant(ledger: Ledger, c, now: datetime) -> bool:
    """Oynanan açık kuponlar ve henüz başlamamış öneriler panelde gösterilir."""
    if c["played"]:
        return True
    first = min(datetime.fromisoformat(leg["kickoff"]) for leg in ledger.legs(c["id"]))
    return first > now


def _coupon_card(ledger: Ledger, coupon_id: int, title: str, interactive: bool, token: str,
                 extra: str = "") -> str:
    c = ledger.coupon(coupon_id)
    ev = c["win_prob"] * c["total_odds"] - 1
    stake = c["stake"] if c["played"] else c["suggested_stake"]
    played = ("Oynandı" if c["played"] else "Öneri — henüz oynanmadı")
    result = c["result"]
    status = f'<span class="pill {escape(result)}">{RESULT_LABEL[result]}</span>' if result \
        else f'<span class="pill bek">{played}</span>'
    form = ""
    if interactive and not c["played"] and result is None:
        form = (
            f'<form class="inline" method="post" action="/islem">'
            f'<input type="hidden" name="token" value="{escape(token)}">'
            f'<input type="hidden" name="islem" value="oynadim">'
            f'<input type="hidden" name="kupon" value="{c["id"]}">'
            f'<label class="small muted">Oynadığın tutar (TL)</label>'
            f'<input name="tutar" inputmode="decimal" value="{stake // 100 if stake else ""}">'
            f'<button>Oynadım</button></form>'
        )
    payout = f"{fmt_tl(int(stake * c['total_odds']))}" if stake else "-"
    return (
        f'<section class="card"><div style="display:flex;justify-content:space-between;gap:8px;'
        f'flex-wrap:wrap"><h2>{escape(title)} · #{c["id"]}</h2><div>{status}</div></div>'
        f'{_legs_table(ledger, coupon_id)}'
        f'<div class="summary"><div><span class="muted small">Toplam oran</span>'
        f'<b>{num(c["total_odds"])}</b></div><div><span class="muted small">Tutma olasılığı</span>'
        f'<b>{pct(c["win_prob"])}</b></div><div><span class="muted small">Beklenen değer</span>'
        f'<b class="{_tl_cls(1 if ev > 0 else -1)}">{pct(ev, signed=True)}</b></div>'
        f'<div><span class="muted small">{"Oynanan" if c["played"] else "Önerilen"} tutar</span>'
        f'<b>{fmt_tl(stake) if stake else "-"}</b></div><div><span class="muted small">Tutarsa</span>'
        f'<b>{payout}</b></div></div>{form}{extra}</section>'
    )


def _tiles(s: Stats) -> str:
    def tile(k, v, cls=""):
        return f'<div class="tile"><div class="k">{k}</div><div class="v {cls}">{v}</div></div>'

    hit = f"{s.won}/{s.played}" if s.played else "-"
    return '<div class="grid">' + "".join([
        tile("Kasa", fmt_tl(s.balance)),
        tile("Net yatırılan", fmt_tl(s.deposited - s.withdrawn)),
        tile("Bahis kâr/zarar", fmt_tl(s.betting_pl), _tl_cls(s.betting_pl)),
        tile("ROI", pct(s.roi, signed=True), _tl_cls(s.betting_pl)),
        tile("Tutan / oynanan", hit),
        tile("Beklenen tutan", num(s.expected_wins, 1) if s.played else "-"),
        tile("Açıktaki bahis", fmt_tl(s.open_stake)),
    ]) + "</div>"


def _today(ledger: Ledger, now: datetime, interactive: bool, token: str) -> str:
    day = now.astimezone(TR).strftime("%Y-%m-%d")
    run = ledger.run_for(day)
    if run is None:
        return ('<section class="card"><div class="decision muted">BUGÜN</div>'
                '<p>Bugün için henüz karar üretilmedi. Her sabah 06:00\'da otomatik üretilir.</p>'
                '</section>')
    if run["decision"] == "kupon" and run["coupon_id"]:
        note = f'<p class="muted small">{escape(run["reason"])}</p>' if run["reason"] else ""
        extra = f"{note}<p class='muted small'>{escape(run['summary'] or '')}</p>"
        return _coupon_card(ledger, run["coupon_id"], "Günün kuponu", interactive, token, extra)
    label = "BUGÜN PAS" if run["decision"] == "pas" else "KORUMA DEVREDE"
    return (f'<section class="card"><div class="decision">{label}</div>'
            f'<p>{escape(run["reason"] or "")}</p>'
            f'<p class="muted small">{escape(run["summary"] or "")}</p></section>')


def _history(ledger: Ledger) -> str:
    rows = []
    for c in ledger.coupons(limit=40):
        legs = ledger.legs(c["id"])
        matches = "<br>".join(
            f"{escape(leg['home'])} – {escape(leg['away'])} "
            f"<span class='muted'>{escape(outcome_label(leg['market'], leg['outcome']))}</span>"
            for leg in legs)
        result = c["result"]
        pill = f'<span class="pill {escape(result)}">{RESULT_LABEL[result]}</span>' if result \
            else '<span class="pill bek">Bekliyor</span>'
        if c["played"] and result:
            pl = (c["payout"] or 0) - c["stake"]
            pl_html = f'<span class="{_tl_cls(pl)}">{fmt_tl(pl)}</span>'
        elif c["played"]:
            pl_html = "-"
        else:
            pl_html = '<span class="muted">oynanmadı</span>'
        rows.append(
            f"<tr><td>#{c['id']}<div class='muted small'>{escape(c['day'])}</div></td>"
            f"<td class='small'>{matches}</td><td class='r'>{num(c['total_odds'])}</td>"
            f"<td class='r'>{pct(c['win_prob'])}</td>"
            f"<td class='r'>{fmt_tl(c['stake']) if c['played'] else '-'}</td>"
            f"<td>{pill}</td><td class='r'>{pl_html}</td></tr>"
        )
    if not rows:
        return '<p class="muted">Henüz kupon yok.</p>'
    return ("<table><thead><tr><th>Kupon</th><th>Maçlar</th><th class='r'>Oran</th>"
            "<th class='r'>Olasılık</th><th class='r'>Tutar</th><th>Sonuç</th>"
            "<th class='r'>K/Z</th></tr></thead><tbody>" + "".join(rows) + "</tbody></table>")


def _runs(ledger: Ledger) -> str:
    rows = [
        f"<tr><td>{escape(r['day'])}</td><td>{escape(r['decision'].upper())}</td>"
        f"<td class='small'>{escape(r['reason'] or '')}"
        f"<div class='muted'>{escape(r['summary'] or '')}</div></td></tr>"
        for r in ledger.runs(14)
    ]
    if not rows:
        return '<p class="muted">Henüz günlük çalıştırma yok.</p>'
    return ("<table><thead><tr><th>Gün</th><th>Karar</th><th>Ayrıntı</th></tr></thead><tbody>"
            + "".join(rows) + "</tbody></table>")


def _actions(token: str) -> str:
    def form(action, label, field=None, ghost=False):
        inp = ('<input name="tutar" inputmode="decimal" placeholder="Tutar (TL)" required>'
               if field else "")
        cls = ' class="ghost"' if ghost else ""
        return (f'<form class="inline" method="post" action="/islem">'
                f'<input type="hidden" name="token" value="{escape(token)}">'
                f'<input type="hidden" name="islem" value="{action}">{inp}'
                f'<button{cls}>{label}</button></form>')

    return ('<section class="card"><h2>İşlemler</h2>'
            + form("yatir", "Kasaya yatır", field=True)
            + form("cek", "Kasadan çek", field=True, ghost=True)
            + '<div style="display:flex;gap:8px;flex-wrap:wrap">'
            + form("sonuclandir", "Sonuçları kontrol et", ghost=True)
            + form("uret", "Bugünün kararını yeniden üret", ghost=True)
            + "</div></section>")


def dashboard(ledger: Ledger, cfg, demo: bool = False, interactive: bool = False,
              token: str = "", flash: str | None = None, now: datetime | None = None) -> str:
    now = now or ledger.clock()
    s = ledger.stats()
    today_run = ledger.run_for(now.astimezone(TR).strftime("%Y-%m-%d"))
    today_cid = today_run["coupon_id"] if today_run else None
    opens = [c for c in ledger.open_coupons() if c["id"] != today_cid and _still_relevant(ledger, c, now)]
    open_cards = "".join(_coupon_card(ledger, c["id"], "Açık kupon", interactive, token)
                         for c in opens)
    badge = ' <span class="badge">DEMO · sentetik veri</span>' if demo else ""
    flash_html = f'<div class="flash">{escape(flash)}</div>' if flash else ""
    return f"""<!doctype html>
<html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bilinçli Kupon</title><style>{CSS}</style></head>
<body><main>
<header><h1>Bilinçli Kupon{badge}</h1>
<span class="muted small">Güncelleme: {escape(now.astimezone(TR).strftime('%d.%m.%Y %H:%M'))}</span></header>
{flash_html}
{_tiles(s)}
{_today(ledger, now, interactive, token)}
{open_cards}
<section class="card"><h2>Kasa seyri</h2>{_chart(ledger.balance_series())}</section>
<section class="card"><h2>Kupon geçmişi</h2>{_history(ledger)}</section>
<section class="card"><h2>Son 14 günün kararları</h2>{_runs(ledger)}</section>
{_actions(token) if interactive else ""}
<footer>Bu araç kazanç garantisi vermez; iddaa oranlarındaki marj her kuponda aleyhinedir.
Yalnızca keskin piyasaya göre avantajlı görünen seçimleri önerir, avantaj yoksa pas geçer.
"Beklenen tutan" ile gerçekleşen arasındaki fark, modelin gerçek dünyada çalışıp
çalışmadığının en dürüst göstergesidir.</footer>
</main></body></html>"""
