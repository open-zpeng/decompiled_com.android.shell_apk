package com.android.shell;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import com.android.internal.content.FileSystemProvider;
import java.io.File;
import java.io.FileNotFoundException;
/* loaded from: classes.dex */
public class BugreportStorageProvider extends FileSystemProvider {
    private File mRoot;
    private static final String[] DEFAULT_ROOT_PROJECTION = {"root_id", "flags", "icon", "title", "document_id"};
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {"document_id", "mime_type", "_display_name", "last_modified", "flags", "_size"};

    public boolean onCreate() {
        super.onCreate(DEFAULT_DOCUMENT_PROJECTION);
        this.mRoot = new File(getContext().getFilesDir(), "bugreports");
        return true;
    }

    public Cursor queryRoots(String[] strArr) throws FileNotFoundException {
        MatrixCursor matrixCursor = new MatrixCursor(resolveRootProjection(strArr));
        MatrixCursor.RowBuilder newRow = matrixCursor.newRow();
        newRow.add("root_id", "bugreport");
        newRow.add("flags", 2);
        newRow.add("icon", 17629184);
        newRow.add("title", getContext().getString(R.string.bugreport_storage_title));
        newRow.add("document_id", "bugreport");
        return matrixCursor;
    }

    public Cursor queryChildDocuments(String str, String[] strArr, String str2) throws FileNotFoundException {
        Cursor queryChildDocuments = super.queryChildDocuments(str, strArr, str2);
        Bundle bundle = new Bundle();
        bundle.putCharSequence("info", getContext().getText(R.string.bugreport_confirm));
        queryChildDocuments.setExtras(bundle);
        return queryChildDocuments;
    }

    public Cursor queryDocument(String str, String[] strArr) throws FileNotFoundException {
        if ("bugreport".equals(str)) {
            MatrixCursor matrixCursor = new MatrixCursor(resolveDocumentProjection(strArr));
            includeDefaultDocument(matrixCursor);
            return matrixCursor;
        }
        return super.queryDocument(str, strArr);
    }

    public ParcelFileDescriptor openDocument(String str, String str2, CancellationSignal cancellationSignal) throws FileNotFoundException {
        if (ParcelFileDescriptor.parseMode(str2) != 268435456) {
            throw new FileNotFoundException("Failed to open: " + str + ", mode = " + str2);
        }
        return ParcelFileDescriptor.open(getFileForDocId(str), 268435456);
    }

    protected Uri buildNotificationUri(String str) {
        return DocumentsContract.buildChildDocumentsUri("com.android.shell.documents", str);
    }

    private static String[] resolveRootProjection(String[] strArr) {
        return strArr != null ? strArr : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] strArr) {
        return strArr != null ? strArr : DEFAULT_DOCUMENT_PROJECTION;
    }

    protected String getDocIdForFile(File file) {
        return "bugreport:" + file.getName();
    }

    protected File getFileForDocId(String str, boolean z) throws FileNotFoundException {
        if ("bugreport".equals(str)) {
            return this.mRoot;
        }
        int indexOf = str.indexOf(58, 1);
        String substring = str.substring(indexOf + 1);
        if (indexOf == -1 || !"bugreport".equals(str.substring(0, indexOf)) || !FileUtils.isValidExtFilename(substring)) {
            throw new FileNotFoundException("Invalid document ID: " + str);
        }
        File file = new File(this.mRoot, substring);
        if (file.exists()) {
            return file;
        }
        throw new FileNotFoundException("File not found: " + str);
    }

    protected MatrixCursor.RowBuilder includeFile(MatrixCursor matrixCursor, String str, File file) throws FileNotFoundException {
        MatrixCursor.RowBuilder includeFile = super.includeFile(matrixCursor, str, file);
        includeFile.add("flags", 4);
        return includeFile;
    }

    private void includeDefaultDocument(MatrixCursor matrixCursor) {
        MatrixCursor.RowBuilder newRow = matrixCursor.newRow();
        newRow.add("document_id", "bugreport");
        newRow.add("mime_type", "vnd.android.document/directory");
        newRow.add("_display_name", this.mRoot.getName());
        newRow.add("last_modified", Long.valueOf(this.mRoot.lastModified()));
        newRow.add("flags", 32);
    }
}
