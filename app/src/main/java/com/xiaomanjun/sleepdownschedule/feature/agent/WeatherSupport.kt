package com.xiaomanjun.sleepdownschedule.feature.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

private val WeatherJson = Json { ignoreUnknownKeys = true; isLenient = true }
private const val DayAgentWeatherCacheMillis = 30 * 60 * 1000L

data class AgentWeatherSnapshot(
    val summary: String,
    val temperature: Int,
    val apparentTemperature: Int,
    val precipitationProbability: Int,
    val windSpeed: Int,
    val fetchedAt: Long
) {
    val hasAlert: Boolean
        get() = precipitationProbability >= 60 || temperature >= 35 || temperature <= 5 || windSpeed >= 35
}

internal fun isDayAgentWeatherCacheFresh(fetchedAt: Long, now: Long): Boolean {
    if (fetchedAt <= 0L) return false
    return now - fetchedAt in 0L..DayAgentWeatherCacheMillis
}

object DayAgentWeatherStore {
    private const val Prefs = "day_agent_weather"

    fun load(context: Context): AgentWeatherSnapshot? {
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        val fetchedAt = prefs.getLong("fetched_at", 0L)
        if (!isDayAgentWeatherCacheFresh(fetchedAt, System.currentTimeMillis())) return null
        val summary = prefs.getString("summary", null) ?: return null
        return AgentWeatherSnapshot(
            summary = summary,
            temperature = prefs.getInt("temperature", 0),
            apparentTemperature = prefs.getInt("apparent_temperature", 0),
            precipitationProbability = prefs.getInt("precipitation_probability", 0),
            windSpeed = prefs.getInt("wind_speed", 0),
            fetchedAt = fetchedAt
        )
    }

    fun save(context: Context, weather: AgentWeatherSnapshot) {
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit {
            putLong("fetched_at", weather.fetchedAt)
                .putString("summary", weather.summary)
                .putInt("temperature", weather.temperature)
                .putInt("apparent_temperature", weather.apparentTemperature)
                .putInt("precipitation_probability", weather.precipitationProbability)
                .putInt("wind_speed", weather.windSpeed)
        }
    }
}

class DayAgentWeatherRepository(private val context: Context) {
    fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    suspend fun getWeather(forceRefresh: Boolean = false): AgentWeatherSnapshot? = withContext(Dispatchers.IO) {
        if (!forceRefresh) DayAgentWeatherStore.load(context)?.let { return@withContext it }
        runCatching {
            val location = if (hasLocationPermission()) lastKnownLocation() else null
            val coordinates = location?.let { it.latitude to it.longitude } ?: return@runCatching null
            val weather = fetchApiZeroWeather(coordinates.first, coordinates.second) ?: return@runCatching null
            DayAgentWeatherStore.save(context, weather)
            weather
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun fetchApiZeroWeather(latitude: Double, longitude: Double): AgentWeatherSnapshot? {
        val endpoint = buildString {
            append("https://v1.apizero.cn/api/weather?location=")
            append(longitude).append(',').append(latitude)
            append("&type=weather&days=1&hours=24&alert=false")
        }
        return parseApiZeroWeatherPayload(httpGet(endpoint))
    }
}

internal fun parseApiZeroWeatherPayload(
    payload: String,
    fetchedAt: Long = System.currentTimeMillis()
): AgentWeatherSnapshot? {
    val root = WeatherJson.parseToJsonElement(payload).jsonObject
    if (root["code"]?.jsonPrimitive?.intOrNull != 0) return null
    val data = root["data"]?.jsonObject ?: return null
    val current = data["summary"]?.jsonObject ?: return null
    val temperature = current["temperature"]?.jsonPrimitive?.content
        ?.toDoubleOrNull()?.toInt() ?: return null
    val apparent = current["apparent_temperature"]?.jsonPrimitive?.content
        ?.toDoubleOrNull()?.toInt() ?: temperature
    val wind = current["wind"]?.jsonObject
        ?.get("speed_ms")?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0
    val probability = data["hourly"]?.jsonObject
        ?.get("precipitation")?.jsonArray
        ?.firstOrNull()?.jsonObject
        ?.get("probability")?.jsonPrimitive?.intOrNull ?: 0
    val skyCondition = current["skycon"]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() } ?: "天气情况未知"
    val summary = "$skyCondition，${temperature}°C，体感 ${apparent}°C，降雨概率 ${probability}%"
    return AgentWeatherSnapshot(
        summary,
        temperature,
        apparent,
        probability.coerceIn(0, 100),
        wind,
        fetchedAt
    )
}

private fun httpGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
