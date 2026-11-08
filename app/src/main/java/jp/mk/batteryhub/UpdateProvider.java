package jp.mk.batteryhub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * ダウンロードした更新APKをインストーラへ渡すための最小 ContentProvider（v1.6〜）。
 * androidx の FileProvider が使えない（外部ライブラリなし）ための自前実装。
 * 外部ファイル領域の Downloads にある BatteryHub-update.apk だけを読み取りで返す。
 */
public class UpdateProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".updateprovider";
    public static final String APK_FILENAME = "BatteryHub-update.apk";

    public static Uri apkUri(String packageName) {
        return Uri.parse("content://" + packageName + AUTHORITY_SUFFIX + "/" + APK_FILENAME);
    }

    public static File apkFile(android.content.Context ctx) {
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return new File(dir, APK_FILENAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no context");
        File f = apkFile(getContext());
        if (!f.exists()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
