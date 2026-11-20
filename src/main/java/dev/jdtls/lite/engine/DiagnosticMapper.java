package dev.jdtls.lite.engine;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/** Conversion of ECJ {@link IProblem} to LSP {@link Diagnostic}. */
public final class DiagnosticMapper {

    private DiagnosticMapper() {
    }

    public static Diagnostic of(String sourceText, IProblem problem) {
        Range range = rangeOf(sourceText, problem);
        Diagnostic d = new Diagnostic(range, problem.getMessage());
        d.setSeverity(severityOf(problem));
        d.setSource("ecj");
        d.setCode(String.valueOf(problem.getID()));
        return d;
    }

    public static Range rangeOf(String sourceText, IProblem problem) {
        int start = Math.max(0, problem.getSourceStart());
        int end = problem.getSourceEnd() + 1; // IProblem end is inclusive; LSP is exclusive
        Position startPos = positionOf(sourceText, start);
        Position endPos = positionOf(sourceText, Math.max(start, end));
        return new Range(startPos, endPos);
    }

    public static Position positionOf(String sourceText, int offset) {
        PositionUtil.LineCol lc = PositionUtil.offsetToLineCol(sourceText, offset);
        return new Position(lc.line(), lc.column());
    }

    private static DiagnosticSeverity severityOf(IProblem problem) {
        if (problem.isError()) {
            return DiagnosticSeverity.Error;
        }
        if (problem.isWarning()) {
            return DiagnosticSeverity.Warning;
        }
        return DiagnosticSeverity.Information;
    }
}
