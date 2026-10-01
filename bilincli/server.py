"""Yerel web paneli: http://127.0.0.1:8765 (yalnızca bu bilgisayardan erişilir)."""

from __future__ import annotations

import secrets
import urllib.parse
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import render
from .app import App, generate, settle, write_panel
from .ledger import LedgerError, parse_tl
from .providers.http import ProviderError


def make_handler(app_factory, token: str):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # sessiz
            pass

        def _send(self, body: str, status: int = 200) -> None:
            data = body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Frame-Options", "DENY")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            url = urllib.parse.urlsplit(self.path)
            if url.path != "/":
                self._send("Bulunamadı", HTTPStatus.NOT_FOUND)
                return
            flash = urllib.parse.parse_qs(url.query).get("m", [None])[0]
            app = app_factory()
            try:
                self._send(render.dashboard(app.ledger, app.cfg, interactive=True,
                                            token=token, flash=flash))
            finally:
                app.ledger.close()

        def do_POST(self):
            if urllib.parse.urlsplit(self.path).path != "/islem":
                self._send("Bulunamadı", HTTPStatus.NOT_FOUND)
                return
            length = min(int(self.headers.get("Content-Length") or 0), 10_000)
            form = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8"))
            get = lambda k: form.get(k, [""])[0]  # noqa: E731
            if not secrets.compare_digest(get("token"), token):
                self._send("Geçersiz istek", HTTPStatus.FORBIDDEN)
                return
            app = app_factory()
            try:
                message = self._apply(app, get("islem"), get)
                write_panel(app)
            except (LedgerError, ProviderError, ValueError) as exc:
                message = f"Hata: {exc}"
            finally:
                app.ledger.close()
            self.send_response(HTTPStatus.SEE_OTHER)
            self.send_header("Location", "/?" + urllib.parse.urlencode({"m": message}))
            self.end_headers()

        @staticmethod
        def _apply(app: App, action: str, get) -> str:
            if action == "yatir":
                amount = parse_tl(get("tutar"))
                app.ledger.deposit(amount, "panel")
                return f"Kasaya {get('tutar')} TL eklendi."
            if action == "cek":
                amount = parse_tl(get("tutar"))
                app.ledger.withdraw(amount, "panel")
                return f"Kasadan {get('tutar')} TL çekildi."
            if action == "oynadim":
                cid = int(get("kupon"))
                app.ledger.mark_played(cid, parse_tl(get("tutar")))
                return f"Kupon #{cid} oynandı olarak kaydedildi."
            if action == "sonuclandir":
                msgs = settle(app)
                return " ".join(msgs) or "Sonuçlanacak maç yok."
            if action == "uret":
                r = generate(app, force=True)
                if r.error:
                    return f"Hata: {r.error}"
                if r.blocked:
                    return r.blocked
                if r.decision.is_pass:
                    return f"Bugün pas: {r.decision.reason}"
                return f"Yeni kupon #{r.coupon_id} üretildi."
            raise ValueError("bilinmeyen işlem")

    return Handler


def serve(app_factory, port: int = 8765, open_browser: bool = True) -> None:
    token = secrets.token_urlsafe(16)  # başka sitelerin bu panele form göndermesini engeller
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(app_factory, token))
    url = f"http://127.0.0.1:{port}/"
    print(f"Panel açık: {url}  (kapatmak için Ctrl+C)")
    if open_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
