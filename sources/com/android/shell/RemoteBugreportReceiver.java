package com.android.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;
/* loaded from: classes.dex */
public class RemoteBugreportReceiver extends BroadcastReceiver {
    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        BugreportReceiver.cleanupOldFiles(this, intent, "com.android.internal.intent.action.REMOTE_BUGREPORT_FINISHED", 3, 86400000L);
        Uri uri = BugreportProgressService.getUri(context, BugreportProgressService.getFileExtra(intent, "android.intent.extra.BUGREPORT"));
        String stringExtra = intent.getStringExtra("android.intent.extra.REMOTE_BUGREPORT_HASH");
        Intent intent2 = new Intent("android.intent.action.REMOTE_BUGREPORT_DISPATCH");
        intent2.setDataAndType(uri, "application/vnd.android.bugreport");
        intent2.putExtra("android.intent.extra.REMOTE_BUGREPORT_HASH", stringExtra);
        context.sendBroadcastAsUser(intent2, UserHandle.SYSTEM, "android.permission.DUMP");
    }
}
