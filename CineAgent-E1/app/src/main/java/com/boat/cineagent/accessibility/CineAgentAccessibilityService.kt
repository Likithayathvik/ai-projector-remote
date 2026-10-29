package com.boat.cineagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.boat.cineagent.CineAgentService

/** [packageName] is the app owning the field, [isPassword] distinguishes it from a plain
 *  search/username field, [text] is its current contents (for pre-filling the phone's
 *  Keyboard Mode screen). */
data class FocusedTextField(val packageName: String, val isPassword: Boolean, val text: String)

class CineAgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CineAgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // BootReceiver's BOOT_COMPLETED path isn't reliably delivered on every OEM skin - a
        // real-device capture after a power cycle showed this accessibility service bound and
        // running while CineAgentService never started at all, leaving the projector
        // undiscoverable until MainActivity was opened by hand. Accessibility rebinding is
        // driven directly by the system's persisted enabled_accessibility_services setting, so
        // it fires reliably after boot regardless of broadcast quirks - use it as a second,
        // more dependable trigger for the same start-if-not-running call MainActivity makes.
        if (!CineAgentService.isRunning) {
            CineAgentService.start(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling needed; commands query the node tree on demand.
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // Real-time foreground-package lookup, independent of UsageStatsManager entirely - confirmed
    // live 2026-08-18 as the fix for a device whose usage-stats bucket files were corrupted by a
    // clock jump (see CommandHandler.foregroundAppInfo()): rootInActiveWindow reflects the OS's
    // actual current window right now, with no dependency on any usage-stats bucket/rollover
    // state, so it isn't affected by that corruption at all.
    fun currentForegroundPackageName(): String? = rootInActiveWindow?.packageName?.toString()

    // findFocus(FOCUS_INPUT) looked right (and matched Netflix's search box, an
    // AutoCompleteTextView) but confirmed live 2026-08-18 to be flaky on Hotstar's login field:
    // its custom on-screen numeric keypad keeps a TextView digit marked isFocused() at the same
    // time as the real EditText underneath, and findFocus(FOCUS_INPUT) flapped between returning
    // the two from one poll to the next - editable=false about half the time. A direct tree walk
    // for isEditable && isFocused, ignoring which one the system's single-node FOCUS_INPUT arbiter
    // picked, found the real EditText on every single poll instead. YouTube TV's Cobalt
    // (Chromium-based) runtime is a separate, confirmed-live dead end: its whole screen is one
    // opaque android.view.View with no accessible EditText anywhere in the tree at all - not a
    // focus-arbitration issue, there is genuinely nothing to find there.
    fun currentFocusedTextField(): FocusedTextField? {
        val node = currentTextFieldNode() ?: return null
        return FocusedTextField(
            packageName = node.packageName?.toString() ?: "",
            isPassword = node.isPassword,
            text = realFieldText(node)
        )
    }

    /** node.text on an empty field is often the hint/placeholder ("Search") rather than "" -
     *  passing that back makes the phone's Keyboard Mode pre-fill it, so the user has to delete
     *  it before typing. Treat a field that's showing hint text (flag set, or text == hint) as
     *  empty. */
    private fun realFieldText(node: AccessibilityNodeInfo): String {
        val raw = node.text?.toString().orEmpty()
        if (raw.isEmpty()) return ""
        val hint = node.hintText?.toString().orEmpty()
        return if (node.isShowingHintText || (hint.isNotEmpty() && raw == hint)) "" else raw
    }

    /** (package, view id) of the last focused editable field, for [currentTextFieldNode]. */
    @Volatile
    private var lastTextField: Pair<String, String>? = null

    /**
     * The focused editable field, or - once none is focused - the last focused one while it is
     * still on screen. After a search runs, apps move focus off the search box onto the results
     * (Hotstar: search_bar -> hero_img, confirmed live 2026-10-16) while the box itself stays put;
     * treating that as "field gone" closed the phone's Keyboard Mode on every Send. The field
     * counts as gone only when it leaves the screen (the user backed out of the search page).
     * Fields without a view id keep the focused-only behaviour.
     */
    private fun currentTextFieldNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        findFocusedEditableNode(root)?.let { node ->
            lastTextField = node.viewIdResourceName?.let { (node.packageName?.toString() ?: "") to it }
            return node
        }
        val (pkg, viewId) = lastTextField ?: return null
        if (root.packageName?.toString() != pkg) return null
        return root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull { it.isEditable && it.isVisibleToUser }
    }

    private fun findFocusedEditableNode(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val match = findFocusedEditableNode(node.getChild(i), depth + 1)
            if (match != null) return match
        }
        return null
    }

    // ACTION_SET_TEXT writes directly into the focused field's real value - this reaches the
    // underlying input even on apps whose visible on-screen keyboard is a custom D-pad-driven
    // grid (Hotstar's numeric keypad), since it targets the actual EditText node, not whatever
    // grid UI is drawn on top of it.
    fun setFocusedFieldText(text: String): Boolean {
        val node = currentTextFieldNode() ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** Fires the focused editable field's IME action - the on-screen keyboard's
     *  Search / Go / Done / Send key - so a query typed via [setFocusedFieldText] actually
     *  runs and the keyboard closes, instead of the text just sitting in the field. Uses
     *  ACTION_IME_ENTER (API 30+): performs whatever editor action the field declares, with no
     *  INJECT_EVENTS permission (which a normal app can never get). Returns false if nothing
     *  editable is focused, the platform is pre-30, or the field exposes no IME action -
     *  callers treat that as "nothing to submit", not an error. */
    fun submitFocusedField(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val node = currentTextFieldNode() ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun openPowerDialog(): Boolean = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // GLOBAL_ACTION_DPAD_* was added in API 33; older devices have no
    // system-level equivalent for a directional-pad press.
    fun dpadUp(): Boolean = dpadAction(GLOBAL_ACTION_DPAD_UP)

    fun dpadDown(): Boolean = dpadAction(GLOBAL_ACTION_DPAD_DOWN)

    fun dpadLeft(): Boolean = dpadAction(GLOBAL_ACTION_DPAD_LEFT)

    fun dpadRight(): Boolean = dpadAction(GLOBAL_ACTION_DPAD_RIGHT)

    fun dpadCenter(): Boolean = dpadAction(GLOBAL_ACTION_DPAD_CENTER)

    private fun dpadAction(action: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return performGlobalAction(action)
    }

    // Finds the first on-screen node whose text or content description
    // contains [text], then clicks it (walking up to the nearest clickable
    // ancestor, since the matched node is often a non-clickable label
    // inside a clickable list item / grid tile).
    // Manual recursive walk, not AccessibilityNodeInfo.findAccessibilityNodeInfosByText() -
    // confirmed live 2026-08-25 that the built-in method is unreliable on at least one real
    // Compose-based TV UI (Netflix's bottom nav): a diagnostic probe on the exact same screen
    // found "Home" (the other, currently-selected nav tab) via the built-in method but returned
    // zero matches for "Search" (the adjacent, unselected tab) every time, even though a
    // uiautomator dump of that same screen at that same moment clearly shows a real node with
    // text "Search" and a genuinely clickable ancestor one level up. Root cause not fully
    // pinned down (suspected: Compose's semantics merging behaves differently for a merged vs.
    // unselected tab item in a way the built-in search doesn't traverse into), but this same
    // depth-capped manual walk already proven reliable elsewhere in this file
    // (findFocusedEditableNode) sidesteps it entirely by not depending on that API at all.
    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (node.text?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.contains(text, ignoreCase = true) == true
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val match = findNodeByText(node.getChild(i), text, depth + 1)
            if (match != null) return match
        }
        return null
    }

    fun clickNodeByText(text: String): Boolean {
        val target = findNodeByText(rootInActiveWindow, text) ?: return false
        var node: AccessibilityNodeInfo? = target
        while (node != null) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            node = node.parent
        }
        return false
    }

    /** Clicks the first on-screen node with the fully-qualified [viewId]
     *  (e.g. "in.startv.hotstar:id/search_bar"), walking up to the nearest clickable node -
     *  same as [clickNodeByText] but matched by resource-id instead of by label. Returns false
     *  if no such node is on screen yet (e.g. the target screen hasn't rendered). Used where the
     *  target has a stable id but no reliable text/contentDescription to match against.
     *
     *  ACTION_CLICK alone does not grant input focus: a real touch sets it via the
     *  ACTION_DOWN-time requestFocusFromTouch() call inside View.onTouchEvent(), which
     *  performAction(ACTION_CLICK) bypasses entirely by calling performClick() directly. On
     *  Hotstar's search_bar this meant the click "worked" (Hotstar reacted visually) but
     *  isFocused stayed false, so currentFocusedTextField() never reported it and Keyboard
     *  Mode never appeared - matching the field logs showing repeated OPEN_SEARCH retries with
     *  zero SEND_TEXT ever following, and the field reports that pressing BACK or relaunching
     *  Hotstar (both of which grant focus through a real input/framework path instead) "fixed"
     *  it. Explicitly requesting focus on [target] - the matched node itself, not whichever
     *  clickable ancestor absorbed the click - closes that gap. */
    fun clickNodeById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (target in root.findAccessibilityNodeInfosByViewId(viewId).orEmpty()) {
            var node: AccessibilityNodeInfo? = target
            while (node != null) {
                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked && target.isFocusable) {
                        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    }
                    return clicked
                }
                node = node.parent
            }
        }
        return false
    }

    /** Re-requests input focus (ACTION_FOCUS only - no click) on the first on-screen node with
     *  the fully-qualified [viewId]. For re-asserting focus after an initial [clickNodeById]
     *  already succeeded once, without repeating ACTION_CLICK on every attempt (which could
     *  trigger whatever the app's click handler does beyond focusing - e.g. re-opening an
     *  already-open overlay). See CommandHandler's Hotstar search flow for why a single
     *  ACTION_FOCUS isn't enough on its own. */
    fun focusNodeById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findAccessibilityNodeInfosByViewId(viewId).orEmpty().firstOrNull() ?: return false
        if (!target.isFocusable) return false
        return target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }
}
