// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class Utils {
    private Utils() {
    }

    static int count(String container, char searchable) {
        if (container == null || container.isEmpty())
            return 0;
        int count = 0;
        for (int i = 0; i < container.length(); i++)
            if (container.charAt(i) == searchable)
                count++;
        return count;
    }

    static String nCopies(String base, String delimeter, int count) {
        if (count <= 0)
            return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(base);
            if (i < count - 1) {
                sb.append(delimeter);
            }
        }
        return sb.toString();
    }

    static String camelToSnake(String str, boolean upper) {
        StringBuilder result = new StringBuilder();
        for (char c : str.toCharArray()) {
            if (Character.isUpperCase(c) && result.length() > 0) {
                result.append('_');
                result.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            } else
                result.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return result.toString();
    }

    static <F, T> List<T> map(Iterable<F> from, Function<F, T> convertor) {
        if (from == null || convertor == null) return Collections.emptyList();
        ArrayList<T> result = new ArrayList<>();
        for (F item : from)
            result.add(convertor.apply(item));
        return result;
    }

    static <F, T> Object[] mapToArray(List<F> from, Function<F, T> convertor, List<T> additional) {
        if (from == null || convertor == null) return new Object[0];
        Object[] result = new Object[from.size() + (additional == null ? 0 : additional.size())];
        int fromSize = from.size();
        for (int i = 0; i < fromSize; i++)
            result[i] = convertor.apply(from.get(i));
        if (additional != null)
            for (int j = 0; j < additional.size(); j++)
                result[fromSize + j] = additional.get(j);
        return result;
    }

    static <T> List<T> filter(Iterable<T> from, Predicate<T> filter) {
        if (from == null || filter == null) return Collections.emptyList();
        ArrayList<T> result = new ArrayList<>();
        for (T item : from)
            if (filter.test(item))
                result.add(item);
        return result;
    }

    static <T> T extract(Collection<T> data, Predicate<T> filter) {
        Iterator<T> iterator = data.iterator();
        while (iterator.hasNext()) {
            T item = iterator.next();
            if (filter.test(item)) {
                iterator.remove();
                return item;
            }
        }
        return null;
    }

    static boolean isBaseClass(Class<?> request) {
        if (request == null)
            return true;
        if (request.isArray() || Iterable.class.isAssignableFrom(request) || Map.class.isAssignableFrom(request))
            return false;
        return request.isPrimitive() || request.getName().startsWith("java.") || request.getName().startsWith("javax.");
    }

    static final class LazyProperty<T> {
        private final Supplier<T> initializer;
        private T value;
        private boolean isSet;

        LazyProperty(Supplier<T> initializer) {
            this.initializer = initializer;
        }

        T get() {
            if (!isSet) {
                synchronized (this) {
                    if (!isSet) {
                        value = initializer.get();
                        isSet = true;
                    }
                }
            }
            return value;
        }
    }
}
