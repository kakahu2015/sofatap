package com.chekusu.sofatap.tv;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/** Private disposable-emulator text target. Absent from release builds. */
public final class ImeFixtureActivity extends Activity {
    EditText editor;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        editor.setShowSoftInputOnFocus(false);
        setContentView(editor);
        editor.requestFocus();
    }
}
