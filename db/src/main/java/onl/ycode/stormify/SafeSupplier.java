// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

/**
 * A functional interface that can be used to get a value, and throw an exception if an error occurs.
 *
 * @param <T> The type of the value.
 */
public interface SafeSupplier<T> {
    /**
     * Get a value.
     *
     * @return The value.
     * @throws Throwable If an error occurs.
     */
    T get() throws Throwable;
}
