package com.syntra.os.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/** Lets the agent see and operate other apps, using the platform accessibility API. */
class SyntraAccessibility : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: SyntraAccessibility? = null
    }
}

object Phone {
    private class Item(val node: AccessibilityNodeInfo, val label: String, val clickable: Boolean, val editable: Boolean, val bounds: Rect)

    private fun collect(): List<Item> {
        val root = (SyntraAccessibility.instance ?: error("Enable Syntra in Settings → Accessibility first (Setup → Phone control)."))
            .rootInActiveWindow ?: error("No active window")
        val out = mutableListOf<Item>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || out.size > 250) return
            val label = listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString())
                .firstOrNull { it.isNotBlank() }.orEmpty()
            val b = Rect().also { n.getBoundsInScreen(it) }
            if ((label.isNotBlank() || n.isClickable || n.isEditable) && n.isVisibleToUser && !b.isEmpty)
                out += Item(n, label.take(80), n.isClickable, n.isEditable, b)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    /** Numbered list of what is on screen; the agent refers to items by number or text. */
    fun screen(): String {
        val items = collect()
        val pkg = SyntraAccessibility.instance?.rootInActiveWindow?.packageName
        return "App: $pkg\n" + items.mapIndexed { i, it ->
            "[$i] ${if (it.editable) "input" else if (it.clickable) "button" else "text"} \"${it.label}\" @${it.bounds.centerX()},${it.bounds.centerY()}"
        }.joinToString("\n")
    }

    private fun find(target: String): Item {
        val items = collect()
        target.trim().removePrefix("[").removeSuffix("]").toIntOrNull()?.let { return items.getOrNull(it) ?: error("no item $it") }
        return items.firstOrNull { it.label.equals(target, true) } ?: items.firstOrNull { it.label.contains(target, true) }
            ?: error("\"$target\" not on screen")
    }

    suspend fun tap(target: String): String {
        val it = find(target)
        var n: AccessibilityNodeInfo? = it.node
        while (n != null && !n.isClickable) n = n.parent
        if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) gesture(it.bounds.centerX().toFloat(), it.bounds.centerY().toFloat())
        delay(600)
        return "Tapped \"${it.label}\"."
    }

    fun type(text: String, target: String?): String {
        val node = if (target.isNullOrBlank()) collect().firstOrNull { it.node.isFocused && it.editable }?.node ?: collect().firstOrNull { it.editable }?.node
        else find(target).node
        node ?: error("no text field on screen")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
        return if (ok) "Typed." else "Field rejected the text."
    }

    suspend fun swipe(direction: String): String {
        val s = SyntraAccessibility.instance ?: error("accessibility not enabled")
        val dm = s.resources.displayMetrics
        val (w, h) = dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
        val (x1, y1, x2, y2) = when (direction.lowercase()) {
            "up" -> listOf(w / 2, h * 0.75f, w / 2, h * 0.25f)
            "down" -> listOf(w / 2, h * 0.25f, w / 2, h * 0.75f)
            "left" -> listOf(w * 0.85f, h / 2, w * 0.15f, h / 2)
            else -> listOf(w * 0.15f, h / 2, w * 0.85f, h / 2)
        }
        gesture(x1, y1, x2, y2, 350)
        delay(500)
        return "Swiped $direction."
    }

    fun action(name: String): String {
        val s = SyntraAccessibility.instance ?: error("accessibility not enabled")
        val a = when (name.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> error("unknown action $name")
        }
        return if (s.performGlobalAction(a)) "Done: $name" else "Failed: $name"
    }

    private fun gesture(x: Float, y: Float, x2: Float = x, y2: Float = y, ms: Long = 60) {
        val s = SyntraAccessibility.instance ?: error("accessibility not enabled")
        val p = Path().apply { moveTo(x, y); lineTo(x2, y2) }
        s.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            ?: error("no app named $name")
        ctx.startActivity(pm.getLaunchIntentForPackage(hit.activityInfo.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened ${hit.loadLabel(pm)}."
    }

    fun listApps(ctx: Context): String {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() }.sorted().joinToString(", ")
    }
}
