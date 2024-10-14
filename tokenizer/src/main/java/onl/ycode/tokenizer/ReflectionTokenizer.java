// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tokenizer;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.time.temporal.Temporal;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.*;

/**
 * ReflectionTokenizer is a utility class that provides methods to tokenize a string into an object and vice versa.
 */
@SuppressWarnings("unchecked")
public class ReflectionTokenizer {

    private static final Class<Annotation> SIZE_ANN;
    private static final Method SIZE_MET;
    private static final Map<Class<?>, Map<String, Tokenizer>> tokenizers = new HashMap<>();

    static {
        Class<Annotation> foundC = null;
        Method foundM = null;
        try {
            foundC = (Class<Annotation>) Class.forName("javax.validation.constraints.Size");
            foundM = foundC.getMethod("max");
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException ignore) {
        }
        SIZE_ANN = foundC;
        SIZE_MET = foundM;
    }

    /**
     * Returns a collection of contexts for the given class.
     *
     * @param cls the class to get the contexts from
     * @return a collection of contexts
     */
    public static Collection<String> getContexts(Class<?> cls) {
        Collection<String> result = new TreeSet<>();
        for (Field f : cls.getDeclaredFields()) {
            MTokenizer tokenizer = f.getAnnotation(MTokenizer.class);
            if (tokenizer != null)
                result.add(tokenizer.context());
        }
        return result;
    }

    /**
     * Returns a tokenizer for the given class.
     *
     * @param cls the class to get the tokenizer from
     * @return the tokenizer
     * @throws TokenizerException if the tokenizer is invalid
     */
    public static Tokenizer getTokenizer(Class<?> cls) throws TokenizerException {
        return getTokenizer(cls, MTokenizer.DEFAULT_BASE);
    }

    /**
     * Returns a tokenizer for the given class and context.
     *
     * @param cls     the class to get the tokenizer from
     * @param context the context to get the tokenizer from
     * @return the tokenizer
     * @throws TokenizerException if the tokenizer is invalid
     */
    public static Tokenizer getTokenizer(Class<?> cls, String context) throws TokenizerException {
        Map<String, Tokenizer> tokc = tokenizers.computeIfAbsent(cls, k -> new HashMap<>());
        Tokenizer tk = tokc.get(context);
        if (tk == null) {
            tk = getTokenizerImpl(cls, context);
            tokc.put(context, tk);
        }
        return tk;
    }

