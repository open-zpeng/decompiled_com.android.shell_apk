package com.android.shell;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IDumpstate;
import android.os.IDumpstateListener;
import android.os.IDumpstateToken;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Patterns;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.google.android.collect.Lists;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import libcore.io.Streams;
/* loaded from: classes.dex */
public class BugreportProgressService extends Service {
    private Context mContext;
    private boolean mIsTv;
    private boolean mIsWatch;
    private int mLastProgressPercent;
    private Handler mMainThreadHandler;
    private ScreenshotHandler mScreenshotHandler;
    private File mScreenshotsDir;
    private ServiceHandler mServiceHandler;
    private boolean mTakingScreenshot;
    @GuardedBy({"sNotificationBundle"})
    private static final Bundle sNotificationBundle = new Bundle();
    private static final String SHORT_EXTRA_ORIGINAL_INTENT = "android.intent.extra.ORIGINAL_INTENT".substring(21);
    private final Object mLock = new Object();
    private final SparseArray<DumpstateListener> mProcesses = new SparseArray<>();
    private final BugreportInfoDialog mInfoDialog = new BugreportInfoDialog();
    private int mForegroundId = -1;

    @VisibleForTesting
    static boolean isValid(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || ((c >= '0' && c <= '9') || c == '_' || c == '-');
    }

    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override // android.app.Service
    public void onCreate() {
        this.mContext = getApplicationContext();
        this.mMainThreadHandler = new Handler(Looper.getMainLooper());
        this.mServiceHandler = new ServiceHandler("BugreportProgressServiceMainThread");
        this.mScreenshotHandler = new ScreenshotHandler("BugreportProgressServiceScreenshotThread");
        this.mScreenshotsDir = new File(getFilesDir(), "bugreports");
        if (!this.mScreenshotsDir.exists()) {
            Log.i("BugreportProgressService", "Creating directory " + this.mScreenshotsDir + " to store temporary screenshots");
            if (!this.mScreenshotsDir.mkdir()) {
                Log.w("BugreportProgressService", "Could not create directory " + this.mScreenshotsDir);
            }
        }
        boolean z = true;
        this.mIsWatch = (this.mContext.getResources().getConfiguration().uiMode & 15) == 6;
        PackageManager packageManager = getPackageManager();
        if (!packageManager.hasSystemFeature("android.software.leanback") && !packageManager.hasSystemFeature("android.hardware.type.television")) {
            z = false;
        }
        this.mIsTv = z;
        NotificationManager.from(this.mContext).createNotificationChannel(new NotificationChannel("bugreports", this.mContext.getString(R.string.bugreport_notification_channel), isTv(this) ? 3 : 2));
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int i, int i2) {
        Log.v("BugreportProgressService", "onStartCommand(): " + dumpIntent(intent));
        if (intent != null) {
            Message obtainMessage = this.mServiceHandler.obtainMessage();
            obtainMessage.what = 1;
            obtainMessage.obj = intent;
            this.mServiceHandler.sendMessage(obtainMessage);
            return 2;
        }
        return 2;
    }

    @Override // android.app.Service
    public void onDestroy() {
        this.mServiceHandler.getLooper().quit();
        this.mScreenshotHandler.getLooper().quit();
        super.onDestroy();
    }

