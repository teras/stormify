// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

/**
 * A functional interface that can be used to run code that may throw an exception.
 * This interface is used when defining a transaction context.
 * <p>
 * If an exception is thrown while running the code, the transaction will be rolled back.
 */
public interface SafeRunnable {
    /**
     * The code inside the transaction that may throw an exception.
     *
     * @throws Exception If an error occurs while running the code.
     */
    void run() throws Exception;
}
