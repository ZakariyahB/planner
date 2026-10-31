package app.planner.sleep

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.planner.data.SLEEP_SOURCE_HEALTH_CONNECT
import app.planner.data.SLEEP_SOURCE_MANUAL
import app.planner.data.SleepDao
import app.planner.data.toEntity
import app.planner.data.toSession
import app.planner.errors.PlainErrors
import app.planner.readiness.SleepSession
import app.planner.readiness.SleepSources
import app.planner.readiness.SleepStage
import app.planner.readiness.StageKind
import app.planner.readiness.sleepSessionFromStages
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

sealed interface HealthStatus {
    /** Health Connect missing or needs an update from the Play Store. */
    data class Unavailable(val reason: String) : HealthStatus
    data object NeedsPermission : HealthStatus
    data object Ready : HealthStatus
}

sealed interface SleepSyncResult {
    data class Synced(val sessions: Int) : SleepSyncResult
    data class NotReady(val status: HealthStatus) : SleepSyncResult
    data class Failed(val message: String) : SleepSyncResult
}

/** Sleep sessions from Health Connect (Fitbit writes into it) plus any typed in by hand. */
@Singleton
class SleepRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SleepDao,
    private val clock: Clock,
) {
    val sessions: Flow<List<SleepSession>> =
        flow { emitAll(dao.observeSince(lookbackStart().toEpochMilli())) }.map { rows -> rows.map { it.toSession() } }

    suspend fun healthStatus(): HealthStatus {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> try {
                val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
                if (PERMISSIONS.all { it in granted }) HealthStatus.Ready else HealthStatus.NeedsPermission
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HealthStatus.Unavailable("Couldn't reach Health Connect. Open the Health Connect app once, then tap Refresh.")
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthStatus.Unavailable("Health Connect needs an update from the Play Store")
            else -> HealthStatus.Unavailable("Health Connect isn't available on this phone")
        }
    }

    /**
     * Whether the periodic job may read sleep while the app is closed. Null when that does not apply: Health
     * Connect is missing or the sleep permission itself is not granted yet.
     */
    suspend fun backgroundReadGranted(): Boolean? {
        if (healthStatus() != HealthStatus.Ready) return null
        return try {
            BACKGROUND_PERMISSION in HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Replaces the Health Connect rows of the last three weeks with what Health Connect has now: when he slept,
     * when he woke, the times he was awake in between, and the total slept. Each record's writer (`dataOrigin`)
     * decides whether it is kept: Fitbit's records always, another app's (such as Samsung Health) only for a sleep
     * day Fitbit has nothing for, so two apps recording the same night are never added together.
     */
    suspend fun syncFromHealthConnect(): SleepSyncResult {
        val status = healthStatus()
        if (status != HealthStatus.Ready) return SleepSyncResult.NotReady(status)
        return try {
            val since = lookbackStart()
            val client = HealthConnectClient.getOrCreate(context)
            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(since, clock.instant().plus(Duration.ofHours(1))),
                ),
            ).records
            val sessions = SleepSources.select(records.map(::toSession), clock.zone)
            dao.deleteHealthConnectSince(since.toEpochMilli())
            dao.insertAll(sessions.map { it.toEntity(SLEEP_SOURCE_HEALTH_CONNECT) })
            SleepSyncResult.Synced(sessions.size)
        } catch (e: SecurityException) {
            SleepSyncResult.NotReady(HealthStatus.NeedsPermission)
        } catch (e: IOException) {
            SleepSyncResult.Failed(PlainErrors.HEALTH_READ_FAILED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SleepSyncResult.Failed(PlainErrors.HEALTH_READ_FAILED)
        }
    }

    /** For nights Health Connect doesn't have; counted as asleep for the whole period. */
    suspend fun addManual(start: Instant, end: Instant) {
        require(end > start) { "wake time must be after bed time" }
        val minutes = Duration.between(start, end).toMinutes().toInt()
        dao.insert(SleepSession(start, end, minutes, manual = true).toEntity(SLEEP_SOURCE_MANUAL))
    }

    private fun lookbackStart(): Instant = clock.instant().minus(Duration.ofDays(LOOKBACK_DAYS))

    /** Time in bed minus the stretches he was awake (see [sleepSessionFromStages] for why), with the app that wrote it. */
    private fun toSession(r: SleepSessionRecord): SleepSession =
        sleepSessionFromStages(r.startTime, r.endTime, r.stages.map { SleepStage(stageKind(it.stage), it.startTime, it.endTime) })
            .copy(origin = r.metadata.dataOrigin.packageName)

    private fun stageKind(stage: Int): StageKind = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        -> StageKind.AWAKE
        SleepSessionRecord.STAGE_TYPE_LIGHT -> StageKind.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> StageKind.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> StageKind.REM
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> StageKind.ASLEEP
        else -> StageKind.UNKNOWN
    }

    companion object {
        private const val LOOKBACK_DAYS = 21L

        /** Lets the 15-minute job read sleep while the app is closed. Not every Health Connect version offers it. */
        const val BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        /** Request these through Health Connect's permission contract. */
        val PERMISSIONS: Set<String> = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))
    }
}
