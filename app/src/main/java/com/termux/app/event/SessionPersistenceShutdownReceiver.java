package com.termux.app.event;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.session.SessionPersistenceManager;
import com.termux.shared.termux.shell.session.SessionPersistenceState;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

/**
 * Persists terminal session state as a last-resort save when the device is shutting down or
 * rebooting, registered statically in {@code AndroidManifest.xml} for
 * {@link Intent#ACTION_SHUTDOWN} and {@link Intent#ACTION_REBOOT}.
 *
 * This only has anything to do if this app's process happens to already be alive and holding
 * sessions when the broadcast arrives (i.e. {@link TermuxShellManager#getShellManager()} is
 * non-null) — it does not and cannot start the app just to save state. The other four save
 * triggers (activity onPause/onStop, service onTaskRemoved, the debounced/periodic snapshot)
 * are what normally keep the on-disk state fresh; this receiver only covers the narrow extra
 * window between the last of those triggers and an imminent shutdown/reboot.
 *
 * See docs/report/session-persistence.md for the overall design.
 */
public class SessionPersistenceShutdownReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "SessionPersistenceShutdownReceiver";

    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (!Intent.ACTION_SHUTDOWN.equals(action) && !Intent.ACTION_REBOOT.equals(action)) return;

        Logger.logDebug(LOG_TAG, "Received \"" + action + "\", attempting to persist terminal session state");

        TermuxShellManager shellManager = TermuxShellManager.getShellManager();
        if (shellManager == null) {
            Logger.logVerbose(LOG_TAG, "No TermuxShellManager available (app process not running), nothing to persist");
            return;
        }

        try {
            List<TermuxSession> termuxSessions = shellManager.mTermuxSessions;
            int selectedIndex = getSelectedSessionIndex(context, termuxSessions);
            SessionPersistenceState state = SessionPersistenceManager.captureState(termuxSessions, selectedIndex,
                SessionPersistenceManager.DEFAULT_MAX_SCROLLBACK_CHARS_PER_SESSION);
            SessionPersistenceManager.saveAtomic(SessionPersistenceManager.getDefaultStateFile(context), state);
            Logger.logDebug(LOG_TAG, "Persisted " + termuxSessions.size() + " terminal session(s) state on \"" + action + "\"");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to persist terminal session state on \"" + action + "\": " + e.getMessage());
        }
    }

    private static int getSelectedSessionIndex(@NonNull Context context, @NonNull List<TermuxSession> termuxSessions) {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        String currentSessionHandle = preferences == null ? null : preferences.getCurrentSession();
        if (currentSessionHandle != null) {
            for (int i = 0; i < termuxSessions.size(); i++) {
                TerminalSession terminalSession = termuxSessions.get(i).getTerminalSession();
                if (terminalSession.mHandle.equals(currentSessionHandle)) return i;
            }
        }
        return termuxSessions.isEmpty() ? -1 : termuxSessions.size() - 1;
    }

}
