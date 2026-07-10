package me.rerere.rikkahub.data.ai.tools.local

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

fun screenTimeTool(context: Context): Tool = Tool(
    name = "screen_time",
    description = """
        Query app screen usage (screen time) over a time range.
        Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week).
        Returns the total foreground time and a per-app breakdown sorted by usage time (descending).
        The device timezone is '${ZoneId.systemDefault()}'.
        Requires the 'Usage access' special permission; if it is not granted, the device's
        usage access settings page is opened automatically and an error is returned.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put("description",
                        "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', offset date-time, or epoch milliseconds. " +
                            "When provided, 'range' is ignored.")
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put("description", "End time (exclusive), same formats as 'begin'. Defaults to now.")
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("today"); add("week") })
                    put("description", "Convenience preset, used only when 'begin' is omitted: today or week. Default today.")
                })
                put("top", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of top apps to return, sorted by usage time. Default 10.")
                })
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val top = params["top"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = if (beginRaw != null) {
                parseTimeString(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.toLocalDate().atStartOfDay(zone)
                    .minusDays(now.dayOfWeek.value.toLong() - 1)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
            endTime = if (endRaw != null) {
                parseTimeString(endRaw, zone)
            } else when (rangePreset) {
                "week" -> startTime.plusDays(7)
                else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
            }
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageStats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            startMs,
            endMs
        )

        if (usageStats.isNullOrEmpty()) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION_OR_NO_DATA")
                put("message",
                    "No usage data available. Make sure 'Usage access' permission is granted " +
                        "for RikkaHub Agent in Settings → Security → Apps with usage access.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val sorted = usageStats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(top)

        val totalMs = sorted.sumOf { it.totalTimeInForeground }
        val totalStr = formatDuration(totalMs)

        val apps = buildJsonArray {
            for (stat in sorted) {
                add(buildJsonObject {
                    put("package", stat.packageName)
                    put("foreground_duration", formatDuration(stat.totalTimeInForeground))
                    put("foreground_ms", stat.totalTimeInForeground)
                    put("last_used", stat.lastTimeUsed.let {
                        if (it > 0) Instant.ofEpochMilli(it).atZone(zone).withNano(0).toString()
                        else "never"
                    })
                })
            }
        }

        val payload = buildJsonObject {
            put("range_start", startTime.withNano(0).toString())
            put("range_end", endTime.withNano(0).toString())
            put("total_foreground_time", totalStr)
            put("app_count", apps.size)
            put("apps", apps)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun parseTimeString(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return java.time.OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return java.time.LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Invalid time format: '$raw'")
}

private fun formatDuration(millis: Long): String {
    val d = Duration.ofMillis(millis)
    val h = d.toHours()
    val m = d.toMinutes() % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
