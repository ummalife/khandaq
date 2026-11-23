package com.zoffcc.applications.trifa;

import android.widget.FrameLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * KHANDAQ (tester 23.11): the attach sheet of a chat or a group opens whole. As a plain BottomSheetDialog it opened at
 * its peek height, and on a short phone or with large text the foot of the sheet stayed below the screen until the
 * sheet was dragged up. Dragging it down still closes it.
 */
final class AttachSheet
{
    private AttachSheet()
    {
    }

    static void showWhole(final BottomSheetDialog sheet)
    {
        final BottomSheetBehavior<FrameLayout> behavior = sheet.getBehavior();
        // Without the collapsed state a drag down goes from whole straight to closed.
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.show();
    }
}
