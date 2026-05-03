package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.Button
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.GridLayout
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.TextBox
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.gui2.WindowBasedTextGUI
import com.googlecode.lanterna.gui2.WindowListenerAdapter
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import onl.ycode.stormify.schemasync.model.DecimalSlot
import onl.ycode.stormify.schemasync.model.IntegralSlot
import onl.ycode.stormify.schemasync.model.TextSlot
import java.util.concurrent.atomic.AtomicBoolean

private const val NAME_BOX_WIDTH = 16
private const val NUM_BOX_WIDTH = 8

fun editTextSlot(gui: WindowBasedTextGUI, slot: TextSlot): TextSlot? {
    val nameBox = TextBox(TerminalSize(NAME_BOX_WIDTH, 1), slot.name)
    val lengthBox = TextBox(TerminalSize(NUM_BOX_WIDTH, 1), slot.length?.toString() ?: "")
    val errorLabel = Label("")
    return runDialog(
        gui = gui,
        title = "Edit text slot",
        rows = listOf(
            "Name" to nameBox,
            "Length" to lengthBox,
        ),
        helpLine = "leave Length empty for unbounded TEXT/CLOB",
        errorLabel = errorLabel,
        validate = {
            val name = nameBox.text.trim()
            if (name.isEmpty()) return@runDialog "name cannot be empty"
            val length = parseOptionalLength(lengthBox.text) ?: return@runDialog "length must be a positive number or empty"
            null
        },
        produce = { TextSlot(nameBox.text.trim(), parseOptionalLength(lengthBox.text)?.takeIf { it > 0 }) },
    )
}

fun editIntegralSlot(gui: WindowBasedTextGUI, slot: IntegralSlot): IntegralSlot? {
    val nameBox = TextBox(TerminalSize(NAME_BOX_WIDTH, 1), slot.name)
    val digitsBox = TextBox(TerminalSize(NUM_BOX_WIDTH, 1), slot.digits?.toString() ?: "")
    val errorLabel = Label("")
    return runDialog(
        gui = gui,
        title = "Edit integral slot",
        rows = listOf(
            "Name" to nameBox,
            "Digits" to digitsBox,
        ),
        helpLine = "digits = total number of decimal digits (1–38), empty for unbounded NUMERIC",
        errorLabel = errorLabel,
        validate = {
            if (nameBox.text.trim().isEmpty()) return@runDialog "name cannot be empty"
            val raw = digitsBox.text.trim()
            if (raw.isNotEmpty()) {
                val d = raw.toIntOrNull()
                if (d == null || d !in 1..38) return@runDialog "digits must be 1–38 or empty"
            }
            null
        },
        produce = {
            val raw = digitsBox.text.trim()
            IntegralSlot(nameBox.text.trim(), if (raw.isEmpty()) null else raw.toInt())
        },
    )
}

fun editDecimalSlot(gui: WindowBasedTextGUI, slot: DecimalSlot): DecimalSlot? {
    val nameBox = TextBox(TerminalSize(NAME_BOX_WIDTH, 1), slot.name)
    val precBox = TextBox(TerminalSize(NUM_BOX_WIDTH, 1), slot.precision?.toString() ?: "")
    val scaleBox = TextBox(TerminalSize(NUM_BOX_WIDTH, 1), slot.scale?.toString() ?: "")
    val errorLabel = Label("")
    return runDialog(
        gui = gui,
        title = "Edit decimal slot",
        rows = listOf(
            "Name" to nameBox,
            "Precision" to precBox,
            "Scale" to scaleBox,
        ),
        helpLine = "empty precision → unbounded NUMERIC; empty scale → NUMERIC(precision)",
        errorLabel = errorLabel,
        validate = {
            if (nameBox.text.trim().isEmpty()) return@runDialog "name cannot be empty"
            val pRaw = precBox.text.trim()
            val sRaw = scaleBox.text.trim()
            val p = if (pRaw.isEmpty()) null else pRaw.toIntOrNull()
                ?: return@runDialog "precision must be 1–38 or empty"
            val s = if (sRaw.isEmpty()) null else sRaw.toIntOrNull()
                ?: return@runDialog "scale must be ≥ 0 or empty"
            if (p != null && p !in 1..38) return@runDialog "precision must be 1–38 or empty"
            if (s != null && s < 0) return@runDialog "scale must be ≥ 0 or empty"
            if (p == null && s != null) return@runDialog "scale requires a precision"
            if (p != null && s != null && s > p) return@runDialog "scale cannot exceed precision"
            null
        },
        produce = {
            val pRaw = precBox.text.trim()
            val sRaw = scaleBox.text.trim()
            DecimalSlot(
                nameBox.text.trim(),
                if (pRaw.isEmpty()) null else pRaw.toInt(),
                if (sRaw.isEmpty()) null else sRaw.toInt(),
            )
        },
    )
}

private fun parseOptionalLength(input: String): Int? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return -1 // sentinel for "valid empty"
    return trimmed.toIntOrNull()?.takeIf { it > 0 }
}

private fun <T> runDialog(
    gui: WindowBasedTextGUI,
    title: String,
    rows: List<Pair<String, TextBox>>,
    helpLine: String,
    errorLabel: Label,
    validate: () -> String?,
    produce: () -> T,
): T? {
    val window = BasicWindow(title)
    window.setHints(listOf(Window.Hint.CENTERED, Window.Hint.MODAL))

    val grid = Panel(GridLayout(2).setLeftMarginSize(1).setRightMarginSize(1))
    val labelWidth = rows.maxOf { it.first.length } + 1
    rows.forEach { (label, box) ->
        grid.addComponent(Label(label.padEnd(labelWidth)))
        grid.addComponent(box)
    }

    var result: T? = null

    val okButton = plainButton("OK") {
        val err = validate()
        if (err != null) {
            errorLabel.text = err
            return@plainButton
        }
        result = produce()
        window.close()
    }
    val cancelButton = plainButton("Cancel") {
        window.close()
    }

    val buttons = Panel(LinearLayout(Direction.HORIZONTAL))
    buttons.addComponent(okButton)
    buttons.addComponent(EmptySpace(TerminalSize(2, 1)))
    buttons.addComponent(cancelButton)

    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    root.addComponent(grid)
    root.addComponent(EmptySpace(TerminalSize(1, 1)))
    root.addComponent(Label(helpLine))
    root.addComponent(errorLabel)
    root.addComponent(EmptySpace(TerminalSize(1, 1)))
    root.addComponent(buttons)

    window.component = root
    window.focusedInteractable = rows.first().second

    window.addWindowListener(object : WindowListenerAdapter() {
        override fun onUnhandledInput(basePane: Window, keyStroke: KeyStroke, hasBeenHandled: AtomicBoolean) {
            if (keyStroke.keyType == KeyType.Escape) {
                window.close()
                hasBeenHandled.set(true)
            }
        }
    })

    gui.addWindowAndWait(window)
    return result
}
