package dev.jdtls.lite.engine;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import org.eclipse.lsp4j.MarkupContent;

import dev.jdtls.lite.Log;

/**
 * Hover for sources that are not backed by the JavaModel — e.g.
 * FernFlower-decompiled third-party class files opened by the client.
 * Parses the buffer without bindings (fast) and describes the declaration
 * covering the hover position.
 */
public final class AstHover {

    private AstHover() {
    }

    /** Markdown hover for the declaration at (line, character), or null. */
    public static org.eclipse.lsp4j.Hover hoverAt(String source, int line, int character) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setSource(source.toCharArray());
            parser.setCompilerOptions(CompilerDefaults.options());
            CompilationUnit cu = (CompilationUnit) parser.createAST(null);
            if (cu == null) {
                Log.info("astHover: parse returned null");
                return null;
            }
            Log.info("astHover: parsed " + source.length() + " chars, problems=" + cu.getProblems().length);
            int offset = PositionUtil.offsetAt(source, line, character);
            ASTNode node = NodeFinder.perform(cu, offset, 0);
            String md = describe(node, cu);
            if (md == null) {
                Log.info("astHover: no description for node " + node.getClass().getSimpleName()
                        + " at offset " + offset + " (source " + source.length() + " chars)");
                return null;
            }
            org.eclipse.lsp4j.Hover hover = new org.eclipse.lsp4j.Hover();
            hover.setContents(new MarkupContent("markdown", md));
            return hover;
        } catch (Throwable t) {
            Log.warn("astHover failed: " + t);
            return null;
        }
    }

    private static String describe(ASTNode node, CompilationUnit cu) {
        ASTNode cur = node;
        while (cur != null && !(cur instanceof CompilationUnit)) {
            if (cur instanceof MethodDeclaration m) {
                StringBuilder sb = new StringBuilder("```java\n");
                if (m.getReturnType2() != null) {
                    sb.append(m.getReturnType2().toString()).append(' ');
                }
                sb.append(m.getName().getIdentifier()).append('(');
                List<SingleVariableDeclaration> params = m.parameters();
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(params.get(i).getType().toString()).append(' ')
                            .append(params.get(i).getName().getIdentifier());
                }
                sb.append(")\n```");
                String cls = declaringClass(m);
                if (cls != null) {
                    sb.append("\n`").append(cls).append("`");
                }
                return sb.toString();
            }
            if (cur instanceof FieldDeclaration field && node instanceof SimpleName sn) {
                return "```java\n" + field.getType().toString() + " " + sn.getIdentifier() + "\n```";
            }
            if (cur instanceof AbstractTypeDeclaration type) {
                String kind = type instanceof org.eclipse.jdt.core.dom.EnumDeclaration ? "enum"
                        : (type instanceof org.eclipse.jdt.core.dom.TypeDeclaration td && td.isInterface())
                                ? "interface"
                                : "class";
                String pkg = cu.getPackage() == null ? "" : cu.getPackage().getName().getFullyQualifiedName();
                return "```java\n" + kind + " " + (pkg.isEmpty() ? "" : pkg + ".") + type.getName().getIdentifier()
                        + "\n```";
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static String declaringClass(BodyDeclaration decl) {
        ASTNode p = decl.getParent();
        while (p != null && !(p instanceof AbstractTypeDeclaration)) {
            p = p.getParent();
        }
        if (p instanceof AbstractTypeDeclaration type) {
            CompilationUnit cu = (CompilationUnit) type.getRoot();
            String pkg = cu.getPackage() == null ? "" : cu.getPackage().getName().getFullyQualifiedName() + ".";
            StringBuilder fq = new StringBuilder(pkg).append(type.getName().getIdentifier());
            ASTNode outer = type.getParent();
            while (outer instanceof AbstractTypeDeclaration outerType) {
                fq.insert(0, outerType.getName().getIdentifier() + ".");
                outer = outerType.getParent();
            }
            return fq.toString();
        }
        return null;
    }

    /** First line/character of {@code needle} in {@code source} (for probes/tests). */
    public static int[] find(String source, String needle) {
        int idx = source.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        PositionUtil.LineCol lc = PositionUtil.offsetToLineCol(source, idx);
        return new int[] { lc.line(), lc.column() };
    }

    /** Names covered along the chain (diagnostics helper). */
    static List<String> chainNames(ASTNode node) {
        List<String> names = new ArrayList<>();
        ASTNode cur = node;
        while (cur != null && !(cur instanceof CompilationUnit)) {
            if (cur instanceof SimpleName sn) {
                names.add(sn.getIdentifier());
            }
            cur = cur.getParent();
        }
        return names;
    }

    @SuppressWarnings("unused")
    private static Name unusedKeepImport(Name n) {
        return n;
    }
}
