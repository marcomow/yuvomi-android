package io.github.marcomow.yuvomi.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marcomow.yuvomi.core.api.ApiProblem
import io.github.marcomow.yuvomi.core.design.Yuvomi
import io.github.marcomow.yuvomi.core.i18n.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/** `weather-location.js`, pure (SET-R26, R27). */
object WeatherRules {
    const val MIN_SEARCH = 2

    /** `hasValidWeatherCoords`. */
    fun validCoords(lat: String, lon: String): Boolean {
        val a = lat.trim().toDoubleOrNull() ?: return false
        val o = lon.trim().toDoubleOrNull() ?: return false
        return a in -90.0..90.0 && o in -180.0..180.0
    }

    /** A place's second line: district, region, country, without repeats. */
    fun placeMeta(p: JsonObject): String = listOfNotNull(p.str("admin2"), p.str("admin1"), p.str("country")).distinct().joinToString(", ")

    fun coord(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.4f", it) }.orEmpty()

    /** `geocodeErrorKey`. */
    fun geocodeErrorKey(e: Throwable): String = if ((e as? ApiProblem.Http)?.reason == "name_too_short") "settings.weatherPlaceTooShort" else "settings.weatherPlaceUnavailable"
}

/** `personal-weather.js`: my own weather place, or the household's (SET-R26, R27). */
@Composable
fun PersonalWeatherSection(ctx: SectionContext, openLeaf: (String) -> Unit) {
    val slots by ctx.vm.slots.collectAsState()
    val prefs = slots[SettingsSheetViewModel.PREFERENCES]?.value as? JsonObject ?: run {
        val slot = slots[SettingsSheetViewModel.PREFERENCES]
        if (slot?.errorKey != null) SettingsGroup(footer = t(slot.errorKey)) { ActionRow(t("settings.retry")) { ctx.vm.loadPreferences(force = true) } }
        else Busy(true)
        return
    }
    val wu = prefs.child("weather_user")
    val own = wu.num("lat") != null || wu.num("lon") != null
    var query by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable(wu.toString()) { mutableStateOf(WeatherRules.coord(wu.num("lat"))) }
    var lon by rememberSaveable(wu.toString()) { mutableStateOf(WeatherRules.coord(wu.num("lon"))) }
    var city by rememberSaveable(wu.toString()) { mutableStateOf(wu.str("city").orEmpty()) }
    var units by rememberSaveable(wu.toString()) { mutableStateOf(wu.str("units") ?: prefs.str("weather_units") ?: "metric") }
    var results by remember { mutableStateOf<List<JsonObject>?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    SettingsGroup(title = t("settings.personalWeatherTitle"), footer = t("settings.personalWeatherDescription")) {
        GroupBody {
            Text(t(if (own) "settings.personalWeatherSourceUser" else "settings.personalWeatherSourceHousehold"), color = if (own) Yuvomi.colors.success else Yuvomi.colors.textSecondary)
            Field(t("settings.weatherPlaceLabel"), query, { query = it }, searchError, hint = t("settings.weatherPlaceHint"), enabled = ctx.editable)
            Hint(if (city.isNotBlank()) ctx.strings.t("settings.weatherPlaceCurrent", "place" to city) else t("settings.weatherPlaceNone"))
            if (ctx.editable) OutlinedButton(enabled = !busy, onClick = {
                if (query.trim().length < WeatherRules.MIN_SEARCH) { searchError = ctx.strings.t("settings.weatherPlaceTooShort"); return@OutlinedButton }
                searchError = null
                busy = true
                ctx.scope.launch {
                    ctx.vm.attempt { it.data(SettingsOperation.WEATHER_GEOCODE, query = listOf("name" to query.trim(), "lang" to ctx.strings.locale)) }
                        .onSuccess { data ->
                            val list = data.arr().map { it.obj() }
                            results = list
                            searchError = when {
                                data is JsonNull -> ctx.strings.t("settings.weatherPlaceUnavailable")
                                list.isEmpty() -> ctx.strings.t("settings.weatherPlaceNotFound")
                                else -> null
                            }
                        }
                        .onFailure { searchError = ctx.strings.t(WeatherRules.geocodeErrorKey(it)) }
                    busy = false
                }
            }) { Text(t("settings.weatherPlaceSearch")) }
            results?.takeIf { it.isNotEmpty() }?.let { list ->
                Hint(t("settings.weatherPlacePick"))
                list.forEach { place ->
                    ListItem(
                        headlineContent = { Text(place.str("name").orEmpty()) },
                        supportingContent = { Text(WeatherRules.placeMeta(place), color = Yuvomi.colors.textSecondary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            lat = WeatherRules.coord(place.num("latitude"))
                            lon = WeatherRules.coord(place.num("longitude"))
                            city = place.str("name").orEmpty()
                            results = null
                            ctx.notify(ctx.strings.t("settings.weatherPlaceChosen", "place" to city))
                        },
                    )
                }
            }
            ChoiceRow(t("settings.weatherUnitsLabel"), listOf("metric" to t("settings.weatherUnitsMetric"), "imperial" to t("settings.weatherUnitsImperial")), units, ctx.editable) { units = it }
            Text(t("settings.weatherManualTitle"))
            Field(t("settings.weatherLatLabel"), lat, { lat = it }, enabled = ctx.editable, keyboard = KeyboardType.Decimal)
            Field(t("settings.weatherLonLabel"), lon, { lon = it }, enabled = ctx.editable, keyboard = KeyboardType.Decimal)
            Field(t("settings.weatherCityLabel"), city, { city = it }, enabled = ctx.editable)
            Hint(t("settings.weatherCoordHint"))
            error?.let { Hint(it, Yuvomi.colors.danger) }
            if (ctx.editable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                if (own) OutlinedButton(onClick = {
                    ctx.save("settings.personalWeatherReset", buildJsonObject {
                        putJsonObject("weather_user") { put("lat", JsonNull); put("lon", JsonNull); put("city", JsonNull); put("units", JsonNull); put("auto_locate", JsonNull) }
                    })
                }) { Text(t("settings.personalWeatherUseHousehold")) }
                Button(onClick = {
                    if (lat.isBlank() && lon.isBlank() && city.isBlank()) { searchError = ctx.strings.t("settings.weatherPlaceRequired"); return@Button }
                    if (!WeatherRules.validCoords(lat, lon)) { error = ctx.strings.t("settings.weatherCoordsInvalid"); return@Button }
                    error = null
                    ctx.save("settings.personalWeatherSaved", buildJsonObject {
                        putJsonObject("weather_user") {
                            put("lat", lat.trim().toDouble())
                            put("lon", lon.trim().toDouble())
                            if (city.isBlank()) put("city", JsonNull) else put("city", city.trim())
                            put("units", units)
                            put("auto_locate", wu.bool("auto_locate"))
                        }
                    })
                }) { Text(t("settings.weatherSave")) }
            }
        }
    }
    if (ctx.isAdmin) SettingsGroup(footer = t("settings.weatherHouseholdLinkHint")) {
        ActionRow(t("settings.pageIntegrations")) { openLeaf("admin-integrations") }
    }
}
