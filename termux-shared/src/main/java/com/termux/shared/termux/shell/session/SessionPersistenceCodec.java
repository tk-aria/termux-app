package com.termux.shared.termux.shell.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes/decodes a {@link SessionPersistenceState} to/from a small JSON-like text format.
 *
 * This intentionally does not use {@code org.json}/{@code android.util.JsonReader}: those are
 * Android framework classes that are not safely usable from a plain (non-Robolectric) JVM unit
 * test, and this module otherwise has no JSON library dependency. The format below is a strict
 * subset of JSON (objects, arrays, strings, numbers) which is enough to round-trip our own
 * schema, and is still readable/diffable on disk.
 */
public final class SessionPersistenceCodec {

    private SessionPersistenceCodec() {}

    @NonNull
    public static String encode(@NonNull SessionPersistenceState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(state.version).append(",\n");
        sb.append("  \"savedAtMillis\": ").append(state.savedAtMillis).append(",\n");
        sb.append("  \"selectedIndex\": ").append(state.selectedIndex).append(",\n");
        sb.append("  \"sessions\": [\n");
        for (int i = 0; i < state.sessions.size(); i++) {
            TerminalSessionSnapshot snapshot = state.sessions.get(i);
            sb.append("    {\n");
            sb.append("      \"sessionName\": ").append(encodeStringOrNull(snapshot.sessionName)).append(",\n");
            sb.append("      \"workingDirectory\": ").append(encodeStringOrNull(snapshot.workingDirectory)).append(",\n");
            sb.append("      \"cursorRow\": ").append(snapshot.cursorRow).append(",\n");
            sb.append("      \"cursorCol\": ").append(snapshot.cursorCol).append(",\n");
            sb.append("      \"screenAndScrollbackText\": ").append(encodeString(snapshot.screenAndScrollbackText)).append("\n");
            sb.append("    }");
            if (i != state.sessions.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** @return the decoded state, or {@code null} if {@code json} is not a well-formed instance of our schema. */
    @Nullable
    public static SessionPersistenceState decode(@Nullable String json) {
        if (json == null) return null;
        try {
            Parser parser = new Parser(json);
            Object root = parser.parseValue();
            if (!(root instanceof java.util.Map)) return null;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) root;

            int version = ((Number) map.get("version")).intValue();
            long savedAtMillis = ((Number) map.get("savedAtMillis")).longValue();
            int selectedIndex = ((Number) map.get("selectedIndex")).intValue();

            List<TerminalSessionSnapshot> sessions = new ArrayList<>();
            Object rawSessions = map.get("sessions");
            if (rawSessions instanceof List) {
                for (Object rawSession : (List<?>) rawSessions) {
                    if (!(rawSession instanceof java.util.Map)) return null;
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> sessionMap = (java.util.Map<String, Object>) rawSession;
                    String sessionName = (String) sessionMap.get("sessionName");
                    String workingDirectory = (String) sessionMap.get("workingDirectory");
                    int cursorRow = ((Number) sessionMap.get("cursorRow")).intValue();
                    int cursorCol = ((Number) sessionMap.get("cursorCol")).intValue();
                    String text = (String) sessionMap.get("screenAndScrollbackText");
                    if (text == null) text = "";
                    sessions.add(new TerminalSessionSnapshot(sessionName, workingDirectory, text, cursorRow, cursorCol));
                }
            }

            return new SessionPersistenceState(version, savedAtMillis, selectedIndex, sessions);
        } catch (Exception e) {
            // Malformed/corrupt state file; callers should treat this as "nothing to restore".
            return null;
        }
    }

    @NonNull
    private static String encodeStringOrNull(@Nullable String value) {
        return value == null ? "null" : encodeString(value);
    }

    @NonNull
    private static String encodeString(@NonNull String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** A minimal recursive-descent parser for the strict JSON subset produced by {@link #encode}. */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.pos = 0;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= s.length()) throw new RuntimeException("Unexpected end of input");
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 'n' && s.startsWith("null", pos)) { pos += 4; return null; }
            return parseNumber();
        }

        java.util.Map<String, Object> parseObject() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; break; }
                throw new RuntimeException("Expected ',' or '}' at position " + pos);
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; break; }
                throw new RuntimeException("Expected ',' or ']' at position " + pos);
            }
            return list;
        }

        String parseString() {
            skipWhitespace();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new RuntimeException("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char escaped = s.charAt(pos++);
                    switch (escaped) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            sb.append(escaped);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Number parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.' ||
                s.charAt(pos) == 'e' || s.charAt(pos) == 'E' || s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            String token = s.substring(start, pos);
            if (token.isEmpty()) throw new RuntimeException("Expected number at position " + start);
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        char peek() {
            skipWhitespace();
            if (pos >= s.length()) throw new RuntimeException("Unexpected end of input");
            return s.charAt(pos);
        }

        void expect(char c) {
            skipWhitespace();
            if (pos >= s.length() || s.charAt(pos) != c)
                throw new RuntimeException("Expected '" + c + "' at position " + pos);
            pos++;
        }
    }
}
