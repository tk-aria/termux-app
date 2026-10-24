package com.termux.shared.termux.settings.preferences;

import static org.junit.Assert.assertEquals;

import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;

import org.junit.Test;

/**
 * Tests for {@link TermuxAppSharedPreferences#parseMaxTerminalSessions(String, int)} and
 * {@link TermuxAppSharedPreferences#clampMaxTerminalSessions(int)}.
 */
public class TermuxAppSharedPreferencesMaxTerminalSessionsTest {

    @Test
    public void unsetValueFallsBackToDefault() {
        assertEquals(TERMUX_APP.DEFAULT_VALUE_MAX_TERMINAL_SESSIONS,
            TermuxAppSharedPreferences.parseMaxTerminalSessions(null, TERMUX_APP.DEFAULT_VALUE_MAX_TERMINAL_SESSIONS));
    }

    @Test
    public void valueWithinRangeIsPreserved() {
        assertEquals(15, TermuxAppSharedPreferences.parseMaxTerminalSessions("15", TERMUX_APP.DEFAULT_VALUE_MAX_TERMINAL_SESSIONS));
    }

    @Test
    public void valueBelowMinimumIsClampedToMinimum() {
        assertEquals(TERMUX_APP.MIN_VALUE_MAX_TERMINAL_SESSIONS,
            TermuxAppSharedPreferences.parseMaxTerminalSessions("0", TERMUX_APP.DEFAULT_VALUE_MAX_TERMINAL_SESSIONS));
    }

    @Test
    public void valueAboveMaximumIsClampedToMaximum() {
        assertEquals(TERMUX_APP.MAX_VALUE_MAX_TERMINAL_SESSIONS,
            TermuxAppSharedPreferences.parseMaxTerminalSessions("999", TERMUX_APP.DEFAULT_VALUE_MAX_TERMINAL_SESSIONS));
    }

    @Test
    public void nonNumericValueFallsBackToDefault() {
        assertEquals(TERMUX_APP.DEFAULT_VALUE_MAX_TERMINAL_SESSIONS,
            TermuxAppSharedPreferences.parseMaxTerminalSessions("abc", TERMUX_APP.DEFAULT_VALUE_MAX_TERMINAL_SESSIONS));
    }

    @Test
    public void nullValueFallsBackToClampedDefault() {
        assertEquals(TERMUX_APP.MAX_VALUE_MAX_TERMINAL_SESSIONS,
            TermuxAppSharedPreferences.parseMaxTerminalSessions(null, 999));
    }

}
