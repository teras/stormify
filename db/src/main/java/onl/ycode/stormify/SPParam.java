// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

/**
 * This class is used to pass parameters to stored procedures.
 *
 * @param <T> The type of the parameter.
 */
public class SPParam<T> {

    private final Class<T> type;
    private final Object value;
    private final Mode mode;
    private Object result;

    SPParam(Class<T> type, Object value, Mode mode) {
        this.type = type;
        this.value = value;
        this.mode = mode;
    }

    Class<T> getType() {
        return type;
    }

    Mode getMode() {
        return mode;
    }

    Object getValue() {
        return value;
    }

    void setResult(Object result) {
        this.result = result == null || result.toString().equals("null") ? null : result;
    }

    /**
     * Get the result of the stored procedure.
     *
     * @return The result of the stored procedure.
     */
    @SuppressWarnings("unchecked")
    public T getResult() {
        return (T) result;
    }

    enum Mode {
        IN, OUT, INOUT
    }

    @Override
    public String toString() {
        return mode.name() + (mode == Mode.OUT ? "" : ":" + value);
    }

    /**
     * Create a new IN parameter.
     *
     * @param type  The type of the parameter.
     * @param value The value of the parameter.
     * @param <T>   The type of the parameter.
     * @return The new parameter.
     */
    public static <T> SPParam<T> in(Class<T> type, T value) {
        return new SPParam<>(type, value, Mode.IN);
    }

    /**
     * Create a new OUT parameter.
     *
     * @param type The type of the parameter.
     * @param <T>  The type of the parameter.
     * @return The new parameter.
     */
    public static <T> SPParam<T> out(Class<T> type) {
        return new SPParam<>(type, null, Mode.OUT);
    }

    /**
     * Create a new INOUT parameter.
     *
     * @param type  The type of the parameter.
     * @param value The value of the parameter.
     * @param <T>   The type of the parameter.
     * @return The new parameter.
     */
    public static <T> SPParam<T> inout(Class<T> type, T value) {
        return new SPParam<>(type, value, Mode.INOUT);
    }

}
