package dev.jdtls.lite.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.corext.refactoring.rename.MethodChecks;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameEnumConstProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameFieldProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameLocalVariableProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameNonVirtualMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameTypeParameterProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameTypeProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameVirtualMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.tagging.IReferenceUpdating;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.CreateChangeOperation;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.ltk.core.refactoring.CheckConditionsOperation;
import org.eclipse.ltk.core.refactoring.participants.RefactoringProcessor;
import org.eclipse.ltk.core.refactoring.participants.RenameRefactoring;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.WorkspaceEdit;

import dev.jdtls.lite.Log;

/**
 * LTK-backed rename: runs the real jdt rename processors (jdt.ls-grade rename
 * with reference search, method hierarchy, etc.) and converts the resulting
 * {@link Change} tree into an LSP {@link WorkspaceEdit}.
 *
 * The rename-participants mechanism is unused (no participants are registered
 * in our standalone registry), which is fine — the processors themselves
 * produce the reference updates.
 */
public final class LtkRename {

    private LtkRename() {
    }

    /** Runs the LTK rename; null when the element type is unsupported or a fatal condition failed. */
    public static WorkspaceEdit rename(IJavaElement el, String newName, NullProgressMonitor monitor)
            throws JavaModelException, Exception {
        org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor processor = processorFor(el);
        if (processor == null) {
            Log.info("ltk rename: unsupported element (" + el.getElementType() + " " + el.getElementName() + ")");
            return null;
        }
        try {
            processor.setNewElementName(newName);
            if (processor instanceof org.eclipse.jdt.internal.corext.refactoring.tagging.IReferenceUpdating ref) {
                ref.setUpdateReferences(true);
            }
            RenameRefactoring refactoring = new RenameRefactoring(processor);
            CheckConditionsOperation check = new CheckConditionsOperation(refactoring,
                    CheckConditionsOperation.ALL_CONDITIONS);
            CreateChangeOperation create = new CreateChangeOperation(check, RefactoringStatus.FATAL);
            create.run(monitor);
            if (check.getStatus() == null || check.getStatus().hasFatalError()) {
                Log.info("ltk rename: fatal condition — " + check.getStatus());
                return null;
            }
            Change change = create.getChange();
            if (change == null) {
                return null;
            }
            return convert(change, monitor);
        } catch (OperationCanceledException | org.eclipse.core.runtime.CoreException e) {
            Log.warn("ltk rename failed: " + e);
            return null;
        }
    }

    private static org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor processorFor(IJavaElement el) throws JavaModelException {
        if (el instanceof IType type) {
            return new RenameTypeProcessor(type);
        }
        if (el instanceof IMethod method) {
            if (method.isConstructor()) {
                return null; // renaming a constructor renames the type; unsupported in v1
            }
            return MethodChecks.isVirtual(method)
                    ? new RenameVirtualMethodProcessor(method)
                    : new RenameNonVirtualMethodProcessor(method);
        }
        if (el instanceof IField field) {
            return JdtFlags.isEnum(field)
                    ? new RenameEnumConstProcessor(field)
                    : new RenameFieldProcessor(field);
        }
        if (el instanceof ILocalVariable variable) {
            return new RenameLocalVariableProcessor(variable);
        }
        if (el instanceof ITypeParameter parameter) {
            return new RenameTypeParameterProcessor(parameter);
        }
        return null;
    }

    // ---------------------------------------------------------------- change → WorkspaceEdit

    private static WorkspaceEdit convert(Change change, NullProgressMonitor monitor) throws Exception {
        Map<String, List<org.eclipse.lsp4j.TextEdit>> changes = new LinkedHashMap<>();
        collect(change, changes, monitor);
        if (changes.isEmpty()) {
            return null;
        }
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setChanges(changes);
        return edit;
    }

    private static void collect(Change change, Map<String, List<org.eclipse.lsp4j.TextEdit>> changes,
            NullProgressMonitor monitor) throws Exception {
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collect(child, changes, monitor);
            }
            return;
        }
        if (change instanceof TextFileChange textChange) {
            org.eclipse.core.resources.IFile file = textChange.getFile();
            if (file == null || file.getLocationURI() == null) {
                Log.info("ltk convert: skipping change without file: " + change.getName());
                return;
            }
            String uri = file.getLocationURI().toString();
            String original = textChange.getCurrentContent(monitor);
            if (original == null) {
                Log.info("ltk convert: no current content for " + uri);
                return;
            }
            TextEdit root = textChange.getEdit();
            if (root == null) {
                return;
            }
            List<org.eclipse.lsp4j.TextEdit> edits =
                    changes.computeIfAbsent(uri, k -> new ArrayList<>());
            collectEdits(root, original, edits);
            return;
        }
        Log.info("ltk convert: skipped change type " + change.getClass().getSimpleName()
                + " (" + change.getName() + ")");
    }

    /** Flattens the edit tree into LSP leaf text edits (positions against the original content). */
    private static void collectEdits(TextEdit edit, String original, List<org.eclipse.lsp4j.TextEdit> out) {
        if (edit instanceof MultiTextEdit) {
            for (TextEdit child : edit.getChildren()) {
                collectEdits(child, original, out);
            }
            return;
        }
        int offset = edit.getOffset();
        int length = edit.getLength();
        String newText = textOf(edit);
        if (offset < 0 || offset + length > original.length()) {
            Log.info("ltk convert: dropping out-of-range edit @" + offset + "+" + length);
            return;
        }
        if (length == 0 && newText.isEmpty()) {
            return;
        }
        Position start = DiagnosticMapper.positionOf(original, offset);
        Position end = DiagnosticMapper.positionOf(original, offset + length);
        out.add(new org.eclipse.lsp4j.TextEdit(new Range(start, end), newText));
    }

    private static String textOf(TextEdit edit) {
        if (edit instanceof ReplaceEdit replace) {
            return replace.getText();
        }
        if (edit instanceof InsertEdit insert) {
            return insert.getText();
        }
        return ""; // DeleteEdit
    }

}
