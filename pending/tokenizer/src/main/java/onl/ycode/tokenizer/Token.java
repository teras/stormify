// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

abstract class Token<T> {
    static final boolean LEFT = true;
    static final boolean RIGHT = !LEFT;

    final int start;
    final int end;
    final String name;
    private final boolean indent;

    protected Token(int start, int end, String name, boolean indent) {
        if (start < 0)
            throw new IllegalArgumentException("Token start could not be smaller than zero");
        if (start >= end)
            throw new IllegalArgumentException("Token start (" + start + ") shouldcould not be bigger than end (" + end + ")");
        this.start = start;
        this.end = end;
        this.name = name == null ? "" : name;
        this.indent = indent;
    }

    T get(String line) throws TokenizerException {
        if (end > line.length())
            throw new TokenizerException("Token end (" + end + ") should be smaller than line length (" + line.length() + ")");
        String data = line.substring(start, end);
        return asData(data);
    }

    String toString(Object object) throws TokenizerException {
        String result = asString(object);
        if (result == null)
            result = "";
        if (result.length() > (end - start))
            throw new TokenizerException("The size of produced record data is bigger than the provided slot for field " + name + "; provided " + result.length() + ", required " + (end - start) +
                    ", from '" + object + "' as string '" + result + "'");
        String padding = TokenUtils.repetitions(' ', end - start - result.length());
        return indent == LEFT ? result + padding : padding + result;
    }

    @Override
    public String toString() {
        return "Token[" + (name.isEmpty() ? "" : name + " ") + start + ", " + end + " (" + (end - start) + ")]";
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public int getSize() {
        return end - start;
    }

    public String getName() {
        return name;
    }

    String getFieldName() {
        int dotLoc = name.indexOf(TPart.COLON);
        return dotLoc < 0
                ? name
                : name.substring(0, dotLoc);
    }

    protected abstract T asData(String data) throws TokenizerException;

    protected abstract String asString(Object data) throws TokenizerException;
}