    private static Tokenizer getTokenizerImpl(Class<?> cls, String context) throws TokenizerException {
        Map<Integer, TPart> parts = new TreeMap<>();
        int idx = 0;
        for (Field f : cls.getDeclaredFields()) {
            MTokenizer tokenizer = f.getAnnotation(MTokenizer.class);
            if (tokenizer == null)
                continue;
            if (!tokenizer.context().equals(context))
                continue;
            idx = tokenizer.index() > 0 ? tokenizer.index() : idx + 1;
            int size = tokenizer.size();
            if (tokenizer.gap() > 0 && tokenizer.start() >= 0)
                throw new TokenizerException("Tokenizer defined gap and start for field " + f.getName() + " is not possible.");
            if (SIZE_MET != null) {
                Annotation ann = f.getAnnotation(SIZE_ANN);
                if (ann != null)
                    try {
                        Object result = SIZE_MET.invoke(ann);
                        int sizeMax = Integer.MAX_VALUE;
                        try {
                            if (result != null)
                                sizeMax = Integer.parseInt(result.toString());
                        } catch (NumberFormatException ignored) {
                        }
                        if (size < 0) size = sizeMax;
                        if (sizeMax != size)
                            throw new TokenizerException("Tokenizer defined size of " + tokenizer.size() + " for field " + f.getName() + ", does not match maximum size " + sizeMax + ".");
                    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ignored) {
                    }
            }
            if (size <= 0)
                throw new TokenizerException("Tokenizer defined size for field " + f.getName() + " is not valid.");
            parts.put(idx, new TPart(f, tokenizer.start(), tokenizer.gap(), size, tokenizer.dateFormat(), tokenizer.decimals(), tokenizer.indent()));
        }
        if (parts.isEmpty())
            throw new TokenizerException("No Tokenizers found");

        int cursor = 0;
        int vIdx = 0;
        Tokenizer tk = new Tokenizer();
        for (Map.Entry<Integer, TPart> entry : parts.entrySet()) {
            vIdx++;
            int index = entry.getKey();
            TPart p = entry.getValue();
            if (vIdx != index)
                throw new TokenizerException("Field with name " + p.name + " has wrong index number, expecting " + cursor + ", found " + index);
            int from = p.from < 0 ? cursor + p.gap : p.from;
            int to = from + p.size;
            cursor = to;
            if (Date.class.isAssignableFrom(p.type) || Temporal.class.isAssignableFrom(p.type))
                tk.date(from, to, p.dateformat, p.name, p.indent);
            else if (double.class.equals(p.type) || Double.class.equals(p.type) || float.class.equals(p.type) || Float.class.equals(p.type) || BigDecimal.class.equals(p.type))
                tk.decimal(p.decimals, from, to, p.name, p.indent);
            else if (byte.class.equals(p.type) || Byte.class.equals(p.type) || short.class.equals(p.type) || Short.class.equals(p.type) || Integer.class.equals(p.type) || int.class.equals(p.type) || Long.class.equals(p.type) || long.class.equals(p.type) || BigInteger.class.equals(p.type))
                tk.integral(from, to, p.name, p.indent);
            else if (Character.class.equals(p.type) || char.class.equals(p.type) || String.class.equals(p.type))
                tk.text(from, to, p.name, p.indent);
            else
                throw new TokenizerException("Unable to tokenize fiels with type " + p.type.getName());
        }
        return tk.fix();
    }

    /**
     * Returns an object of the given class from the input string.
     *
     * @param objType the class of the object
     * @param input   the input string
     * @param <T>     the type of the object
     * @return the object
     * @throws TokenizerException if the object is invalid
     */
    public static <T> T getObject(Class<T> objType, String input) throws TokenizerException {
        return getObject(objType, null, input);
    }

    /**
     * Returns an object of the given class from the input string and context.
     *
     * @param objType the class of the object
     * @param context the context of the object
     * @param input   the input string
     * @param <T>     the type of the object
     * @return the object
     * @throws TokenizerException if the object is invalid
     */
    @SuppressWarnings("UseSpecificCatch")
    public static <T> T getObject(Class<T> objType, String context, String input) throws TokenizerException {
        T nobj;
        try {
            nobj = objType.getConstructor().newInstance();
        } catch (Exception ex) {
            throw new TokenizerException(ex.getMessage());
        }
        updateObject(nobj, context, input);
        return nobj;
    }

    /**
     * Updates the object with the input string.
     *
     * @param obj   the object to update
     * @param input the input string
     * @throws TokenizerException if the object is invalid
     */
    public static void updateObject(Object obj, String input) throws TokenizerException {
        updateObject(obj, null, input);
    }

