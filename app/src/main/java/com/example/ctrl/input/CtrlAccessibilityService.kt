package com.example.ctrl.input

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CtrlAccessibilityService : AccessibilityService() {
    companion object {
        private var instance: CtrlAccessibilityService? = null
        
        fun getInstance(): CtrlAccessibilityService? = instance
        
        fun getRootNode(): AccessibilityNodeInfo? {
            return instance?.rootInActiveWindow
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        InputController.bind(this)
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We can use this to track window changes if needed
    }

    override fun onDestroy() {
        instance = null
        InputController.unbind(this)
        super.onDestroy()
    }
}

// Data class for UI element info
data class UiElementInfo(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val packageName: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val children: List<UiElementInfo>
)

fun AccessibilityNodeInfo.toUiElementInfo(): UiElementInfo {
    val bounds = Rect()
    getBoundsInScreen(bounds)
    
    return UiElementInfo(
        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        viewId = viewIdResourceName,
        className = className?.toString(),
        packageName = packageName?.toString(),
        bounds = bounds,
        clickable = isClickable,
        editable = isEditable,
        focused = isFocused,
        enabled = isEnabled,
        children = (0 until childCount).mapNotNull { i ->
            getChild(i)?.toUiElementInfo()
        }
    )
}

fun dumpUiTree(node: AccessibilityNodeInfo?): List<UiElementInfo> {
    if (node == null) return emptyList()
    return listOf(node.toUiElementInfo())
}

fun findElementByText(node: AccessibilityNodeInfo?, text: String): UiElementInfo? {
    if (node == null) return null
    
    val info = node.toUiElementInfo()
    if (info.text?.contains(text, ignoreCase = true) == true ||
        info.contentDescription?.contains(text, ignoreCase = true) == true) {
        return info
    }
    
    for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        val result = findElementByText(child, text)
        if (result != null) return result
    }
    
    return null
}

data class UiSelector(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val enabled: Boolean? = null,
)

fun findNodesBySelector(node: AccessibilityNodeInfo?, selector: UiSelector, maxResults: Int): List<AccessibilityNodeInfo> {
    if (node == null) return emptyList()
    val results = ArrayList<AccessibilityNodeInfo>()

    fun matches(n: AccessibilityNodeInfo): Boolean {
        selector.text?.let {
            val t = n.text?.toString() ?: return false
            if (!t.contains(it, ignoreCase = true)) return false
        }
        selector.contentDescription?.let {
            val d = n.contentDescription?.toString() ?: return false
            if (!d.contains(it, ignoreCase = true)) return false
        }
        selector.viewId?.let {
            val v = n.viewIdResourceName ?: return false
            if (v != it) return false
        }
        selector.className?.let {
            val c = n.className?.toString() ?: return false
            if (c != it) return false
        }
        selector.packageName?.let {
            val p = n.packageName?.toString() ?: return false
            if (p != it) return false
        }
        selector.clickable?.let {
            if (n.isClickable != it) return false
        }
        selector.editable?.let {
            if (n.isEditable != it) return false
        }
        selector.enabled?.let {
            if (n.isEnabled != it) return false
        }
        return true
    }

    fun visit(n: AccessibilityNodeInfo) {
        if (results.size >= maxResults) return
        if (matches(n)) {
            results.add(n)
        }
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            visit(child)
            if (results.size >= maxResults) return
        }
    }

    visit(node)
    return results
}
