package com.syntra.os.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Runs shell commands in Termux via its RUN_COMMAND API and reads the result back.
 * Needs Termux installed with `allow-external-apps = true` in ~/.termux/termux.properties.
 */
object Termux {
    private const val ACTION_RESULT = "com.syntra.os.TERMUX_RESULT"
    private var seq = 0

    suspend fun run(ctx: Context, command: String, timeoutMs: Long = 120_000): String {
        val id = ++seq
        val result = CompletableDeferred<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra("syntra_id", -1) != id) return
                val b = i.getBundleExtra("result")
                result.complete(if (b == null) "No result from Termux" else buildString {
                    append("exit ").append(b.getInt("exitCode")).append('\n')
                    b.getString("stdout")?.let { append(it) }
                    b.getString("stderr")?.takeIf { it.isNotBlank() }?.let { append("\nstderr: ").append(it) }
                    b.getString("errmsg")?.takeIf { it.isNotBlank() }?.let { append("\nerror: ").append(it) }
                })
            }
        }
        val filter = IntentFilter(ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(receiver, filter)
        try {
            val back = Intent(ACTION_RESULT).setPackage(ctx.packageName).putExtra("syntra_id", id)
            val pi = PendingIntent.getBroadcast(ctx, id, back, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val intent = Intent("com.termux.RUN_COMMAND")
                .setComponent(ComponentName("com.termux", "com.termux.app.RunCommandService"))
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            try { ctx.startService(intent) } catch (e: SecurityException) {
                error("Grant Syntra the Termux 'Run commands' permission (Setup → Phone control), and install Termux.")
            }
            return withTimeout(timeoutMs) { result.await() }.take(6000)
        } finally { ctx.unregisterReceiver(receiver) }
    }
}
