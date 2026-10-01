"""Komut satırı arayüzü: `bilincli <komut>` ya da `python -m bilincli <komut>`."""

from __future__ import annotations

import argparse
import logging
import re
import shutil
import sys
import time
import webbrowser
from datetime import datetime, timedelta
from importlib import resources
from pathlib import Path

from . import render
from .app import App, day_key, run_daily, settle, write_panel
from .config import DEFAULT_CONFIG_NAME, Config, ConfigError, load_config
from .engine import decide
from .ledger import LedgerError, fmt_tl, parse_tl, to_kurus
from .matching import match_events
from .models import LOST, TR, VOID, WON
from .providers.http import ProviderError


def _app(args) -> App:
    return App(load_config(args.ayar))


# ---- komutlar -------------------------------------------------------------
def cmd_kur(args) -> int:
    target = Path(args.ayar or DEFAULT_CONFIG_NAME)
    if target.exists():
        print(f"{target} zaten var, dokunulmadı.")
    else:
        template = resources.files("bilincli").joinpath("ornek_ayar.toml").read_text("utf-8")
        target.write_text(template, encoding="utf-8")
        print(f"{target} oluşturuldu.")
    cfg = load_config(target)
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    print(f"Veri klasörü: {cfg.data_dir}")
    print("\nSonraki adımlar:\n"
          "  1) bilincli.toml içine The Odds API anahtarını yaz (odds_api_key)\n"
          "  2) bilincli kontrol          → veri kaynaklarını test et\n"
          "  3) bilincli yatir 5000       → kasayı oluştur\n"
          "  4) bilincli gunluk           → bugünün kararını üret\n"
          "  5) bilincli zamanla          → her sabah 06:00'da otomatik çalıştır")
    return 0


def cmd_yatir(args) -> int:
    app = _app(args)
    app.ledger.deposit(parse_tl(args.tutar), args.not_ or "")
    print(f"Yatırıldı. Kasa: {fmt_tl(app.ledger.balance())}")
    write_panel(app)
    return 0


def cmd_cek(args) -> int:
    app = _app(args)
    app.ledger.withdraw(parse_tl(args.tutar), args.not_ or "")
    print(f"Çekildi. Kasa: {fmt_tl(app.ledger.balance())}")
    write_panel(app)
    return 0


def cmd_gunluk(args) -> int:
    app = _app(args)
    result = run_daily(app, force=args.zorla, notify=not args.bildirimsiz)
    if result.skipped:
        for msg in result.settle_messages:
            print(msg)
        print(f"Bugünün ({result.day}) kararı zaten verilmiş. Yeniden üretmek için --zorla.")
        if result.coupon_id:
            print(render.coupon_text(app.ledger, result.coupon_id))
        return 0
    print(render.daily_text(app.ledger, result))
    print(f"\nPanel: {app.cfg.panel_path}")
    return 1 if result.error else 0


def cmd_oynadim(args) -> int:
    app = _app(args)
    c = app.ledger.coupon(args.kupon)
    stake = parse_tl(args.tutar) if args.tutar else c["suggested_stake"]
    if not stake:
        raise LedgerError("önerilen tutar yok; --tutar ile oynadığın tutarı gir")
    odds = None
    if args.oranlar:
        try:
            odds = [float(x.replace(",", ".")) for x in re.split(r"[;\s]+", args.oranlar.strip())]
        except ValueError as exc:
            raise LedgerError("oranları boşlukla ayırarak yaz, örn. \"1.85 2.10\"") from exc
    app.ledger.mark_played(args.kupon, stake, odds)
    print(f"Kupon #{args.kupon} oynandı: {fmt_tl(stake)}. Kasa: {fmt_tl(app.ledger.balance())}")
    write_panel(app)
    return 0


def cmd_sonuclandir(args) -> int:
    app = _app(args)
    msgs = settle(app)
    print("\n".join(msgs) if msgs else "Sonuçlanacak maç yok.")
    write_panel(app)
    return 0


