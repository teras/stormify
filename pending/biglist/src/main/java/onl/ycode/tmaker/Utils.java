package onl.ycode.tmaker;

import onl.ycode.stormify.SafeSupplier;

import static onl.ycode.stormify.StormifyManager.stormify;

class Utils {
    /**
     * Execute a statement that throws an exception and log the exception if it occurs.
     * <p>
     * When the exception occurs, the function will return null.
     * This is useful for handling non-important exceptions in a safe way.
     * The exception will be logged using the logger of the controller.
     *
     * @param supplier the supplier to execute
     * @param <T>      the type of the result
     * @return the result of the supplier or null if an exception occurs
     */
    static <T> T silence(SafeSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable e) {
            stormify().getLogger().error(e.getMessage(), e);
            return null;
        }
    }
}
