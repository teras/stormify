package com.googlecode.lanterna.terminal.win32;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.function.Consumer;

import com.googlecode.lanterna.terminal.win32.WinDef.INPUT_RECORD;
import com.googlecode.lanterna.terminal.win32.WinDef.KEY_EVENT_RECORD;
import com.googlecode.lanterna.terminal.win32.WinDef.MOUSE_EVENT_RECORD;
import com.googlecode.lanterna.terminal.win32.WinDef.WINDOW_BUFFER_SIZE_RECORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

public class WindowsConsoleInputStream extends InputStream {

	private final HANDLE hConsoleInput;
	private final Charset encoderCharset;
	private ByteBuffer buffer = ByteBuffer.allocate(0);

	public WindowsConsoleInputStream(Charset encoderCharset) {
		this(Wincon.INSTANCE.GetStdHandle(Wincon.STD_INPUT_HANDLE), encoderCharset);
	}

	public WindowsConsoleInputStream(HANDLE hConsoleInput, Charset encoderCharset) {
		this.hConsoleInput = hConsoleInput;
		this.encoderCharset = encoderCharset;
	}

	public HANDLE getHandle() {
		return hConsoleInput;
	}

	public Charset getEncoderCharset() {
		return encoderCharset;
	}

	private INPUT_RECORD[] readConsoleInput() throws IOException {
		INPUT_RECORD[] lpBuffer = new INPUT_RECORD[64];
		IntByReference lpNumberOfEventsRead = new IntByReference();
		if (Wincon.INSTANCE.ReadConsoleInput(hConsoleInput, lpBuffer, lpBuffer.length, lpNumberOfEventsRead)) {
			int n = lpNumberOfEventsRead.getValue();
			return Arrays.copyOfRange(lpBuffer, 0, n);
		}
		throw new EOFException();
	}

	private int availableConsoleInput() {
		IntByReference lpcNumberOfEvents = new IntByReference();
		if (Wincon.INSTANCE.GetNumberOfConsoleInputEvents(hConsoleInput, lpcNumberOfEvents)) {
			return lpcNumberOfEvents.getValue();
		}
		return 0;
	}

	@Override
	public synchronized int read() throws IOException {
		while (!buffer.hasRemaining()) {
			buffer = readKeyEvents(true);
		}

		return buffer.get();
	}

	@Override
	public synchronized int read(byte[] b, int offset, int length) throws IOException {
		while (length > 0 && !buffer.hasRemaining()) {
			buffer = readKeyEvents(true);
		}

		int n = Math.min(buffer.remaining(), length);
		buffer.get(b, offset, n);
		return n;
	}

	@Override
	public synchronized int available() throws IOException {
		if (buffer.hasRemaining()) {
			return buffer.remaining();
		}

		buffer = readKeyEvents(false);
		return buffer.remaining();
	}

	private ByteBuffer readKeyEvents(boolean blocking) throws IOException {
		StringBuilder keyEvents = new StringBuilder();

		if (blocking || availableConsoleInput() > 0) {
			for (INPUT_RECORD i : readConsoleInput()) {
				filter(i, keyEvents);
			}
		}

		// Strip SGR mouse hover sequences (CSI < Cb=35 ; X ; Y M — the bare
		// no-button motion event) right here, before the bytes ever reach
		// Lanterna's InputDecoder. conhost emits a flood of these whenever
		// the user moves the cursor and Windows' VT translation forwards
		// every one of them. Even though our SgrMouseCharacterPattern would
		// drop them, the per-stroke decode loop processes ~12 bytes at a
		// time and visibly lags behind a click that lands at the back of a
		// large hover queue.
		stripSgrHoverEvents(keyEvents);

		return encoderCharset.encode(CharBuffer.wrap(keyEvents));
	}

	private static void stripSgrHoverEvents(StringBuilder buf) {
		int i = 0;
		while (i < buf.length() - 4) {
			if (buf.charAt(i) != 0x1B || buf.charAt(i + 1) != '[' || buf.charAt(i + 2) != '<') {
				i++;
				continue;
			}
			// Parse `\e[<Cb;...M|m`. Pull Cb, scan to terminator.
			int cbStart = i + 3;
			int cbEnd = cbStart;
			while (cbEnd < buf.length() && buf.charAt(cbEnd) >= '0' && buf.charAt(cbEnd) <= '9') cbEnd++;
			if (cbEnd == cbStart || cbEnd >= buf.length() || buf.charAt(cbEnd) != ';') { i++; continue; }
			int term = cbEnd + 1;
			while (term < buf.length() && buf.charAt(term) != 'M' && buf.charAt(term) != 'm') term++;
			if (term >= buf.length()) break; // incomplete tail; leave for next read
			int cb;
			try { cb = Integer.parseInt(buf.substring(cbStart, cbEnd)); }
			catch (NumberFormatException e) { i++; continue; }
			// Cb=35 → motion bit (0x20) + button-state-bits 11 (no button).
			// All other values (presses, releases, drags, scroll) pass through.
			if (cb == 35 && buf.charAt(term) == 'M') {
				buf.delete(i, term + 1);
			} else {
				i = term + 1;
			}
		}
	}

	private void filter(INPUT_RECORD input, Appendable keyEvents) throws IOException {
		switch (input.EventType) {
		case INPUT_RECORD.KEY_EVENT:
			if (input.Event.KeyEvent.uChar != 0 && input.Event.KeyEvent.bKeyDown) {
				keyEvents.append(input.Event.KeyEvent.uChar);
			}
			if (keyEventHandler != null) {
				keyEventHandler.accept(input.Event.KeyEvent);
			}
			break;
		case INPUT_RECORD.MOUSE_EVENT:
			if (mouseEventHandler != null) {
				mouseEventHandler.accept(input.Event.MouseEvent);
			}
			break;
		case INPUT_RECORD.WINDOW_BUFFER_SIZE_EVENT:
			if (windowBufferSizeEventHandler != null) {
				windowBufferSizeEventHandler.accept(input.Event.WindowBufferSizeEvent);
			}
			break;
		}
	}

	private Consumer<KEY_EVENT_RECORD> keyEventHandler = null;
	private Consumer<MOUSE_EVENT_RECORD> mouseEventHandler = null;
	private Consumer<WINDOW_BUFFER_SIZE_RECORD> windowBufferSizeEventHandler = null;

	public void onKeyEvent(Consumer<KEY_EVENT_RECORD> handler) {
		if (keyEventHandler == null) {
			keyEventHandler = handler;
		} else {
			keyEventHandler = keyEventHandler.andThen(handler);
		}
	}

	public void onMouseEvent(Consumer<MOUSE_EVENT_RECORD> handler) {
		if (mouseEventHandler == null) {
			mouseEventHandler = handler;
		} else {
			mouseEventHandler = mouseEventHandler.andThen(handler);
		}
	}

	public void onWindowBufferSizeEvent(Consumer<WINDOW_BUFFER_SIZE_RECORD> handler) {
		if (windowBufferSizeEventHandler == null) {
			windowBufferSizeEventHandler = handler;
		} else {
			windowBufferSizeEventHandler = windowBufferSizeEventHandler.andThen(handler);
		}
	}

}
