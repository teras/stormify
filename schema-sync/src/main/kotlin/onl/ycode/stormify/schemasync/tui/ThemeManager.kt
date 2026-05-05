package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.bundle.LanternaThemes
import com.googlecode.lanterna.gui2.WindowBasedTextGUI
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType

/**
 * Global theme cycling state. Every window registers the same handler via
 * [handleKey] so F8 (forward) / Shift-F8 (back) walks the same list and
 * applies to the shared [WindowBasedTextGUI].
 */
internal object ThemeManager {
    private val names = listOf(
        "default", "businessmachine", "blaster", "bigsnake", "conqueror", "defrost",
    )
    private var idx = 0

    fun current(): String = names[idx]

    fun apply(gui: WindowBasedTextGUI) {
        LanternaThemes.getRegisteredTheme(names[idx])?.let { gui.theme = it }
    }

    /** Advance one step (forward or backward) and apply to [gui]. Used by the
     *  theme button on the top status row; F8 keyboard binding goes through
     *  [handleKey] for the same effect. */
    fun cycle(gui: WindowBasedTextGUI, forward: Boolean = true) {
        idx = if (forward) (idx + 1) % names.size else (idx - 1 + names.size) % names.size
        apply(gui)
    }

    /** Returns true when the keystroke matched (caller should set hasBeenHandled). */
    fun handleKey(keyStroke: KeyStroke, gui: WindowBasedTextGUI): Boolean {
        if (keyStroke.keyType != KeyType.F8) return false
        idx = if (keyStroke.isShiftDown) (idx - 1 + names.size) % names.size
              else (idx + 1) % names.size
        apply(gui)
        return true
    }
}
