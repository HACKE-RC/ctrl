package com.example.ctrl.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference

object InputController {
    private val serviceRef: AtomicReference<AccessibilityService?> = AtomicReference(null)

    fun bind(service: AccessibilityService) {
        serviceRef.set(service)
    }

    fun unbind(service: AccessibilityService) {
        serviceRef.compareAndSet(service, null)
    }

    fun tap(x: Float, y: Float): Boolean {
        val service = serviceRef.get() ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50) // 50ms duration for better visibility
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun longPress(x: Float, y: Float, durationMs: Long): Boolean {
        val service = serviceRef.get() ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val service = serviceRef.get() ?: return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun globalBack(): Boolean {
        val service = serviceRef.get() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun globalHome(): Boolean {
        val service = serviceRef.get() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun globalRecents(): Boolean {
        val service = serviceRef.get() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    fun setText(text: String): Boolean {
        val service = serviceRef.get() ?: return false
        val root = service.rootInActiveWindow ?: return false

        val target = findFocusedEditable(root) ?: findFirstEditable(root)
        target ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) return found
        }
        return null
    }

    fun isBound(): Boolean = serviceRef.get() != null
}
