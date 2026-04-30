package onl.ycode.stormify.schemasync.tui

import com.googlecode.lanterna.gui2.WindowBasedTextGUI
import com.googlecode.lanterna.gui2.dialogs.MessageDialog
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton

private const val HELP_TEXT = """schema-sync — keybindings

Main view:
  ↑ ↓             navigate tables
  / Tab           filter / switch panes
  F1 / ?          this help
  F2              cycle theme
  F3              slots editor
  F4              classify columns
  F5              export migration.sql
  F6              defaults (DDL templates + auto-default values)
  F7              code edits (insert into entity / mark @Transient)
  Esc / q         quit

Slots editor (F3):
  ↑ ↓             navigate
  Tab             next pane
  Enter           edit slot
  Insert          duplicate
  Delete          remove
  Shift+↑↓        move up/down
  Esc             back

Classify columns (F4):
  1-9             pick slot at position (auto-advances)
  Enter           accept selected slot (auto-advances)
  ← →             prev / next column
  ↑ ↓             change selected slot (resolution)
  Esc             back"""

fun showHelp(gui: WindowBasedTextGUI) {
    MessageDialog.showMessageDialog(gui, "Help", HELP_TEXT, MessageDialogButton.OK)
}
