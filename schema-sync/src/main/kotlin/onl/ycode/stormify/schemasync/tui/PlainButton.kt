package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.Button
import com.googlecode.lanterna.gui2.TextGUIGraphics

/**
 * [Button.ButtonRenderer] that paints a button label uniformly, without the
 * per-letter mnemonic colour cue Lanterna's default renderer applies to the
 * first character.
 */
/** Convenience: a [Button] wired to [PlainButtonRenderer] from the start. */
internal fun plainButton(label: String, onActivate: () -> Unit): Button =
    Button(label, onActivate).apply { renderer = PlainButtonRenderer() }

internal class PlainButtonRenderer : Button.ButtonRenderer {

    override fun getCursorLocation(button: Button): TerminalPosition? = null

    override fun getPreferredSize(button: Button): TerminalSize =
        TerminalSize(Math.max(button.label.length + 2, 5), 1)

    override fun drawComponent(graphics: TextGUIGraphics, button: Button) {
        val themeDef = button.themeDefinition
        graphics.applyThemeStyle(if (button.isFocused) themeDef.active else themeDef.insensitive)
        graphics.fill(' ')
        graphics.setCharacter(0, 0, themeDef.getCharacter("LEFT_BORDER", '<'))
        graphics.setCharacter(graphics.size.columns - 1, 0, themeDef.getCharacter("RIGHT_BORDER", '>'))
        graphics.applyThemeStyle(if (button.isFocused) themeDef.selected else themeDef.normal)
        val labelShift = if (graphics.size.columns > 2 + button.label.length) 1 else 0
        graphics.putString(1 + labelShift, 0, button.label)
    }
}
