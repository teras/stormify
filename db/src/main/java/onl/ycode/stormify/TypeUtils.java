// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;


import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Utility class for converting between different types and handling exceptions.
 */
@SuppressWarnings("unchecked")
public final class TypeUtils {

    private TypeUtils() {
    }

    // first key: target class
    // second key: source class
    // function: converter from source class to target class
    private static final Map<Class<?>, Map<Class<?>, Function<?, ?>>> registry = new HashMap<>();

    static {
        // Add base numeric values
        Class<?>[] numeric = {Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class, BigDecimal.class, BigInteger.class};

        registry.put(Byte.class, getConv(numeric, (Function<Number, Byte>) Number::byteValue));
        registry.put(Short.class, getConv(numeric, (Function<Number, Short>) Number::shortValue));
        registry.put(Integer.class, getConv(numeric, (Function<Number, Integer>) Number::intValue));
        registry.put(Long.class, getConv(numeric, (Function<Number, Long>) Number::longValue));
        registry.put(Float.class, getConv(numeric, (Function<Number, Float>) Number::floatValue));
        registry.put(Double.class, getConv(numeric, (Function<Number, Double>) Number::doubleValue));

        // Add BigInteger
        Map<Class<?>, Function<?, ?>> bigint = getConv(new Class[]{Byte.class, Short.class, Integer.class, Long.class},
                (Function<Number, BigInteger>) i -> BigInteger.valueOf(i.longValue()));
        addConv(bigint, new Class[]{Float.class, Double.class},
                (Function<Number, BigInteger>) i -> BigDecimal.valueOf(i.doubleValue()).toBigInteger());
        bigint.put(BigDecimal.class, (Function<BigDecimal, BigInteger>) BigDecimal::toBigInteger);
        registry.put(BigInteger.class, bigint);

        // Add BigDecimal
        Map<Class<?>, Function<?, ?>> bigdec = getConv(new Class[]{Byte.class, Short.class, Integer.class, Long.class},
                (Function<Number, BigDecimal>) i -> BigDecimal.valueOf(i.longValue()));
        addConv(bigdec, new Class[]{Float.class, Double.class},
                (Function<Number, BigDecimal>) i -> BigDecimal.valueOf(i.doubleValue()));
        bigdec.put(BigInteger.class, (Function<BigInteger, BigDecimal>) BigDecimal::new);
        registry.put(BigDecimal.class, bigdec);

        // Add Boolean
        Map<Class<?>, Function<?, ?>> toBoolean = new HashMap<>();
        registry.put(Boolean.class, toBoolean);
        toBoolean.put(String.class, v -> Boolean.parseBoolean((String) v));
        toBoolean.put(Byte.class, v -> ((Byte) v) != 0);
        toBoolean.put(Short.class, v -> ((Short) v) != 0);
        toBoolean.put(Integer.class, v -> ((Integer) v) != 0);
        toBoolean.put(Long.class, v -> ((Long) v) != 0);
        toBoolean.put(Float.class, v -> Math.abs((Float) v) > 1e-10);
        toBoolean.put(Double.class, v -> Math.abs((Double) v) > 1e-10);
        toBoolean.put(BigInteger.class, v -> !BigInteger.ZERO.equals(v));
        toBoolean.put(BigDecimal.class, v -> Math.abs(((BigDecimal) v).doubleValue()) > 1e-10);
        registry.get(Byte.class).put(Boolean.class, v -> (boolean) v ? (byte) 1 : (byte) 0);
        registry.get(Short.class).put(Boolean.class, v -> (boolean) v ? (short) 1 : (short) 0);
        registry.get(Integer.class).put(Boolean.class, v -> (boolean) v ? 1 : 0);
        registry.get(Long.class).put(Boolean.class, v -> (boolean) v ? 1L : 0L);
        registry.get(Float.class).put(Boolean.class, v -> (boolean) v ? 1f : 0f);
        registry.get(Double.class).put(Boolean.class, v -> (boolean) v ? 1d : 0d);
        registry.get(BigInteger.class).put(Boolean.class, v -> (boolean) v ? BigInteger.ONE : BigInteger.ZERO);
        registry.get(BigDecimal.class).put(Boolean.class, v -> (boolean) v ? BigDecimal.ONE : BigDecimal.ZERO);


        // Add Character
        Map<Class<?>, Function<?, ?>> charv = new HashMap<>();
        charv.put(String.class, (String s) -> s == null || s.isEmpty() ? null : s.charAt(0));
        registry.put(Character.class, charv);

        // Add arrays
        Map<Class<?>, Function<?, ?>> toBytes = new HashMap<>();
        registry.put(byte[].class, toBytes);
        toBytes.put(String.class, (String s) -> s.getBytes(UTF_8));

        Map<Class<?>, Function<?, ?>> fromBytes = new HashMap<>();
        registry.put(String.class, fromBytes);
        fromBytes.put(byte[].class, (byte[] b) -> new String(b, UTF_8));

        Map<Class<?>, Function<?, ?>> toChars = new HashMap<>();
        registry.put(char[].class, toChars);
        toChars.put(String.class, (String s) -> s.toCharArray());

        Map<Class<?>, Function<?, ?>> fromChars = new HashMap<>();
        registry.put(String.class, fromChars);
        fromChars.put(char[].class, (char[] c) -> new String(c));


        // Add date-related
        registerTimeRelated(Date.class, Date::new);
        registerTimeRelated(java.sql.Date.class, java.sql.Date::new);
        registerTimeRelated(Timestamp.class, Timestamp::new);
        registerTimeRelated(Time.class, Time::new);
        registerTimeRelated(LocalDateTime.class, time -> Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDateTime());
        registerTimeRelated(LocalDate.class, time -> Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate());
        registerTimeRelated(LocalTime.class, time -> Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalTime());
        registerTimeRelated(Long.class, time -> time);
        registerTimeRelated(Double.class, time -> time / 1000d);
        registerTimeRelated(Float.class, time -> time / 1000f);
        registerTimeRelated(BigInteger.class, BigInteger::valueOf);
        registerTimeRelated(BigDecimal.class, BigDecimal::valueOf);
        registerTimeRelated(String.class, time -> Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_INSTANT));
    }

    /**
     * Convert a value to the target class
     *
     * @param targetClass the target class
     * @param value       the value to convert
     * @param <F>         the source class type
     * @param <T>         the target class type
     * @return the converted value
     */
    public static <F, T> T castTo(Class<T> targetClass, F value) {
        if (value == null) return null;
        requireNonNull(targetClass, "Target class should not be null");
        Class<T> boxedTargetClass = targetClass.isPrimitive() ? (Class<T>) getWrapper(targetClass) : targetClass;
        Class<?> givenClass = value.getClass();
        if (boxedTargetClass.isAssignableFrom(givenClass)) return (T) value;
        Function<Object, ?> typeconv = (Function<Object, ?>) getOrThrow(() -> registry.get(boxedTargetClass), () -> "Target class " + boxedTargetClass.getName() + " is not convertible")
                .get(givenClass);
        if (typeconv == null && boxedTargetClass == String.class) // if no special conversion is present, use the default toString mechanism
            typeconv = Object::toString;
        if (typeconv == null)
            throw new QueryException("Unable to convert " + givenClass.getName() + " to " + boxedTargetClass.getName());
        try {
            return (T) typeconv.apply(value);
        } catch (Throwable th) {
            throw new QueryException("Error while trying to convert from " + value.getClass().getName() + " to " + boxedTargetClass.getName(), th);
        }
    }

    /**
     * Get the value of a supplier or throw an exception if the value is null
     *
     * @param supplier the supplier to get the value from
     * @param message  the message to use in the exception
     * @param <T>      the type of the value
     * @return the value
     */
    static <T> T getOrThrow(SafeSupplier<T> supplier, Supplier<String> message) {
        T result;
        try {
            result = supplier.get();
        } catch (QueryException e) {
            throw e;
        } catch (Throwable e) {
            throw new QueryException("Missing item", e);
        }
        if (result == null)
            throw new QueryException(message.get());
        return result;
    }

    private static Map<Class<?>, Function<?, ?>> getConv(Class<?>[] acceptedTypes, Function<?, ?> converter) {
        Map<Class<?>, Function<?, ?>> converters = addConv(new HashMap<>(), acceptedTypes, converter);
        converters.put(String.class, input -> ((Function<Number, Object>) converter).apply(new BigDecimal(input.toString())));
        return converters;
    }

    private static Map<Class<?>, Function<?, ?>> addConv(Map<Class<?>, Function<?, ?>> result, Class<?>[] acceptedTypes, Function<?, ?> converter) {
        for (Class<?> cls : acceptedTypes)
            result.put(cls, converter);
        return result;
    }

    private static void registerTimeRelated(Class<?> destClass, LongFunction<?> toNative) {
        Map<Class<?>, Function<?, ?>> converters;
        boolean isCore = Number.class.isAssignableFrom(destClass) || CharSequence.class.isAssignableFrom(destClass);
        if (isCore)
            converters = getOrThrow(() -> registry.get(destClass), () -> "Unable to access converter for " + destClass.getName());
        else {
            converters = new HashMap<>();
            registry.put(destClass, converters);
        }
        if (destClass != Date.class)
            converters.put(Date.class, d -> toNative.apply(((Date) d).getTime()));
        if (destClass != java.sql.Date.class)
            converters.put(java.sql.Date.class, d -> toNative.apply(((java.sql.Date) d).getTime()));
        if (destClass != Timestamp.class)
            converters.put(Timestamp.class, d -> toNative.apply(((Timestamp) d).getTime()));
        if (destClass != Time.class) converters.put(Time.class, d -> toNative.apply(((Time) d).getTime()));
        if (destClass != LocalDateTime.class)
            converters.put(LocalDateTime.class, d -> toNative.apply(((LocalDateTime) d).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        if (destClass != LocalDate.class)
            converters.put(LocalDate.class, d -> toNative.apply(((LocalDate) d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        if (destClass != LocalTime.class)
            converters.put(LocalTime.class, d -> toNative.apply(((LocalTime) d).atDate(LocalDate.now()).toInstant(ZonedDateTime.now().getOffset()).toEpochMilli()));
        if (!isCore) {
            converters.put(Long.class, n -> toNative.apply((Long) n));
            converters.put(BigInteger.class, n -> toNative.apply(((BigInteger) n).longValue()));
            converters.put(BigDecimal.class, n -> toNative.apply(((BigDecimal) n).longValue()));
            converters.put(Double.class, n -> toNative.apply(Math.round(((Double) n) * 1000d)));
            converters.put(Float.class, n -> toNative.apply(Math.round(((Float) n) * 1000d)));
            converters.put(String.class, n -> toNative.apply(Instant.parse((String) n).toEpochMilli()));
        }
    }


    /**
     * Register a conversion function from sourceClass to targetClass.
     * This function will provide custom conversion between classes, when casting objects of
     * different types.
     *
     * @param sourceClass the source class that needs to be converted
     * @param targetClass the target class that the data should be converted to
     * @param converter   the function that will convert the data
     * @param <F>         the source class type
     * @param <T>         the target class type
     * @return the previous conversion function if it was already registered
     */
    public static <F, T> Function<F, T> registerConversion(Class<F> sourceClass, Class<T> targetClass, Function<F, T> converter) {
        return (Function<F, T>) registry.computeIfAbsent(targetClass, k -> new HashMap<>()).put(sourceClass, converter);
    }

    /**
     * Get the boxed type class for a primitive class
     *
     * @param primitive the primitive class
     * @return the boxed class
     */
    public static Class<?> getWrapper(Class<?> primitive) {
        if (!primitive.isPrimitive())
            return primitive;
        if (primitive == byte.class) return Byte.class;
        if (primitive == short.class) return Short.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == char.class) return Character.class;
        throw new QueryException("Unknown primitive type " + primitive.getName());
    }

    static int convertJavaTypeToSQLType(Class<?> javaType) {
        if (javaType == null) {
            throw new NullPointerException("No type provided");
        } else if (javaType == byte.class || javaType == Byte.class || javaType == short.class || javaType == Short.class) {
            return Types.SMALLINT;
        } else if (javaType == int.class || javaType == Integer.class) {
            return Types.INTEGER;
        } else if (javaType == long.class || javaType == Long.class || javaType == java.math.BigInteger.class) {
            return Types.BIGINT;
        } else if (javaType == float.class || javaType == Float.class) {
            return Types.REAL;
        } else if (javaType == double.class || javaType == Double.class) {
            return Types.DOUBLE;
        } else if (javaType == java.math.BigDecimal.class) {
            return Types.NUMERIC;
        } else if (javaType == boolean.class || javaType == Boolean.class) {
            return Types.BOOLEAN;
        } else if (javaType == String.class || javaType == char.class || javaType == Character.class) {
            return Types.VARCHAR;
        } else if (javaType == byte[].class) {
            return Types.BLOB;
        } else if (javaType == java.util.Date.class || javaType == java.sql.Timestamp.class || javaType == java.time.LocalDateTime.class || javaType == java.util.Calendar.class) {
            return Types.TIMESTAMP;
        } else if (javaType == java.sql.Date.class || javaType == java.time.LocalDate.class) {
            return Types.DATE;
        } else if (javaType == java.sql.Time.class || javaType == java.time.LocalTime.class) {
            return Types.TIME;
        } else {
            throw new QueryException("Java type " + javaType.getName() + " can not be converted to SQL Type");
        }
    }

    static Class<?> normalizeClass(Class<?> clazz) {
        while (clazz != null) {
            String name = clazz.getSimpleName();
            if (!name.isEmpty() && !Modifier.isAbstract(clazz.getModifiers()) && !name.startsWith("java.") && !name.startsWith("javax."))
                return clazz;
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}
