package com.mindfullness.weather.data

import com.mindfullness.weather.domain.Place
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class ParsersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val forecastJson: String = javaClass.getResource("/forecast_istanbul.json")!!.readText()

    private val modelsJson = """
        {"hourly": {
          "time": ["2026-10-08T00:00", "2026-10-08T01:00"],
          "precipitation_ecmwf_ifs025": [0.0, 1.2], "wind_gusts_10m_ecmwf_ifs025": [20, 31.5], "temperature_2m_ecmwf_ifs025": [10.1, 9.8],
          "precipitation_icon_seamless": [0.1, null], "wind_gusts_10m_icon_seamless": [22, null], "temperature_2m_icon_seamless": [10, null],
          "precipitation_gfs_seamless": [null, null], "wind_gusts_10m_gfs_seamless": [null, null], "temperature_2m_gfs_seamless": [null, null]
        }}
    """.trimIndent()

    private val airJson = """
        {"hourly": {
          "time": ["2026-10-08T00:00", "2026-10-08T01:00"],
          "european_aqi": [42, 87], "pm2_5": [11.5, 30.2], "pm10": [20, 160], "dust": [4, null]
        }}
    """.trimIndent()

    @Test
    fun `splits multi-model responses and skips models without data`() {
        val runs = ModelsParser.parse(modelsJson)
        assertEquals(listOf("ecmwf_ifs025", "icon_seamless"), runs.map { it.name })
        val ecmwf = runs[0].hours
        assertEquals(LocalDateTime.of(2026, 10, 8, 1, 0), ecmwf[1].time)
        assertEquals(1.2, ecmwf[1].precipitation!!, 1e-9)
        assertEquals(31.5, ecmwf[1].windGusts!!, 1e-9)
        assertNull(runs[1].hours[1].precipitation)
        // Unsuffixed keys cannot be attributed to a model.
        assertTrue(ModelsParser.parse("""{"hourly": {"time": ["2026-10-08T00:00"], "precipitation": [1.0]}}""").isEmpty())
    }

    @Test
    fun `parses air quality`() {
        val air = AirQualityParser.parse(airJson)
        assertEquals(2, air.size)
        assertEquals(87.0, air[1].europeanAqi!!, 1e-9)
        assertEquals(160.0, air[1].pm10!!, 1e-9)
        assertNull(air[1].dust)
    }

    @Test
    fun `parses cape`() {
        val forecast = ForecastParser.parse(forecastJson, 0)
        assertTrue(forecast.hourly.isNotEmpty())
        val withCape = JSONObject(forecastJson).apply {
            val hourly = getJSONObject("hourly")
            hourly.put("cape", org.json.JSONArray(List(hourly.getJSONArray("time").length()) { 1234.0 }))
        }
        assertEquals(1234.0, ForecastParser.parse(withCape.toString(), 0).hourly.first().cape!!, 1e-9)
    }

    @Test
    fun `reads both cache formats`() {
        val place = Place(Place.CURRENT_LOCATION_ID, "Konum", latitude = 41.0, longitude = 29.0)
        val dir = folder.newFolder()
        val repository = WeatherRepository(OpenMeteoApi(), dir)
        val file = File(dir, "forecast_current.json")

        // Version 1.0 wrote "<fetchedAt>\n<forecast json>".
        file.writeText("1234\n$forecastJson")
        val old = repository.cached(place)
        assertNotNull(old)
        assertEquals(1234L, old!!.fetchedAtMillis)
        assertTrue(old.models.isEmpty())

        file.writeText(
            JSONObject().put("fetchedAt", 5678L).put("forecast", forecastJson).put("models", modelsJson).put("air", airJson).toString(),
        )
        val current = repository.cached(place)!!
        assertEquals(5678L, current.fetchedAtMillis)
        assertEquals(2, current.models.size)
        assertEquals(2, current.air.size)

        // A broken extra must not cost the forecast itself.
        file.writeText(JSONObject().put("fetchedAt", 1L).put("forecast", forecastJson).put("air", "not json").toString())
        assertTrue(repository.cached(place)!!.air.isEmpty())
    }
}
