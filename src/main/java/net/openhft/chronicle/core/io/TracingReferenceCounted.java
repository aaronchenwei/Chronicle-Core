/*
 * Copyright (c) 2016-2019 Chronicle Software Ltd
 */

package net.openhft.chronicle.core.io;

import net.openhft.chronicle.core.StackTrace;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class TracingReferenceCounted implements ReferenceCountedTracer {
    private final Map<ReferenceOwner, StackTrace> references = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<ReferenceOwner, StackTrace> releases = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Runnable onRelease;
    private final String uniqueId;
    private final StackTrace createdHere;
    private volatile StackTrace releasedHere;

    TracingReferenceCounted(final Runnable onRelease, String uniqueId) {
        this.onRelease = onRelease;
        this.uniqueId = uniqueId;
        createdHere = stackTrace("init", INIT);
        references.put(INIT, createdHere);
    }

    static String asString(Object id) {
        if (id == INIT) return "INIT";
        String s = id instanceof ReferenceOwner
                ? ((ReferenceOwner) id).referenceName()
                : id.getClass().getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(id));
        if (id instanceof ReferenceCounted)
            s += " refCount=" + ((ReferenceCounted) id).refCount();
        if (id instanceof Closeable)
            s += " closed=" + ((Closeable) id).isClosed();
        return s;
    }

    @Override
    public StackTrace createdHere() {
        return createdHere;
    }

    @Override
    public boolean reservedBy(ReferenceOwner owner) {
        if (references.containsKey(owner))
            return true;
        StackTrace stackTrace = releases.get(owner);
        if (stackTrace == null)
            throw new IllegalStateException("Never reserved by " + asString(owner));
        throw new IllegalStateException("No longer reserved by " + asString(owner), stackTrace);
    }

    @Override
    public void reserve(ReferenceOwner id) throws IllegalStateException {
        tryReserve(id, true);
    }

    @Override
    public boolean tryReserve(ReferenceOwner id) {
        return tryReserve(id, false);
    }

    private boolean tryReserve(ReferenceOwner id, boolean must) {
        if (id == this)
            throw new IllegalArgumentException("The counter cannot reserve itself");
//        if (Jvm.isDebug())
//            System.out.println(Thread.currentThread().getName() + " " + uniqueId + " - tryReserve " + asString(id));
        synchronized (references) {
            if (references.isEmpty()) {
                if (must)
                    throw new IllegalStateException("Cannot reserve freed resource", createdHere);
                return false;
            }
            StackTrace stackTrace = references.get(id);
            if (stackTrace == null)
                references.putIfAbsent(id, stackTrace("reserve", id));
            else
                throw new IllegalStateException("Already reserved resource by " + asString(id) + " here", stackTrace);
        }
        releases.remove(id);
        return true;
    }

    @Override
    public void release(ReferenceOwner id) throws IllegalStateException {
//        if (Jvm.isDebug())
//            System.out.println(Thread.currentThread().getName() + " " + uniqueId + " - release " + asString(id));

        synchronized (references) {
            if (references.remove(id) == null) {
                StackTrace stackTrace = releases.get(id);
                if (stackTrace == null) {
                    Throwable cause = createdHere;
                    if (!references.isEmpty()) {
                        StackTrace ste = references.values().iterator().next();
                        cause = new IllegalStateException("Reserved by " + referencesAsString(), ste);
                    }
                    throw new IllegalStateException("Not reserved by " + asString(id), cause);
                } else {
                    throw new IllegalStateException("Already released " + asString(id) + " location ", stackTrace);
                }
            }
            releases.put(id, stackTrace("release", id));
            if (references.isEmpty()) {
                if (releasedHere != null) {
                    throw new IllegalStateException("Already released", releasedHere);
                }
                releasedHere = new StackTrace(getClass() + " - Release here");
                // prevent this being called more than once.
                onRelease.run();
            }
        }
    }

    @NotNull
    public List<String> referencesAsString() {
        synchronized (references) {
            return references.keySet().stream()
                    .map(TracingReferenceCounted::asString)
                    .collect(Collectors.toList());
        }
    }

    @Override
    public void releaseLast(ReferenceOwner id) throws IllegalStateException {
        synchronized (references) {
            if (references.size() <= 1) {
                release(id);
            } else {
                Exception e0 = null;
                try {
                    release(id);
                } catch (Exception e) {
                    e0 = e;
                }
                IllegalStateException ise = new IllegalStateException("Still reserved " + referencesAsString(), createdHere);
                references.values().forEach(ise::addSuppressed);
                if (e0 != null)
                    ise.addSuppressed(e0);
                throw ise;
            }
        }
    }

    @Override
    public int refCount() {
        return references.size();
    }

    @NotNull
    public String toString() {
        return uniqueId + " - " + referencesAsString();
    }

    @NotNull
    private StackTrace stackTrace(String oper, ReferenceOwner ro) {
        return new StackTrace(toString() + ' '
                + Thread.currentThread().getName() + ' '
                + oper + ' '
                + asString(ro));
    }

    @Override
    public void throwExceptionIfNotReleased() throws IllegalStateException {
        synchronized (references) {
            if (references.isEmpty())
                return;
            IllegalStateException ise = new IllegalStateException("Retained reference closed");
            for (Map.Entry<ReferenceOwner, StackTrace> entry : references.entrySet()) {
                ReferenceOwner referenceOwner = entry.getKey();
                StackTrace reservedHere = entry.getValue();
                IllegalStateException ise2 = new IllegalStateException("Reserved by " + asString(referenceOwner), reservedHere);
                if (referenceOwner instanceof Closeable) {
                    try {
                        ((Closeable) referenceOwner).throwExceptionIfClosed();
                    } catch (IllegalStateException ise3) {
                        ise2.addSuppressed(ise3);
                    }
                } else if (referenceOwner instanceof AbstractReferenceCounted) {
                    try {
                        ((AbstractReferenceCounted) referenceOwner).throwExceptionIfReleased();
                    } catch (IllegalStateException ise3) {
                        ise2.addSuppressed(ise3);
                    }
                }
                ise.addSuppressed(ise2);
                if (referenceOwner instanceof AbstractCloseable) {
                    AbstractCloseable ac = (AbstractCloseable) referenceOwner;
                    try {
                        ac.throwExceptionIfClosed();

                    } catch (IllegalStateException e) {
                        ise.addSuppressed(e);
                    }
                } else if (referenceOwner instanceof QueryCloseable) {
                    try {
                        ((QueryCloseable) referenceOwner).throwExceptionIfClosed();

                    } catch (Throwable t) {
                        ise.addSuppressed(new IllegalStateException("Closed " + asString(referenceOwner), t));
                    }
                }
            }
            if (ise.getSuppressed().length > 0)
                throw ise;
        }
    }

    @Override
    public void throwExceptionIfReleased() throws IllegalStateException {
        if (refCount() <= 0)
            throw new IllegalStateException("Released", releasedHere);
    }

    @Override
    public void warnAndReleaseIfNotReleased() {
        if (refCount() > 0) {
            Slf4jExceptionHandler.WARN.on(getClass(), "Discarded without being released", createdHere);
            onRelease.run();
        }
    }
}