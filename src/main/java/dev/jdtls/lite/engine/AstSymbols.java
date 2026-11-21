package dev.jdtls.lite.engine;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

/**
 * AST-based document symbols for sources that are not backed by the JavaModel —
 * e.g. FernFlower-decompiled third-party class files opened by the client.
 * Parses without bindings; nothing here touches JavaCore/JavaModelManager.
 */
public final class AstSymbols {

    private AstSymbols() {
    }

    public static List<DocumentSymbol> build(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setSource(source.toCharArray());
            parser.setCompilerOptions(CompilerDefaults.options());
            CompilationUnit cu = (CompilationUnit) parser.createAST(null);
            if (cu == null) {
                return List.of();
            }
            List<DocumentSymbol> out = new ArrayList<>();
            for (Object o : cu.types()) {
                if (o instanceof AbstractTypeDeclaration type) {
                    out.add(typeSymbol(type, source));
                }
            }
            return out;
        } catch (Throwable t) {
            dev.jdtls.lite.Log.warn("astSymbols failed: " + t);
            return List.of();
        }
    }

    private static DocumentSymbol typeSymbol(AbstractTypeDeclaration type, String source) {
        DocumentSymbol s = new DocumentSymbol();
        s.setName(type.getName().getIdentifier());
        if (type instanceof EnumDeclaration) {
            s.setKind(SymbolKind.Enum);
        } else if (type instanceof TypeDeclaration td && td.isInterface()) {
            s.setKind(SymbolKind.Interface);
        } else {
            s.setKind(SymbolKind.Class);
        }
        s.setRange(rangeOf(type, source));
        s.setSelectionRange(rangeOf(type.getName(), source));
        List<DocumentSymbol> children = new ArrayList<>();
        for (Object o : type.bodyDeclarations()) {
            if (o instanceof AbstractTypeDeclaration inner) {
                children.add(typeSymbol(inner, source));
            } else if (o instanceof MethodDeclaration m) {
                String label = m.isConstructor() ? m.getName().getIdentifier()
                        : m.getName().getIdentifier() + "()";
                SymbolKind kind = m.isConstructor() ? SymbolKind.Constructor : SymbolKind.Method;
                DocumentSymbol ms = leaf(label, kind, m, source);
                if (!m.isConstructor() && m.getReturnType2() != null) {
                    ms.setDetail(m.getReturnType2().toString());
                }
                children.add(ms);
            } else if (o instanceof FieldDeclaration field) {
                String typeName = field.getType().toString();
                for (Object frag : field.fragments()) {
                    if (frag instanceof VariableDeclarationFragment v) {
                        DocumentSymbol fs = leaf(v.getName().getIdentifier(), SymbolKind.Field, v, source);
                        fs.setDetail(typeName);
                        children.add(fs);
                    }
                }
            } else if (o instanceof Initializer init) {
                children.add(leaf("<clinit>", SymbolKind.Method, init, source));
            }
        }
        if (type instanceof EnumDeclaration enumDecl) {
            for (Object o : enumDecl.enumConstants()) {
                if (o instanceof EnumConstantDeclaration c) {
                    children.add(leaf(c.getName().getIdentifier(), SymbolKind.EnumMember, c, source));
                }
            }
        }
        s.setChildren(children);
        return s;
    }

    private static DocumentSymbol leaf(String name, SymbolKind kind,
            org.eclipse.jdt.core.dom.ASTNode node, String source) {
        DocumentSymbol s = new DocumentSymbol();
        s.setName(name);
        s.setKind(kind);
        s.setRange(rangeOf(node, source));
        s.setSelectionRange(rangeOf(node, source));
        s.setChildren(List.of());
        return s;
    }

    private static Range rangeOf(org.eclipse.jdt.core.dom.ASTNode node, String source) {
        int start = node.getStartPosition();
        int end = start + node.getLength();
        return new Range(
                clamped(source, start),
                clamped(source, Math.max(start, end)));
    }

    private static Position clamped(String source, int offset) {
        if (offset < 0) {
            return new Position(0, 0);
        }
        return DiagnosticMapper.positionOf(source, Math.min(offset, source.length()));
    }
}
