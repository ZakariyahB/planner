package app.planner.engine.codec

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// java.time values as ISO-8601 strings. toString/parse round-trip exactly (nanoseconds included), and the strings stay
// readable in a database browser or a backup file.

internal abstract class IsoSerializer<T : Any>(
    name: String,
    private val format: (T) -> String,
    private val parse: (String) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.planner.$name", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(format(value))
    override fun deserialize(decoder: Decoder): T = parse(decoder.decodeString())
}

internal object LocalDateIso : IsoSerializer<LocalDate>("LocalDate", LocalDate::toString, LocalDate::parse)

internal object LocalTimeIso : IsoSerializer<LocalTime>("LocalTime", LocalTime::toString, LocalTime::parse)

internal object LocalDateTimeIso : IsoSerializer<LocalDateTime>("LocalDateTime", LocalDateTime::toString, LocalDateTime::parse)

internal object InstantIso : IsoSerializer<Instant>("Instant", Instant::toString, Instant::parse)

/** Unknown names (a value written by a newer version, or a typo in a backup) become null instead of failing. */
internal inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }
