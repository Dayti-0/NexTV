package tv.nextv.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.nextv.app.R
import tv.nextv.core.weather.Sky
import tv.nextv.core.weather.Weather
import kotlin.math.cos
import kotlin.math.sin

/**
 * Météo de l'écran des pubs : fond aux couleurs du temps qu'il fait (jour ou nuit), grand pictogramme et température,
 * ressenti, vent, risque de pluie et humidité, les cinq prochaines heures (avec leur risque de pluie), et demain avec
 * l'écart de ses températures.
 */
@Composable
internal fun WeatherCard(weather: Weather, modifier: Modifier = Modifier) {
    val (top, bottom) = skyColors(weather.sky, weather.day)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // Maintenant.
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkyIcon(weather.sky, weather.day, Modifier.size(54.dp))
            Spacer(Modifier.width(10.dp))
            Text("${weather.temperature}°", fontSize = 42.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    weather.place.name.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.75f),
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(skyLabel(weather.sky, weather.day)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                weather.feelsLike?.let {
                    Text(stringResource(R.string.weather_feels_like, it), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // Détails du jour.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Detail(Mini.RANGE, "${weather.todayMin}° / ${weather.todayMax}°")
            weather.windKmh?.let { Detail(Mini.WIND, stringResource(R.string.weather_wind, it)) }
            weather.todayRainChance?.let { Detail(Mini.RAIN, "$it %") }
            weather.humidity?.let { Detail(Mini.HUMIDITY, "$it %") }
        }
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.18f)))
        Spacer(Modifier.height(5.dp))
        // Prochaines heures.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            weather.hours.forEach { hour ->
                Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.weather_hour, hour.hour), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                    SkyIcon(hour.sky, hour.day, Modifier.size(26.dp).padding(vertical = 1.dp))
                    Text("${hour.temperature}°", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        hour.rainChance?.takeIf { it >= RAIN_SHOWN }?.let { "$it %" }.orEmpty(),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = RAIN_BLUE,
                    )
                }
            }
        }
        // Demain.
        val tomorrow = weather.tomorrowSky
        val tomorrowMin = weather.tomorrowMin
        val tomorrowMax = weather.tomorrowMax
        if (tomorrow != null && tomorrowMin != null && tomorrowMax != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(stringResource(R.string.weather_tomorrow), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
                SkyIcon(tomorrow, day = true, Modifier.size(20.dp))
                Text(
                    stringResource(skyLabel(tomorrow, day = true)).lowercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                weather.tomorrowRainChance?.takeIf { it >= RAIN_SHOWN }?.let { Detail(Mini.RAIN, "$it %") }
                Text("$tomorrowMin°", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                Box(
                    Modifier
                        .width(34.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF81D4FA), Color(0xFFFFB74D)))),
                )
                Text("$tomorrowMax°", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/** Petites indications du jour : écart de températures, vent, risque de pluie, humidité. */
private enum class Mini { RANGE, WIND, RAIN, HUMIDITY }

@Composable
private fun Detail(icon: Mini, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MiniIcon(icon, Modifier.size(13.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.92f), maxLines = 1)
    }
}

@Composable
private fun MiniIcon(icon: Mini, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.minDimension
        val line = w * 0.13f
        when (icon) {
            Mini.RANGE -> {
                // Thermomètre.
                drawRoundRect(Color.White, Offset(w * 0.4f, w * 0.05f), Size(w * 0.2f, w * 0.62f), CornerRadius(w * 0.1f), style = Stroke(line * 0.8f))
                drawCircle(Color(0xFFFF8A65), w * 0.2f, Offset(w * 0.5f, w * 0.76f))
            }
            Mini.WIND -> {
                drawLine(Color.White, Offset(w * 0.05f, w * 0.32f), Offset(w * 0.7f, w * 0.32f), line, StrokeCap.Round)
                drawLine(Color.White, Offset(w * 0.05f, w * 0.55f), Offset(w * 0.95f, w * 0.55f), line, StrokeCap.Round)
                drawLine(Color.White, Offset(w * 0.25f, w * 0.78f), Offset(w * 0.75f, w * 0.78f), line, StrokeCap.Round)
            }
            Mini.RAIN -> drawPath(drop(Offset(w * 0.5f, w * 0.05f), w * 0.9f), RAIN_BLUE)
            Mini.HUMIDITY -> drawPath(drop(Offset(w * 0.5f, w * 0.05f), w * 0.9f), Color.White, style = Stroke(line * 0.9f))
        }
    }
}

