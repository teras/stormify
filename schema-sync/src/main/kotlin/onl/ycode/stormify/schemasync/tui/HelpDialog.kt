package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.gui2.WindowBasedTextGUI
import com.googlecode.lanterna.gui2.dialogs.MessageDialog
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton

private const val HELP_TEXT = """schema-sync — keybindings

Main view:
  ↑ ↓             navigate within current pane
  ← →             switch panes (tables / properties / diff)
  Space           in properties pane: cycle insert/delete/none for current row
  Delete          in properties pane: clear action on current row
  / Tab           filter / switch panes
  1-9             in diff pane: pick slot for current entity-only column
  F1 / ?          this help
  F2              apply pending code edits + write migration.sql
  F3              bulk classify entity-only fields
  F7              config (slots / defaults)
  F8              cycle theme
  Esc / q         quit

Slots editor (F7 → Slots):
  ↑ ↓             navigate
  Tab             next pane
  Enter           edit slot
  Insert          duplicate
  Delete          remove
  Shift+↑↓        move up/down
  D               set as default for category (* marker)
  Esc             back"""

fun showHelp(gui: WindowBasedTextGUI) {
    MessageDialog.showMessageDialog(gui, "Help", HELP_TEXT, MessageDialogButton.OK)
}
