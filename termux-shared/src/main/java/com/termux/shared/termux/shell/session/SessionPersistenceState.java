package com.termux.shared.termux.shell.session;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The full persisted state of all terminal sessions/tabs: how many there are, their order,
 * which one was selected, and a {@link TerminalSessionSnapshot} for each.
 */
public final class SessionPersistenceState {

    /** Bumped if the on-disk schema changes in an incompatible way. */
    public static final int CURRENT_VERSION = 1;

    public final int version;

    /** Wall clock time ({@link System#currentTimeMillis()}) the state was captured at. */
    public final long savedAtMillis;

    /** Index into {@link #sessions} of the tab that was selected/foregrounded, or -1 if none. */
    public final int selectedIndex;

    /** The sessions/tabs, in their on-screen tab order. */
    @NonNull
    public final List<TerminalSessionSnapshot> sessions;

    public SessionPersistenceState(int version, long savedAtMillis, int selectedIndex,
                                    @NonNull List<TerminalSessionSnapshot> sessions) {
        this.version = version;
        this.savedAtMillis = savedAtMillis;
        this.selectedIndex = selectedIndex;
        this.sessions = Collections.unmodifiableList(new ArrayList<>(sessions));
    }

    public SessionPersistenceState(long savedAtMillis, int selectedIndex,
                                    @NonNull List<TerminalSessionSnapshot> sessions) {
        this(CURRENT_VERSION, savedAtMillis, selectedIndex, sessions);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SessionPersistenceState)) return false;
        SessionPersistenceState other = (SessionPersistenceState) obj;
        return version == other.version &&
            savedAtMillis == other.savedAtMillis &&
            selectedIndex == other.selectedIndex &&
            sessions.equals(other.sessions);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(version, savedAtMillis, selectedIndex, sessions);
    }
}