/** Goutte pointue en haut, de hauteur [height], la pointe en [tip]. */
private fun drop(tip: Offset, height: Float): Path = Path().apply {
    val radius = height * 0.32f
    val center = Offset(tip.x, tip.y + height - radius)
    moveTo(tip.x, tip.y)
    cubicTo(tip.x + radius * 0.2f, tip.y + height * 0.3f, center.x + radius, center.y - radius * 0.6f, center.x + radius, center.y)
    arcTo(Rect(center, radius), 0f, 180f, false)
    cubicTo(center.x - radius, center.y - radius * 0.6f, tip.x - radius * 0.2f, tip.y + height * 0.3f, tip.x, tip.y)
    close()
}

internal fun skyLabel(sky: Sky, day: Boolean): Int = when (sky) {
    Sky.CLEAR -> if (day) R.string.weather_sunny else R.string.weather_clear_night
    Sky.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
    Sky.CLOUDY -> R.string.weather_cloudy
    Sky.FOG -> R.string.weather_fog
    Sky.DRIZZLE -> R.string.weather_drizzle
    Sky.RAIN -> R.string.weather_rain
    Sky.SHOWERS -> R.string.weather_showers
    Sky.SNOW -> R.string.weather_snow
    Sky.STORM -> R.string.weather_storm
}

/** Couleurs du fond (haut, bas) selon le temps, plus sombres la nuit. */
private fun skyColors(sky: Sky, day: Boolean): Pair<Color, Color> = when (sky) {
    Sky.CLEAR -> if (day) Color(0xFF2D6CDF) to Color(0xFF4AA3E0) else Color(0xFF10233A) to Color(0xFF2C4A6B)
    Sky.PARTLY_CLOUDY -> if (day) Color(0xFF3D6A9E) to Color(0xFF6E97C2) else Color(0xFF16222A) to Color(0xFF34556A)
    Sky.CLOUDY -> if (day) Color(0xFF5B6884) to Color(0xFF3B4862) else Color(0xFF2B3440) to Color(0xFF1A2029)
    Sky.FOG -> if (day) Color(0xFF7A8499) to Color(0xFF4E576B) else Color(0xFF3A4050) to Color(0xFF242934)
    Sky.DRIZZLE, Sky.RAIN, Sky.SHOWERS -> if (day) Color(0xFF4A6382) to Color(0xFF2B3D52) else Color(0xFF263B52) to Color(0xFF0F1B2A)
    Sky.SNOW -> if (day) Color(0xFF7F9DC7) to Color(0xFF4D6A8E) else Color(0xFF3E5573) to Color(0xFF1F2E44)
    Sky.STORM -> Color(0xFF3A3F5C) to Color(0xFF141625)
}

/**
 * Pictogrammes du temps, dessinés (pas d'images à charger) : soleil en dégradé, croissant de lune, nuages en relief,
 * gouttes, flocons, éclair, brouillard.
 */
@Composable
internal fun SkyIcon(sky: Sky, day: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.minDimension
        when (sky) {
            Sky.CLEAR -> if (day) sun(center, w * 0.24f) else moon(center, w * 0.32f)
            Sky.PARTLY_CLOUDY -> {
                if (day) sun(Offset(w * 0.38f, w * 0.36f), w * 0.17f) else moon(Offset(w * 0.4f, w * 0.36f), w * 0.22f)
                cloud(w, dy = w * 0.1f)
            }
            Sky.CLOUDY -> {
                cloud(w, dx = -w * 0.12f, dy = -w * 0.1f, scale = 0.75f, top = Color(0xFFB0BEC5), bottom = Color(0xFF78909C))
                cloud(w, dy = w * 0.04f)
            }
            Sky.FOG -> {
                cloud(w, dy = -w * 0.16f, top = Color(0xFFCFD8DC), bottom = Color(0xFF90A4AE))
                repeat(2) { i ->
                    val y = w * (0.72f + i * 0.14f)
                    drawLine(Color.White.copy(alpha = 0.8f), Offset(w * (0.18f + i * 0.08f), y), Offset(w * (0.82f - i * 0.04f), y), w * 0.07f, StrokeCap.Round)
                }
            }
            Sky.DRIZZLE -> {
                cloud(w, dy = -w * 0.14f)
                drops(w, count = 2, small = true)
            }
            Sky.RAIN -> {
                cloud(w, dy = -w * 0.14f, top = Color(0xFFECEFF1), bottom = Color(0xFF90A4AE))
                drops(w, count = 3)
            }
            Sky.SHOWERS -> {
                if (day) sun(Offset(w * 0.66f, w * 0.26f), w * 0.13f) else moon(Offset(w * 0.66f, w * 0.26f), w * 0.17f)
                cloud(w, dy = -w * 0.1f, top = Color(0xFFECEFF1), bottom = Color(0xFF90A4AE))
                drops(w, count = 3)
            }
            Sky.SNOW -> {
                cloud(w, dy = -w * 0.14f)
                repeat(3) { i -> flake(Offset(w * (0.3f + i * 0.2f), w * (0.8f + (i % 2) * 0.08f)), w * 0.07f) }
            }
            Sky.STORM -> {
                cloud(w, dy = -w * 0.14f, top = Color(0xFF90A4AE), bottom = Color(0xFF546E7A))
                val bolt = Path().apply {
                    moveTo(w * 0.54f, w * 0.56f)
                    lineTo(w * 0.4f, w * 0.78f)
                    lineTo(w * 0.52f, w * 0.77f)
                    lineTo(w * 0.45f, w * 0.97f)
                    lineTo(w * 0.66f, w * 0.7f)
                    lineTo(w * 0.54f, w * 0.71f)
                    close()
                }
                drawPath(bolt, Color(0xFFFFD54F))
            }
        }
    }
}

