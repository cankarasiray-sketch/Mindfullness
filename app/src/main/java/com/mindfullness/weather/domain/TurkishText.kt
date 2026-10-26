package com.mindfullness.weather.domain

/** Turkish suffixes that depend on how a number is read out. */
object TurkishText {
    private val UNITS = listOf("", "'i", "'si", "'ü", "'ü", "'i", "'sı", "'si", "'i", "'u")
    private val TENS = listOf("", "'u", "'si", "'u", "'ı", "'si", "'ı", "'i", "'i", "'ı")

    /** Possessive suffix after a number, as in "%78'i", "%30'u", "%100'ü", "%50'si". */
    fun possessive(number: Int): String {
        val n = kotlin.math.abs(number)
        return when {
            n == 0 -> "'ı"
            n % 1000 == 0 -> "'i" // bin
            n % 100 == 0 -> "'ü" // yüz
            n % 10 != 0 -> UNITS[n % 10]
            else -> TENS[(n / 10) % 10]
        }
    }
}
