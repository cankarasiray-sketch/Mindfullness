package com.mindfullness.weather.data

import com.mindfullness.weather.domain.BulletinDay
import com.mindfullness.weather.domain.BulletinGroup
import com.mindfullness.weather.domain.BulletinReading
import com.mindfullness.weather.domain.PollenBulletin
import com.mindfullness.weather.domain.PollenBulletins
import com.mindfullness.weather.domain.PollenGroup
import com.mindfullness.weather.domain.PollenLevel
import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneOffset
import java.util.Locale

/**
 * Reads the city pollen bulletins from their web pages, which have no API. Both are plain
 * server-rendered HTML; anything unexpected gives null, and the app falls back to a link.
 */
object BulletinParser {
    private val TR = Locale.forLanguageTag("tr-TR")

    /** Both cities are in Turkey, which keeps UTC+3 all year: "Bugün" on the page is this date. */
    private val TURKEY = ZoneOffset.ofHours(3)

    fun parse(bulletin: PollenBulletin, html: String, fetchedAtMillis: Long): BulletinReading? = runCatching {
        when (bulletin.id) {
            PollenBulletins.ANKARA.id -> ankara(bulletin, html, fetchedAtMillis)
            PollenBulletins.IZMIR.id -> izmir(bulletin, html, fetchedAtMillis)
            else -> null
        }
    }.getOrNull()

    // region Ankara: a daily bulletin for today and the next two days ---------------------------