private fun DrawScope.sun(center: Offset, radius: Float) {
    repeat(8) { i ->
        val angle = i * Math.PI.toFloat() / 4
        val direction = Offset(cos(angle), sin(angle))
        drawLine(Color(0xFFFFCA28), center + direction * radius * 1.35f, center + direction * radius * 1.8f, radius * 0.28f, StrokeCap.Round)
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF176), Color(0xFFFFB300)), center, radius), radius, center)
}

/** Croissant de lune (deux cercles soustraits : rien n'est peint par-dessus le fond). */
private fun DrawScope.moon(center: Offset, radius: Float) {
    val disc = Path().apply { addOval(Rect(center, radius)) }
    val shadow = Path().apply { addOval(Rect(center + Offset(radius * 0.5f, -radius * 0.35f), radius * 0.85f)) }
    val crescent = Path().apply { op(disc, shadow, PathOperation.Difference) }
    drawPath(crescent, Brush.linearGradient(listOf(Color(0xFFFFF8E1), Color(0xFFFFE082)), center - Offset(radius, radius), center + Offset(radius, radius)))
}

/** Nuage en relief (dégradé vertical commun à ses parties), décalé de [dx], [dy] et réduit par [scale]. */
private fun DrawScope.cloud(
    w: Float,
    dx: Float = 0f,
    dy: Float = 0f,
    scale: Float = 1f,
    top: Color = Color.White,
    bottom: Color = Color(0xFFB0BEC5),
) {
    fun x(v: Float) = w * 0.5f + (v - w * 0.5f) * scale + dx
    fun y(v: Float) = w * 0.5f + (v - w * 0.5f) * scale + dy
    val brush = Brush.verticalGradient(listOf(top, bottom), startY = y(w * 0.2f), endY = y(w * 0.74f))
    drawCircle(brush, w * 0.17f * scale, Offset(x(w * 0.32f), y(w * 0.54f)))
    drawCircle(brush, w * 0.23f * scale, Offset(x(w * 0.52f), y(w * 0.44f)))
    drawCircle(brush, w * 0.16f * scale, Offset(x(w * 0.7f), y(w * 0.56f)))
    drawRoundRect(brush, Offset(x(w * 0.16f), y(w * 0.52f)), Size(w * 0.7f * scale, w * 0.21f * scale), CornerRadius(w * 0.1f * scale))
}

private fun DrawScope.drops(w: Float, count: Int, small: Boolean = false) {
    val height = w * (if (small) 0.18f else 0.24f)
    repeat(count) { i ->
        val x = w * (0.32f + i * (0.36f / (count - 1).coerceAtLeast(1)))
        drawPath(drop(Offset(x, w * 0.68f + (i % 2) * w * 0.06f), height), RAIN_BLUE)
    }
}

private fun DrawScope.flake(center: Offset, radius: Float) {
    repeat(3) { i ->
        val angle = i * Math.PI.toFloat() / 3
        val direction = Offset(cos(angle), sin(angle)) * radius
        drawLine(Color.White, center - direction, center + direction, radius * 0.4f, StrokeCap.Round)
    }
}

private val RAIN_BLUE = Color(0xFF64B5F6)

/** Risque de pluie montré à partir de… (en dessous, rien : il ne pleuvra sans doute pas). */
private const val RAIN_SHOWN = 20