def cmd_sonuc(args) -> int:
    app = _app(args)
    legs = app.ledger.legs(args.kupon)
    match = [leg for leg in legs if leg["position"] == args.sira]
    if not match:
        raise LedgerError(f"kupon #{args.kupon} içinde {args.sira}. maç yok")
    app.ledger.set_leg_result(match[0]["id"], args.sonuc, args.skor)
    outcome = app.ledger.settle_coupon(args.kupon)
    print(f"Kaydedildi. Kupon durumu: {render.RESULT_LABEL[outcome]}")
    write_panel(app)
    return 0


def cmd_durum(args) -> int:
    print(render.status_text(_app(args).ledger))
    return 0


def cmd_kupon(args) -> int:
    app = _app(args)
    cid = args.kupon
    if cid is None:
        run = app.ledger.run_for(day_key(app.now()))
        if not run:
            print("Bugün için karar yok. `bilincli gunluk` çalıştır.")
            return 0
        if not run["coupon_id"]:
            print(f"Bugün {run['decision'].upper()}: {run['reason']}")
            return 0
        cid = run["coupon_id"]
    print(render.coupon_text(app.ledger, cid))
    return 0


def cmd_panel(args) -> int:
    from .server import serve
    load_config(args.ayar)  # ayar hatası varsa sunucu açılmadan söylensin
    serve(lambda: _app(args), port=args.port, open_browser=not args.tarayicisiz)
    return 0


def cmd_kontrol(args) -> int:
    app = _app(args)
    ok = True
    try:
        book = app.book.fetch_events()
        print(f"iddaa bülteni ({app.cfg.sources.iddaa_source}): {len(book)} maç okundu")
        for ev in book[:5]:
            print(f"  {ev.kickoff.astimezone(TR):%d.%m %H:%M} {ev.home} - {ev.away} "
                  f"MBS {ev.mbs} {ev.odds}")
        if args.ham and getattr(app.book, "last_payload", None) is not None:
            import json
            raw = app.cfg.data_dir / "nesine_ham.json"
            raw.write_text(json.dumps(app.book.last_payload, ensure_ascii=False)[:5_000_000],
                           encoding="utf-8")
            print(f"  ham yanıt kaydedildi: {raw}")
    except ProviderError as exc:
        print(f"iddaa bülteni okunamadı: {exc}")
        ok, book = False, []
    try:
        sharp = app.sharp.fetch_events()
        print(f"Keskin piyasa: {len(sharp)} maç (kalan API kredisi: {app.sharp.remaining})")
    except ProviderError as exc:
        print(f"Keskin piyasa okunamadı: {exc}")
        ok, sharp = False, []
    if book and sharp:
        pairs = match_events(book, sharp)
        print(f"Eşleşen maç: {len(pairs)}")
        for b, s, score in pairs[:8]:
            print(f"  {b.home} - {b.away}  ⇄  {s.home} - {s.away}  (benzerlik {score:.2f})")
        decision = decide(book, sharp, app.now(), app.cfg.strategy)
        print(render.stats_line(decision.stats))
    return 0 if ok else 1


