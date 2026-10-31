package app.planner.engine.nudge

import app.planner.engine.checkin.CheckInTexts
import app.planner.engine.checkin.NoticeText
import app.planner.engine.checkin.OpenTarget
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.ports.TodayBlock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

// Prompts carried over from v2 (newplan section 12). Pure: :app sets one exact alarm per upcoming prompt and, when it
// fires, asks [NudgeTexts] what to say given what the app knows at that moment.

enum class NudgeKind {
    /** On Sundays, ask for upcoming deadlines. */
    SUNDAY_DEADLINES,

    /** Each week, ask for rough Fajr times (sleep and wake coaching). Skipped when the week's Fajr is known. */
    WEEKLY_FAJR,

    /** The evening before: a 5-minute look at tomorrow, deciding roughly when each task happens. */
    NIGHT_BEFORE_REVIEW,
}

data class Nudge(
    /** Stable per kind and date: rescheduling replaces it. */
    val id: String,
    val kind: NudgeKind,
    val at: LocalDateTime,
    /** The day the prompt is about: the Sunday, the Monday of the Fajr week, or tomorrow. */
    val forDate: LocalDate,
)

/** When the weekly prompts go out. Built from [NotificationPrefs] by [from]; the defaults match NotificationPrefs'. */
data class NudgeSchedule(
    val deadlinesDay: DayOfWeek = DayOfWeek.SUNDAY,
    /** The legacy Sunday review went out at 19:00; the deadlines prompt replaces it at the same time. */
    val deadlinesAt: LocalTime = LocalTime.of(19, 0),
    val fajrDay: DayOfWeek = DayOfWeek.SUNDAY,
    /** Midday, so it does not arrive on top of the deadlines prompt. */
    val fajrAt: LocalTime = LocalTime.of(12, 0),
    val nightBeforeEnabled: Boolean = true,
) {
    companion object {
        fun from(prefs: app.planner.engine.model.NotificationPrefs) = NudgeSchedule(
            deadlinesAt = prefs.sundayDeadlinesAt,
            fajrDay = prefs.fajrPromptDay,
            fajrAt = prefs.fajrPromptAt,
            nightBeforeEnabled = prefs.nightBeforeReviewEnabled,
        )
    }
}

object NudgePlanner {
    private const val LOOKAHEAD_DAYS = 8L

    /**
     * The next occurrence (strictly after [now]) of every enabled prompt. The Sunday prompts follow
     * NotificationPrefs.sundayPrompts; the night-before review goes out at NotificationPrefs.nightBeforeReview,
     * never later than the hard cap. A prompt that lands in a prayer slot moves to the end of the slot, unless that
     * would take it past the hard cap.
     */
    fun next(
        now: LocalDateTime,
        settings: Settings,
        anchorsOn: (LocalDate) -> List<PrayerAnchor> = { emptyList() },
        schedule: NudgeSchedule = NudgeSchedule.from(settings.notifications),
    ): List<Nudge> {
        val prefs = settings.notifications
        val kinds = buildList {
            if (prefs.sundayPrompts) {
                add(NudgeKind.SUNDAY_DEADLINES)
                add(NudgeKind.WEEKLY_FAJR)
            }
            if (schedule.nightBeforeEnabled) add(NudgeKind.NIGHT_BEFORE_REVIEW)
        }
        return kinds.mapNotNull { kind ->
            (0..LOOKAHEAD_DAYS).asSequence()
                .mapNotNull { occurrence(kind, now.toLocalDate().plusDays(it), settings, anchorsOn, schedule) }
                .firstOrNull { it.at.isAfter(now) }
        }.sortedBy { it.at }
    }

    private fun occurrence(
        kind: NudgeKind,
        date: LocalDate,
        settings: Settings,
        anchorsOn: (LocalDate) -> List<PrayerAnchor>,
        schedule: NudgeSchedule,
    ): Nudge? {
        val (time, forDate) = when (kind) {
            NudgeKind.SUNDAY_DEADLINES ->
                if (date.dayOfWeek != schedule.deadlinesDay) return null else schedule.deadlinesAt to date
            NudgeKind.WEEKLY_FAJR ->
                if (date.dayOfWeek != schedule.fajrDay) return null
                else schedule.fajrAt to date.plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
            NudgeKind.NIGHT_BEFORE_REVIEW -> minOf(settings.notifications.nightBeforeReview, settings.hardCap) to date.plusDays(1)
        }
        val cap = date.atTime(settings.hardCap)
        val planned = date.atTime(minOf(time, settings.hardCap))
        val quiet = outOfPrayer(planned, date, anchorsOn(date), settings.prayerSlotMin.toLong())
        val at = if (quiet.isAfter(cap)) planned else quiet
        return Nudge(idFor(kind, forDate), kind, at, forDate)
    }

