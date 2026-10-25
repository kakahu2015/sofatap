package com.chekusu.sofatap.tv.input;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Main-thread editor state. Holds no editor text and never replays an old connection's input. */
public final class ImeSession {
    public interface Editor {
        boolean commit(String text);
        boolean finishComposing();
        boolean deleteCodePointBeforeCursor();
        boolean action(int action);
    }

    public record Result(boolean ok, String code) { }
    private Editor editor;
    private String epoch;
    private boolean sensitive;
    private enum Selection { UNKNOWN, COLLAPSED, RANGE }
    private Selection selection = Selection.UNKNOWN;
    private int selectionStart = -1, selectionEnd = -1, editorAction;

    /** Called for every start, including a restart of the same editor. */
    public void start(Editor editor, boolean sensitive, int start, int end, int action) {
        invalidate();
        this.sensitive = sensitive;
        if (editor == null || sensitive) return;
        this.editor = editor;
        epoch = UUID.randomUUID().toString();
        selection(start, end);
        editorAction = action;
    }

    public void invalidate() {
        editor = null;
        epoch = null;
        sensitive = false;
        selectionStart = selectionEnd = -1;
        selection = Selection.UNKNOWN;
        editorAction = 0;
    }

    /** Reconnection must obtain a new epoch, while the focused editor can remain usable. */
    public void controllerDisconnected() {
        if (editor != null) epoch = UUID.randomUUID().toString();
    }

    public void selection(int start, int end) {
        selectionStart = start >= 0 ? start : -1;
        selectionEnd = end >= 0 ? end : -1;
        selection = start < 0 || end < 0 ? Selection.UNKNOWN
            : start == end ? Selection.COLLAPSED : Selection.RANGE;
    }

    public String status() { return sensitive ? "sensitive" : editor == null ? "no_editor" : "ready"; }
    public String epoch() { return epoch; }

    public Result apply(String type, String expectedEpoch, String text, BooleanSupplier current) {
        if (current == null || !current.getAsBoolean()) return failure("session_expired");
        if (!"ready".equals(status())) return failure(status());
        if (expectedEpoch == null || !expectedEpoch.equals(epoch)) return failure("stale_editor");
        Editor target = editor;
        BooleanSupplier valid = () -> current.getAsBoolean() && editor == target && expectedEpoch.equals(epoch);
        if (!valid.getAsBoolean()) return failure("session_expired");
        boolean accepted;
        switch (type == null ? "" : type) {
            case "text_commit":
                if (!validText(text)) return failure("invalid_text");
                if (!valid.getAsBoolean()) return failure("session_expired");
                // Clear selection knowledge before dispatch; the real editor can filter or
                // transform text. Only its next selection callback can establish the result.
                selection(-1, -1);
                accepted = target.commit(text);
                break;
            case "text_backspace":
                if (selection == Selection.UNKNOWN) return failure("selection_unavailable");
                if (selection == Selection.RANGE) {
                    selection(-1, -1);
                    // commitText replaces composing text first. Finish it before deleting
                    // the selected range, otherwise an unrelated composition could be removed.
                    if (!target.finishComposing()) return failure("edit_rejected");
                    if (!valid.getAsBoolean()) return failure("session_expired");
                    selection(-1, -1);
                    accepted = target.commit("");
                } else {
                    // A caret at the beginning is a no-op and may produce no selection
                    // callback. Keep it usable for the next key press.
                    if (selectionStart == 0 && selectionEnd == 0) return new Result(true, "ok");
                    if (!valid.getAsBoolean()) return failure("session_expired");
                    // Surrounding-text deletion preserves a collapsed selection. Retain
                    // that fact for rapid Backspace, but never guess its UTF-16 position
                    // (the removed code point may occupy one or two code units).
                    selectionStart = selectionEnd = -1;
                    accepted = target.deleteCodePointBeforeCursor();
                }
                break;
            case "text_action":
                if (editorAction == 0) return failure("action_unavailable");
                if (!valid.getAsBoolean()) return failure("session_expired");
                accepted = target.action(editorAction);
                break;
            default: return failure("unsupported_action");
        }
        return accepted ? new Result(true, "ok") : failure("edit_rejected");
    }

    /** Defense in depth for internal callers which bypass the wire parser. */
    public static boolean validText(String text) {
        if (text == null || text.isEmpty() || text.indexOf('\0') >= 0
                || text.getBytes(StandardCharsets.UTF_8).length > 4096) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static Result failure(String code) { return new Result(false, code); }
}
