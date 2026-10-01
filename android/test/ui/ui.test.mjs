// Arayüz (assets/index.html) için uçtan uca testler: sahte Android köprüsüyle jsdom'da çalışır.
// Kullanım: node ui.test.mjs <durum.json>   (durum, PreviewState ile Java çekirdeğinden üretilir)
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";

const html = readFileSync(new URL("../../assets/index.html", import.meta.url), "utf8");
const baseState = JSON.parse(readFileSync(process.argv[2], "utf8"));
const clone = (o) => JSON.parse(JSON.stringify(o));

function boot(state, hash = "") {
  const calls = [];
  const errors = [];
  const dom = new JSDOM(html, {
    url: "file:///android_asset/index.html" + hash,
    runScripts: "dangerously",
    pretendToBeVisual: true,
    beforeParse(win) {
      win.scrollTo = () => {};
      win.addEventListener("error", (e) => errors.push(e.message));
      win.MockAndroid = {
        state: () => JSON.stringify(state),
        act: (action, payload, id) => {
          calls.push({ action, payload: JSON.parse(payload) });
          setTimeout(() => win.onActResult(id, { ok: true, message: "tamam" }), 0);
        },
      };
    },
  });
  const w = dom.window;
  const $ = (sel) => w.document.querySelector(sel);
  const $$ = (sel) => [...w.document.querySelectorAll(sel)];
  const button = (text) => {
    const b = $$("button").find((x) => x.textContent.trim() === text);
    assert.ok(b, `buton yok: ${text}`);
    return b;
  };
  const tick = () => new Promise((r) => setTimeout(r, 5));
  return { w, $, $$, button, calls, errors, tick, text: () => w.document.body.textContent };
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test("günün kuponu ve özet görünür", async () => {
  const t = boot(clone(baseState));
  assert.match(t.text(), /Günün kuponu/);
  assert.match(t.$("#hdrBal").textContent, /TL$/);
  assert.equal(t.$$("nav button").length, 4);
  assert.deepEqual(t.errors, []);
});

test("takım adları HTML olarak çalıştırılmaz", async () => {
  const s = clone(baseState);
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  c.legs[0].home = '<img src=x onerror="window.pwned=1">';
  const t = boot(s);
  assert.equal(t.$$("main img").length, 0);
  assert.match(t.text(), /<img src=x/);
  assert.equal(t.w.pwned, undefined);
});

test("oynadım: tutar ve değişen oranlar köprüye gider", async () => {
  const s = clone(baseState);
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  c.played = false; c.stake = 0; c.suggestedStake = 15000; c.result = null;
  const t = boot(s);
  t.button("Oynadım").click();
  assert.equal(t.$("#pAmount").value, "150");
  t.$("#pAmount").value = "200";
  t.$("#pOdds").value = "2,70";
  t.button("Kaydet").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "played", payload: { coupon: c.id, amount: "200", odds: "2,70" } });
});

test("pas günü gerekçesiyle gösterilir", async () => {
  const s = clone(baseState);
  s.todayRun = { day: s.today, decision: "pas", reason: "48 seçim karşılaştırıldı, hiçbirinde avantaj yok. Bugün pas.", summary: "Bülten 32 maç", couponId: null };
  const t = boot(s);
  assert.match(t.text(), /Pas/);
  assert.match(t.text(), /hiçbirinde avantaj yok/);
});

test("ilk kurulum: anahtar ve kasa uyarıları, şimdi üret", async () => {
  const s = clone(baseState);
  Object.assign(s, { coupons: [], runs: [], todayRun: null, series: [], transactions: [], notifications: false, exactAlarm: false });
  s.stats = Object.fromEntries(Object.keys(s.stats).map((k) => [k, k === "roi" ? null : 0]));
  s.settings.oddsApiKey = "";
  const t = boot(s);
  assert.match(t.text(), /Kurulumu tamamla/);
  assert.match(t.text(), /Kasan boş/);
  assert.match(t.text(), /Bildirim izni kapalı/);
  assert.match(t.text(), /Tam saatli alarm/);
  t.button("Şimdi üret").click();
  await t.tick();
  assert.equal(t.calls.at(-1).action, "generate");
  t.w.show("gecmis");
  assert.match(t.text(), /Henüz kupon yok/);
  assert.match(t.text(), /Grafik ilk kupon/);
});

test("ayarlar: Türkçe ondalık ve yüzdeler doğru dönüştürülür", async () => {
  const t = boot(clone(baseState), "#ayarlar");
  t.$("#sKey").value = "  abc123 ";
  t.$$(".lg").find((x) => x.value === "soccer_epl").checked = false;
  t.$("#f_minLegEv").value = "2,5";
  t.$("#f_maxLegOdds").value = "3,2";
  t.$("#f_minCouponAmount").value = "1.000";
  t.$("#sTime").value = "07:30";
  t.button("Ayarları kaydet").click();
  await t.tick();
  const st = t.calls.at(-1).payload.settings;
  assert.equal(t.calls.at(-1).action, "saveSettings");
  assert.equal(st.oddsApiKey, "abc123");
  assert.ok(!st.leagues.includes("soccer_epl"));
  assert.ok(Math.abs(st.minLegEv - 0.025) < 1e-12);
  assert.equal(st.maxLegOdds, 3.2);
  assert.equal(st.minCouponAmount, 1000);
  assert.equal(st.runHour, 7);
  assert.equal(st.runMinute, 30);
});

test("kasa: yatırma ve boş tutar uyarısı", async () => {
  const t = boot(clone(baseState), "#kasa");
  t.button("Yatır").click();
  assert.equal(t.calls.length, 0);
  assert.match(t.$("#toast").textContent, /Tutar gir/);
  t.$("#mAmount").value = "5.000";
  t.button("Yatır").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "deposit", payload: { amount: "5.000" } });
});

test("geri tuşu: önce sayfayı kapatır, sonra ana sekmeye döner", async () => {
  const t = boot(clone(baseState), "#gecmis");
  t.w.detail(t.w.S.coupons[0].id);
  assert.equal(t.w.goBack(), true);
  assert.ok(!t.$("#sheetBg").classList.contains("show"));
  assert.equal(t.w.goBack(), true);
  assert.equal(t.w.tab, "bugun");
  assert.equal(t.w.goBack(), false);
});

test("demo modunda işlem butonları gizli", async () => {
  const s = clone(baseState);
  s.demo = true;
  s.demoSummary = { start: 1000000, end: 1100000, baselineEnd: 700000 };
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  c.played = false; c.result = null;
  const t = boot(s);
  assert.match(t.text(), /Demo modu/);
  assert.ok(!t.$$("button").some((b) => b.textContent.trim() === "Oynadım"));
  assert.ok(!t.$$("button").some((b) => b.textContent.trim() === "Sonuç kontrolü"));
});

let failed = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log("✓", name);
  } catch (e) {
    failed++;
    console.log("✗", name, "\n ", e.message);
  }
}
console.log(failed ? `${failed} test başarısız` : `${tests.length} arayüz testi geçti`);
process.exit(failed ? 1 : 0);
