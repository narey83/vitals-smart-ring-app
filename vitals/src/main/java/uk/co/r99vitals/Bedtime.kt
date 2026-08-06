package uk.co.r99vitals

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import java.util.Calendar

/**
 * The hours the wearer means to keep, and the two nudges that follow from them.
 *
 * Both times are minutes past midnight, which survives a timezone change in a way a stored
 * instant does not: eleven o'clock is eleven o'clock wherever the phone wakes up.
 */
data class SleepPlan(
    val bedtime: Int = 23 * 60,
    val wake: Int = 7 * 60,
    val remind: Boolean = false,
    val report: Boolean = false,
    /** How long before bedtime the reminder lands. Long enough to act on, short enough to mean it. */
    val lead: Int = 30
) {
    /**
     * What the wearer is actually aiming for, in seconds — bedtime to wake time, crossing
     * midnight. This is what the score measures a night against, so the target is the wearer's
     * own rather than a number this app decided on their behalf.
     */
    val target: Int get() = ((wake - bedtime + 24 * 60) % (24 * 60)) * 60

    fun write(prefs: SharedPreferences) = prefs.edit()
        .putInt("bedtime", bedtime).putInt("wake", wake)
        .putBoolean("remindBedtime", remind).putBoolean("morningReport", report)
        .apply()

    companion object {
        fun read(prefs: SharedPreferences) = SleepPlan(
            bedtime = prefs.getInt("bedtime", 23 * 60),
            wake = prefs.getInt("wake", 7 * 60),
            remind = prefs.getBoolean("remindBedtime", false),
            report = prefs.getBoolean("morningReport", false)
        )

        fun read(context: Context) = read(context.getSharedPreferences("ring", Context.MODE_PRIVATE))
    }
}

/** Minutes past midnight as a clock face. */
fun clockOf(minutes: Int) = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

object Bedtime {

    const val CHANNEL = "sleep"

    /**
     * When the reminder should next land: the first time today or tomorrow that the lead-in
     * before bedtime falls after [now].
     *
     * Worked out on a calendar rather than by adding milliseconds, so the hour survives the
     * clocks going forward — a reminder set for half ten stays at half ten through the change.
     */
    fun nextReminder(plan: SleepPlan, now: Long): Long {
        val at = (plan.bedtime - plan.lead + 24 * 60) % (24 * 60)
        val when0 = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, at / 60)
            set(Calendar.MINUTE, at % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (when0.timeInMillis <= now) when0.add(Calendar.DAY_OF_YEAR, 1)
        return when0.timeInMillis
    }

    /**
     * Books the next reminder, or clears it when the wearer has turned it off.
     *
     * Inexact on purpose: an alarm the system may shift by a few minutes needs no special
     * permission, and a bedtime nudge does not care about seconds. Wanting to be woken to the
     * second is an alarm clock, which this deliberately is not.
     */
    fun apply(context: Context, plan: SleepPlan = SleepPlan.read(context)) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, BedtimeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        alarms.cancel(pending)
        if (!plan.remind) return
        alarms.setWindow(
            AlarmManager.RTC_WAKEUP,
            nextReminder(plan, System.currentTimeMillis()),
            5 * 60 * 1000L,
            pending
        )
    }

    fun channel(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Sleep", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Bedtime reminders and your morning sleep report" }
            )
        }
        return CHANNEL
    }

    /** Opens the app on the Sleep tab, since that is what both of these notifications are about. */
    fun openSleep(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, VitalsActivity::class.java).putExtra("tab", "sleep"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun notify(context: Context, id: Int, title: String, text: String) {
        val notification = Notification.Builder(context, channel(context))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openSleep(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    const val REMINDER = 2
    const val REPORT = 3
}

/** Fires the nudge, then books tomorrow's — a repeating alarm the system may drop is not enough. */
class BedtimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val plan = SleepPlan.read(context)
        if (plan.remind) {
            Bedtime.notify(
                context,
                Bedtime.REMINDER,
                "Bedtime at ${clockOf(plan.bedtime)}",
                "In ${plan.lead} minutes. Sleeping now until ${clockOf(plan.wake)} gives you " +
                    "${Sleep.spell(plan.target)}."
            )
        }
        Bedtime.apply(context, plan)
    }
}

/** Alarms do not survive a restart, so they are booked again once the phone is up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Bedtime.apply(context)
    }
}

/**
 * The morning report: what last night came to, shown when the phone is next unlocked.
 *
 * Deliberately tied to unlocking rather than to a time. A report that arrives at seven while the
 * wearer is still asleep is a notification they will never see happen, and one keyed to the ring
 * finishing its night would fire in the small hours.
 */
object SleepReport {

    /**
     * Whether [night] is worth telling the wearer about now.
     *
     * One report per night, and only while it is still this morning's news — the same night read
     * back off the ring tomorrow is not a fresh report, and neither is a scrap.
     */
    fun due(plan: SleepPlan, night: Sleep.Night?, now: Long, alreadyReported: Long): Boolean {
        if (!plan.report || night == null) return false
        if (night.startedAt == alreadyReported) return false
        if (SleepInsight.isFragment(night)) return false
        val since = now - night.endedAt
        return since in 0..STALE
    }

    /** After this long a night is history rather than this morning. */
    private const val STALE = 18 * 60 * 60 * 1000L

    fun post(context: Context, night: Sleep.Night, plan: SleepPlan) {
        val (score, _) = SleepInsight.score(night, plan.target)
        Bedtime.notify(
            context,
            Bedtime.REPORT,
            "Last night: ${Sleep.spell(night.asleep)} asleep",
            "${SleepInsight.verdict(score)} · $score out of 100 · " +
                "deep ${Sleep.spell(night.seconds(Sleep.DEEP))}, " +
                "REM ${Sleep.spell(night.seconds(Sleep.REM))}, " +
                "${clockOf(atClock(night.startedAt))} to ${clockOf(atClock(night.endedAt))}"
        )
        context.getSharedPreferences("ring", Context.MODE_PRIVATE)
            .edit().putLong("reportedNight", night.startedAt).apply()
    }

    private fun atClock(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }
        .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
}
