package app.planner.engine.codec

import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.InverseOp
import app.planner.engine.model.EventRef
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Encodes one domain type to JSON and back. [decode] throws [kotlinx.serialization.SerializationException] on JSON it cannot read at all. */
interface JsonCodec<T> {
    fun encode(value: T): String
    fun decode(text: String): T
    fun encodeList(values: List<T>): String
    fun decodeList(text: String): List<T>
}

private class DtoCodec<T, D>(
    private val json: Json,
    private val serializer: KSerializer<D>,
    private val toDto: (T) -> D,
    private val fromDto: (D) -> T,
) : JsonCodec<T> {
    private val listSerializer = ListSerializer(serializer)
    override fun encode(value: T): String = json.encodeToString(serializer, toDto(value))
    override fun decode(text: String): T = fromDto(json.decodeFromString(serializer, text))
    override fun encodeList(values: List<T>): String = json.encodeToString(listSerializer, values.map(toDto))
    override fun decodeList(text: String): List<T> = json.decodeFromString(listSerializer, text).map(fromDto)
}

/**
 * JSON codecs for every v2 type that is stored (Room JSON columns, backups, the assistant's context).
 *
 * Decoding is tolerant: unknown keys are ignored, a missing field takes the domain type's default, a null where a
 * value with a default is expected takes the default, and an unknown enum name falls back to the default (or is
 * dropped from a set or map). Encoding writes every field, defaults included, so stored data does not silently change
 * meaning if a default changes later.
 */
object PlannerCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    private fun <T, D> codec(serializer: KSerializer<D>, to: (T) -> D, from: (D) -> T): JsonCodec<T> =
        DtoCodec(json, serializer, to, from)

    val task: JsonCodec<Task> = codec(TaskDto.serializer(), Task::toDto, TaskDto::toModel)
    val settings: JsonCodec<Settings> = codec(SettingsDto.serializer(), Settings::toDto, SettingsDto::toModel)
    val eventRef: JsonCodec<EventRef> = codec(EventRef.serializer(), { it }, { it })
    val eventTime: JsonCodec<EventTime> = codec(EventTimeDto.serializer(), EventTime::toDto, EventTimeDto::toModel)
    val eventSnapshot: JsonCodec<EventSnapshot> =
        codec(EventSnapshotDto.serializer(), EventSnapshot::toDto, EventSnapshotDto::toModel)
    val eventDraft: JsonCodec<EventDraft> = codec(EventDraftDto.serializer(), EventDraft::toDto, EventDraftDto::toModel)
    val change: JsonCodec<Change> = codec(ChangeDto.serializer(), Change::toDto, ChangeDto::toModel)
    val changeSet: JsonCodec<ChangeSet> = codec(ChangeSetDto.serializer(), ChangeSet::toDto, ChangeSetDto::toModel)
    val inverseOp: JsonCodec<InverseOp> = codec(InverseOpDto.serializer(), InverseOp::toDto, InverseOpDto::toModel)
    val appliedRecord: JsonCodec<AppliedRecord> =
        codec(AppliedRecordDto.serializer(), AppliedRecord::toDto, AppliedRecordDto::toModel)
    val executionLog: JsonCodec<ExecutionLog> =
        codec(ExecutionLogDto.serializer(), ExecutionLog::toDto, ExecutionLogDto::toModel)
}