def cmd_zamanla(args) -> int:
    hour, minute = (int(x) for x in args.saat.split(":"))
    app_args = args
    print(f"Zamanlayıcı çalışıyor: her gün {args.saat} (Türkiye saati). Durdurmak için Ctrl+C.")

    def next_run(after: datetime) -> datetime:
        local = after.astimezone(TR)
        target = local.replace(hour=hour, minute=minute, second=0, microsecond=0)
        return target if target > local else target + timedelta(days=1)

    def run_once() -> bool:
        try:
            app = _app(app_args)
            result = run_daily(app)
            print(f"[{datetime.now(TR):%d.%m %H:%M}] {result.headline}"
                  + ("" if not result.error else f": {result.error}"), flush=True)
            app.ledger.close()
            return result.error is None
        except Exception as exc:  # zamanlayıcı tek bir hata yüzünden durmasın
            logging.exception("Günlük çalıştırma başarısız: %s", exc)
            return False

    # Bilgisayar 06:00'da kapalıysa, açıldığında bugünün kararını hemen üret.
    now = datetime.now(TR)
    today_target = now.replace(hour=hour, minute=minute, second=0, microsecond=0)
    probe = _app(app_args)
    if now >= today_target and probe.ledger.run_for(day_key(now)) is None:
        run_once()
    probe.ledger.close()
    target = next_run(datetime.now(TR))
    retries = 0
    try:
        while True:
            if datetime.now(TR) >= target:
                if run_once() or retries >= 3:
                    retries = 0
                    target = next_run(datetime.now(TR))
                else:  # veri kaynağına ulaşılamadı: 20 dk sonra yeniden dene
                    retries += 1
                    target = datetime.now(TR) + timedelta(minutes=20)
            time.sleep(30)
    except KeyboardInterrupt:
        return 0


def cmd_demo(args) -> int:
    from .providers.demo import DemoWorld

    out = Path(args.klasor).resolve()
    if out.exists():
        shutil.rmtree(out)
    cfg = Config(data_dir=out)
    cfg.strategy.auto_play = True
    world = DemoWorld(seed=args.tohum)
    today = datetime.now(TR).replace(hour=6, minute=0, second=0, microsecond=0)
    start = today - timedelta(days=args.gun)
    world.now = start - timedelta(minutes=1)
    app = App(cfg, demo_world=world)
    initial = to_kurus(args.kasa)
    app.ledger.deposit(initial, "demo başlangıç")

    baseline = initial
    kinds = {"KUPON": 0, "PAS": 0, "KORUMA": 0, "HATA": 0}
    for d in range(args.gun):
        world.now = start + timedelta(days=d)
        result = run_daily(app, notify=False)
        kinds[result.headline] += 1
        # Kıyas: her gün en düşük oranlı 3 favoriyle "banko" kupon, kasanın %2'si.
        picks = world.favourites(world.now)
        stake = baseline * 2 // 100
        odds = 1.0
        for o, _ in picks:
            odds *= o
        baseline += int(stake * odds) - stake if all(hit for _, hit in picks) else -stake
    # Bugün: dünün kuponları sonuçlanır, bugünün kararı üretilir (sonucu henüz belli değil).
    world.now = start + timedelta(days=args.gun)
    kinds[run_daily(app, notify=False).headline] += 1

    s = app.ledger.stats()
    print(f"DEMO · {args.gun} gün · sentetik veri (hayali takımlar, iddaa marjı ~%8)")
    print(f"Kupon günü {kinds['KUPON']}, pas {kinds['PAS']}, koruma {kinds['KORUMA']} "
          f"(bugün dahil)")
    print(f"Bilinçli strateji : {fmt_tl(initial)} → {fmt_tl(s.balance)} "
          f"(ROI {render.pct(s.roi, signed=True)}, {s.won}/{s.played} tuttu, "
          f"beklenen {render.num(s.expected_wins, 1)})")
    print(f"'Banko' 3'lü kupon: {fmt_tl(initial)} → {fmt_tl(baseline)}")
    print(f"Panel: {cfg.panel_path}")
    print("Not: Demo dünyasında avantaj tasarım gereği var. Gerçekte var olup olmadığını "
          "yalnızca gerçek veriyle tutulan kayıt gösterir.")
    if args.ac:
        webbrowser.open(cfg.panel_path.as_uri())
    return 0


