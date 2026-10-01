#!/usr/bin/env bash
# Bilinçli Kupon APK derlemesi. Android SDK ya da Gradle gerekmez; JDK 17+, python3,
# curl ve Linux/macOS yeterli. Araçlar Maven Central'dan indirilir ve SHA-256 ile doğrulanır.
#
#   ./build.sh          -> testler + build/BilincliKupon.apk
#   ./build.sh test     -> yalnızca testler
#
# İmza anahtarı: .keystore/bilincli.p12 (yoksa oluşturulur, repoya girmez). Uygulamayı
# güncellerken AYNI anahtar kullanılmalı; anahtar kaybolursa telefondaki sürüm
# kaldırılmadan yeni sürüm kurulamaz (önce uygulamadan yedek al).
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(pwd)
TOOLS="$ROOT/.tools"
OUT="$ROOT/build"
MIN_SDK=26
TARGET_SDK=34
VERSION_CODE=27
VERSION_NAME=1.9.5
MAVEN=https://repo1.maven.org/maven2

mkdir -p "$TOOLS" "$OUT"
command -v sha256sum >/dev/null || sha256sum() { shasum -a 256 "$@"; }  # macOS
JAVA_OPTS_QUIET() { "$@" 2>&1 | grep -v "Picked up JAVA_TOOL_OPTIONS" || true; }

fetch() {  # fetch <maven yolu> <yerel ad> <sha256>
    local dest="$TOOLS/$2"
    if [ -f "$dest" ] && echo "$3  $dest" | sha256sum -c --quiet 2>/dev/null; then return; fi
    echo "indiriliyor: $2"
    for attempt in 1 2 3 4; do
        if curl -fsSL -o "$dest.part" "$MAVEN/$1" && echo "$3  $dest.part" | sha256sum -c --quiet; then
            mv "$dest.part" "$dest"
            return
        fi
        sleep $((attempt * 5))  # Maven Central hız sınırı (429)
    done
    echo "HATA: $2 indirilemedi ya da sağlama toplamı tutmadı" >&2
    exit 1
}

fetch org/apktool/apktool-lib/3.0.3/apktool-lib-3.0.3.jar apktool-lib.jar \
    983773879fd89ede2cd938858e3efce2a90ac1123f6a5140e9d949dcf4464e3e
fetch org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar android-all.jar \
    6be2218c6a53fe3c57bc22ebdc723edcb7270a8a6f187545708aa5c0ed813977
fetch com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar dx.jar \
    1e4b645628e3bdb097b5331d669e177ef235a551582a8c646dbe36865e541907
fetch com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar apksig.jar \
    9637078c0016244e4be0941836295365a7e2e5b164c59cb7885783c40460bfee
fetch junit/junit/4.13.2/junit-4.13.2.jar junit.jar \
    8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3
fetch org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar hamcrest.jar \
    66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9

# apktool paketinin içindeki aapt2 ve framework kaynakları
case "$(uname -s)" in
    Linux) AAPT_SRC=prebuilt/linux/aapt2 ;;
    Darwin) AAPT_SRC=prebuilt/macosx/aapt2 ;;
    *) echo "Desteklenmeyen sistem: $(uname -s)" >&2; exit 1 ;;
esac
if [ ! -x "$TOOLS/aapt2" ] || [ ! -f "$TOOLS/android-framework.jar" ]; then
    (cd "$TOOLS" && unzip -o -q -j apktool-lib.jar "$AAPT_SRC" prebuilt/android-framework.jar && chmod +x aapt2)
fi
AAPT2="$TOOLS/aapt2"

# ---- testler ----------------------------------------------------------------
echo "== testler"
if python3 -c "import sys; sys.path.insert(0, '..'); import bilincli" 2>/dev/null; then
    (cd .. && PYTHONPATH=. python3 android/test/fixtures/make_fixtures.py)
else
    echo "UYARI: Python paketi bulunamadı, eşdeğerlik verisi yeniden üretilmedi"
