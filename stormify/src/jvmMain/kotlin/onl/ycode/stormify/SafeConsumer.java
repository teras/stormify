package onl.ycode.stormify;

public interface SafeConsumer<T> {
    void accept(T t) throws Throwable;
}