    @Override // android.app.Service
    protected void dump(FileDescriptor fileDescriptor, PrintWriter printWriter, String[] strArr) {
        int size = this.mProcesses.size();
        if (size == 0) {
            printWriter.println("No monitored processes");
            return;
        }
        printWriter.print("Foreground id: ");
        printWriter.println(this.mForegroundId);
        printWriter.println("\n");
        printWriter.println("Monitored dumpstate processes");
        printWriter.println("-----------------------------");
        int i = 0;
        while (i < size) {
            printWriter.print("#");
            int i2 = i + 1;
            printWriter.println(i2);
            printWriter.println(this.mProcesses.valueAt(i).info);
            i = i2;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ServiceHandler extends Handler {
        public ServiceHandler(String str) {
            super(BugreportProgressService.newLooper(str));
        }

        /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
        /* JADX WARN: Code restructure failed: missing block: B:38:0x00d4, code lost:
            if (r15.equals("com.android.internal.intent.action.BUGREPORT_STARTED") != false) goto L26;
         */
        @Override // android.os.Handler
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void handleMessage(android.os.Message r15) {
            /*
                Method dump skipped, instructions count: 360
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.shell.BugreportProgressService.ServiceHandler.handleMessage(android.os.Message):void");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ScreenshotHandler extends Handler {
        public ScreenshotHandler(String str) {
            super(BugreportProgressService.newLooper(str));
        }

        @Override // android.os.Handler
        public void handleMessage(Message message) {
            if (message.what == 3) {
                BugreportProgressService.this.handleScreenshotRequest(message);
                return;
            }
            Log.e("BugreportProgressService", "Invalid message type: " + message.what);
        }
    }

    private BugreportInfo getInfo(int i) {
        DumpstateListener dumpstateListener = this.mProcesses.get(i);
        if (dumpstateListener == null) {
            Log.w("BugreportProgressService", "Not monitoring process with ID " + i);
            return null;
        }
        return dumpstateListener.info;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean startProgress(String str, int i, int i2, int i3) {
        if (str == null) {
            Log.w("BugreportProgressService", "Missing android.intent.extra.NAME on start intent");
        }
        if (i == -1) {
            Log.e("BugreportProgressService", "Missing android.intent.extra.ID on start intent");
            return false;
        } else if (i2 == -1) {
            Log.e("BugreportProgressService", "Missing android.intent.extra.PID on start intent");
            return false;
        } else if (i3 <= 0) {
            Log.e("BugreportProgressService", "Invalid value for extra android.intent.extra.MAX: " + i3);
            return false;
        } else {
            BugreportInfo bugreportInfo = new BugreportInfo(this.mContext, i, i2, str, i3);
            if (this.mProcesses.indexOfKey(i) >= 0) {
                Log.w("BugreportProgressService", "ID " + i + " already watched");
                return true;
            }
            DumpstateListener dumpstateListener = new DumpstateListener(bugreportInfo);
            this.mProcesses.put(bugreportInfo.id, dumpstateListener);
            if (dumpstateListener.connect()) {
                updateProgress(bugreportInfo);
                return true;
            }
            Log.w("BugreportProgressService", "not updating progress because it could not connect to dumpstate");
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateProgress(BugreportInfo bugreportInfo) {
        if (bugreportInfo.max <= 0 || bugreportInfo.progress < 0) {
            Log.e("BugreportProgressService", "Invalid progress values for " + bugreportInfo);
        } else if (bugreportInfo.finished) {
            Log.w("BugreportProgressService", "Not sending progress notification because bugreport has finished already (" + bugreportInfo + ")");
        } else {
            NumberFormat percentInstance = NumberFormat.getPercentInstance();
            percentInstance.setMinimumFractionDigits(2);
            percentInstance.setMaximumFractionDigits(2);
            String format = percentInstance.format(bugreportInfo.progress / bugreportInfo.max);
            String string = this.mContext.getString(R.string.bugreport_in_progress_title, Integer.valueOf(bugreportInfo.id));
            if (this.mIsWatch) {
                percentInstance.setMinimumFractionDigits(0);
                percentInstance.setMaximumFractionDigits(0);
                string = string + "\n" + percentInstance.format(bugreportInfo.progress / bugreportInfo.max);
            }
            String str = bugreportInfo.name;
            if (str == null) {
                str = this.mContext.getString(R.string.bugreport_unnamed);
            }
            Notification.Builder ongoing = newBaseNotification(this.mContext).setContentTitle(string).setTicker(string).setContentText(str).setProgress(bugreportInfo.max, bugreportInfo.progress, false).setOngoing(true);
            if (!this.mIsWatch && !this.mIsTv) {
                Notification.Action build = new Notification.Action.Builder((Icon) null, this.mContext.getString(17039360), newCancelIntent(this.mContext, bugreportInfo)).build();
                Intent intent = new Intent(this.mContext, BugreportProgressService.class);
                intent.setAction("android.intent.action.BUGREPORT_INFO_LAUNCH");
                intent.putExtra("android.intent.extra.ID", bugreportInfo.id);
                PendingIntent service = PendingIntent.getService(this.mContext, bugreportInfo.id, intent, 134217728);
                Notification.Action build2 = new Notification.Action.Builder((Icon) null, this.mContext.getString(R.string.bugreport_info_action), service).build();
                Intent intent2 = new Intent(this.mContext, BugreportProgressService.class);
                intent2.setAction("android.intent.action.BUGREPORT_SCREENSHOT");
                intent2.putExtra("android.intent.extra.ID", bugreportInfo.id);
                ongoing.setContentIntent(service).setActions(build2, new Notification.Action.Builder((Icon) null, this.mContext.getString(R.string.bugreport_screenshot_action), this.mTakingScreenshot ? null : PendingIntent.getService(this.mContext, bugreportInfo.id, intent2, 134217728)).build(), build);
            }
            int i = bugreportInfo.progress;
            int i2 = (i * 100) / bugreportInfo.max;
            if (i == 0 || i >= 100 || i2 / 10 != this.mLastProgressPercent / 10) {
                Log.d("BugreportProgressService", "Progress #" + bugreportInfo.id + ": " + format);
            }
            this.mLastProgressPercent = i2;
            sendForegroundabledNotification(bugreportInfo.id, ongoing.build());
        }
    }

    private void sendForegroundabledNotification(int i, Notification notification) {
        if (this.mForegroundId >= 0) {
            NotificationManager.from(this.mContext).notify(i, notification);
            return;
        }
        this.mForegroundId = i;
        Log.d("BugreportProgressService", "Start running as foreground service on id " + this.mForegroundId);
        startForeground(this.mForegroundId, notification);
    }

    private static PendingIntent newCancelIntent(Context context, BugreportInfo bugreportInfo) {
        Intent intent = new Intent("android.intent.action.BUGREPORT_CANCEL");
        intent.setClass(context, BugreportProgressService.class);
        intent.putExtra("android.intent.extra.ID", bugreportInfo.id);
        return PendingIntent.getService(context, bugreportInfo.id, intent, 134217728);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopProgress(int i) {
        if (this.mProcesses.indexOfKey(i) < 0) {
            Log.w("BugreportProgressService", "ID not watched: " + i);
        } else {
            Log.d("BugreportProgressService", "Removing ID " + i);
            this.mProcesses.remove(i);
        }
        stopForegroundWhenDone(i);
        Log.d("BugreportProgressService", "stopProgress(" + i + "): cancel notification");
        NotificationManager.from(this.mContext).cancel(i);
        stopSelfWhenDone();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancel(int i) {
        MetricsLogger.action(this, 296);
        Log.v("BugreportProgressService", "cancel: ID=" + i);
        this.mInfoDialog.cancel();
        BugreportInfo info = getInfo(i);
        if (info != null && !info.finished) {
            Log.i("BugreportProgressService", "Cancelling bugreport service (ID=" + i + ") on user's request");
            setSystemProperty("ctl.stop", "bugreport");
            deleteScreenshots(info);
        }
        stopProgress(i);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void launchBugreportInfoDialog(int i) {
        MetricsLogger.action(this, 297);
        final BugreportInfo info = getInfo(i);
        if (info == null) {
            Log.w("BugreportProgressService", "launchBugreportInfoDialog(): canceling notification because id " + i + " was not found");
            NotificationManager.from(this.mContext).cancel(i);
            return;
        }
        collapseNotificationBar();
        try {
            IWindowManager.Stub.asInterface(ServiceManager.getService("window")).dismissKeyguard((IKeyguardDismissCallback) null, (CharSequence) null);
        } catch (Exception unused) {
        }
        this.mMainThreadHandler.post(new Runnable() { // from class: com.android.shell.-$$Lambda$BugreportProgressService$nlOKvqBw46I5iKhbiBMadgRbJIc
            @Override // java.lang.Runnable
            public final void run() {
                BugreportProgressService.this.lambda$launchBugreportInfoDialog$0$BugreportProgressService(info);
            }
        });
    }

    public /* synthetic */ void lambda$launchBugreportInfoDialog$0$BugreportProgressService(BugreportInfo bugreportInfo) {
        this.mInfoDialog.initialize(this.mContext, bugreportInfo);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void takeScreenshot(int i) {
        MetricsLogger.action(this, 298);
        if (getInfo(i) == null) {
            Log.w("BugreportProgressService", "takeScreenshot(): canceling notification because id " + i + " was not found");
            NotificationManager.from(this.mContext).cancel(i);
            return;
        }
        setTakingScreenshot(true);
        collapseNotificationBar();
        String quantityString = this.mContext.getResources().getQuantityString(18153473, 3, 3);
        Log.i("BugreportProgressService", quantityString);
        Toast.makeText(this.mContext, quantityString, 0).show();
        takeScreenshot(i, 3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void takeScreenshot(int i, int i2) {
        if (i2 > 0) {
            Log.d("BugreportProgressService", "Taking screenshot for " + i + " in " + i2 + " seconds");
            Message obtainMessage = this.mServiceHandler.obtainMessage();
            obtainMessage.what = 2;
            obtainMessage.arg1 = i;
            obtainMessage.arg2 = i2 + (-1);
            this.mServiceHandler.sendMessageDelayed(obtainMessage, 1000L);
            return;
        }
        BugreportInfo info = getInfo(i);
        if (info == null) {
            return;
        }
        Message.obtain(this.mScreenshotHandler, 3, i, -2, new File(this.mScreenshotsDir, info.getPathNextScreenshot()).getAbsolutePath()).sendToTarget();
    }

    private void setTakingScreenshot(boolean z) {
        synchronized (this) {
            this.mTakingScreenshot = z;
            for (int i = 0; i < this.mProcesses.size(); i++) {
                BugreportInfo bugreportInfo = this.mProcesses.valueAt(i).info;
                if (bugreportInfo.finished) {
                    Log.d("BugreportProgressService", "Not updating progress for " + bugreportInfo.id + " while taking screenshot because share notification was already sent");
                } else {
                    updateProgress(bugreportInfo);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleScreenshotRequest(Message message) {
        String str = (String) message.obj;
        boolean takeScreenshot = takeScreenshot(this.mContext, str);
        setTakingScreenshot(false);
        Message.obtain(this.mServiceHandler, 4, message.arg1, takeScreenshot ? 1 : 0, str).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleScreenshotResponse(Message message) {
        String str;
        boolean z = message.arg2 != 0;
        BugreportInfo info = getInfo(message.arg1);
        if (info == null) {
            return;
        }
        File file = new File((String) message.obj);
        if (z) {
            info.addScreenshot(file);
            if (info.finished) {
                Log.d("BugreportProgressService", "Screenshot finished after bugreport; updating share notification");
                info.renameScreenshots(this.mScreenshotsDir);
                sendBugreportNotification(info, this.mTakingScreenshot);
            }
            str = this.mContext.getString(R.string.bugreport_screenshot_taken);
        } else {
            String string = this.mContext.getString(R.string.bugreport_screenshot_failed);
            Toast.makeText(this.mContext, string, 0).show();
            str = string;
        }
        Log.d("BugreportProgressService", str);
    }

    private void deleteScreenshots(BugreportInfo bugreportInfo) {
        for (File file : bugreportInfo.screenshotFiles) {
            Log.i("BugreportProgressService", "Deleting screenshot file " + file);
            file.delete();
        }
    }

    private void stopForegroundWhenDone(int i) {
        if (i != this.mForegroundId) {
            Log.d("BugreportProgressService", "stopForegroundWhenDone(" + i + "): ignoring since foreground id is " + this.mForegroundId);
            return;
        }
        Log.d("BugreportProgressService", "detaching foreground from id " + this.mForegroundId);
        stopForeground(2);
        this.mForegroundId = -1;
        int size = this.mProcesses.size();
        if (size > 0) {
            for (int i2 = 0; i2 < size; i2++) {
                BugreportInfo bugreportInfo = this.mProcesses.valueAt(i2).info;
                if (!bugreportInfo.finished) {
                    updateProgress(bugreportInfo);
                    return;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopSelfWhenDone() {
        if (this.mProcesses.size() > 0) {
            return;
        }
        Log.v("BugreportProgressService", "No more processes to handle, shutting down");
        stopSelf();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onBugreportFinished(int i, Intent intent) {
        File fileExtra = getFileExtra(intent, "android.intent.extra.BUGREPORT");
        if (fileExtra == null) {
            Log.wtf("BugreportProgressService", "Missing android.intent.extra.BUGREPORT on intent " + intent);
            return;
        }
        onBugreportFinished(i, fileExtra, getFileExtra(intent, "android.intent.extra.SCREENSHOT"), intent.getStringExtra("android.intent.extra.TITLE"), intent.getStringExtra("android.intent.extra.DESCRIPTION"), intent.getIntExtra("android.intent.extra.MAX", -1));
    }

    private void onBugreportFinished(int i, File file, File file2, String str, String str2, int i2) {
        this.mInfoDialog.onBugreportFinished();
        BugreportInfo info = getInfo(i);
        if (info == null) {
            Log.v("BugreportProgressService", "Creating info for untracked ID " + i);
            info = new BugreportInfo(this.mContext, i);
            this.mProcesses.put(i, new DumpstateListener(info));
        }
        info.renameScreenshots(this.mScreenshotsDir);
        info.bugreportFile = file;
        if (file2 != null) {
            info.addScreenshot(file2);
        }
        if (i2 != -1) {
            MetricsLogger.histogram(this, "dumpstate_duration", i2);
            info.max = i2;
        }
        if (!TextUtils.isEmpty(str)) {
            info.title = str;
            if (!TextUtils.isEmpty(str2)) {
                info.shareDescription = str2;
            }
            Log.d("BugreportProgressService", "Bugreport title is " + info.title + ", shareDescription is " + info.shareDescription);
        }
        info.finished = true;
        stopForegroundWhenDone(i);
        triggerLocalNotification(this.mContext, info);
    }

    private void triggerLocalNotification(Context context, BugreportInfo bugreportInfo) {
        if (!bugreportInfo.bugreportFile.exists() || !bugreportInfo.bugreportFile.canRead()) {
            Log.e("BugreportProgressService", "Could not read bugreport file " + bugreportInfo.bugreportFile);
            Toast.makeText(context, (int) R.string.bugreport_unreadable_text, 1).show();
            stopProgress(bugreportInfo.id);
        } else if (!bugreportInfo.bugreportFile.getName().toLowerCase().endsWith(".txt")) {
            sendBugreportNotification(bugreportInfo, this.mTakingScreenshot);
        } else {
            sendZippedBugreportNotification(bugreportInfo, this.mTakingScreenshot);
        }
    }

    private static Intent buildWarningIntent(Context context, Intent intent) {
        Intent intent2 = new Intent(context, BugreportWarningActivity.class);
        intent2.putExtra("android.intent.extra.INTENT", intent);
        return intent2;
    }

    private static Intent buildSendIntent(Context context, BugreportInfo bugreportInfo) {
        int i;
        try {
            Uri uri = getUri(context, bugreportInfo.bugreportFile);
            Intent intent = new Intent("android.intent.action.SEND_MULTIPLE");
            intent.addFlags(1);
            intent.addCategory("android.intent.category.DEFAULT");
            intent.setType("application/vnd.android.bugreport");
            String lastPathSegment = !TextUtils.isEmpty(bugreportInfo.title) ? bugreportInfo.title : uri.getLastPathSegment();
            intent.putExtra("android.intent.extra.SUBJECT", lastPathSegment);
            StringBuilder sb = new StringBuilder("Build info: ");
            sb.append(SystemProperties.get("ro.build.description"));
            sb.append("\nSerial number: ");
            sb.append(SystemProperties.get("ro.serialno"));
            if (TextUtils.isEmpty(bugreportInfo.description)) {
                i = 0;
            } else {
                sb.append("\nDescription: ");
                sb.append(bugreportInfo.description);
                i = bugreportInfo.description.length();
            }
            intent.putExtra("android.intent.extra.TEXT", sb.toString());
            ClipData clipData = new ClipData(null, new String[]{"application/vnd.android.bugreport"}, new ClipData.Item(null, null, null, uri));
            Log.d("BugreportProgressService", "share intent: bureportUri=" + uri);
            ArrayList<? extends Parcelable> newArrayList = Lists.newArrayList(new Uri[]{uri});
            for (File file : bugreportInfo.screenshotFiles) {
                Uri uri2 = getUri(context, file);
                Log.d("BugreportProgressService", "share intent: screenshotUri=" + uri2);
                clipData.addItem(new ClipData.Item(null, null, null, uri2));
                newArrayList.add(uri2);
            }
            intent.setClipData(clipData);
            intent.putParcelableArrayListExtra("android.intent.extra.STREAM", newArrayList);
            Pair<UserHandle, Account> findSendToAccount = findSendToAccount(context, SystemProperties.get("sendbug.preferred.domain"));
            if (findSendToAccount != null) {
                intent.putExtra("android.intent.extra.EMAIL", new String[]{((Account) findSendToAccount.second).name});
            }
            Log.d("BugreportProgressService", "share intent: EXTRA_SUBJECT=" + lastPathSegment + ", EXTRA_TEXT=" + sb.length() + " chars, description=" + i + " chars");
            return intent;
        } catch (IllegalArgumentException e) {
            Log.wtf("BugreportProgressService", "Could not get URI for " + bugreportInfo.bugreportFile, e);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void shareBugreport(int i, BugreportInfo bugreportInfo) {
        MetricsLogger.action(this, 299);
        BugreportInfo info = getInfo(i);
        if (info == null) {
            Log.d("BugreportProgressService", "shareBugreport(): no info for ID " + i + " on managed processes (" + this.mProcesses + "), using info from intent instead (" + bugreportInfo + ")");
        } else {
            Log.v("BugreportProgressService", "shareBugReport(): id " + i + " info = " + info);
            bugreportInfo = info;
        }
        addDetailsToZipFile(bugreportInfo);
        Intent buildSendIntent = buildSendIntent(this.mContext, bugreportInfo);
        if (buildSendIntent == null) {
            Log.w("BugreportProgressService", "Stopping progres on ID " + i + " because share intent could not be built");
            stopProgress(i);
            return;
        }
        boolean z = true;
        if (BugreportPrefs.getWarningState(this.mContext, 0) != 2) {
            buildSendIntent = buildWarningIntent(this.mContext, buildSendIntent);
            z = false;
        }
        buildSendIntent.addFlags(268435456);
        if (z) {
            sendShareIntent(this.mContext, buildSendIntent);
        } else {
            this.mContext.startActivity(buildSendIntent);
        }
        stopProgress(i);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void sendShareIntent(Context context, Intent intent) {
        Intent createChooser = Intent.createChooser(intent, context.getResources().getText(R.string.bugreport_intent_chooser_title));
        createChooser.putExtra("com.android.internal.app.ChooserActivity.EXTRA_PRIVATE_RETAIN_IN_ON_STOP", true);
        createChooser.addFlags(268435456);
        context.startActivity(createChooser);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendBugreportNotification(BugreportInfo bugreportInfo, boolean z) {
        String string;
        String str;
        addDetailsToZipFile(bugreportInfo);
        Intent intent = new Intent("android.intent.action.BUGREPORT_SHARE");
        intent.setClass(this.mContext, BugreportProgressService.class);
        intent.setAction("android.intent.action.BUGREPORT_SHARE");
        intent.putExtra("android.intent.extra.ID", bugreportInfo.id);
        intent.putExtra("android.intent.extra.INFO", bugreportInfo);
        if (z) {
            string = this.mContext.getString(R.string.bugreport_finished_pending_screenshot_text);
        } else {
            string = this.mContext.getString(R.string.bugreport_finished_text);
        }
        if (TextUtils.isEmpty(bugreportInfo.title)) {
            str = this.mContext.getString(R.string.bugreport_finished_title, Integer.valueOf(bugreportInfo.id));
        } else {
            str = bugreportInfo.title;
            if (!TextUtils.isEmpty(bugreportInfo.shareDescription) && !z) {
                string = bugreportInfo.shareDescription;
            }
        }
        Notification.Builder deleteIntent = newBaseNotification(this.mContext).setContentTitle(str).setTicker(str).setContentText(string).setContentIntent(PendingIntent.getService(this.mContext, bugreportInfo.id, intent, 134217728)).setDeleteIntent(newCancelIntent(this.mContext, bugreportInfo));
        if (!TextUtils.isEmpty(bugreportInfo.name)) {
            deleteIntent.setSubText(bugreportInfo.name);
        }
        Log.v("BugreportProgressService", "Sending 'Share' notification for ID " + bugreportInfo.id + ": " + str);
        NotificationManager.from(this.mContext).notify(bugreportInfo.id, deleteIntent.build());
    }

    private void sendBugreportBeingUpdatedNotification(Context context, int i) {
        String string = context.getString(R.string.bugreport_updating_title);
        Notification.Builder contentText = newBaseNotification(context).setContentTitle(string).setTicker(string).setContentText(context.getString(R.string.bugreport_updating_wait));
        Log.v("BugreportProgressService", "Sending 'Updating zip' notification for ID " + i + ": " + string);
        sendForegroundabledNotification(i, contentText.build());
    }

    private static Notification.Builder newBaseNotification(Context context) {
        synchronized (sNotificationBundle) {
            if (sNotificationBundle.isEmpty()) {
                sNotificationBundle.putString("android.substName", context.getString(17039490));
            }
        }
        return new Notification.Builder(context, "bugreports").addExtras(sNotificationBundle).setSmallIcon(isTv(context) ? R.drawable.ic_bug_report_black_24dp : 17303526).setLocalOnly(true).setColor(context.getColor(17170460)).extend(new Notification.TvExtender());
    }

    private void sendZippedBugreportNotification(final BugreportInfo bugreportInfo, final boolean z) {
        new AsyncTask<Void, Void, Void>() { // from class: com.android.shell.BugreportProgressService.1
            /* JADX INFO: Access modifiers changed from: protected */
            @Override // android.os.AsyncTask
            public Void doInBackground(Void... voidArr) {
                Looper.prepare();
                BugreportProgressService.zipBugreport(bugreportInfo);
                BugreportProgressService.this.sendBugreportNotification(bugreportInfo, z);
                return null;
            }
        }.execute(new Void[0]);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void zipBugreport(BugreportInfo bugreportInfo) {
        String absolutePath = bugreportInfo.bugreportFile.getAbsolutePath();
        String replace = absolutePath.replace(".txt", ".zip");
        Log.v("BugreportProgressService", "zipping " + absolutePath + " as " + replace);
        File file = new File(replace);
        try {
            FileInputStream fileInputStream = new FileInputStream(bugreportInfo.bugreportFile);
            ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
            try {
                addEntry(zipOutputStream, bugreportInfo.bugreportFile.getName(), fileInputStream);
                if (bugreportInfo.bugreportFile.delete()) {
                    Log.v("BugreportProgressService", "deleted original bugreport (" + absolutePath + ")");
                } else {
                    Log.e("BugreportProgressService", "could not delete original bugreport (" + absolutePath + ")");
                }
                bugreportInfo.bugreportFile = file;
                $closeResource(null, zipOutputStream);
                $closeResource(null, fileInputStream);
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    $closeResource(th, zipOutputStream);
                    throw th2;
                }
            }
        } catch (IOException e) {
            Log.e("BugreportProgressService", "exception zipping file " + replace, e);
        }
    }

    private static /* synthetic */ void $closeResource(Throwable th, AutoCloseable autoCloseable) {
        if (th == null) {
            autoCloseable.close();
            return;
        }
        try {
            autoCloseable.close();
        } catch (Throwable th2) {
            th.addSuppressed(th2);
        }
    }

    private void addDetailsToZipFile(BugreportInfo bugreportInfo) {
        synchronized (this.mLock) {
            addDetailsToZipFileLocked(bugreportInfo);
        }
    }

    private void addDetailsToZipFileLocked(BugreportInfo bugreportInfo) {
        if (bugreportInfo.bugreportFile == null) {
            Log.wtf("BugreportProgressService", "addDetailsToZipFile(): no bugreportFile on " + bugreportInfo);
        } else if (TextUtils.isEmpty(bugreportInfo.title) && TextUtils.isEmpty(bugreportInfo.description)) {
            Log.d("BugreportProgressService", "Not touching zip file since neither title nor description are set");
        } else if (bugreportInfo.addedDetailsToZip || bugreportInfo.addingDetailsToZip) {
            Log.d("BugreportProgressService", "Already added details to zip file for " + bugreportInfo);
        } else {
            bugreportInfo.addingDetailsToZip = true;
            sendBugreportBeingUpdatedNotification(this.mContext, bugreportInfo.id);
            File parentFile = bugreportInfo.bugreportFile.getParentFile();
            File file = new File(parentFile, "tmp-" + bugreportInfo.bugreportFile.getName());
            Log.d("BugreportProgressService", "Writing temporary zip file (" + file + ") with title and/or description");
            try {
                try {
                    ZipFile zipFile = new ZipFile(bugreportInfo.bugreportFile);
                    try {
                        ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(file));
                        Enumeration<? extends ZipEntry> entries = zipFile.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry nextElement = entries.nextElement();
                            String name = nextElement.getName();
                            if (!nextElement.isDirectory()) {
                                addEntry(zipOutputStream, name, nextElement.getTime(), zipFile.getInputStream(nextElement));
                            } else {
                                Log.w("BugreportProgressService", "skipping directory entry: " + name);
                            }
                        }
                        addEntry(zipOutputStream, "title.txt", bugreportInfo.title);
                        addEntry(zipOutputStream, "description.txt", bugreportInfo.description);
                        $closeResource(null, zipOutputStream);
                        $closeResource(null, zipFile);
                        bugreportInfo.addedDetailsToZip = true;
                        bugreportInfo.addingDetailsToZip = false;
                        stopForegroundWhenDone(bugreportInfo.id);
                        if (file.renameTo(bugreportInfo.bugreportFile)) {
                            return;
                        }
                        Log.e("BugreportProgressService", "Could not rename " + file + " to " + bugreportInfo.bugreportFile);
                    } catch (Throwable th) {
                        try {
                            throw th;
                        } catch (Throwable th2) {
                            $closeResource(th, zipFile);
                            throw th2;
                        }
                    }
                } catch (Throwable th3) {
                    bugreportInfo.addedDetailsToZip = true;
                    bugreportInfo.addingDetailsToZip = false;
                    stopForegroundWhenDone(bugreportInfo.id);
                    throw th3;
                }
            } catch (IOException e) {
                Log.e("BugreportProgressService", "exception zipping file " + file, e);
                Toast.makeText(this.mContext, (int) R.string.bugreport_add_details_to_zip_failed, 1).show();
                bugreportInfo.addedDetailsToZip = true;
                bugreportInfo.addingDetailsToZip = false;
                stopForegroundWhenDone(bugreportInfo.id);
            }
        }
    }

    private static void addEntry(ZipOutputStream zipOutputStream, String str, String str2) throws IOException {
        if (TextUtils.isEmpty(str2)) {
            return;
        }
        addEntry(zipOutputStream, str, new ByteArrayInputStream(str2.getBytes(StandardCharsets.UTF_8)));
    }

    private static void addEntry(ZipOutputStream zipOutputStream, String str, InputStream inputStream) throws IOException {
        addEntry(zipOutputStream, str, System.currentTimeMillis(), inputStream);
    }

    private static void addEntry(ZipOutputStream zipOutputStream, String str, long j, InputStream inputStream) throws IOException {
        ZipEntry zipEntry = new ZipEntry(str);
        zipEntry.setTime(j);
        zipOutputStream.putNextEntry(zipEntry);
        Streams.copy(inputStream, zipOutputStream);
        zipOutputStream.closeEntry();
    }

    @VisibleForTesting
    static Pair<UserHandle, Account> findSendToAccount(Context context, String str) {
        Account[] accountsAsUser;
        UserManager userManager = (UserManager) context.getSystemService(UserManager.class);
        AccountManager accountManager = (AccountManager) context.getSystemService(AccountManager.class);
        if (str != null && !str.startsWith("@")) {
            str = "@" + str;
        }
        Pair<UserHandle, Account> pair = null;
        for (UserHandle userHandle : userManager.getUserProfiles()) {
            try {
                for (Account account : accountManager.getAccountsAsUser(userHandle.getIdentifier())) {
                    if (Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
                        Pair<UserHandle, Account> create = Pair.create(userHandle, account);
                        if (TextUtils.isEmpty(str) || account.name.endsWith(str)) {
                            return create;
                        }
                        if (pair == null) {
                            pair = create;
                        }
                    }
                }
                continue;
            } catch (RuntimeException e) {
                Log.e("BugreportProgressService", "Could not get accounts for preferred domain " + str + " for user " + userHandle, e);
            }
        }
        return pair;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static Uri getUri(Context context, File file) {
        if (file != null) {
            return FileProvider.getUriForFile(context, "com.android.shell", file);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static File getFileExtra(Intent intent, String str) {
        String stringExtra = intent.getStringExtra(str);
        if (stringExtra != null) {
            return new File(stringExtra);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String dumpIntent(Intent intent) {
        if (intent == null) {
            return "NO INTENT";
        }
        String action = intent.getAction();
        if (action == null) {
            action = "no action";
        }
        StringBuilder sb = new StringBuilder(action);
        sb.append(" extras: ");
        addExtra(sb, intent, "android.intent.extra.ID");
        addExtra(sb, intent, "android.intent.extra.PID");
        addExtra(sb, intent, "android.intent.extra.MAX");
        addExtra(sb, intent, "android.intent.extra.NAME");
        addExtra(sb, intent, "android.intent.extra.DESCRIPTION");
        addExtra(sb, intent, "android.intent.extra.BUGREPORT");
        addExtra(sb, intent, "android.intent.extra.SCREENSHOT");
        addExtra(sb, intent, "android.intent.extra.INFO");
        addExtra(sb, intent, "android.intent.extra.TITLE");
        if (intent.hasExtra("android.intent.extra.ORIGINAL_INTENT")) {
            sb.append(SHORT_EXTRA_ORIGINAL_INTENT);
            sb.append(": ");
            sb.append(dumpIntent((Intent) intent.getParcelableExtra("android.intent.extra.ORIGINAL_INTENT")));
        } else {
            sb.append("no ");
            sb.append(SHORT_EXTRA_ORIGINAL_INTENT);
        }
        return sb.toString();
    }

    private static void addExtra(StringBuilder sb, Intent intent, String str) {
        String substring = str.substring(str.lastIndexOf(46) + 1);
        if (intent.hasExtra(str)) {
            sb.append(substring);
            sb.append('=');
            sb.append(intent.getExtra(str));
        } else {
            sb.append("no ");
            sb.append(substring);
        }
        sb.append(", ");
    }

    private static boolean setSystemProperty(String str, String str2) {
        try {
            SystemProperties.set(str, str2);
            return true;
        } catch (IllegalArgumentException e) {
            Log.e("BugreportProgressService", "Could not set property " + str + " to " + str2, e);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean setBugreportNameProperty(int i, String str) {
        Log.d("BugreportProgressService", "Updating bugreport name to " + str);
        return setSystemProperty("dumpstate." + i + ".name", str);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateBugreportInfo(int i, String str, String str2, String str3) {
        BugreportInfo info = getInfo(i);
        if (info == null) {
            return;
        }
        if (str2 != null && !str2.equals(info.title)) {
            Log.d("BugreportProgressService", "updating bugreport title: " + str2);
            MetricsLogger.action(this, 301);
        }
        info.title = str2;
        if (str3 != null && !str3.equals(info.description)) {
            Log.d("BugreportProgressService", "updating bugreport description: " + str3.length() + " chars");
            MetricsLogger.action(this, 302);
        }
        info.description = str3;
        if (str == null || str.equals(info.name)) {
            return;
        }
        Log.d("BugreportProgressService", "updating bugreport name: " + str);
        MetricsLogger.action(this, 300);
        info.name = str;
        updateProgress(info);
    }

    private void collapseNotificationBar() {
        sendBroadcast(new Intent("android.intent.action.CLOSE_SYSTEM_DIALOGS"));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Looper newLooper(String str) {
        HandlerThread handlerThread = new HandlerThread(str, 10);
        handlerThread.start();
        return handlerThread.getLooper();
    }

    private static boolean takeScreenshot(Context context, String str) {
        Bitmap takeScreenshot = Screenshooter.takeScreenshot();
        try {
            if (takeScreenshot == null) {
                return false;
            }
            FileOutputStream fileOutputStream = new FileOutputStream(str);
            try {
                if (takeScreenshot.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)) {
                    ((Vibrator) context.getSystemService("vibrator")).vibrate(150L);
                    $closeResource(null, fileOutputStream);
                    return true;
                }
                Log.e("BugreportProgressService", "Failed to save screenshot on " + str);
                $closeResource(null, fileOutputStream);
                return false;
            } finally {
            }
        } catch (IOException e) {
            Log.e("BugreportProgressService", "Failed to save screenshot on " + str, e);
            return false;
        } finally {
            takeScreenshot.recycle();
        }
    }

    private static boolean isTv(Context context) {
        return context.getPackageManager().hasSystemFeature("android.software.leanback");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class BugreportInfoDialog {
        private AlertDialog mDialog;
        private int mId;
        private EditText mInfoDescription;
        private EditText mInfoName;
        private EditText mInfoTitle;
        private Button mOkButton;
        private int mPid;
        private String mSavedName;
        private String mTempName;

        private BugreportInfoDialog() {
        }

        void initialize(final Context context, BugreportInfo bugreportInfo) {
            String string = context.getString(R.string.bugreport_info_dialog_title, Integer.valueOf(bugreportInfo.id));
            ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(context, 16974563);
            AlertDialog alertDialog = this.mDialog;
            if (alertDialog == null) {
                View inflate = View.inflate(contextThemeWrapper, R.layout.dialog_bugreport_info, null);
                this.mInfoName = (EditText) inflate.findViewById(R.id.name);
                this.mInfoTitle = (EditText) inflate.findViewById(R.id.title);
                this.mInfoDescription = (EditText) inflate.findViewById(R.id.description);
                this.mInfoName.setOnFocusChangeListener(new View.OnFocusChangeListener() { // from class: com.android.shell.BugreportProgressService.BugreportInfoDialog.1
                    @Override // android.view.View.OnFocusChangeListener
                    public void onFocusChange(View view, boolean z) {
                        if (z) {
                            return;
                        }
                        BugreportInfoDialog.this.sanitizeName();
                    }
                });
                this.mDialog = new AlertDialog.Builder(contextThemeWrapper).setView(inflate).setTitle(string).setCancelable(true).setPositiveButton(context.getString(R.string.save), (DialogInterface.OnClickListener) null).setNegativeButton(context.getString(17039360), new DialogInterface.OnClickListener() { // from class: com.android.shell.BugreportProgressService.BugreportInfoDialog.2
                    @Override // android.content.DialogInterface.OnClickListener
                    public void onClick(DialogInterface dialogInterface, int i) {
                        MetricsLogger.action(context, 304);
                        if (BugreportInfoDialog.this.mTempName.equals(BugreportInfoDialog.this.mSavedName)) {
                            return;
                        }
                        BugreportInfoDialog bugreportInfoDialog = BugreportInfoDialog.this;
                        BugreportProgressService.this.setBugreportNameProperty(bugreportInfoDialog.mPid, BugreportInfoDialog.this.mSavedName);
                    }
                }).create();
                this.mDialog.getWindow().setAttributes(new WindowManager.LayoutParams(2008));
            } else {
                alertDialog.setTitle(string);
                this.mInfoName.setText((CharSequence) null);
                this.mInfoName.setEnabled(true);
                this.mInfoTitle.setText((CharSequence) null);
                this.mInfoDescription.setText((CharSequence) null);
            }
            String str = bugreportInfo.name;
            this.mTempName = str;
            this.mSavedName = str;
            this.mId = bugreportInfo.id;
            this.mPid = bugreportInfo.pid;
            if (!TextUtils.isEmpty(str)) {
                this.mInfoName.setText(bugreportInfo.name);
            }
            if (!TextUtils.isEmpty(bugreportInfo.title)) {
                this.mInfoTitle.setText(bugreportInfo.title);
            }
            if (!TextUtils.isEmpty(bugreportInfo.description)) {
                this.mInfoDescription.setText(bugreportInfo.description);
            }
            this.mDialog.show();
            if (this.mOkButton == null) {
                this.mOkButton = this.mDialog.getButton(-1);
                this.mOkButton.setOnClickListener(new View.OnClickListener() { // from class: com.android.shell.BugreportProgressService.BugreportInfoDialog.3
                    @Override // android.view.View.OnClickListener
                    public void onClick(View view) {
                        MetricsLogger.action(context, 303);
                        BugreportInfoDialog.this.sanitizeName();
                        String obj = BugreportInfoDialog.this.mInfoName.getText().toString();
                        String obj2 = BugreportInfoDialog.this.mInfoTitle.getText().toString();
                        String obj3 = BugreportInfoDialog.this.mInfoDescription.getText().toString();
                        BugreportInfoDialog bugreportInfoDialog = BugreportInfoDialog.this;
                        BugreportProgressService.this.updateBugreportInfo(bugreportInfoDialog.mId, obj, obj2, obj3);
                        BugreportInfoDialog.this.mDialog.dismiss();
                    }
                });
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void sanitizeName() {
            String obj = this.mInfoName.getText().toString();
            if (obj.equals(this.mTempName)) {
                return;
            }
            StringBuilder sb = new StringBuilder(obj.length());
            boolean z = false;
            for (int i = 0; i < obj.length(); i++) {
                char charAt = obj.charAt(i);
                if (BugreportProgressService.isValid(charAt)) {
                    sb.append(charAt);
                } else {
                    sb.append('_');
                    z = true;
                }
            }
            if (z) {
                Log.v("BugreportProgressService", "changed invalid name '" + obj + "' to '" + ((Object) sb) + "'");
                obj = sb.toString();
                this.mInfoName.setText(obj);
            }
            this.mTempName = obj;
            BugreportProgressService.this.setBugreportNameProperty(this.mPid, obj);
        }

        void onBugreportFinished() {
            EditText editText = this.mInfoName;
            if (editText != null) {
                editText.setEnabled(false);
                this.mInfoName.setText(this.mSavedName);
            }
        }

        void cancel() {
            AlertDialog alertDialog = this.mDialog;
            if (alertDialog != null) {
                alertDialog.cancel();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class BugreportInfo implements Parcelable {
        public static final Parcelable.Creator<BugreportInfo> CREATOR = new Parcelable.Creator<BugreportInfo>() { // from class: com.android.shell.BugreportProgressService.BugreportInfo.1
            /* JADX WARN: Can't rename method to resolve collision */
            @Override // android.os.Parcelable.Creator
            public BugreportInfo createFromParcel(Parcel parcel) {
                return new BugreportInfo(parcel);
            }

            /* JADX WARN: Can't rename method to resolve collision */
            @Override // android.os.Parcelable.Creator
            public BugreportInfo[] newArray(int i) {
                return new BugreportInfo[i];
            }
        };
        boolean addedDetailsToZip;
        boolean addingDetailsToZip;
        File bugreportFile;
        private final Context context;
        String description;
        boolean finished;
        String formattedLastUpdate;
        final int id;
        long lastUpdate;
        int max;
        String name;
        final int pid;
        int progress;
        int realMax;
        int realProgress;
        int screenshotCounter;
        List<File> screenshotFiles;
        String shareDescription;
        String title;

        @Override // android.os.Parcelable
        public int describeContents() {
            return 0;
        }

        BugreportInfo(Context context, int i, int i2, String str, int i3) {
            this.lastUpdate = System.currentTimeMillis();
            this.screenshotFiles = new ArrayList(1);
            this.context = context;
            this.id = i;
            this.pid = i2;
            this.name = str;
            this.realMax = i3;
            this.max = i3;
        }

        BugreportInfo(Context context, int i) {
            this(context, i, i, null, 0);
            this.finished = true;
        }

        String getPathNextScreenshot() {
            this.screenshotCounter++;
            return "screenshot-" + this.pid + "-" + this.screenshotCounter + ".png";
        }

        void addScreenshot(File file) {
            this.screenshotFiles.add(file);
        }

        void renameScreenshots(File file) {
            if (TextUtils.isEmpty(this.name)) {
                return;
            }
            ArrayList arrayList = new ArrayList(this.screenshotFiles.size());
            for (File file2 : this.screenshotFiles) {
                String name = file2.getName();
                String replaceFirst = name.replaceFirst(Integer.toString(this.pid), this.name);
                if (replaceFirst.equals(name)) {
                    Log.w("BugreportProgressService", "Name didn't change: " + name);
                } else {
                    File file3 = new File(file, replaceFirst);
                    Log.d("BugreportProgressService", "Renaming screenshot file " + file2 + " to " + file3);
                    if (file2.renameTo(file3)) {
                        file2 = file3;
                    }
                }
                arrayList.add(file2);
            }
            this.screenshotFiles = arrayList;
        }

        String getFormattedLastUpdate() {
            Context context = this.context;
            if (context == null) {
                String str = this.formattedLastUpdate;
                return str == null ? Long.toString(this.lastUpdate) : str;
            }
            return DateUtils.formatDateTime(context, this.lastUpdate, 17);
        }

        public String toString() {
            float f = (this.progress * 100.0f) / this.max;
            float f2 = (this.realProgress * 100.0f) / this.realMax;
            StringBuilder sb = new StringBuilder();
            sb.append("\tid: ");
            sb.append(this.id);
            sb.append(", pid: ");
            sb.append(this.pid);
            sb.append(", name: ");
            sb.append(this.name);
            sb.append(", finished: ");
            sb.append(this.finished);
            sb.append("\n\ttitle: ");
            sb.append(this.title);
            sb.append("\n\tdescription: ");
            String str = this.description;
            if (str == null) {
                sb.append("null");
            } else {
                if (TextUtils.getTrimmedLength(str) == 0) {
                    sb.append("empty ");
                }
                sb.append("(");
                sb.append(this.description.length());
                sb.append(" chars)");
            }
            sb.append("\n\tfile: ");
            sb.append(this.bugreportFile);
            sb.append("\n\tscreenshots: ");
            sb.append(this.screenshotFiles);
            sb.append("\n\tprogress: ");
            sb.append(this.progress);
            sb.append("/");
            sb.append(this.max);
            sb.append(" (");
            sb.append(f);
            sb.append(")");
            sb.append("\n\treal progress: ");
            sb.append(this.realProgress);
            sb.append("/");
            sb.append(this.realMax);
            sb.append(" (");
            sb.append(f2);
            sb.append(")");
            sb.append("\n\tlast_update: ");
            sb.append(getFormattedLastUpdate());
            sb.append("\n\taddingDetailsToZip: ");
            sb.append(this.addingDetailsToZip);
            sb.append(" addedDetailsToZip: ");
            sb.append(this.addedDetailsToZip);
            sb.append("\n\tshareDescription: ");
            sb.append(this.shareDescription);
            return sb.toString();
        }

        protected BugreportInfo(Parcel parcel) {
            this.lastUpdate = System.currentTimeMillis();
            this.screenshotFiles = new ArrayList(1);
            this.context = null;
            this.id = parcel.readInt();
            this.pid = parcel.readInt();
            this.name = parcel.readString();
            this.title = parcel.readString();
            this.description = parcel.readString();
            this.max = parcel.readInt();
            this.progress = parcel.readInt();
            this.realMax = parcel.readInt();
            this.realProgress = parcel.readInt();
            this.lastUpdate = parcel.readLong();
            this.formattedLastUpdate = parcel.readString();
            this.bugreportFile = readFile(parcel);
            int readInt = parcel.readInt();
            for (int i = 1; i <= readInt; i++) {
                this.screenshotFiles.add(readFile(parcel));
            }
            this.finished = parcel.readInt() == 1;
            this.screenshotCounter = parcel.readInt();
            this.shareDescription = parcel.readString();
        }

        @Override // android.os.Parcelable
        public void writeToParcel(Parcel parcel, int i) {
            parcel.writeInt(this.id);
            parcel.writeInt(this.pid);
            parcel.writeString(this.name);
            parcel.writeString(this.title);
            parcel.writeString(this.description);
            parcel.writeInt(this.max);
            parcel.writeInt(this.progress);
            parcel.writeInt(this.realMax);
            parcel.writeInt(this.realProgress);
            parcel.writeLong(this.lastUpdate);
            parcel.writeString(getFormattedLastUpdate());
            writeFile(parcel, this.bugreportFile);
            parcel.writeInt(this.screenshotFiles.size());
            for (File file : this.screenshotFiles) {
                writeFile(parcel, file);
            }
            parcel.writeInt(this.finished ? 1 : 0);
            parcel.writeInt(this.screenshotCounter);
            parcel.writeString(this.shareDescription);
        }

        private void writeFile(Parcel parcel, File file) {
            parcel.writeString(file == null ? null : file.getPath());
        }

        private File readFile(Parcel parcel) {
            String readString = parcel.readString();
            if (readString == null) {
                return null;
            }
            return new File(readString);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class DumpstateListener extends IDumpstateListener.Stub implements IBinder.DeathRecipient {
        private final BugreportInfo info;
        private IDumpstateToken token;

        @Override // android.os.IDumpstateListener
        public void onError(int i) throws RemoteException {
        }

        @Override // android.os.IDumpstateListener
        public void onFinished() throws RemoteException {
        }

        @Override // android.os.IDumpstateListener
        public void onSectionComplete(String str, int i, int i2, int i3) throws RemoteException {
        }

        DumpstateListener(BugreportInfo bugreportInfo) {
            this.info = bugreportInfo;
        }

        boolean connect() {
            if (this.token != null) {
                Log.d("BugreportProgressService", "connect(): " + this.info.id + " already connected");
                return true;
            }
            IBinder service = ServiceManager.getService("dumpstate");
            if (service == null) {
                Log.d("BugreportProgressService", "dumpstate service not bound yet");
                return true;
            }
            try {
                this.token = IDumpstate.Stub.asInterface(service).setListener("Shell", this, false);
                if (this.token != null) {
                    this.token.asBinder().linkToDeath(this, 0);
                }
            } catch (Exception e) {
                Log.e("BugreportProgressService", "Could not set dumpstate listener: " + e);
            }
            return this.token != null;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            if (!this.info.finished) {
                Log.w("BugreportProgressService", "Dumpstate process died:\n" + this.info);
                BugreportProgressService.this.stopProgress(this.info.id);
            }
            this.token.asBinder().unlinkToDeath(this, 0);
        }

        @Override // android.os.IDumpstateListener
        public void onProgress(int i) throws RemoteException {
            updateProgressInfo(i, 100);
        }

        @Override // android.os.IDumpstateListener
        public void onProgressUpdated(int i) throws RemoteException {
            BugreportInfo bugreportInfo = this.info;
            bugreportInfo.realProgress = i;
            int i2 = (bugreportInfo.progress * 10000) / bugreportInfo.max;
            int i3 = bugreportInfo.realMax;
            int i4 = (bugreportInfo.realProgress * 10000) / i3;
            if (i4 > 9900) {
                i3 = 10000;
                i = 9900;
                i4 = 9900;
            }
            if (i4 > i2) {
                updateProgressInfo(i, i3);
            }
        }

        @Override // android.os.IDumpstateListener
        public void onMaxProgressUpdated(int i) throws RemoteException {
            Log.d("BugreportProgressService", "onMaxProgressUpdated: " + i);
            this.info.realMax = i;
        }

        private void updateProgressInfo(int i, int i2) {
            BugreportInfo bugreportInfo = this.info;
            bugreportInfo.progress = i;
            bugreportInfo.max = i2;
            bugreportInfo.lastUpdate = System.currentTimeMillis();
            BugreportProgressService.this.updateProgress(this.info);
        }
    }
}
