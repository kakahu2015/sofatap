package com.chekusu.sofatap.tv.input;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ImeSessionTest {
    private final ImeSession session = new ImeSession();
    private final Editor editor = new Editor();
    private String start(int begin, int end, int action) {
        session.start(editor, false, begin, end, action);
        return session.epoch();
    }
    private ImeSession.Result apply(String type, String epoch, String text) {
        return session.apply(type, epoch, text, () -> true);
    }

    @Test public void normalAndSameEditorRestartAlwaysCreateNewEpoch() {
        String first = start(0, 0, 0), second = start(0, 0, 0);
        assertNotEquals(first, second);
        assertEquals("stale_editor", apply("text_commit", first, "old").code());
        assertTrue(apply("text_commit", second, "new").ok());
        assertEquals("new", editor.committed);
        assertEquals(1, editor.commits);
    }

    @Test public void finishingDropsEditorAndOldPackets() {
        String epoch = start(0, 0, 0);
        session.invalidate();
        assertNull(session.epoch());
        assertEquals("no_editor", session.status());
        assertEquals("no_editor", apply("text_commit", epoch, "queued").code());
        String replacement = start(0, 0, 0);
        assertNotEquals(epoch, replacement);
        assertEquals("stale_editor", apply("text_commit", epoch, "queued").code());
        assertEquals(0, editor.calls);
    }

    @Test public void disconnectRotatesEpochAndPreservesTheCurrentEditorWithoutReplaying() {
        String oldEpoch = start(3, 3, 0);
        session.controllerDisconnected();
        String newEpoch = session.epoch();
        assertNotEquals(oldEpoch, newEpoch);
        assertEquals("ready", session.status());
        assertEquals("stale_editor", apply("text_commit", oldEpoch, "late").code());
        assertEquals(0, editor.calls);
        assertTrue(apply("text_commit", newEpoch, "current").ok());
        assertEquals("current", editor.committed);
    }

    @Test public void sensitiveFieldHasNoEpochAndNeverCallsEditor() {
        session.start(editor, true, 0, 0, 6);
        assertEquals("sensitive", session.status());
        assertNull(session.epoch());
        assertEquals("sensitive", apply("text_commit", "anything", "private").code());
        assertEquals(0, editor.calls);
    }

    @Test public void nullConnectionIsNotAnEditor() {
        session.start(null, false, 0, 0, 0);
        assertEquals("no_editor", session.status());
        assertNull(session.epoch());
    }

    @Test public void unicodeIsCommittedAsOneStringWithoutKeyConversion() {
        String epoch = start(0, 0, 0);
        String text = "沙发瘫 日本語 🛋️ 👨‍👩‍👧‍👦 e\u0301\n";
        assertTrue(apply("text_commit", epoch, text).ok());
        assertEquals(text, editor.committed);
        assertEquals(1, editor.commits);
    }

    @Test public void invalidUnicodeAndOverlongTextNeverReachEditor() {
        String epoch = start(0, 0, 0);
        for (String invalid : new String[]{null, "", "x\0y", "\uD83D", "\uDC00", "\uD83Dx", "界".repeat(1366)}) {
            assertEquals("invalid_text", apply("text_commit", epoch, invalid).code());
        }
        assertEquals(0, editor.calls);
        assertTrue(apply("text_commit", epoch, "😀".repeat(1024)).ok());
    }

    @Test public void collapsedSelectionDeletesOneCodePointNotOneUtf16Unit() {
        String epoch = start(2, 2, 0);
        assertTrue(apply("text_backspace", epoch, null).ok());
        assertEquals(1, editor.codePointDeletes);
        assertEquals(0, editor.commits);
    }

    @Test public void backspaceAtStartRemainsUsableWithoutAnySelectionCallback() {
        String epoch = start(0, 0, 0);
        for (int i = 0; i < 10; i++) assertTrue(apply("text_backspace", epoch, null).ok());
        assertEquals(0, editor.calls);
        assertTrue(apply("text_commit", epoch, "next").ok());
    }

    @Test public void rapidCodePointBackspaceDoesNotNeedAnIntermediateSelectionCallback() {
        StringBuilder contents = new StringBuilder("A🛋");
        session.start(new ImeSession.Editor() {
            @Override public boolean commit(String text) { fail("Unexpected commit"); return false; }
            @Override public boolean finishComposing() { fail("Unexpected composition"); return false; }
            @Override public boolean action(int value) { fail("Unexpected action"); return false; }
            @Override public boolean deleteCodePointBeforeCursor() {
                if (contents.length() > 0) contents.delete(contents.offsetByCodePoints(contents.length(), -1), contents.length());
                return true;
            }
        }, false, contents.length(), contents.length(), 0);
        String epoch = session.epoch();
        assertTrue(apply("text_backspace", epoch, null).ok());
        assertEquals("A", contents.toString());
        assertTrue(apply("text_backspace", epoch, null).ok());
        assertEquals("", contents.toString());
        assertTrue(apply("text_backspace", epoch, null).ok());
        assertEquals("", contents.toString());
    }

    @Test public void selectedRangeEndsCompositionThenDeletesSelection() {
        String epoch = start(5, 1, 0);
        assertTrue(apply("text_backspace", epoch, null).ok());
        assertEquals("finish;commit;", editor.order);
        assertEquals("", editor.committed);
        assertEquals(0, editor.codePointDeletes);
    }

    @Test public void lostControllerBetweenCompositionAndDeletionCannotDelete() {
        String epoch = start(0, 5, 0);
        AtomicBoolean current = new AtomicBoolean(true);
        editor.onFinish = () -> current.set(false);
        ImeSession.Result result = session.apply("text_backspace", epoch, null, current::get);
        assertEquals("session_expired", result.code());
        assertEquals(0, editor.commits);
    }

    @Test public void changedEditorDuringCompositionCannotDeleteNewSelection() {
        String epoch = start(0, 5, 0);
        editor.onFinish = () -> session.start(editor, false, 0, 3, 0);
        assertEquals("session_expired", apply("text_backspace", epoch, null).code());
        assertEquals(0, editor.commits);
    }

    @Test public void unknownSelectionDoesNotGuessADeletion() {
        String epoch = start(-1, -1, 0);
        assertEquals("selection_unavailable", apply("text_backspace", epoch, null).code());
        assertEquals(0, editor.calls);
        session.selection(3, 3);
        assertTrue(apply("text_backspace", epoch, null).ok());
    }

    @Test public void editingWaitsForAuthoritativeSelectionBeforeNextBackspace() {
        String epoch = start(0, 0, 0);
        assertTrue(apply("text_commit", epoch, "abc").ok());
        assertEquals("selection_unavailable", apply("text_backspace", epoch, null).code());
        session.selection(3, 3);
        assertTrue(apply("text_backspace", epoch, null).ok());
    }

    @Test public void falseFrameworkReturnIsNeverSuccessOrFallbackMutation() {
        String epoch = start(1, 1, 0);
        editor.accept = false;
        assertEquals("edit_rejected", apply("text_backspace", epoch, null).code());
        assertEquals(1, editor.calls);
        assertEquals(0, editor.commits);
    }

    @Test public void failedFinishDoesNotDeleteSelectedText() {
        String epoch = start(1, 3, 0);
        editor.accept = false;
        assertEquals("edit_rejected", apply("text_backspace", epoch, null).code());
        assertEquals(0, editor.commits);
    }

    @Test public void onlyEditorAdvertisedActionIsDispatched() {
        String epoch = start(0, 0, 3);
        assertTrue(apply("text_action", epoch, null).ok());
        assertEquals(3, editor.action);
        epoch = start(0, 0, 0);
        assertEquals("action_unavailable", apply("text_action", epoch, null).code());
        assertEquals(1, editor.calls);
    }

    @Test public void expiredControllerCannotUseEvenCurrentEditorEpoch() {
        String epoch = start(0, 0, 3);
        assertEquals("session_expired", session.apply("text_commit", epoch, "no", () -> false).code());
        assertEquals("session_expired", session.apply("text_commit", epoch, "no", null).code());
        assertEquals(0, editor.calls);
    }

    @Test public void controllerIsRecheckedImmediatelyBeforeCommit() {
        String epoch = start(0, 0, 0);
        AtomicInteger checks = new AtomicInteger();
        assertEquals("session_expired", session.apply("text_commit", epoch, "late",
            () -> checks.incrementAndGet() < 3).code());
        assertEquals(0, editor.calls);
    }

    @Test public void noGlobalNavigationOrUnknownActionIsAccepted() {
        String epoch = start(0, 0, 0);
        for (String type : new String[]{"key", "home", "text_select_all", "shell", null}) {
            assertEquals("unsupported_action", apply(type, epoch, null).code());
        }
        assertEquals(0, editor.calls);
    }

    private static final class Editor implements ImeSession.Editor {
        int calls, commits, codePointDeletes, action;
        boolean accept = true;
        String committed, order = "";
        Runnable onFinish = () -> { };
        @Override public boolean commit(String text) {
            calls++; commits++; committed = text; order += "commit;"; return accept;
        }
        @Override public boolean finishComposing() {
            calls++; order += "finish;"; onFinish.run(); return accept;
        }
        @Override public boolean deleteCodePointBeforeCursor() { calls++; codePointDeletes++; return accept; }
        @Override public boolean action(int value) { calls++; action = value; return accept; }
    }
}
