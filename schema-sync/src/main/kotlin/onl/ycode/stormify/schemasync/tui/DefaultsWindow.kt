package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.BasicWindow
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
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.model.DefaultsProfile
import onl.ycode.stormify.schemasync.model.DialectOverride
import java.util.concurrent.atomic.AtomicBoolean

private const val DDL_BOX_WIDTH = 36

/**
 * F6 Defaults — edit DDL templates for deterministic Kotlin types.
 *
 * If a connected dialect is provided (anything other than [Dialect.GENERIC]),
 * the boxes are populated with the *merged* effective values for that dialect
 * and edits persist into the corresponding `[defaults.<dialect>]` override
 * block, so you can tweak a single dialect without disturbing the base profile
 * or the other dialects' overrides.
 */
fun runDefaultsView(gui: WindowBasedTextGUI, state: ConfigState, dialect: Dialect = Dialect.GENERIC) {
    val title = if (dialect == Dialect.GENERIC) "Defaults (base)" else "Defaults [${dialect.tomlKey}]"
    val window = BasicWindow(title)
    window.setHints(listOf(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS))

    val effective = state.current.defaults.mergedFor(dialect.tomlKey)

    data class Row(
        val label: String,
        val initial: String,
        val applyBase: (String, DefaultsProfile) -> DefaultsProfile,
        val applyOverride: (String?, DialectOverride) -> DialectOverride,
        val baseValueOf: (DefaultsProfile) -> String,
    )

    val rows = listOf(
        Row("PK Int",            effective.pkIntDdl,
            { v, d -> d.copy(pkIntDdl = v) },        { v, o -> o.copy(pkIntDdl = v) },        { it.pkIntDdl }),
        Row("PK Long",           effective.pkLongDdl,
            { v, d -> d.copy(pkLongDdl = v) },       { v, o -> o.copy(pkLongDdl = v) },       { it.pkLongDdl }),
        Row("PK Int (auto)",     effective.pkAutoIntDdl,
            { v, d -> d.copy(pkAutoIntDdl = v) },    { v, o -> o.copy(pkAutoIntDdl = v) },    { it.pkAutoIntDdl }),
        Row("PK Long (auto)",    effective.pkAutoLongDdl,
            { v, d -> d.copy(pkAutoLongDdl = v) },   { v, o -> o.copy(pkAutoLongDdl = v) },   { it.pkAutoLongDdl }),
        Row("Boolean",           effective.booleanDdl,
            { v, d -> d.copy(booleanDdl = v) },      { v, o -> o.copy(booleanDdl = v) },      { it.booleanDdl }),
        Row("LocalDate",         effective.localDateDdl,
            { v, d -> d.copy(localDateDdl = v) },    { v, o -> o.copy(localDateDdl = v) },    { it.localDateDdl }),
        Row("LocalTime",         effective.localTimeDdl,
            { v, d -> d.copy(localTimeDdl = v) },    { v, o -> o.copy(localTimeDdl = v) },    { it.localTimeDdl }),
        Row("LocalDateTime",     effective.localDateTimeDdl,
            { v, d -> d.copy(localDateTimeDdl = v) },{ v, o -> o.copy(localDateTimeDdl = v) },{ it.localDateTimeDdl }),
        Row("Instant / OffsetDT",effective.instantDdl,
            { v, d -> d.copy(instantDdl = v) },      { v, o -> o.copy(instantDdl = v) },      { it.instantDdl }),
        Row("UUID",              effective.uuidDdl,
            { v, d -> d.copy(uuidDdl = v) },         { v, o -> o.copy(uuidDdl = v) },         { it.uuidDdl }),
        Row("ByteArray",         effective.byteArrayDdl,
            { v, d -> d.copy(byteArrayDdl = v) },    { v, o -> o.copy(byteArrayDdl = v) },    { it.byteArrayDdl }),
        Row("CharArray",         effective.charArrayDdl,
            { v, d -> d.copy(charArrayDdl = v) },    { v, o -> o.copy(charArrayDdl = v) },    { it.charArrayDdl }),
        Row("Enum (ordinal)",    effective.enumOrdinalDdl,
            { v, d -> d.copy(enumOrdinalDdl = v) },  { v, o -> o.copy(enumOrdinalDdl = v) },  { it.enumOrdinalDdl }),
        Row("auto-DEFAULT text",     effective.autoDefaultText,
            { v, d -> d.copy(autoDefaultText = v) },  { v, o -> o.copy(autoDefaultText = v) },  { it.autoDefaultText }),
        Row("auto-DEFAULT int",      effective.autoDefaultInt,
            { v, d -> d.copy(autoDefaultInt = v) },   { v, o -> o.copy(autoDefaultInt = v) },   { it.autoDefaultInt }),
        Row("auto-DEFAULT decimal",  effective.autoDefaultDecimal,
            { v, d -> d.copy(autoDefaultDecimal = v) },{ v, o -> o.copy(autoDefaultDecimal = v) },{ it.autoDefaultDecimal }),
        Row("auto-DEFAULT bool",     effective.autoDefaultBoolean,
            { v, d -> d.copy(autoDefaultBoolean = v) },{ v, o -> o.copy(autoDefaultBoolean = v) },{ it.autoDefaultBoolean }),
        Row("auto-DEFAULT date",     effective.autoDefaultLocalDate,
            { v, d -> d.copy(autoDefaultLocalDate = v) },{ v, o -> o.copy(autoDefaultLocalDate = v) },{ it.autoDefaultLocalDate }),
        Row("auto-DEFAULT time",     effective.autoDefaultLocalTime,
            { v, d -> d.copy(autoDefaultLocalTime = v) },{ v, o -> o.copy(autoDefaultLocalTime = v) },{ it.autoDefaultLocalTime }),
        Row("auto-DEFAULT ldt",      effective.autoDefaultLocalDateTime,
            { v, d -> d.copy(autoDefaultLocalDateTime = v) },{ v, o -> o.copy(autoDefaultLocalDateTime = v) },{ it.autoDefaultLocalDateTime }),
        Row("auto-DEFAULT instant",  effective.autoDefaultInstant,
            { v, d -> d.copy(autoDefaultInstant = v) },{ v, o -> o.copy(autoDefaultInstant = v) },{ it.autoDefaultInstant }),
        Row("auto-DEFAULT uuid",     effective.autoDefaultUuid,
            { v, d -> d.copy(autoDefaultUuid = v) },  { v, o -> o.copy(autoDefaultUuid = v) },  { it.autoDefaultUuid }),
        Row("auto-DEFAULT bytes",    effective.autoDefaultBytes,
            { v, d -> d.copy(autoDefaultBytes = v) }, { v, o -> o.copy(autoDefaultBytes = v) }, { it.autoDefaultBytes }),
    )

    val boxes = mutableListOf<Pair<TextBox, Row>>()
    val grid = Panel(GridLayout(2).setHorizontalSpacing(2).setVerticalSpacing(0))
    for (row in rows) {
        grid.addComponent(Label(row.label.padEnd(20)))
        val box = TextBox(TerminalSize(DDL_BOX_WIDTH, 1), row.initial)
        grid.addComponent(box)
        boxes += box to row
    }

    fun saveAll() {
        state.update { cfg ->
            if (dialect == Dialect.GENERIC) {
                var d = cfg.defaults
                for ((box, row) in boxes) {
                    d = row.applyBase(box.text.trim(), d)
                }
                cfg.copy(defaults = d)
            } else {
                // Persist as dialect override; field is recorded only when it differs from base.
                var override = currentOverride(cfg.defaults, dialect)
                val base = cfg.defaults
                for ((box, row) in boxes) {
                    val v = box.text.trim()
                    val baseValue = row.baseValueOf(base)
                    val newOverride = if (v == baseValue) null else v
                    override = row.applyOverride(newOverride, override)
                }
                cfg.copy(defaults = withOverride(cfg.defaults, dialect, override))
            }
        }
    }

    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    root.addComponent(grid.withTitledBorder(
        if (dialect == Dialect.GENERIC) "DDL templates for deterministic Kotlin types"
        else "DDL templates for ${dialect.tomlKey} (overrides base profile)",
    ))
    root.addComponent(EmptySpace(TerminalSize(1, 1)))
    root.addComponent(Label("Tab next field · Enter / Esc save & close · F1 help"))

    window.component = root
    if (boxes.isNotEmpty()) window.focusedInteractable = boxes.first().first

    window.addWindowListener(object : WindowListenerAdapter() {
        override fun onUnhandledInput(basePane: Window, keyStroke: KeyStroke, hasBeenHandled: AtomicBoolean) {
            when {
                keyStroke.keyType == KeyType.F1 || keyStroke.character == '?' -> {
                    showHelp(gui)
                    hasBeenHandled.set(true)
                }
                keyStroke.keyType == KeyType.Escape || keyStroke.keyType == KeyType.Enter
                    || keyStroke.character == 'q' || keyStroke.character == 'Q' -> {
                    saveAll()
                    window.close()
                    hasBeenHandled.set(true)
                }
            }
        }
    })

    gui.addWindowAndWait(window)
}

private fun currentOverride(p: DefaultsProfile, d: Dialect): DialectOverride = when (d) {
    Dialect.POSTGRESQL -> p.postgresql
    Dialect.MYSQL -> p.mysql
    Dialect.MARIADB -> p.mariadb
    Dialect.ORACLE -> p.oracle
    Dialect.MSSQL -> p.mssql
    Dialect.SQLITE -> p.sqlite
    Dialect.GENERIC -> DialectOverride()
}

private fun withOverride(p: DefaultsProfile, d: Dialect, o: DialectOverride): DefaultsProfile = when (d) {
    Dialect.POSTGRESQL -> p.copy(postgresql = o)
    Dialect.MYSQL -> p.copy(mysql = o)
    Dialect.MARIADB -> p.copy(mariadb = o)
    Dialect.ORACLE -> p.copy(oracle = o)
    Dialect.MSSQL -> p.copy(mssql = o)
    Dialect.SQLITE -> p.copy(sqlite = o)
    Dialect.GENERIC -> p
}
