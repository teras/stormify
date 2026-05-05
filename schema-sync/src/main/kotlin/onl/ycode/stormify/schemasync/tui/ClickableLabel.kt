package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.AbstractInteractableComponent
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.InteractableRenderer
import com.googlecode.lanterna.gui2.TextGUIGraphics
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.MouseActionType

/**
 * Static label that fires [onClick] on mouse click and never grabs keyboard
 * focus. Used where we want a label-styled clickable region without Button's
 * focus / Enter-activation semantics.
 */
class ClickableLabel(
    label: String,
    private val onClick: () -> Unit,
) : AbstractInteractableComponent<ClickableLabel>() {

    var label: String = label
        set(value) { field = value; invalidate() }

    override fun createDefaultRenderer(): InteractableRenderer<ClickableLabel> = Renderer()

    override fun handleKeyStroke(keyStroke: KeyStroke): Interactable.Result {
        keyStroke.asMouse?.let { m ->
            if (m.actionType == MouseActionType.CLICK_DOWN) onClick()
            return Interactable.Result.HANDLED
        }
        return Interactable.Result.UNHANDLED
    }

    private inner class Renderer : InteractableRenderer<ClickableLabel> {
        override fun getCursorLocation(component: ClickableLabel) = null
        override fun getPreferredSize(component: ClickableLabel) =
            TerminalSize(component.label.length, 1)
        override fun drawComponent(graphics: TextGUIGraphics, component: ClickableLabel) {
            graphics.applyThemeStyle(component.theme.getDefinition(ClickableLabel::class.java).normal)
            graphics.fill(' ')
            graphics.putString(0, 0, component.label)
        }
    }
}
