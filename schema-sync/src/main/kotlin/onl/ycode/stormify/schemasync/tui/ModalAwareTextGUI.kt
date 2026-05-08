package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.gui2.DefaultWindowManager
import com.googlecode.lanterna.gui2.Component
import com.googlecode.lanterna.gui2.MultiWindowTextGUI
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.gui2.WindowManager
import com.googlecode.lanterna.screen.Screen

/**
 * [MultiWindowTextGUI] that respects [Window.Hint.MODAL] for *mouse* input
 * routing.
 *
 * Lanterna 3.1.2's `MultiWindowTextGUI` blocks keyboard delivery to non-modal
 * windows when a modal is on top, but a click on coordinates that fall on a
 * background window still calls [setActiveWindow] with that window —
 * surfacing it and shoving the modal into limbo. The next Esc then re-opens
 * the modal instead of exiting, which is the bug the user reported.
 *
 * Override: if the topmost window carries [Window.Hint.MODAL], reject any
 * activation that targets a different window. Closing the modal (which
 * removes it from the stack) goes through a different code path and is not
 * affected.
 */
internal class ModalAwareTextGUI(
    screen: Screen,
    windowManager: WindowManager = DefaultWindowManager(),
    background: Component,
) : MultiWindowTextGUI(screen, windowManager, background) {

    override fun setActiveWindow(activeWindow: Window?): MultiWindowTextGUI {
        if (activeWindow != null) {
            val stack = windows
            val topmost = stack.lastOrNull()
            if (topmost != null
                && topmost !== activeWindow
                && topmost.hints.contains(Window.Hint.MODAL)
            ) {
                return this
            }
        }
        return super.setActiveWindow(activeWindow)
    }
}
