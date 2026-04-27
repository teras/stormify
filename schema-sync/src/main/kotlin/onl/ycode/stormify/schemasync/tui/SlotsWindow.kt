package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.ActionListBox
import com.googlecode.lanterna.gui2.BasicWindow
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.EmptySpace
import com.googlecode.lanterna.gui2.GridLayout
import com.googlecode.lanterna.gui2.Interactable
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.Window
import com.googlecode.lanterna.gui2.WindowBasedTextGUI
import com.googlecode.lanterna.gui2.WindowListenerAdapter
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.model.DecimalSlot
import onl.ycode.stormify.schemasync.model.IntegralSlot
import onl.ycode.stormify.schemasync.model.MAX_SLOTS_PER_CATEGORY
import onl.ycode.stormify.schemasync.model.TextSlot
import java.util.concurrent.atomic.AtomicBoolean

private const val NAME_WIDTH = 9
private const val DDL_WIDTH = 16

/** Editable browser for slot profile. Enter edits, Esc closes. */
fun runSlotsView(gui: WindowBasedTextGUI, state: ConfigState) {
    val window = BasicWindow("Slots")
    window.setHints(listOf(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS))

    val textList = OpAwareListBox()
    val integralList = OpAwareListBox()
    val decimalList = OpAwareListBox()

    val textCount = Label("")
    val integralCount = Label("")
    val decimalCount = Label("")

    fun refreshCount(label: Label, size: Int) {
        label.text = " $size of $MAX_SLOTS_PER_CATEGORY used"
    }

    fun rebuildText() {
        val keep = textList.selectedIndex.coerceAtLeast(0)
        textList.clearItems()
        state.current.slots.text.forEachIndexed { i, slot ->
            textList.addItem(formatRow(i, slot.name, slot.ddl), Runnable {
                val current = state.current.slots.text[i]
                val edited = editTextSlot(gui, current) ?: return@Runnable
                state.update { cfg ->
                    cfg.copy(slots = cfg.slots.copy(
                        text = cfg.slots.text.toMutableList().also { it[i] = edited }
                    ))
                }
                rebuildText()
            })
        }
        if (keep in 0 until textList.itemCount) textList.selectedIndex = keep
        refreshCount(textCount, state.current.slots.text.size)
    }

    fun rebuildIntegral() {
        val keep = integralList.selectedIndex.coerceAtLeast(0)
        integralList.clearItems()
        state.current.slots.integral.forEachIndexed { i, slot ->
            integralList.addItem(formatRow(i, slot.name, slot.ddl), Runnable {
                val current = state.current.slots.integral[i]
                val edited = editIntegralSlot(gui, current) ?: return@Runnable
                state.update { cfg ->
                    cfg.copy(slots = cfg.slots.copy(
                        integral = cfg.slots.integral.toMutableList().also { it[i] = edited }
                    ))
                }
                rebuildIntegral()
            })
        }
        if (keep in 0 until integralList.itemCount) integralList.selectedIndex = keep
        refreshCount(integralCount, state.current.slots.integral.size)
    }

    fun rebuildDecimal() {
        val keep = decimalList.selectedIndex.coerceAtLeast(0)
        decimalList.clearItems()
        state.current.slots.decimal.forEachIndexed { i, slot ->
            decimalList.addItem(formatRow(i, slot.name, slot.ddl), Runnable {
                val current = state.current.slots.decimal[i]
                val edited = editDecimalSlot(gui, current) ?: return@Runnable
                state.update { cfg ->
                    cfg.copy(slots = cfg.slots.copy(
                        decimal = cfg.slots.decimal.toMutableList().also { it[i] = edited }
                    ))
                }
                rebuildDecimal()
            })
        }
        if (keep in 0 until decimalList.itemCount) decimalList.selectedIndex = keep
        refreshCount(decimalCount, state.current.slots.decimal.size)
    }

    fun applyTextOp(transform: (List<TextSlot>, Int) -> Pair<List<TextSlot>, Int>?) {
        val (newList, newIdx) = transform(state.current.slots.text, textList.selectedIndex) ?: return
        state.update { cfg -> cfg.copy(slots = cfg.slots.copy(text = newList)) }
        rebuildText()
        textList.selectedIndex = newIdx
    }

    fun applyIntegralOp(transform: (List<IntegralSlot>, Int) -> Pair<List<IntegralSlot>, Int>?) {
        val (newList, newIdx) = transform(state.current.slots.integral, integralList.selectedIndex) ?: return
        state.update { cfg -> cfg.copy(slots = cfg.slots.copy(integral = newList)) }
        rebuildIntegral()
        integralList.selectedIndex = newIdx
    }

    fun applyDecimalOp(transform: (List<DecimalSlot>, Int) -> Pair<List<DecimalSlot>, Int>?) {
        val (newList, newIdx) = transform(state.current.slots.decimal, decimalList.selectedIndex) ?: return
        state.update { cfg -> cfg.copy(slots = cfg.slots.copy(decimal = newList)) }
        rebuildDecimal()
        decimalList.selectedIndex = newIdx
    }

    textList.onShiftUp = { applyTextOp(::opMoveUp) }
    textList.onShiftDown = { applyTextOp(::opMoveDown) }
    integralList.onShiftUp = { applyIntegralOp(::opMoveUp) }
    integralList.onShiftDown = { applyIntegralOp(::opMoveDown) }
    decimalList.onShiftUp = { applyDecimalOp(::opMoveUp) }
    decimalList.onShiftDown = { applyDecimalOp(::opMoveDown) }

    rebuildText()
    rebuildIntegral()
    rebuildDecimal()

    val termSize = gui.screen.terminalSize
    val listHeight = termSize.rows - 8
    val colWidth = (termSize.columns - 6) / 3

    fun pane(count: Label, list: ActionListBox, title: String) =
        Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
            addComponent(count)
            addComponent(list)
        }.withTitledBorder(title).apply {
            preferredSize = TerminalSize(colWidth, listHeight)
        }

    val grid = Panel(GridLayout(3).setHorizontalSpacing(1))
    grid.addComponent(pane(textCount, textList, "TEXT"))
    grid.addComponent(pane(integralCount, integralList, "INTEGRAL"))
    grid.addComponent(pane(decimalCount, decimalList, "DECIMAL"))

    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    root.addComponent(grid)
    root.addComponent(EmptySpace(TerminalSize(1, 1)))
    root.addComponent(Label("↑↓ navigate · Tab next pane · Enter edit · Ins dup · Del rm · Shift+↑↓ move · Esc back"))

    window.component = root
    window.focusedInteractable = textList

    window.addWindowListener(object : WindowListenerAdapter() {
        override fun onUnhandledInput(basePane: Window, keyStroke: KeyStroke, hasBeenHandled: AtomicBoolean) {
            val focused = window.focusedInteractable
            when {
                keyStroke.keyType == KeyType.Escape || keyStroke.character == 'q' || keyStroke.character == 'Q' -> {
                    window.close()
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.Insert -> {
                    when (focused) {
                        textList -> applyTextOp(::opDuplicate)
                        integralList -> applyIntegralOp(::opDuplicate)
                        decimalList -> applyDecimalOp(::opDuplicate)
                    }
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.Delete -> {
                    when (focused) {
                        textList -> applyTextOp(::opDelete)
                        integralList -> applyIntegralOp(::opDelete)
                        decimalList -> applyDecimalOp(::opDelete)
                    }
                    hasBeenHandled.set(true)
                }
            }
        }
    })

    gui.addWindowAndWait(window)
}

private fun formatRow(index: Int, name: String, ddl: String): String =
    "${index + 1} ${name.padEnd(NAME_WIDTH)} ${ddl.padEnd(DDL_WIDTH)}"

/** ActionListBox that intercepts Shift+Up/Down before navigation can consume them. */
private class OpAwareListBox : ActionListBox() {
    var onShiftUp: () -> Unit = {}
    var onShiftDown: () -> Unit = {}

    override fun handleKeyStroke(keyStroke: KeyStroke): Interactable.Result {
        if (keyStroke.isShiftDown) {
            when (keyStroke.keyType) {
                KeyType.ArrowUp -> {
                    onShiftUp()
                    return Interactable.Result.HANDLED
                }
                KeyType.ArrowDown -> {
                    onShiftDown()
                    return Interactable.Result.HANDLED
                }
                else -> {}
            }
        }
        return super.handleKeyStroke(keyStroke)
    }
}

private fun <X> opDuplicate(items: List<X>, idx: Int): Pair<List<X>, Int>? {
    if (items.size >= MAX_SLOTS_PER_CATEGORY || idx !in items.indices) return null
    val newList = items.toMutableList().apply { add(idx + 1, items[idx]) }
    return newList to (idx + 1)
}

private fun <X> opDelete(items: List<X>, idx: Int): Pair<List<X>, Int>? {
    if (items.size <= 1 || idx !in items.indices) return null
    val newList = items.toMutableList().apply { removeAt(idx) }
    val newIdx = if (idx < newList.size) idx else newList.size - 1
    return newList to newIdx
}

private fun <X> opMoveUp(items: List<X>, idx: Int): Pair<List<X>, Int>? {
    if (idx <= 0 || idx >= items.size) return null
    val newList = items.toMutableList().apply { add(idx - 1, removeAt(idx)) }
    return newList to (idx - 1)
}

private fun <X> opMoveDown(items: List<X>, idx: Int): Pair<List<X>, Int>? {
    if (idx < 0 || idx >= items.size - 1) return null
    val newList = items.toMutableList().apply { add(idx + 1, removeAt(idx)) }
    return newList to (idx + 1)
}
