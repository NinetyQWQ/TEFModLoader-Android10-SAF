package eternal.future;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Objects;

import eternal.future.silkrift.APKKiller;
import eternal.future.silkrift.Core;
import eternal.future.silkrift.IORedirects;
import eternal.future.silkrift.MapsGhost;
import eternal.future.utility.AssetManager;
import eternal.future.utility.FileUtils;

/**
 * 注入进游戏的入口 Activity。
 * 外部模式(State.Mode==0)下通过 SAF(ACTION_OPEN_DOCUMENT_TREE + DocumentsContract)
 * 访问外部 mod 目录，将 Modx/EFMod/Data 复制进游戏私有目录后交给 native 以真实路径读取，
 * 从而无需"所有文件访问"权限。
 */
public class TEFModLoader extends Activity {
    private static final int REQUEST_CODE = 1001;
    private static final String PREF_NAME = "tefml_saf";
    private static final String KEY_TREE_URI = "tree_uri";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            String jsonString = AssetManager.readTextOfAsset(this, "config.json");
            assert jsonString != null;
            JSONObject obj = new JSONObject(jsonString);
            State.Mode = obj.getInt("mode");
            State.gameActivity = Class.forName(obj.getString("activity"));
            State.ManagerPackName = obj.getString("manager");
            State.Bypass = obj.getBoolean("bypass");
        } catch (JSONException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        State.Modx = new File(getFilesDir(), "TEFModLoader/Modx");
        State.EFMod = new File(getFilesDir(), "TEFModLoader/EFMod");

        if (State.Mode == 0) {
            prepareExternal();
        } else {
            if (State.Modx.exists()) {
                FileUtils.deleteDirectory(State.Modx);
            }
            if (State.EFMod.exists()) {
                FileUtils.deleteDirectory(State.EFMod);
            }

            State.EFMod.mkdirs();
            State.Modx.mkdirs();

            String android_data = Objects.requireNonNull(new File(this.getExternalFilesDir(null), "").getParentFile()).getParent();

            State.EFMod_c = new File(android_data, State.ManagerPackName + "/files/EFMod").getAbsolutePath();
            File EFMod = new File(android_data, State.ManagerPackName + "/files/TEFModLoader_I/EFMod");
            File Modx_x = new File(android_data, State.ManagerPackName + "/files/TEFModLoader_I/Modx");

            if (Modx_x.exists()) {
                FileUtils.moveContent(Modx_x, State.Modx);
            }

            if (EFMod.exists()) {
                FileUtils.moveContent(EFMod, State.EFMod);
            }

            startGame();
        }
    }

    /** 外部模式：优先取 composeApp 启动时经 Intent 传入的 SAF 目录，其次取持久化授权，最后弹目录选择器 */
    private void prepareExternal() {
        Uri tree = getIntentTreeUri();
        if (tree == null) {
            tree = getPersistedTreeUri();
        }
        if (tree == null) {
            requestSafTree();
        } else {
            persistTreeUri(tree);
            applyExternal(tree);
            startGame();
        }
    }

    /** 读取 composeApp 启动本游戏 Activity 时经 Intent data 传入的 SAF tree URI */
    private Uri getIntentTreeUri() {
        try {
            Intent i = getIntent();
            if (i != null) {
                Uri data = i.getData();
                if (data != null && "content".equalsIgnoreCase(data.getScheme())) {
                    return data;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 从 SAF 目录把 Modx/EFMod/Data 复制进游戏私有目录 */
    private void applyExternal(Uri treeUri) {
        if (State.Modx.exists()) {
            FileUtils.deleteDirectory(State.Modx);
        }
        if (State.EFMod.exists()) {
            FileUtils.deleteDirectory(State.EFMod);
        }
        File dataDir = new File(getFilesDir(), "TEFModLoader/Data");
        if (dataDir.exists()) {
            FileUtils.deleteDirectory(dataDir);
        }

        State.EFMod.mkdirs();
        State.Modx.mkdirs();
        dataDir.mkdirs();

        State.EFMod_c = dataDir.getAbsolutePath();

        ContentResolver cr = getContentResolver();
        copyTreeSubdir(cr, treeUri, "Modx", State.Modx);
        copyTreeSubdir(cr, treeUri, "EFMod", State.EFMod);
        copyTreeSubdir(cr, treeUri, "Data", dataDir);
    }

    private void startGame() {
        if (State.Bypass) {
            try {
                Core.init();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            Core.StealthCrashBypass_Install();
            MapsGhost.init();
            IORedirects.RedirectAPK();
            MapsGhost.add_entry("original.apk");
            MapsGhost.add_entry("libsilkrift.so");
            MapsGhost.add_entry("TEFModLoader");
            APKKiller.init(Core.createAppContext(), IORedirects.repPath);
        }

        Intent gameActivity = new Intent(this, State.gameActivity);
        startActivity(gameActivity);
        Loader.initialize();
    }

    private void requestSafTree() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_CODE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void persistTreeUri(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (Exception ignored) {
        }
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_TREE_URI, uri.toString())
                .apply();
    }

    private Uri getPersistedTreeUri() {
        String s = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(KEY_TREE_URI, null);
        return s == null ? null : Uri.parse(s);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            persistTreeUri(data.getData());
            applyExternal(data.getData());
            startGame();
        }
    }

    /* ==================== SAF 文件读取（DocumentsContract） ==================== */

    private void copyTreeSubdir(ContentResolver cr, Uri treeUri, String subdir, File dest) {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        String childId = treeId + "/" + subdir;
        Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
        copyDocument(cr, treeUri, childUri, dest);
    }

    private void copyDocument(ContentResolver cr, Uri treeUri, Uri doc, File dest) {
        String mime = queryMime(cr, doc);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
            if (!dest.exists() && !dest.mkdirs()) {
                return;
            }
            Cursor c = null;
            try {
                c = cr.query(
                        doc,
                        new String[]{
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE
                        },
                        null, null, null
                );
                if (c != null) {
                    while (c.moveToNext()) {
                        String id = c.getString(0);
                        String name = c.getString(1);
                        String m = c.getString(2);
                        Uri child = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                        copyDocument(cr, treeUri, child, new File(dest, name));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        } else {
            copyDocToFile(cr, doc, dest);
        }
    }

    private void copyDocToFile(ContentResolver cr, Uri doc, File dest) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = cr.openInputStream(doc);
            if (in == null) {
                return;
            }
            if (dest.getParentFile() != null && !dest.getParentFile().exists()) {
                dest.getParentFile().mkdirs();
            }
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String queryMime(ContentResolver cr, Uri doc) {
        Cursor c = null;
        try {
            c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }
}
