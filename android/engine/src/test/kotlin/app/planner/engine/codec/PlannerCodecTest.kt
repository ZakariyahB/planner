package app.planner.engine.codec

import app.planner.engine.changeset.ChangeFlag
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.InverseOp
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.Prayer
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.TaskKind
import app.planner.engine.model.TaskStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PlannerCodecTest {
    private fun <T> JsonCodec<T>.roundTrip(value: T): T = decode(encode(value))

    private fun keys(text: String): Set<String> = Json.parseToJsonElement(text).jsonObject.keys

    // ---- Round trips: every type, every sealed subtype ----

    @Test
    fun `task round-trips with every field set`() {
        assertEquals(Samples.task, PlannerCodec.task.roundTrip(Samples.task))
    }

    @Test
    fun `task round-trips with only required fields`() {
        assertEquals(Samples.minimalTask, PlannerCodec.task.roundTrip(Samples.minimalTask))
    }

    @Test
    fun `task list round-trips in order`() {
        val list = listOf(Samples.task, Samples.minimalTask)
        assertEquals(list, PlannerCodec.task.decodeList(PlannerCodec.task.encodeList(list)))
        assertEquals(emptyList(), PlannerCodec.task.decodeList(PlannerCodec.task.encodeList(emptyList())))
    }

    @Test
    fun `settings round-trip with every field changed`() {
        assertEquals(Samples.settings, PlannerCodec.settings.roundTrip(Samples.settings))
    }

    @Test
    fun `default settings round-trip`() {
        assertEquals(Settings(), PlannerCodec.settings.roundTrip(Settings()))
    }

    @Test
    fun `settings keep zone, local times and the prayer offset map`() {
        val decoded = PlannerCodec.settings.roundTrip(Samples.settings)
        assertEquals(ZoneId.of("America/New_York"), decoded.zone)
        assertEquals(LocalTime.of(8, 45), decoded.wakeTime)
        assertEquals(mapOf(Prayer.DHUHR to 15, Prayer.ASR to -5), decoded.prayerOffsetMin)
        assertEquals(LocalTime.of(22, 5), decoded.notifications.nightBeforeReview)
    }

    @Test
    fun `settings with a fixed-offset zone and no writable calendars round-trip`() {
        val s = Settings(zone = ZoneId.of("UTC+03:00"), writableCategories = emptySet())
        assertEquals(s, PlannerCodec.settings.roundTrip(s))
    }

    @Test
    fun `event time round-trips for both subtypes`() {
        val timed: EventTime = Samples.timed
        val allDay: EventTime = Samples.allDay
        assertEquals(timed, PlannerCodec.eventTime.roundTrip(timed))
        assertEquals(allDay, PlannerCodec.eventTime.roundTrip(allDay))
        assertIs<EventTime.Timed>(PlannerCodec.eventTime.roundTrip(timed))
        assertIs<EventTime.AllDay>(PlannerCodec.eventTime.roundTrip(allDay))
    }

    @Test
    fun `event snapshot round-trips, timed and all-day`() {
        val a = Samples.snapshot()
        val b = Samples.snapshot(time = Samples.allDay)
        assertEquals(a, PlannerCodec.eventSnapshot.roundTrip(a))
        assertEquals(b, PlannerCodec.eventSnapshot.roundTrip(b))
        assertTrue(PlannerCodec.eventSnapshot.roundTrip(a).isPlannerBlock)
    }

    @Test
    fun `event draft round-trips`() {
        val d = Samples.draft()
        assertEquals(d, PlannerCodec.eventDraft.roundTrip(d))
    }

    @Test
    fun `event ref round-trips`() {
        assertEquals(Samples.ref, PlannerCodec.eventRef.roundTrip(Samples.ref))
    }

    @Test
    fun `every change type round-trips`() {
        for (c in listOf(Samples.add, Samples.move, Samples.edit, Samples.remove)) {
            assertEquals(c, PlannerCodec.change.roundTrip(c), c.type.name)
        }
        assertEquals(ChangeType.entries.toSet(), Samples.changeSet.changes.map { it.type }.toSet())
    }

    @Test
    fun `change set round-trips with all its changes`() {
        assertEquals(Samples.changeSet, PlannerCodec.changeSet.roundTrip(Samples.changeSet))
    }

    @Test
    fun `instants keep nanoseconds`() {
        val cs = Samples.changeSet.copy(createdAt = Instant.parse("2026-11-02T18:00:00.123456789Z"))
        assertEquals(cs.createdAt, PlannerCodec.changeSet.roundTrip(cs).createdAt)
    }

    @Test
    fun `every inverse op round-trips`() {
        val ops = listOf(
            InverseOp.Delete(Samples.ref),
            InverseOp.Restore(Samples.snapshot()),
            InverseOp.Restore(Samples.snapshot(time = Samples.allDay)),
            InverseOp.Revert(Samples.ref, Samples.draft(Samples.allDay)),
        )
        for (op in ops) assertEquals(op, PlannerCodec.inverseOp.roundTrip(op))
        assertEquals(ops, PlannerCodec.inverseOp.decodeList(PlannerCodec.inverseOp.encodeList(ops)))
    }

    @Test
    fun `applied record round-trips with results and inverse ops in order`() {
        assertEquals(Samples.appliedRecord, PlannerCodec.appliedRecord.roundTrip(Samples.appliedRecord))
    }

    @Test
    fun `execution log round-trips, full and pending`() {
        val pending = ExecutionLog("log-2", null, null, "Halaqa", Samples.day.atTime(19, 0), Samples.day.atTime(20, 0))
        assertEquals(Samples.log, PlannerCodec.executionLog.roundTrip(Samples.log))
        assertEquals(pending, PlannerCodec.executionLog.roundTrip(pending))
    }

    @Test
    fun `every enum value survives a round trip`() {
        CalendarCategory.entries.forEach { c ->
            assertEquals(c, PlannerCodec.task.roundTrip(Samples.minimalTask.copy(category = c)).category)
        }
        TaskKind.entries.forEach { k -> assertEquals(k, PlannerCodec.task.roundTrip(Samples.minimalTask.copy(kind = k)).kind) }
        TaskStatus.entries.forEach { s -> assertEquals(s, PlannerCodec.task.roundTrip(Samples.minimalTask.copy(status = s)).status) }
        Prayer.entries.forEach { p ->
            assertEquals(p, PlannerCodec.task.roundTrip(Samples.minimalTask.copy(afterPrayer = p)).afterPrayer)
        }
        assertEquals(ChangeFlag.entries.toSet(), PlannerCodec.change.roundTrip(Samples.add.copy(flags = ChangeFlag.entries.toSet())).flags)
        RecurrenceScope.entries.forEach { s -> assertEquals(s, PlannerCodec.change.roundTrip(Samples.move.copy(scope = s)).scope) }
        ChangeStatus.entries.forEach { s -> assertEquals(s, PlannerCodec.changeSet.roundTrip(Samples.changeSet.copy(status = s)).status) }
        ChangeSource.entries.forEach { s -> assertEquals(s, PlannerCodec.changeSet.roundTrip(Samples.changeSet.copy(source = s)).source) }
        ExecutionOutcome.entries.forEach { o -> assertEquals(o, PlannerCodec.executionLog.roundTrip(Samples.log.copy(outcome = o)).outcome) }
        DayOfWeek.entries.forEach { d -> assertEquals(d, PlannerCodec.settings.roundTrip(Settings(catchUpDay = d)).catchUpDay) }
    }

    // ---- Stored format ----

    @Test
    fun `sealed subtypes are tagged with a kind field`() {
        assertEquals(
            """{"kind":"timed","start":"2026-11-03T10:15","end":"2026-11-03T12:30:05.123456789"}""",
            PlannerCodec.eventTime.encode(Samples.timed),
        )
        assertEquals("""{"kind":"allDay","start":"2026-11-03","endExclusive":"2026-11-04"}""", PlannerCodec.eventTime.encode(Samples.allDay))
        assertEquals(
            """{"kind":"delete","ref":{"calendarId":"cal-academic","eventId":"evt0001"}}""",
            PlannerCodec.inverseOp.encode(InverseOp.Delete(Samples.ref)),
        )
    }

    @Test
    fun `encoding writes defaults but leaves out nulls`() {
        val k = keys(PlannerCodec.task.encode(Samples.minimalTask))
        assertTrue("splittable" in k && "minChunkMin" in k && "status" in k && "calendarEventIds" in k)
        assertTrue("deadline" !in k && "notes" !in k && "afterPrayer" !in k)
    }

    @Test
    fun `encoding is deterministic regardless of set and map order`() {
        val a = Samples.add.copy(flags = linkedSetOf(ChangeFlag.RECURRING, ChangeFlag.EXTENDED_DAY))
        val b = Samples.add.copy(flags = linkedSetOf(ChangeFlag.EXTENDED_DAY, ChangeFlag.RECURRING))
        assertEquals(PlannerCodec.change.encode(a), PlannerCodec.change.encode(b))
        val s1 = Settings(prayerOffsetMin = linkedMapOf(Prayer.ASR to 1, Prayer.DHUHR to 2))
        val s2 = Settings(prayerOffsetMin = linkedMapOf(Prayer.DHUHR to 2, Prayer.ASR to 1))
        assertEquals(PlannerCodec.settings.encode(s1), PlannerCodec.settings.encode(s2))
    }

    // ---- Tolerant decoding ----

    @Test
    fun `unknown keys are ignored everywhere`() {
        val task = PlannerCodec.task.decode(
            """{"id":"t1","title":"Gym","category":"FITNESS","durationMin":60,"colour":"red","nested":{"a":[1,2]}}""",
        )
        assertEquals(Task("t1", "Gym", CalendarCategory.FITNESS, 60), task)
        val time = PlannerCodec.eventTime.decode("""{"kind":"allDay","start":"2026-11-03","endExclusive":"2026-11-04","tz":"x"}""")
        assertEquals(Samples.allDay, time)
    }

    @Test
    fun `missing task fields take the model defaults`() {
        val task = PlannerCodec.task.decode("""{"id":"t1","title":"Gym","durationMin":60}""")
        assertEquals(Task("t1", "Gym", CalendarCategory.PERSONAL, 60), task)
    }

    @Test
    fun `an empty settings object is the default settings`() {
        assertEquals(Settings(), PlannerCodec.settings.decode("{}"))
    }

    @Test
    fun `partial settings keep what is there and default the rest`() {
        val s = PlannerCodec.settings.decode("""{"wakeTime":"08:00","notifications":{"snoozeMin":5}}""")
        assertEquals(Settings().copy(wakeTime = LocalTime.of(8, 0), notifications = Settings().notifications.copy(snoozeMin = 5)), s)
    }

    @Test
    fun `unknown enum values fall back to the default`() {
        val task = PlannerCodec.task.decode(
            """{"id":"t1","title":"x","category":"GARDENING","durationMin":30,"kind":"WEIRD","status":"ARCHIVED","afterPrayer":"TAHAJJUD"}""",
        )
        assertEquals(CalendarCategory.PERSONAL, task.category)
        assertEquals(TaskKind.DEEP, task.kind)
        assertEquals(TaskStatus.BACKLOG, task.status)
        assertNull(task.afterPrayer)
        val result = PlannerCodec.appliedRecord.decode(
            """{"changeSetId":"cs","appliedAt":"2026-11-02T18:05:30Z","results":[{"changeId":"c","status":"HALF_DONE"}]}""",
        )
        assertEquals(ChangeStatus.FAILED, result.results.single().status)
        assertTrue(!result.allVerified)
    }

    @Test
    fun `unknown names in enum sets and maps are dropped`() {
        val change = PlannerCodec.change.decode(
            """{"id":"c","type":"ADD","after":{"calendarId":"cal","title":"t","time":{"kind":"allDay","start":"2026-11-03","endExclusive":"2026-11-04"}},"flags":["RECURRING","SHINY"]}""",
        )
        assertEquals(setOf(ChangeFlag.RECURRING), change.flags)
        val s = PlannerCodec.settings.decode(
            """{"prayerOffsetMin":{"DHUHR":10,"TAHAJJUD":5},"writableCategories":["ACADEMIC","GARDENING"],"catchUpDay":"FUNDAY"}""",
        )
        assertEquals(mapOf(Prayer.DHUHR to 10), s.prayerOffsetMin)
        assertEquals(setOf(CalendarCategory.ACADEMIC), s.writableCategories)
        assertEquals(DayOfWeek.SUNDAY, s.catchUpDay)
    }

    @Test
    fun `an unknown zone falls back to Europe London`() {
        assertEquals(ZoneId.of("Europe/London"), PlannerCodec.settings.decode("""{"zone":"Mars/Olympus_Mons"}""").zone)
    }

    @Test
    fun `null where a default exists takes the default`() {
        val task = PlannerCodec.task.decode("""{"id":"t1","title":"x","durationMin":30,"splittable":null,"calendarEventIds":null}""")
        assertEquals(true, task.splittable)
        assertEquals(emptyList(), task.calendarEventIds)
    }

    @Test
    fun `a snapshot without etag or updated decodes and will read as stale`() {
        val s = PlannerCodec.eventSnapshot.decode(
            """{"ref":{"calendarId":"c","eventId":"e"},"time":{"kind":"allDay","start":"2026-11-03","endExclusive":"2026-11-04"}}""",
        )
        assertEquals("", s.etag)
        assertEquals(Instant.EPOCH, s.updated)
        assertNotEquals(Samples.snapshot().etag, s.etag)
    }

    @Test
    fun `old change sets without notes or summary still decode`() {
        val cs = PlannerCodec.changeSet.decode("""{"id":"cs","createdAt":"2026-11-02T18:00:00Z","changes":[]}""")
        assertEquals(ChangeSource.USER, cs.source)
        assertEquals("", cs.summary)
        assertEquals(emptyList(), cs.notes)
        assertEquals(ChangeStatus.PROPOSED, cs.status)
    }

    @Test
    fun `json that is not a record at all is an error, not a silent default`() {
        assertFailsWith<SerializationException> { PlannerCodec.task.decode("""{"title":"no id"}""") }
        assertFailsWith<SerializationException> { PlannerCodec.eventTime.decode("""{"kind":"recurring","rule":"x"}""") }
        assertFailsWith<SerializationException> { PlannerCodec.task.decode("not json") }
    }

    @Test
    fun `a change that breaks its shape rule is rejected`() {
        // ADD with a target: the domain type's own check fires rather than storing a malformed change.
        val text = PlannerCodec.change.encode(Samples.move).replace("\"type\":\"MOVE\"", "\"type\":\"ADD\"")
        assertFailsWith<IllegalArgumentException> { PlannerCodec.change.decode(text) }
    }
}
