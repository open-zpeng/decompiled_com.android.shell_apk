package com.android.shell;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.CheckBox;
import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
/* loaded from: classes.dex */
public class BugreportWarningActivity extends AlertActivity implements DialogInterface.OnClickListener {
    private CheckBox mConfirmRepeat;
    private Intent mSendIntent;

    /* JADX WARN: Multi-variable type inference failed */
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        this.mSendIntent = (Intent) getIntent().getParcelableExtra("android.intent.extra.INTENT");
        this.mSendIntent.hasExtra("android.intent.extra.STREAM");
        AlertController.AlertParams alertParams = ((AlertActivity) this).mAlertParams;
        alertParams.mView = LayoutInflater.from(this).inflate(R.layout.confirm_repeat, (ViewGroup) null);
        alertParams.mPositiveButtonText = getString(17039370);
        alertParams.mNegativeButtonText = getString(17039360);
        alertParams.mPositiveButtonListener = this;
        alertParams.mNegativeButtonListener = this;
        this.mConfirmRepeat = (CheckBox) alertParams.mView.findViewById(16908289);
        boolean z = false;
        int warningState = BugreportPrefs.getWarningState(this, 0);
        if (!Build.IS_USER ? warningState != 1 : warningState == 2) {
            z = true;
        }
        this.mConfirmRepeat.setChecked(z);
        setupAlert();
    }

    /* JADX WARN: Multi-variable type inference failed */
    @Override // android.content.DialogInterface.OnClickListener
    public void onClick(DialogInterface dialogInterface, int i) {
        if (i == -1) {
            BugreportPrefs.setWarningState(this, this.mConfirmRepeat.isChecked() ? 2 : 1);
            BugreportProgressService.sendShareIntent(this, this.mSendIntent);
        }
        finish();
    }
}