    /**
     * Updates the object with the input string and context.
     *
     * @param obj     the object to update
     * @param context the context of the object
     * @param input   the input string
     * @throws TokenizerException if the object is invalid
     */
    @SuppressWarnings("UseSpecificCatch")
    public static void updateObject(Object obj, String context, String input) throws TokenizerException {
        if (obj == null)
            return;
        if (context == null)
            context = MTokenizer.DEFAULT_BASE;
        Tokenizer tokenizer = getTokenizer(obj.getClass(), context);
        if (input.length() != tokenizer.size())
            throw new TokenizerException("Input size does not match tokenizer size for object " + obj.getClass().getName() + ": input=" + input.length() + " expecting=" + tokenizer.size());
        for (Token<?> tk : tokenizer.getTokens())
            try {
                Object val = tk.get(input);
                if (val == null)
                    continue;

                Field f = obj.getClass().getDeclaredField(tk.getFieldName());
                f.setAccessible(true);
                Class<?> type = f.getType();

                if (byte.class.equals(type) || Byte.class.equals(type))
                    f.set(obj, ((BigInteger) val).byteValue());
                else if (short.class.equals(type) || Short.class.equals(type))
                    f.set(obj, ((BigInteger) val).shortValue());
                else if (int.class.equals(type) || Integer.class.equals(type))
                    f.set(obj, ((BigInteger) val).intValue());
                else if (long.class.equals(type) || Long.class.equals(type))
                    f.set(obj, ((BigInteger) val).longValue());
                else if (BigInteger.class.equals(type))
                    f.set(obj, val);

                else if (float.class.equals(type) || Float.class.equals(type))
                    f.set(obj, ((BigDecimal) val).floatValue());
                else if (double.class.equals(type) || Double.class.equals(type))
                    f.set(obj, ((BigDecimal) val).doubleValue());
                else if (BigDecimal.class.equals(type))
                    f.set(obj, val);

                else if (char.class.equals(type))
                    f.set(obj, val.toString().isEmpty() ? 0 : val.toString().charAt(0));
                else if (Character.class.equals(type))
                    f.set(obj, val.toString().isEmpty() ? null : val.toString().charAt(0));
                else if (CharSequence.class.isAssignableFrom(type))
                    f.set(obj, val.toString());

                else if (Date.class.isAssignableFrom(type))
                    f.set(obj, val);
                else if (Temporal.class.isAssignableFrom(type)) {
                    ZonedDateTime zonedDateTime = ((Date) val).toInstant().atZone(ZoneId.systemDefault());
                    if (LocalDateTime.class.isAssignableFrom(type))
                        f.set(obj, zonedDateTime.toLocalDateTime());
                    else if (LocalDate.class.isAssignableFrom(type))
                        f.set(obj, zonedDateTime.toLocalDate());
                    else if (LocalTime.class.isAssignableFrom(type))
                        f.set(obj, zonedDateTime.toLocalTime());
                    else throw new UnsupportedTemporalTypeException("Unsupported temporal type " + type.getName());
                } else
                    throw new IllegalArgumentException("Unsupported field type " + type.getName());
            } catch (Exception ex) {
                throw new TokenizerException("Unable to set value of field " + tk.getFieldName() + " of object " + obj.getClass().getName(), ex);
            }
    }

    /**
     * Returns the object as a tokenized string.
     *
     * @param input the object to get the output from
     * @return the output string
     * @throws TokenizerException if the object is invalid
     */
    public static String getOutput(Object input) throws TokenizerException {
        return getOutput(input, null);
    }

    /**
     * Returns the object as a tokenized string with the given context.
     *
     * @param input   the object to get the output from
     * @param context the context of the object
     * @return the output string
     * @throws TokenizerException if the object is invalid
     */
    public static String getOutput(Object input, String context) throws TokenizerException {
        if (input == null)
            return null;
        if (context == null)
            context = MTokenizer.DEFAULT_BASE;
        Tokenizer tk = getTokenizer(input.getClass(), context);
        Collection<Object> params = new ArrayList<>();
        for (Token<?> t : tk.getTokens())
            try {
                Field f = input.getClass().getDeclaredField(t.getFieldName());
                f.setAccessible(true);
                params.add(f.get(input));
            } catch (Exception ex) {
                throw new TokenizerException("Unable to create output for field " + t.getFieldName(), ex);
            }
        String output = tk.output(params.toArray());
        if (output.length() != tk.size())
            throw new TokenizerException("Output size does not match tokenizer size");
        return output;
    }

    private ReflectionTokenizer() {
    }
}
