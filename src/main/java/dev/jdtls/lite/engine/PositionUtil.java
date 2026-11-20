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

    private PositionUtil() {
    }
}
