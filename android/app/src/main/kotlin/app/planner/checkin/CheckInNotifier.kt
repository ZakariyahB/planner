package app.planner.checkin

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.planner.MainActivity
import app.planner.R
import app.planner.engine.checkin.AlarmPayload
import app.planner.engine.checkin.CheckIn
import app.planner.engine.checkin.CheckInBlock
import app.planner.engine.checkin.CheckInKind
import app.planner.engine.checkin.CheckInTexts
import app.planner.engine.checkin.NoticeText
import app.planner.engine.checkin.OpenTarget
import app.planner.engine.model.NotificationPrefs
import app.planner.engine.nudge.Nudge
import app.planner.engine.nudge.NudgeKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts check-in, prompt and "Review change" notifications. Notifications about one block share its tag, so a
 * later one (end, focus question) replaces the earlier one. Without the notification permission nothing is posted.
 */
@Singleton
class CheckInNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun canNotify(): Boolean = manager.areNotificationsEnabled()

    fun postCheckIn(checkIn: CheckIn, prefs: NotificationPrefs, slotMin: Int) {
        val text = CheckInTexts.forCheckIn(checkIn, slotMin)
        val payload = AlarmPayload.encode(AlarmPayload.CheckInAlarm(checkIn))
        val builder = base(CheckInChannels.CHECK_INS, text, openIntent(OpenTarget.TODAY))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        checkIn.actions.forEach { action ->
            builder.addAction(0, CheckInTexts.actionLabel(action, prefs), CheckInActionReceiver.pending(context, action, checkIn.id, payload))
        }
        if (checkIn.kind == CheckInKind.PRAYER_BREAK) {
            builder.setTimeoutAfter(slotMin * 60_000L) // gone once the prayer slot is over
            notify(prayerTag(checkIn.block), ID_PRAYER, builder)
        } else {
            notify(tag(checkIn.block), ID_CHECK_IN, builder)
        }
    }

    /** The optional 1-5 focus question after Done, replacing the end ping. */
    fun postFocus(block: CheckInBlock) {
        val text = CheckInTexts.focusQuestion(block)
        val input = RemoteInput.Builder(CheckInActionReceiver.KEY_FOCUS)
            .setLabel(context.getString(R.string.checkin_focus_label))
            .setChoices(arrayOf<CharSequence>("1", "2", "3", "4", "5"))
            .setAllowFreeFormInput(false)
            .build()
        val action = NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.checkin_focus_action),
            CheckInActionReceiver.focusPending(context, block),
        ).addRemoteInput(input).build()
        val builder = base(CheckInChannels.CHECK_INS, text, openIntent(OpenTarget.TODAY))
            .addAction(action)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(FOCUS_TIMEOUT_MS)
        notify(tag(block), ID_CHECK_IN, builder)
    }

    /** After "Need 30 more" or "Stop here": a proposal is waiting; tapping opens the approval screen. */
    fun postReplanNotice(notice: NoticeText, changeSetId: String?) {
        val open = openIntent(notice.open) { if (changeSetId != null) putExtra(EXTRA_CHANGE_SET, changeSetId) }
        notify(TAG_REVIEW, ID_REVIEW, base(CheckInChannels.CHECK_INS, notice, open))
    }

    fun postPrompt(nudge: Nudge, text: NoticeText) {
        val builder = base(CheckInChannels.PROMPTS, text, openIntent(text.open))
        if (nudge.kind == NudgeKind.WEEKLY_FAJR) {
            val input = RemoteInput.Builder(CheckInActionReceiver.KEY_FAJR)
                .setLabel(context.getString(R.string.checkin_fajr_hint))
                .build()
            builder.addAction(
                NotificationCompat.Action.Builder(0, context.getString(R.string.checkin_fajr_action), CheckInActionReceiver.fajrPending(context, nudge))
                    .addRemoteInput(input)
                    .build(),
            )
        }
        notify(TAG_PROMPT + nudge.kind.name, ID_PROMPT, builder)
    }

    /** A short reply on the prompt itself (e.g. "Saved: Fajr 05:40"), replacing it. */
    fun confirmPrompt(kind: NudgeKind, title: String, body: String) {
        val builder = base(CheckInChannels.PROMPTS, NoticeText(title, body), openIntent(OpenTarget.SETTINGS))
            .setTimeoutAfter(CONFIRM_TIMEOUT_MS)
        notify(TAG_PROMPT + kind.name, ID_PROMPT, builder)
    }

    fun cancel(block: CheckInBlock) = manager.cancel(tag(block), ID_CHECK_IN)

    private fun base(channel: String, text: NoticeText, open: PendingIntent): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_checkin_bell)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
            .setContentIntent(open)
            .setAutoCancel(true)

    @SuppressLint("MissingPermission") // areNotificationsEnabled() covers the Android 13 permission
    private fun notify(tag: String, id: Int, builder: NotificationCompat.Builder) {
        if (!canNotify()) return
        CheckInChannels.create(context)
        try {
            manager.notify(tag, id, builder.build())
        } catch (e: SecurityException) {
            // Permission withdrawn: nothing to post.
        }
    }

    /**
     * Opens MainActivity with the `open` extra (see docs/redesign/notes/E.md). Each target has its own request code,
     * so the extras of one never overwrite another's.
     */
    private fun openIntent(target: OpenTarget, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN_BASE + target.ordinal,
            launchIntent(context, target).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        /** Names the ChangeSet a "Review change" notification is about, next to `open=approvals`. */
        const val EXTRA_CHANGE_SET = "changeSetId"

        private const val ID_CHECK_IN = 7_001
        private const val ID_REVIEW = 7_002
        private const val ID_PROMPT = 7_003
        private const val ID_PRAYER = 7_004
        private const val TAG_REVIEW = "checkin-review"
        private const val TAG_PROMPT = "checkin-prompt-"
        private const val REQUEST_OPEN_BASE = 7_100
        private const val FOCUS_TIMEOUT_MS = 30 * 60_000L
        private const val CONFIRM_TIMEOUT_MS = 60_000L

        fun tag(block: CheckInBlock): String = "checkin-" + block.key
        private fun prayerTag(block: CheckInBlock): String = "checkin-prayer-" + block.key

        /** MainActivity, brought to the front (or started) with `open=<target>`. */
        fun launchIntent(context: Context, target: OpenTarget): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(OpenTarget.EXTRA, target.extra)

        /** RemoteInput needs a mutable PendingIntent so the system can add the reply. */
        internal val MUTABLE: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    }
}