    private fun outOfPrayer(at: LocalDateTime, date: LocalDate, anchors: List<PrayerAnchor>, slotMin: Long): LocalDateTime {
        val slots = anchors.filter { it.date == date && it.prayer != Prayer.FAJR }.map { date.atTime(it.begin) }
        var t = at
        while (true) {
            val a = slots.firstOrNull { !t.isBefore(it) && t.isBefore(it.plusMinutes(slotMin)) } ?: return t
            t = a.plusMinutes(slotMin)
        }
    }

    fun idFor(kind: NudgeKind, forDate: LocalDate): String = "nudge:${kind.name.lowercase()}:$forDate"
}

/** What the app knows when a prompt fires. */
data class NudgeContext(
    /** A rough Fajr time is already saved for the week the Fajr prompt is about. */
    val fajrKnown: Boolean = false,
    /** Tomorrow's blocks, for the night-before review. */
    val tomorrow: List<TodayBlock> = emptyList(),
    /** Template tasks for tomorrow that are not planned yet. */
    val unscheduledTomorrow: Int = 0,
    /** ChangeSets waiting for approval. */
    val pendingApprovals: Int = 0,
)

object NudgeTexts {
    /** Null means do not post (e.g. the week's Fajr is already known). */
    fun forNudge(nudge: Nudge, ctx: NudgeContext): NoticeText? = when (nudge.kind) {
        NudgeKind.SUNDAY_DEADLINES -> NoticeText(
            title = "Any deadlines coming up?",
            body = "Sunday check: tell the assistant about deadlines in the next few weeks so they get planned in time.",
            open = OpenTarget.CHAT,
        )
        NudgeKind.WEEKLY_FAJR ->
            if (ctx.fajrKnown) null
            else NoticeText(
                title = "Fajr this week?",
                body = "Roughly when is Fajr from ${nudge.forDate.dayOfWeek.label()} ${nudge.forDate.dayOfMonth}? " +
                    "It sets your sleep and wake coaching (about 8h of sleep after Fajr).",
                open = OpenTarget.SETTINGS,
            )
        NudgeKind.NIGHT_BEFORE_REVIEW -> nightBefore(ctx)
    }

    private fun nightBefore(ctx: NudgeContext): NoticeText {
        val tasks = ctx.tomorrow.filter { !it.isFixed }.sortedBy { it.start }
        val fixed = ctx.tomorrow.count { it.isFixed }
        val parts = mutableListOf<String>()
        if (tasks.isEmpty()) {
            parts += "Nothing planned for tomorrow yet."
        } else {
            val first = tasks.first()
            val last = tasks.maxOf { it.end }
            parts += "${plural(tasks.size, "block")} from ${CheckInTexts.hm(first.start.toLocalTime())} " +
                "(first: ${listOfNotNull(first.title, first.chunkLabel).joinToString(" ")}), done by ${CheckInTexts.hm(last.toLocalTime())}."
        }
        if (fixed > 0) parts += "${plural(fixed, "fixed event")}."
        if (ctx.unscheduledTomorrow > 0) parts += "${plural(ctx.unscheduledTomorrow, "task")} not planned yet."
        if (ctx.pendingApprovals > 0) parts += "${plural(ctx.pendingApprovals, "change")} waiting for approval."
        parts += "Take 5 minutes to decide roughly when each thing happens."
        return NoticeText(
            title = "5-minute review: tomorrow",
            body = parts.joinToString(" "),
            open = if (ctx.pendingApprovals > 0) OpenTarget.APPROVALS else OpenTarget.WEEK,
        )
    }

    private fun plural(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"

    private fun DayOfWeek.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
}

/** The Fajr prompt's quick reply: "5:40", "05:40", "540", "5.40" or "5:40am". Fajr is always before noon. */
object FajrInput {
    private val PATTERN = Regex("""^\s*(\d{1,2})\s*[:.h]?\s*(\d{2})\s*(am|a\.m\.)?\s*$""", RegexOption.IGNORE_CASE)

    fun parse(text: String?): LocalTime? {
        val m = PATTERN.matchEntire(text ?: return null) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 2..9 || min !in 0..59) return null // outside any plausible Fajr in London
        return LocalTime.of(h, min)
    }
}
