package com.android.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.FileUtils;
import android.util.Log;
import java.io.File;
/* loaded from: classes.dex */
public class BugreportReceiver extends BroadcastReceiver {
    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        Log.d("BugreportReceiver", "onReceive(): " + BugreportProgressService.dumpIntent(intent));
        cleanupOldFiles(this, intent, "com.android.internal.intent.action.BUGREPORT_FINISHED", 8, 604800000L);
        Intent intent2 = new Intent(context, BugreportProgressService.class);
        intent2.putExtra("android.intent.extra.ORIGINAL_INTENT", intent);
        context.startService(intent2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void cleanupOldFiles(BroadcastReceiver broadcastReceiver, Intent intent, String str, final int i, final long j) {
        if (str.equals(intent.getAction())) {
            final File fileExtra = BugreportProgressService.getFileExtra(intent, "android.intent.extra.BUGREPORT");
            if (fileExtra == null || !fileExtra.exists()) {
                Log.e("BugreportReceiver", "Not deleting old files because file " + fileExtra + " doesn't exist");
                return;
            }
            final BroadcastReceiver.PendingResult goAsync = broadcastReceiver.goAsync();
            new AsyncTask<Void, Void, Void>() { // from class: com.android.shell.BugreportReceiver.1
                /* JADX INFO: Access modifiers changed from: protected */
                @Override // android.os.AsyncTask
                public Void doInBackground(Void... voidArr) {
                    try {
                        FileUtils.deleteOlderFiles(fileExtra.getParentFile(), i, j);
                    } catch (RuntimeException e) {
                        Log.e("BugreportReceiver", "RuntimeException deleting old files", e);
                    }
                    goAsync.finish();
                    return null;
                }
            }.execute(new Void[0]);
        }
    }
}
