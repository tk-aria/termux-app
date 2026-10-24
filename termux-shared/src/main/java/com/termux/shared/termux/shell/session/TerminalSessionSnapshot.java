package com.termux.shared.termux.shell.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An immutable snapshot of a single terminal session's persistable state, used by
 * {@link SessionPersistenceManager} to save and restore terminal sessions across app restarts.
 */
public final class TerminalSessionSnapshot {

    /** The session name/label shown in the sessions list, may be {@code null}. */
    @Nullable
    public final String sessionName;

    /** The shell's working directory at the time of the snapshot, may be {@code null} if unknown. */
    @Nullable
    public final String workingDirectory;

    /**
     * The visible screen content followed by scrollback history, as plain text, already
     * truncated to the configured scrollback cap.
     */
    @NonNull
    public final String screenAndScrollbackText;

    /** The cursor row at the time of the snapshot, relative to the top of the visible screen. */
    public final int cursorRow;

    /** The cursor column at the time of the snapshot. */
    public final int cursorCol;

    public TerminalSessionSnapshot(@Nullable String sessionName, @Nullable String workingDirectory,
                                    @NonNull String screenAndScrollbackText, int cursorRow, int cursorCol) {
        this.sessionName = sessionName;
        this.workingDirectory = workingDirectory;
        this.screenAndScrollbackText = screenAndScrollbackText;
        this.cursorRow = cursorRow;
        this.cursorCol = cursorCol;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TerminalSessionSnapshot)) return false;
        TerminalSessionSnapshot other = (TerminalSessionSnapshot) obj;
        return cursorRow == other.cursorRow &&
            cursorCol == other.cursorCol &&
            java.util.Objects.equals(sessionName, other.sessionName) &&
            java.util.Objects.equals(workingDirectory, other.workingDirectory) &&
            java.util.Objects.equals(screenAndScrollbackText, other.screenAndScrollbackText);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(sessionName, workingDirectory, screenAndScrollbackText, cursorRow, cursorCol);
    }

    @NonNull
    @Override
    public String toString() {
        return "TerminalSessionSnapshot{" +
            "sessionName='" + sessionName + '\'' +
            ", workingDirectory='" + workingDirectory + '\'' +
            ", cursorRow=" + cursorRow +
            ", cursorCol=" + cursorCol +
            ", screenAndScrollbackText.length=" + screenAndScrollbackText.length() +
            '}';
    }

}
