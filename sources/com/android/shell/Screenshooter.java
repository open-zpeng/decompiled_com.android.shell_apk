package com.android.shell;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
/* loaded from: classes.dex */
final class Screenshooter {
    /* JADX INFO: Access modifiers changed from: package-private */
    public static Bitmap takeScreenshot() {
        Display realDisplay = DisplayManagerGlobal.getInstance().getRealDisplay(0);
        Point point = new Point();
        realDisplay.getRealSize(point);
        int i = point.x;
        int i2 = point.y;
        int rotation = realDisplay.getRotation();
        Rect rect = new Rect(0, 0, i, i2);
        Log.d("Screenshooter", "Taking screenshot of dimensions " + i + " x " + i2);
        Bitmap screenshot = SurfaceControl.screenshot(rect, i, i2, rotation);
        if (screenshot == null) {
            Log.e("Screenshooter", "Failed to take screenshot of dimensions " + i + " x " + i2);
            return null;
        }
        screenshot.setHasAlpha(false);
        return screenshot;
    }
}
