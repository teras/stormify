package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.gui2.ComboBox
import com.googlecode.lanterna.gui2.Direction
import com.googlecode.lanterna.gui2.GridLayout
import com.googlecode.lanterna.gui2.Label
import com.googlecode.lanterna.gui2.LinearLayout
import com.googlecode.lanterna.gui2.Panel
import com.googlecode.lanterna.gui2.TextBox
import com.googlecode.lanterna.gui2.WindowBasedTextGUI
import onl.ycode.stormify.schemasync.config.ConfigState
import onl.ycode.stormify.schemasync.db.Dialect
import onl.ycode.stormify.schemasync.model.DateType
import onl.ycode.stormify.schemasync.model.DecimalType
import onl.ycode.stormify.schemasync.model.DefaultsProfile
import onl.ycode.stormify.schemasync.model.DialectOverride
import onl.ycode.stormify.schemasync.model.EntityBase
import onl.ycode.stormify.schemasync.model.NamingPolicy
import onl.ycode.stormify.schemasync.model.PkIntegerType
import onl.ycode.stormify.schemasync.model.TimeType
import onl.ycode.stormify.schemasync.model.TimestampType

private const val DDL_BOX_WIDTH = 36

internal const val DEFAULTS_PANE_HINT =
    "Tab next field · Up/Down/PgUp/PgDn scroll · Esc save & close · F1 help"

internal const val KT_PANE_HINT =
    "Choose how schema-sync writes Kotlin code · Esc save & close"

/** F7 → KT tab: Kotlin-side preferences (naming policy + type choices). */
internal fun buildKtDefaultsPane(state: ConfigState): TabPane {
    val policyCombo = ComboBox<String>(NamingPolicy.entries.map { it.name }).apply {
        selectedIndex = NamingPolicy.entries.indexOf(state.current.namingPolicy)
        isReadOnly = true
    }
    val pkIntCombo = ComboBox<String>(PkIntegerType.entries.map { it.display }).apply {
        selectedIndex = PkIntegerType.entries.indexOf(state.current.kotlin.pkIntegerType)
        isReadOnly = true
    }
    val dateCombo = ComboBox<String>(DateType.entries.map { it.display }).apply {
        selectedIndex = DateType.entries.indexOf(state.current.kotlin.dateType)
        isReadOnly = true
    }
    val timeCombo = ComboBox<String>(TimeType.entries.map { it.display }).apply {
        selectedIndex = TimeType.entries.indexOf(state.current.kotlin.timeType)
        isReadOnly = true
    }
    val timestampCombo = ComboBox<String>(TimestampType.entries.map { it.display }).apply {
        selectedIndex = TimestampType.entries.indexOf(state.current.kotlin.timestampType)
        isReadOnly = true
    }
    val decimalCombo = ComboBox<String>(DecimalType.entries.map { it.display }).apply {
        selectedIndex = DecimalType.entries.indexOf(state.current.kotlin.decimalType)
        isReadOnly = true
    }
    val entityBaseLabels = mapOf(
        EntityBase.NONE to "no ByDb",
        EntityBase.BYDB_ALWAYS to "ByDb (retrofit existing)",
        EntityBase.BYDB_FOR_NEW to "ByDb (new entities only)",
    )
    val entityBaseCombo = ComboBox<String>(EntityBase.entries.map { entityBaseLabels.getValue(it) }).apply {
        selectedIndex = EntityBase.entries.indexOf(state.current.kotlin.entityBase)
        isReadOnly = true
    }

    val grid = Panel(GridLayout(2).setHorizontalSpacing(2).setVerticalSpacing(0))
    grid.addComponent(Label("Naming policy".padEnd(20)));     grid.addComponent(policyCombo)
    grid.addComponent(Label("Entity base".padEnd(20)));       grid.addComponent(entityBaseCombo)
    grid.addComponent(Label("PK Integer".padEnd(20)));        grid.addComponent(pkIntCombo)
    grid.addComponent(Label("DATE".padEnd(20)));              grid.addComponent(dateCombo)
    grid.addComponent(Label("TIME".padEnd(20)));              grid.addComponent(timeCombo)
    grid.addComponent(Label("TIMESTAMP".padEnd(20)));         grid.addComponent(timestampCombo)
    grid.addComponent(Label("DECIMAL".padEnd(20)));           grid.addComponent(decimalCombo)

    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0))
    root.addComponent(grid.withTitledBorder("Kotlin code-generation defaults"))

    return TabPane(
        component = root,
        initialFocus = policyCombo,
        commit = {
            state.update { cfg ->
                cfg.copy(
                    namingPolicy = NamingPolicy.entries[policyCombo.selectedIndex.coerceIn(0, NamingPolicy.entries.lastIndex)],
                    kotlin = cfg.kotlin.copy(
                        pkIntegerType = PkIntegerType.entries[pkIntCombo.selectedIndex.coerceIn(0, PkIntegerType.entries.lastIndex)],
                        dateType = DateType.entries[dateCombo.selectedIndex.coerceIn(0, DateType.entries.lastIndex)],
                        timeType = TimeType.entries[timeCombo.selectedIndex.coerceIn(0, TimeType.entries.lastIndex)],
                        timestampType = TimestampType.entries[timestampCombo.selectedIndex.coerceIn(0, TimestampType.entries.lastIndex)],
                        decimalType = DecimalType.entries[decimalCombo.selectedIndex.coerceIn(0, DecimalType.entries.lastIndex)],
                        entityBase = EntityBase.entries[entityBaseCombo.selectedIndex.coerceIn(0, EntityBase.entries.lastIndex)],
                    ),
                )
            }
        },
    )
}

/** F7 → DB tab: dialect-aware DDL templates + per-type auto-DEFAULT literals. */
internal fun buildDbDefaultsPane(gui: WindowBasedTextGUI, state: ConfigState, dialect: Dialect): TabPane {
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

    val title =
        if (dialect == Dialect.GENERIC) "DDL templates for deterministic Kotlin types"
        else "DDL templates for ${dialect.tomlKey} (overrides base profile)"
    val bordered = grid.withTitledBorder(title).apply {
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    }
    val scroller = ScrollableContainer(Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
        addComponent(bordered)
    }).apply {
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
    }
    val root = Panel(LinearLayout(Direction.VERTICAL).setSpacing(0)).apply {
        layoutData = LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)
        addComponent(scroller)
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

    return TabPane(
        component = root,
        initialFocus = boxes.first().first,
        handleKey = handle@{ keyStroke, focused ->
            scroller.ensureVisible(focused as? com.googlecode.lanterna.gui2.Component)
            val delta = keyStroke.scrollDelta()
            if (delta != null) { scroller.scrollBy(delta); return@handle true }
            when (keyStroke.keyType) {
                com.googlecode.lanterna.input.KeyType.PageUp   -> { scroller.scrollBy(-10); true }
                com.googlecode.lanterna.input.KeyType.PageDown -> { scroller.scrollBy(+10); true }
                else -> false
            }
        },
        commit = { saveAll() },
    )
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
