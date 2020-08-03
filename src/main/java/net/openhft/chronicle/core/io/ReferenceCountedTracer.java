package net.openhft.chronicle.core.io;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.StackTrace;
import net.openhft.chronicle.core.onoes.ExceptionHandler;
import org.jetbrains.annotations.NotNull;

public interface ReferenceCountedTracer extends ReferenceCounted {
    @NotNull
    static ReferenceCountedTracer onReleased(final Runnable onRelease, String uniqueId, ExceptionHandler warn) {
        return Jvm.isResourceTracing()
                ? new DualReferenceCounted(
                new TracingReferenceCounted(onRelease, uniqueId, warn),
                new VanillaReferenceCounted(() -> {
                }, warn))
                : new VanillaReferenceCounted(onRelease, warn);
    }

    default void throwExceptionIfReleased() throws IllegalStateException {
        if (refCount() <= 0)
            throw new IllegalStateException("Released");
    }

    void warnAndReleaseIfNotReleased();

    void throwExceptionIfNotReleased();

    StackTrace createdHere();
}