# ---- argparse -------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="bilincli", description="Bilinçli Kupon — iddaa için "
                                "değer odaklı kupon ve kasa takibi.")
    p.add_argument("--ayar", help=f"ayar dosyası (varsayılan: ./{DEFAULT_CONFIG_NAME})")
    p.add_argument("-v", "--ayrintili", action="store_true", help="ayrıntılı kayıt")
    sub = p.add_subparsers(dest="komut", required=True, metavar="komut")

    sub.add_parser("kur", help="ayar dosyasını ve veri klasörünü oluştur").set_defaults(fn=cmd_kur)

    for name, fn, text in (("yatir", cmd_yatir, "kasaya para ekle"),
                           ("cek", cmd_cek, "kasadan para çek")):
        sp = sub.add_parser(name, help=text)
        sp.add_argument("tutar", help="TL, örn. 1500 ya da 1.500,50")
        sp.add_argument("--not", dest="not_", help="açıklama")
        sp.set_defaults(fn=fn)

    sp = sub.add_parser("gunluk", help="sonuçları kontrol et + bugünün kararını üret + bildir")
    sp.add_argument("--zorla", action="store_true", help="bugün karar verilmiş olsa da yeniden üret")
    sp.add_argument("--bildirimsiz", action="store_true", help="Telegram mesajı gönderme")
    sp.set_defaults(fn=cmd_gunluk)

    sp = sub.add_parser("oynadim", help="öneriyi oynadığını kaydet (tutar kasadan düşülür)")
    sp.add_argument("kupon", type=int)
    sp.add_argument("--tutar", help="oynadığın tutar (varsayılan: önerilen)")
    sp.add_argument("--oranlar", help="oran değiştiyse maç sırasıyla, örn. \"1.85 2.10 1.60\"")
    sp.set_defaults(fn=cmd_oynadim)

    sub.add_parser("sonuclandir", help="biten maçların sonuçlarını çek, kuponları kapat"
                   ).set_defaults(fn=cmd_sonuclandir)

    sp = sub.add_parser("sonuc", help="bir maçın sonucunu elle gir (ertelenen maç vb.)")
    sp.add_argument("kupon", type=int)
    sp.add_argument("sira", type=int, help="kupondaki maç sırası (1, 2, ...)")
    sp.add_argument("sonuc", choices=[WON, LOST, VOID])
    sp.add_argument("--skor", help="örn. 2-1")
    sp.set_defaults(fn=cmd_sonuc)

    sub.add_parser("durum", help="kasa ve performans özeti").set_defaults(fn=cmd_durum)

    sp = sub.add_parser("kupon", help="kupon ayrıntısı (varsayılan: bugünün kuponu)")
    sp.add_argument("kupon", type=int, nargs="?")
    sp.set_defaults(fn=cmd_kupon)

    sp = sub.add_parser("panel", help="yerel web panelini aç (127.0.0.1)")
    sp.add_argument("--port", type=int, default=8765)
    sp.add_argument("--tarayicisiz", action="store_true", help="tarayıcıyı otomatik açma")
    sp.set_defaults(fn=cmd_panel)

    sp = sub.add_parser("kontrol", help="veri kaynaklarını ve eşleştirmeyi test et")
    sp.add_argument("--ham", action="store_true", help="ham iddaa yanıtını veri klasörüne kaydet")
    sp.set_defaults(fn=cmd_kontrol)

    sp = sub.add_parser("zamanla", help="her gün belirli saatte otomatik çalış (varsayılan 06:00)")
    sp.add_argument("--saat", default="06:00")
    sp.set_defaults(fn=cmd_zamanla)

    sp = sub.add_parser("demo", help="sentetik veriyle uçtan uca simülasyon")
    sp.add_argument("--gun", type=int, default=60)
    sp.add_argument("--kasa", type=float, default=10_000)
    sp.add_argument("--tohum", type=int, default=7)
    sp.add_argument("--klasor", default="veri_demo")
    sp.add_argument("--ac", action="store_true", help="paneli tarayıcıda aç")
    sp.set_defaults(fn=cmd_demo)
    return p


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure") and not stream.isatty():
            stream.reconfigure(encoding="utf-8", errors="replace")
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=logging.INFO if args.ayrintili else logging.WARNING,
                        format="%(levelname)s %(name)s: %(message)s")
    try:
        return args.fn(args)
    except (ConfigError, LedgerError, ProviderError) as exc:
        print(f"Hata: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
