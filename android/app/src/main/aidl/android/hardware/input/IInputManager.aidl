package android.hardware.input;

import android.view.InputEvent;

interface IInputManager {
    boolean injectInputEvent(in InputEvent ev, int mode);
}
