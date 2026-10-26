package app.planner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import app.planner.readiness.Readiness
import app.planner.readiness.ReadinessBand
import app.planner.readiness.ReadinessInputs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only the final score of a day is stored: it is fixed once set, so overruns replan instead of rescoring. */
@Entity(tableName = "readiness")
data class ReadinessEntity(
    @PrimaryKey val date: String,
    val score: Int,
    val band: String,
    val inputsJson: String,
    val computedAtMillis: Long,
)

@Serializable
private data class InputsDto(val asleepMin: Int, val debtMin: Int, val lightsOutDelayMin: Int, val doneRatio: Double?)

private val json = Json { ignoreUnknownKeys = true }

fun Readiness.toEntity(date: String, computedAtMillis: Long) = ReadinessEntity(
    date = date,
    score = score,
    band = band.name,
    inputsJson = json.encodeToString(InputsDto(inputs.asleepMin, inputs.debtMin, inputs.lightsOutDelayMin, inputs.doneRatio)),
    computedAtMillis = computedAtMillis,
)

fun ReadinessEntity.toReadiness(): Readiness {
    val i = json.decodeFromString<InputsDto>(inputsJson)
    return Readiness(score, ReadinessBand.valueOf(band), ReadinessInputs(i.asleepMin, i.debtMin, i.lightsOutDelayMin, i.doneRatio))
}

@Dao
interface ReadinessDao {
    @Query("SELECT * FROM readiness WHERE date = :date")
    suspend fun get(date: String): ReadinessEntity?

    @Upsert
    suspend fun put(entity: ReadinessEntity)
}
