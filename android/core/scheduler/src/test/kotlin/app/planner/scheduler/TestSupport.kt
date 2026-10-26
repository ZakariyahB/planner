package app.planner.scheduler

import app.planner.calendar.FixedEvent
import app.planner.model.Prayer
import app.planner.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val london: ZoneId = ZoneId.of("Europe/London")

fun date(s: String): LocalDate = LocalDate.parse(s)

fun time(s: String): LocalTime = LocalTime.parse(s)

fun instant(date: String, time: String): Instant = date(date).atTime(time(time)).atZone(london).toInstant()

fun fixed(id: String, date: String, start: String, end: String) =
    FixedEvent(id, "main", id, instant(date, start), instant(date, end))

fun task(id: Long, title: String, estMin: Int, due: String? = null, priority: app.planner.model.Priority = app.planner.model.Priority.NORMAL, from: Instant? = null, anchor: app.planner.model.Anchor? = null) =
    Task(
        id = id,
        title = title,
        estMin = estMin,
        dueAt = due?.let { instant(it, "23:59") },
        priority = priority,
        availableFrom = from,
        anchor = anchor,
    )

fun prayers(dhuhr: String, asr: String, maghrib: String, isha: String, fajr: String? = null): Map<Prayer, LocalTime> =
    buildMap {
        put(Prayer.DHUHR, time(dhuhr))
        put(Prayer.ASR, time(asr))
        put(Prayer.MAGHRIB, time(maghrib))
        put(Prayer.ISHA, time(isha))
        fajr?.let { put(Prayer.FAJR, time(it)) }
    }

fun Block.span(): String = "${start}-${end}"