fi
rm -rf "$OUT/test" && mkdir -p "$OUT/test"
JAVA_OPTS_QUIET javac --release 8 -nowarn -encoding UTF-8 -cp "$TOOLS/junit.jar" -d "$OUT/test" \
    src/app/bilincli/core/*.java test/app/bilincli/core/*.java
java -cp "$OUT/test:$TOOLS/junit.jar:$TOOLS/hamcrest.jar" -Dparity=test/fixtures/parity.json \
    org.junit.runner.JUnitCore app.bilincli.core.ParityTest app.bilincli.core.CoreTest app.bilincli.core.FeatureTest app.bilincli.core.RadarTest app.bilincli.core.MarketsTest app.bilincli.core.BankrollTest app.bilincli.core.ProfitTest app.bilincli.core.AccuracyTest app.bilincli.core.BasketballTest app.bilincli.core.KeysTest app.bilincli.core.BasketTotalsTest 2>&1 \
    | grep -v "Picked up JAVA_TOOL_OPTIONS" | tee "$OUT/test.log" | tail -3
grep -q "^OK (" "$OUT/test.log" || { echo "TESTLER BAŞARISIZ" >&2; exit 1; }

# Arayüz testleri (jsdom): Java çekirdeğinden üretilen gerçek durumla
if command -v node >/dev/null && command -v npm >/dev/null; then
    JAVA_OPTS_QUIET javac --release 8 -nowarn -encoding UTF-8 -cp "$OUT/test" -d "$OUT/test" \
        test/app/bilincli/preview/PreviewState.java
    java -Dstdout.encoding=UTF-8 -cp "$OUT/test" app.bilincli.preview.PreviewState 2>/dev/null > "$OUT/ui-state.json"
    (cd test/ui && { [ -d node_modules/jsdom ] || npm install --silent --no-audit --no-fund; } && node ui.test.mjs "$OUT/ui-state.json")
else
    echo "UYARI: node yok, arayüz testleri atlandı"
fi
[ "${1:-}" = "test" ] && exit 0

# ---- kaynaklar ----------------------------------------------------------------
echo "== kaynaklar"
rm -rf "$OUT/gen" "$OUT/classes" "$OUT/res.zip" "$OUT/base.apk"
mkdir -p "$OUT/gen" "$OUT/classes"
"$AAPT2" compile --dir res -o "$OUT/res.zip"
"$AAPT2" link -I "$TOOLS/android-framework.jar" --manifest AndroidManifest.xml -A assets \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    --version-code $VERSION_CODE --version-name $VERSION_NAME --replace-version \
    --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res.zip"

# ---- kod ----------------------------------------------------------------------
echo "== derleme"
JAVA_OPTS_QUIET javac --release 8 -nowarn -Xlint:none -encoding UTF-8 -cp "$TOOLS/android-all.jar" \
    -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java') | grep -v "UnsupportedAppUsage\|^Note:\|warning" || true
[ -f "$OUT/classes/app/bilincli/android/MainActivity.class" ] || { echo "derleme başarısız" >&2; exit 1; }
JAVA_OPTS_QUIET java -cp "$TOOLS/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=$MIN_SDK \
    --output="$OUT/classes.dex" "$OUT/classes"

# ---- paket + imza ---------------------------------------------------------------
echo "== paketleme"
python3 tools/align_zip.py "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/classes.dex=classes.dex"
KEYDIR="$ROOT/.keystore"
KEYSTORE="${KEYSTORE:-$KEYDIR/bilincli.p12}"
if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
    mkdir -p "$KEYDIR"
    [ -f "$KEYDIR/password" ] || head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$KEYDIR/password"
    KEYSTORE_PASSWORD=$(cat "$KEYDIR/password")
fi
if [ ! -f "$KEYSTORE" ]; then
    echo "yeni imza anahtarı oluşturuluyor: $KEYSTORE"
    JAVA_OPTS_QUIET keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias bilincli \
        -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Bilincli Kupon" \
        -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD"
fi
mkdir -p "$OUT/signer"
JAVA_OPTS_QUIET javac -nowarn -cp "$TOOLS/apksig.jar" -d "$OUT/signer" tools/Signer.java
APK="$OUT/BilincliKupon-$VERSION_NAME.apk"
# apksig 2.3.0, JDK 17+ üzerinde bu iç paketlere erişim istiyor
JAVA_OPTS_QUIET java -Dstdout.encoding=UTF-8 --add-exports java.base/sun.security.x509=ALL-UNNAMED \
    --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED \
    -cp "$TOOLS/apksig.jar:$OUT/signer" Signer "$OUT/unsigned.apk" "$APK" "$KEYSTORE" "$KEYSTORE_PASSWORD"
python3 tools/align_zip.py --check "$APK"
echo "== hazır: $APK ($(du -h "$APK" | cut -f1))"
