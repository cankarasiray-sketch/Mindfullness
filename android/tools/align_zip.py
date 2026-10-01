"""APK'yı yeniden paketler ve sıkıştırılmamış girdileri 4 bayta hizalar (zipalign eşdeğeri).

Android 11+ hedefleyen uygulamalarda resources.arsc sıkıştırılmamış ve 4 bayta
hizalı olmak zorundadır; aksi halde kurulum reddedilir.
Kullanım: align_zip.py base.apk çıktı.apk yerel/dosya=apk/içindeki/yol ...
"""

import sys
import zipfile

FIXED_TIME = (2008, 1, 1, 0, 0, 0)  # tekrarlanabilir derleme


def build(base_apk, out_apk, extra_files):
    src = zipfile.ZipFile(base_apk)
    entries = [(i.filename, i.compress_type, src.read(i.filename)) for i in src.infolist()]
    for path, arcname in extra_files:
        with open(path, "rb") as fh:
            entries.append((arcname, zipfile.ZIP_DEFLATED, fh.read()))
    with open(out_apk, "wb") as fh, zipfile.ZipFile(fh, "w") as dst:
        for name, compress, data in entries:
            zi = zipfile.ZipInfo(name, date_time=FIXED_TIME)
            zi.compress_type = compress
            if compress == zipfile.ZIP_STORED:
                offset = fh.tell() + 30 + len(name.encode("utf-8"))
                zi.extra = b"\x00" * ((-offset) % 4)
            dst.writestr(zi, data)


def check(apk):
    """Sıkıştırılmamış her girdinin verisi 4 bayta hizalı mı?"""
    import struct

    z = zipfile.ZipFile(apk)
    bad = []
    with open(apk, "rb") as fh:
        for i in z.infolist():
            if i.compress_type != zipfile.ZIP_STORED:
                continue
            fh.seek(i.header_offset)
            header = fh.read(30)
            n, e = struct.unpack("<HH", header[26:30])
            if (i.header_offset + 30 + n + e) % 4:
                bad.append(i.filename)
    return bad


if __name__ == "__main__":
    if sys.argv[1] == "--check":
        bad = check(sys.argv[2])
        if bad:
            sys.exit(f"hizasız girdiler: {bad}")
        print("hizalama tamam")
    else:
        base, out, *rest = sys.argv[1:]
        build(base, out, [tuple(x.split("=", 1)) for x in rest])
