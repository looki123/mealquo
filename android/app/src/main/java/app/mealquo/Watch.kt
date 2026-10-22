package app.mealquo

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object Watch {
    private fun needed() = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    fun status(ctx: Context): String {
        return when (HealthConnectClient.getSdkStatus(ctx)) {
            HealthConnectClient.SDK_AVAILABLE -> "ready"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "install"
            else -> "missing"
        }
    }

    fun granted(ctx: Context): Boolean = runBlocking {
        if (status(ctx) != "ready") return@runBlocking false
        val have = HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
        have.containsAll(needed())
    }

    fun permissions(): Set<String> = needed()

    fun read(ctx: Context, day: String): String = runBlocking {
        val client = HealthConnectClient.getOrCreate(ctx)
        val zone = ZoneId.systemDefault()
        val date = LocalDate.parse(day)
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val filter = TimeRangeFilter.between(start, end)
        val sessions = client.readRecords(
            ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = filter)
        ).records
        val calories = client.readRecords(
            ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeRangeFilter = filter)
        ).records
        val items = JSONArray()
        for (session in sessions) {
            var kcal = 0.0
            for (burn in calories) {
                val from = maxOf(session.startTime, burn.startTime)
                val to = minOf(session.endTime, burn.endTime)
                if (!from.isBefore(to)) continue
                val whole = ChronoUnit.SECONDS.between(burn.startTime, burn.endTime).coerceAtLeast(1)
                val part = ChronoUnit.SECONDS.between(from, to).coerceAtLeast(0)
                kcal += burn.energy.inKilocalories * part.toDouble() / whole.toDouble()
            }
            val minutes = ChronoUnit.MINUTES.between(session.startTime, session.endTime).toInt().coerceAtLeast(1)
            val title = session.title?.trim().orEmpty().ifEmpty { typeName(session.exerciseType) }
            items.put(JSONObject()
                .put("id", session.metadata.id)
                .put("name", title)
                .put("kcal", Math.round(kcal))
                .put("min", minutes)
                .put("date", day))
        }
        JSONObject().put("items", items).toString()
    }

    private fun typeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Bieganie"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Bieżnia"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Spacer"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Rower"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Rower stacjonarny"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Siłownia"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Siłownia"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Pływanie"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Pływanie"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Joga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "Boks"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "Taniec"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "Wioślarz"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Orbitrek"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "Schody"
        else -> "Trening"
    }
}
