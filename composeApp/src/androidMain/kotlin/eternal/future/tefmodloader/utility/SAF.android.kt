package eternal.future.tefmodloader.utility

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import eternal.future.tefmodloader.MainApplication

/** 保存 SAF 授权目录的 Uri 和推导出的真实路径（SharedPreferences 键） */
const val KEY_MOD_DIR_URI = "modDirUri"
const val KEY_MOD_DIR_PATH = "modDirPath"

/**
 * 把 SAF 的 tree Uri 推导成真实路径，供原生库(SilkCasket)和游戏读取使用。
 * 例：content://com.android.externalstorage.documents/tree/primary:Documents%2FTEFModLoader
 * -> /storage/emulated/0/Documents/TEFModLoader
 */
fun treeUriToPath(treeUri: Uri): String {
    return runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri) // 形如 primary:Documents/TEFModLoader
        val split = docId.split(":")
        val root = if (split[0] == "primary") "/storage/emulated/0" else "/storage/${split[0]}"
        val rel = split.drop(1).joinToString("/")
        if (rel.isEmpty()) root else "$root/$rel"
    }.getOrDefault("")
}

/** 持久化 SAF 目录权限（重启后仍然有效） */
fun persistDirectoryPermission(uri: Uri) {
    try {
        val context = MainApplication.getContext()
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        EFLog.i("SAF 权限持久化成功: $uri")
    } catch (e: Exception) {
        EFLog.e("SAF 权限持久化失败", e)
    }
}
