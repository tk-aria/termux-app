package com.termux.shared.termux.shell.session;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves and restores the state of all terminal session tabs (count, order, names, selected tab,
 * each session's working directory, screen/scrollback content and cursor position) to/from a
 * single file in the app's private files directory.
 *
 * Saving is atomic: the new state is written to a temporary file first and then moved into place
 * with {@link File#renameTo(File)}, so a save that is interrupted (crash, kill, power loss) can
 * never leave a half-written/corrupt state file in the file the next read will look at.
 *
 * See {@code docs/report/session-persistence.md} for the overall design and the triggers that
 * call into this class.
 */
public final class SessionPersistenceManager {

    private static final String LOG_TAG = "SessionPersistenceManager";

    /** Name of the state file inside the app's private files directory ({@link Context#getFilesDir()}). */
    public static final String STATE_FILE_NAME = "session_persistence_state.json";

    /**
     * Maximum number of characters of screen+scrollback text kept per session. This bounds both
     * the on-disk file size and the memory used while restoring, regardless of how large a
     * session's actual scrollback buffer is.
     */
    public static final int DEFAULT_MAX_SCROLLBACK_CHARS_PER_SESSION = 100_000;

    private SessionPersistenceManager() {}

    @NonNull
    public static File getDefaultStateFile(@NonNull Context context) {
        return new File(context.getFilesDir(), STATE_FILE_NAME);
    }

    /**
     * Captures the current state of {@code termuxSessions} (in their given order) so it can be
     * persisted later with {@link #saveAtomic(File, SessionPersistenceState)}.
     *
     * @param termuxSessions the live sessions/tabs, in on-screen tab order.
     * @param selectedIndex index into {@code termuxSessions} of the selected/foregrounded tab, or -1.
     * @param maxScrollbackChars see {@link #DEFAULT_MAX_SCROLLBACK_CHARS_PER_SESSION}.
     */
    @NonNull
    public static SessionPersistenceState captureState(@NonNull List<TermuxSession> termuxSessions,
                                                         int selectedIndex, int maxScrollbackChars) {
        List<TerminalSessionSnapshot> snapshots = new ArrayList<>(termuxSessions.size());
        for (TermuxSession termuxSession : termuxSessions) {
            snapshots.add(captureSnapshot(termuxSession.getTerminalSession(), maxScrollbackChars));
        }
        return new SessionPersistenceState(System.currentTimeMillis(), selectedIndex, snapshots);
    }

    @NonNull
    public static TerminalSessionSnapshot captureSnapshot(@NonNull TerminalSession terminalSession, int maxScrollbackChars) {
        String text = "";
        int cursorRow = 0;
        int cursorCol = 0;

        TerminalEmulator emulator = terminalSession.getEmulator();
        if (emulator != null) {
            TerminalBuffer screen = emulator.getScreen();
            if (screen != null) {
                text = truncateToTail(screen.getTranscriptText(), maxScrollbackChars);
            }
            cursorRow = emulator.getCursorRow();
            cursorCol = emulator.getCursorCol();
        }

        String cwd = terminalSession.getCwd();
        return new TerminalSessionSnapshot(terminalSession.mSessionName, cwd, text, cursorRow, cursorCol);
    }

    @NonNull
    private static String truncateToTail(@Nullable String text, int maxChars) {
        if (text == null) return "";
        if (maxChars < 0 || text.length() <= maxChars) return text;
        return text.substring(text.length() - maxChars);
    }

    /**
     * Writes {@code state} into {@code stateFile} atomically: the data is first fully written to
     * {@code stateFile + ".tmp"} and only then renamed on top of {@code stateFile}.
     */
    public static void saveAtomic(@NonNull File stateFile, @NonNull SessionPersistenceState state) throws IOException {
        File parent = stateFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent directory: " + parent);
        }

        File tmpFile = new File(stateFile.getParentFile(), stateFile.getName() + ".tmp");
        byte[] bytes = SessionPersistenceCodec.encode(state).getBytes(StandardCharsets.UTF_8);

        try (FileOutputStream out = new FileOutputStream(tmpFile)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }

        if (!tmpFile.renameTo(stateFile)) {
            // renameTo() can fail across filesystems; fall back to delete+rename once.
            //noinspection ResultOfMethodCallIgnored
            stateFile.delete();
            if (!tmpFile.renameTo(stateFile)) {
                throw new IOException("Failed to atomically move " + tmpFile + " to " + stateFile);
            }
        }
    }

    /**
     * Reads and decodes the persisted state from {@code stateFile}.
     *
     * @return the decoded state, or {@code null} if the file does not exist or is corrupt/unreadable.
     */
    @Nullable
    public static SessionPersistenceState loadIfPresent(@NonNull File stateFile) {
        if (!stateFile.isFile()) return null;

        StringBuilder sb = new StringBuilder();
        try (FileInputStream in = new FileInputStream(stateFile);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
        } catch (IOException e) {
            Logger.logWarn(LOG_TAG, "Failed to read session persistence state file \"" + stateFile + "\": " + e.getMessage());
            return null;
        }

        SessionPersistenceState state = SessionPersistenceCodec.decode(sb.toString());
        if (state == null) {
            Logger.logWarn(LOG_TAG, "Failed to decode session persistence state file \"" + stateFile + "\", ignoring it");
        }
        return state;
    }

    /**
     * Feeds a restored snapshot's screen/scrollback text into a freshly created {@link TerminalSession}
     * so that its emulator shows the previous content before the newly spawned shell has produced
     * any output of its own.
     *
     * This is a best-effort visual restore: the text is replayed as if it had been written by the
     * shell, so the cursor naturally ends up after the replayed text rather than exactly at
     * {@link TerminalSessionSnapshot#cursorRow}/{@link TerminalSessionSnapshot#cursorCol}. The
     * original cursor position is still saved in the snapshot for callers that want it (e.g. to
     * show "cursor was here" in a future richer replay implementation).
     */
    public static void applySnapshot(@NonNull TerminalSessionSnapshot snapshot, @NonNull TerminalSession newTerminalSession) {
        if (snapshot.screenAndScrollbackText.isEmpty()) return;
        TerminalEmulator emulator = newTerminalSession.getEmulator();
        if (emulator == null) return;

        byte[] bytes = snapshot.screenAndScrollbackText.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        // A newline puts the replayed shell prompt that follows on its own line.
        emulator.append(new byte[]{'\r', '\n'}, 2);
    }

}
