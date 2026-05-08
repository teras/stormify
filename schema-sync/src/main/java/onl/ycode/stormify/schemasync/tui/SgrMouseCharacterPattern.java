// SGR (1006) mouse-event parser for Lanterna. Lanterna 3.1.2 ships
// MouseCharacterPattern that only understands the legacy X10 protocol
// (CSI M Cb Cx Cy with each byte offset by 32). Modern terminals — including
// Windows conhost when ENABLE_VIRTUAL_TERMINAL_INPUT is set — emit the SGR
// extended form: CSI < Cb ; Cx ; Cy (M|m), with decimal coords and no 223
// column ceiling. Without this pattern, every Windows mouse event is
// silently dropped by the input decoder.
package onl.ycode.stormify.schemasync.tui;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.KeyDecodingProfile;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

import java.util.List;

public class SgrMouseCharacterPattern implements CharacterPattern {

    private static final char[] HEAD = { KeyDecodingProfile.ESC_CODE, '[', '<' };

    private boolean isMouseDown = false;

    @Override
    public Matching match(List<Character> seq) {
        int size = seq.size();
        for (int i = 0; i < HEAD.length; i++) {
            if (i >= size) return Matching.NOT_YET;
            if (seq.get(i) != HEAD[i]) return null;
        }
        // Need three semicolon-separated decimal numbers terminated by M or m.
        Integer cb = null, cx = null, cy = null;
        int idx = HEAD.length;
        StringBuilder num = new StringBuilder();
        for (int i = idx; i < size; i++) {
            char c = seq.get(i);
            if (c >= '0' && c <= '9') {
                num.append(c);
            } else if (c == ';') {
                if (num.length() == 0) return null;
                int v = Integer.parseInt(num.toString());
                num.setLength(0);
                if (cb == null) cb = v;
                else if (cx == null) cx = v;
                else return null; // too many semicolons
            } else if (c == 'M' || c == 'm') {
                if (cb == null || cx == null || num.length() == 0) return null;
                cy = Integer.parseInt(num.toString());
                boolean release = (c == 'm');
                MouseAction action = buildAction(cb, cx, cy, release);
                // conhost emits ALL mouse motion as SGR events even with mouse
                // capture set to button-event tracking only (1002). Discard
                // pure hover (no button held) so the TUI doesn't treat every
                // cursor wiggle as a selection change. Returns "consumed,
                // no keystroke" — fullMatch=null — so the bytes don't get
                // re-fed to other patterns.
                if (action == null) return new Matching(false, null);
                return new Matching(action);
            } else {
                return null;
            }
        }
        return Matching.NOT_YET;
    }

    private MouseAction buildAction(int cb, int cx, int cy, boolean release) {
        int low = cb & 0x03;
        boolean isMove = (cb & 0x20) != 0;
        boolean isScroll = (cb & 0x40) != 0;
        int button;
        MouseActionType type;
        if (isScroll) {
            button = (low == 0) ? 4 : 5;
            type = (low == 0) ? MouseActionType.SCROLL_UP : MouseActionType.SCROLL_DOWN;
        } else if (release) {
            button = (low == 3) ? 0 : low + 1;
            type = MouseActionType.CLICK_RELEASE;
            isMouseDown = false;
        } else if (isMove) {
            // Bare hover (no button held) — drop. CLICK_RELEASE_DRAG semantics
            // don't expect MOVE events; conhost sends them anyway. Use a
            // sentinel button=-1 so the caller can distinguish drop-vs-emit:
            // we MUST consume the bytes (returning null would leave them in
            // the stream to be re-parsed as Escape + literal chars, queueing
            // up garbage keystrokes that hide clicks behind them).
            if (low == 3 && !isMouseDown) return null;
            button = (low == 3) ? 0 : low + 1;
            type = isMouseDown ? MouseActionType.DRAG : MouseActionType.MOVE;
        } else {
            button = (low == 3) ? 0 : low + 1;
            type = MouseActionType.CLICK_DOWN;
            isMouseDown = true;
        }
        // SGR coords are 1-based; Lanterna positions are 0-based.
        return new MouseAction(type, button, new TerminalPosition(cx - 1, cy - 1));
    }
}
