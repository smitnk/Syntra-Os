package com.syntra.os.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.syntra.os.R
import com.syntra.os.core.Settings
import com.syntra.os.core.Skill
import com.syntra.os.core.Step
import com.syntra.os.core.StepKind
import com.syntra.os.core.Store
import com.syntra.os.core.TaskRun
import com.syntra.os.core.TaskStatus
import com.syntra.os.local.LocalEngines
import com.syntra.os.local.ModelManager
import com.syntra.os.memory.Graph
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.TimeUnit

private const val TAG = "syntra-job"

object Scheduler {
    fun schedule(ctx: Context, goal: String, inMinutes: Long, repeatHours: Long?): String {
        val id = "job${System.currentTimeMillis()}"
        val wm = WorkManager.getInstance(ctx)
        val data = workDataOf("goal" to goal)
        if (repeatHours != null && repeatHours > 0) {
            // WorkManager's minimum repeat interval is 15 minutes.
            val req = PeriodicWorkRequestBuilder<AgentWorker>(repeatHours.coerceAtLeast(1), TimeUnit.HOURS)
                .setInitialDelay(inMinutes.coerceAtLeast(0), TimeUnit.MINUTES).setInputData(data)
                .addTag(TAG).addTag("id:$id").addTag("goal:${goal.take(150)}").addTag("every:${repeatHours}h").build()
            wm.enqueueUniquePeriodicWork(id, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            val req = OneTimeWorkRequestBuilder<AgentWorker>().setInitialDelay(inMinutes.coerceAtLeast(0), TimeUnit.MINUTES)
                .setInputData(data).addTag(TAG).addTag("id:$id").addTag("goal:${goal.take(150)}").build()
            wm.enqueueUniqueWork(id, androidx.work.ExistingWorkPolicy.KEEP, req)
        }
        return "Scheduled $id: \"$goal\" in $inMinutes min" + (repeatHours?.let { ", then every ${it}h" } ?: "")
    }

    fun list(ctx: Context): List<Pair<String, String>> =
        WorkManager.getInstance(ctx).getWorkInfosByTag(TAG).get()
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            .map { w ->
                fun tag(p: String) = w.tags.firstOrNull { it.startsWith(p) }?.removePrefix(p).orEmpty()
                tag("id:") to (tag("goal:") + tag("every:").let { if (it.isBlank()) "" else " · every $it" })
            }

    fun cancel(ctx: Context, id: String): String {
        WorkManager.getInstance(ctx).cancelUniqueWork(id)
        return "Cancelled $id."
    }
}

/** Runs one scheduled goal headlessly. Nobody is there to approve, so sensitive tools are denied. */
class AgentWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val goal = inputData.getString("goal") ?: return Result.failure()
        val app = applicationContext
        val store = Store(app.filesDir)
        var graph = store.load("graph", Graph.serializer(), Graph())
        val steps = mutableListOf<Step>()
        val env = AgentEnv(app, store.load("settings", Settings.serializer(), Settings()),
            store.load("skills", ListSerializer(Skill.serializer()), Skills.builtIn), LocalEngines(ModelManager(app.filesDir)),
            { graph }, { graph = it; store.save("graph", Graph.serializer(), it) }, { steps += it }, approve = { false })
        val (status, text) = try {
            TaskStatus.DONE to Agent(env).run(goal, emptyList())
        } catch (e: Exception) {
            TaskStatus.FAILED to (e.message ?: e.toString())
        }
        val tasks = store.load("tasks", ListSerializer(TaskRun.serializer()), emptyList())
        val run = TaskRun(id = System.currentTimeMillis().toString(), goal = "⏰ $goal", status = status,
            steps = steps + Step(if (status == TaskStatus.DONE) StepKind.ANSWER else StepKind.ERROR, "syntra", text))
        store.save("tasks", ListSerializer(TaskRun.serializer()), (listOf(run) + tasks).take(30))
        notify(app, goal, text)
        return Result.success()
    }
}

fun notify(ctx: Context, title: String, text: String) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("syntra", "Syntra tasks", NotificationManager.IMPORTANCE_DEFAULT))
    if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        android.os.Build.VERSION.SDK_INT >= 33) return
    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
    val pi = android.app.PendingIntent.getActivity(ctx, 0, launch, android.app.PendingIntent.FLAG_IMMUTABLE)
    NotificationManagerCompat.from(ctx).notify(System.currentTimeMillis().toInt(),
        NotificationCompat.Builder(ctx, "syntra").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title.take(60)).setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500))).setContentIntent(pi).setAutoCancel(true).build())
}
