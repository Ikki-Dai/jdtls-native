package dev.jdtls.lite.engine;

/** Char-offset helpers shared by diagnostic/range conversion. */
public final class PositionUtil {

    public record LineCol(int line, int column) {
    }

    /** Converts a 0-based char offset into a 0-based (line, character) pair. */
    public static LineCol offsetToLineCol(String text, int offset) {
        int line = 0;
        int col = 0;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                col = 0;
            } else if (c == '\r') {
                // CRLF: column advances only after the full newline pair
                if (i + 1 < limit && text.charAt(i + 1) == '\n') {
                    continue;
                }
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new LineCol(line, col);
    }

    /** Converts a 0-based (line, character) pair into a 0-based char offset. */
    public static int offsetAt(String text, int line, int character) {
        int currentLine = 0;
        int lineStart = 0;
        int lineLen = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                if (currentLine == line) {
                    lineLen = i - lineStart;
                    return lineStart + Math.min(character, lineLen);
                }
                currentLine++;
                lineStart = i + 1;
            }
        }
        return text.length();
    }

    private PositionUtil() {
    }
}
