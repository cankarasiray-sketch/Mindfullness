package com.mindfullness.weather.platform

/** What the "share the app" action sends: a download link instead of the APK file itself. */
object AppShare {
    /** Always the newest release (CI publishes it under this fixed name). */
    const val DOWNLOAD_URL = "https://github.com/cankarasiray-sketch/Mindfullness/releases/latest/download/HavaUyari.apk"

    fun invitation(): String = """
        Hava Uyarı: yağmur, fırtına, don, sis ve sıcak hava için önceden uyaran hava durumu uygulaması.

        İndirmek için: $DOWNLOAD_URL

        Kurulum (Android 8 ve üzeri):
        1. Bağlantıyı tarayıcıda açın; HavaUyari.apk iner.
        2. İnen dosyaya dokunun. "Bu kaynaktan yüklemeye izin ver" istenirse açın.
        3. Play Protect uyarı verirse "Daha fazla ayrıntı → Yine de yükle"yi seçin.
        Daha önce Hava Uyarı'nın eski bir sürümünü kurduysanız önce onu kaldırın.
    """.trimIndent()
}
