package com.kira.jujutsuneon.rbx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Событие как RBXScriptSignal: connect() возвращает подключение, которое можно отключить. */
public final class Signal<T> {

    public final class Connection {
        private final Consumer<T> fn;
        private final boolean once;

        private Connection(Consumer<T> fn, boolean once) {
            this.fn = fn;
            this.once = once;
        }

        public void disconnect() {
            handlers.remove(this);
        }

        public boolean connected() {
            return handlers.contains(this);
        }
    }

    private final List<Connection> handlers = new ArrayList<>(1);

    public Connection connect(Consumer<T> fn) {
        Connection c = new Connection(fn, false);
        handlers.add(c);
        return c;
    }

    /** Подключение на один раз (как :Once). */
    public Connection once(Consumer<T> fn) {
        Connection c = new Connection(fn, true);
        handlers.add(c);
        return c;
    }

    public boolean hasHandlers() {
        return !handlers.isEmpty();
    }

    public void fire(T value) {
        if (handlers.isEmpty()) return;
        for (Connection c : handlers.toArray(new Signal.Connection[0])) {
            if (c.once) handlers.remove(c);
            try {
                c.fn.accept(value);
            } catch (RuntimeException e) {
                RbxLog.LOGGER.warn("Animation signal handler failed", e);
            }
        }
    }

    public void clear() {
        handlers.clear();
    }
}