    private val ANKARA_GROUP = Regex("""<div class="pollen pollen--([\w-]+)">""")
    private val ANKARA_CARD = Regex("""class="[^"]*\bpollen__info-card(?![-\w])[^"]*"""")
    private val ANKARA_DAY = Regex("""pollen__info-card-heading">(.*?)</h4>""", RegexOption.DOT_MATCHES_ALL)
    private val ANKARA_LEVEL = Regex("""data-level="([^"]+)"""")
    private val ANKARA_ALLERGEN = Regex("""pollen__alergen-item">(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)

    private fun ankara(bulletin: PollenBulletin, html: String, fetchedAtMillis: Long): BulletinReading? {
        val today = turkeyDate(fetchedAtMillis)
        val starts = ANKARA_GROUP.findAll(html).toList()
        val groups = starts.mapIndexedNotNull { index, start ->
            val end = starts.getOrNull(index + 1)?.range?.first
                ?: html.indexOf("</section>", start.range.last).takeIf { it >= 0 }
                ?: html.length
            val block = html.substring(start.range.last + 1, end)
            val group = when (start.groupValues[1]) {
                "nature" -> PollenGroup.TREE
                "wild-herb" -> PollenGroup.WEED
                "meadow" -> PollenGroup.GRASS
                else -> null
            } ?: return@mapIndexedNotNull null
            val cardStarts = ANKARA_CARD.findAll(block).map { it.range.first }.toList()
            val cards = cardStarts.mapIndexed { i, from -> block.substring(from, cardStarts.getOrNull(i + 1) ?: block.length) }
            val days = cards.mapNotNull { card ->
                val label = ANKARA_DAY.find(card)?.let { text(it.groupValues[1]) } ?: return@mapNotNull null
                val date = when (label.lowercase(TR)) {
                    "bugün" -> today
                    "yarın" -> today.plusDays(1)
                    else -> date(label)
                } ?: return@mapNotNull null
                val level = ANKARA_LEVEL.find(card)?.let { level(it.groupValues[1]) } ?: return@mapNotNull null
                BulletinDay(date, level)
            }
            // The allergens are listed on today's card only.
            val dominant = ANKARA_ALLERGEN.findAll(block).map { text(it.groupValues[1]).removePrefix(">").trim() }
                .filter { it.isNotEmpty() }.toList()
            if (days.isEmpty()) null else BulletinGroup(group, days = days, dominant = dominant)
        }
        return if (groups.isEmpty()) null else BulletinReading(bulletin, groups, fetchedAtMillis)
    }

    // endregion
    // region İzmir: a weekly count, with today's chance of being affected -----------------------

    private val IZMIR_WEEK = Regex(
        """Ölçüm Haftası.*?(\d{1,2}\.\d{1,2}\.\d{4})\s*-\s*(\d{1,2}\.\d{1,2}\.\d{4})""",
        RegexOption.DOT_MATCHES_ALL,
    )
    /** The verdict is quoted in the text: <span …>"DÜŞÜKTÜR"</span>. */
    private val IZMIR_RISK = Regex("""etkilenme olasılığınız.*?>\s*"([^"<>]+)"\s*<""", RegexOption.DOT_MATCHES_ALL)
    private val IZMIR_RISK_TIME = Regex("""(\d{1,2}\.\d{1,2}\.\d{4})\s*\(\s*(\d{1,2}:\d{2})\s*-\s*(\d{1,2}:\d{2})\s*\)""")
    private val IZMIR_CARD = Regex("""card-img-top"\s+src="[^"]*/([a-z-]+)\d*\.png"""")
    private val IZMIR_LEVEL = Regex("""Polen Konsantrasyonu\s*<br\s*/?>\s*<span[^>]*>([^<]+)</span>""")
    private val IZMIR_ALLERGEN = Regex("""<p class="card-text[^"]*">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

    private fun izmir(bulletin: PollenBulletin, html: String, fetchedAtMillis: Long): BulletinReading? {
        val week = IZMIR_WEEK.find(html) ?: return null
        val from = date(week.groupValues[1]) ?: return null
        val to = date(week.groupValues[2]) ?: return null
        val cardStarts = IZMIR_CARD.findAll(html).toList()
        val groups = cardStarts.mapIndexedNotNull { index, start ->
            val group = when {
                start.groupValues[1].startsWith("agac") -> PollenGroup.TREE
                start.groupValues[1].startsWith("yabani") -> PollenGroup.WEED
                start.groupValues[1].startsWith("cayir") -> PollenGroup.GRASS
                else -> null
            } ?: return@mapIndexedNotNull null
            val card = html.substring(start.range.last + 1, cardStarts.getOrNull(index + 1)?.range?.first ?: html.length)
            val level = IZMIR_LEVEL.find(card)?.let { level(it.groupValues[1]) } ?: return@mapIndexedNotNull null
            // "Cupressaceae/Cypress/Servi (Selvi)": the Turkish name comes last.
            val dominant = IZMIR_ALLERGEN.findAll(card).map { text(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
            BulletinGroup(group, weekLevel = level, dominant = dominant.map { it.substringAfterLast('/').trim() + latin(it) })
        }
        if (groups.isEmpty()) return null
        val risk = IZMIR_RISK.find(html)?.let { level(it.groupValues[1]) }
        val riskTime = IZMIR_RISK_TIME.find(html)
        return BulletinReading(
            bulletin, groups, fetchedAtMillis,
            measuredFrom = from, measuredTo = to,
            risk = risk,
            riskDate = riskTime?.let { date(it.groupValues[1]) },
            riskHours = riskTime?.let { "${it.groupValues[2]}–${it.groupValues[3]}" },
        )
    }

    /** Keeps the Latin name of "Urticaceae/Nettle/Isırganotugiller" for matching, as Ankara's names do. */
    private fun latin(name: String): String {
        val first = name.substringBefore('/').trim()
        return if (name.contains('/') && first.isNotEmpty()) " ($first)" else ""
    }

    // endregion

    private fun level(word: String): PollenLevel? {
        val lower = word.trim().trim('"').lowercase(TR)
        return when {
            lower.startsWith("çok yüksek") -> PollenLevel.VERY_HIGH
            lower.startsWith("yüksek") -> PollenLevel.HIGH
            lower.startsWith("orta") -> PollenLevel.MODERATE
            lower.startsWith("düşük") -> PollenLevel.LOW
            lower.startsWith("yok") -> PollenLevel.NONE
            else -> null
        }
    }

    /** "28.10.2026" */
    private fun date(text: String): LocalDate? {
        val parts = text.trim().split('.')
        if (parts.size != 3) return null
        return runCatching { LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt()) }.getOrNull()
    }

    private fun turkeyDate(millis: Long): LocalDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), TURKEY).toLocalDate()

    /** Visible text of an HTML fragment. */
    private fun text(html: String): String = html
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&gt;", ">").replace("&lt;", "<").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { match -> match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
}
