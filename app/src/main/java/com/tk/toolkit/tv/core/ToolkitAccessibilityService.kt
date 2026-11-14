package com.tk.toolkit.tv.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.tk.toolkit.tv.MainActivity
import com.tk.toolkit.tv.launcher.data.AppActions

/**
 * System-level helpers that need an accessibility service:
 * - Sends the Home button (and the stock Google TV home screen) to Toolkit, so it works as
 *   the launcher without disabling the stock one.
 * - Drives the system App info screen to press "Force stop" → "OK" after [forceStop].
 */
class ToolkitAccessibilityService : AccessibilityService() {

    private enum class Step { PressForceStop, Confirm }

    private val handler = Handler(Looper.getMainLooper())
    private var target: String? = null
    private var step = Step.PressForceStop
    private val timeout = Runnable { finish(null) }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    fun forceStop(packageName: String) {
        target = packageName
        step = Step.PressForceStop
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
        startActivity(
            AppActions.appInfoIntent(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        )
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                homeLongPressed = false
                handler.removeCallbacks(homeLongPress)
                handler.postDelayed(homeLongPress, LONG_PRESS_MS)
            } else if (event.isLongPress && !homeLongPressed) {
                handler.removeCallbacks(homeLongPress)
                homeLongPress.run()
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(homeLongPress)
                if (!homeLongPressed && !event.isCanceled) openToolkit()
                homeLongPressed = false
            }
        }
        return true
    }

    private var homeLongPressed = false
    private val homeLongPress = Runnable {
        homeLongPressed = true
        openSystemPanel()
    }

    /**
     * Opens Google TV's own long-press-Home panel (settings, audio output, Bluetooth…).
     * Its activity needs STATUS_BAR_SERVICE, so go through System UI's quick-settings action,
     * which Google TV routes to that same panel.
     */
    private fun openSystemPanel() {
        val ok = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        android.util.Log.i("ToolkitHome", "long press: notifications ok=$ok")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Catches the stock home showing up some other way (boot, an app exiting to home).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName == STOCK_LAUNCHER &&
            event.className?.toString() == STOCK_HOME_ACTIVITY
        ) {
            openToolkit()
            return
        }
        if (target == null) return
        val root = rootInActiveWindow ?: return
        when (step) {
            Step.PressForceStop -> {
                val node = root.findByText(FORCE_STOP_LABELS) ?: return
                val clickable = node.clickableAncestor() ?: return
                if (!clickable.isEnabled || !node.isEnabled) {
                    finish("Already stopped")
                } else if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    step = Step.Confirm
                }
            }
            Step.Confirm -> {
                val node = root.findByText(CONFIRM_LABELS) ?: return
                val clickable = node.clickableAncestor() ?: return
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    finish("Force stopped")
                }
            }
        }
    }

    private fun openToolkit() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }

    private fun finish(message: String?) {
        handler.removeCallbacks(timeout)
        val wasRunning = target != null
        target = null
        if (!wasRunning) return
        handler.postDelayed({ openToolkit() }, 250)
        Toast.makeText(this, message ?: "Couldn't force stop — try from App info", Toast.LENGTH_SHORT).show()
    }

    private fun AccessibilityNodeInfo.findByText(labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.text?.toString().equals(label, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo.clickableAncestor(): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = this
        while (node != null && !node.isClickable) node = node.parent
        return node
    }

    companion object {
        @Volatile
        var instance: ToolkitAccessibilityService? = null
            private set

        private const val TIMEOUT_MS = 6_000L
        private const val LONG_PRESS_MS = 600L
        private const val STOCK_LAUNCHER = "com.google.android.apps.tv.launcherx"
        private const val STOCK_HOME_ACTIVITY = "com.google.android.apps.tv.launcherx.home.HomeActivity"
        private val FORCE_STOP_LABELS = listOf("Force stop")
        private val CONFIRM_LABELS = listOf("OK")
    }
}
