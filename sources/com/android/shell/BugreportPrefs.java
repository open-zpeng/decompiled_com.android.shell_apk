package com.android.shell;

import android.content.Context;
/* loaded from: classes.dex */
final class BugreportPrefs {
    /* JADX INFO: Access modifiers changed from: package-private */
    public static int getWarningState(Context context, int i) {
        return context.getSharedPreferences("bugreports", 0).getInt("warning-state", i);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void setWarningState(Context context, int i) {
        context.getSharedPreferences("bugreports", 0).edit().putInt("warning-state", i).apply();
    }
}
