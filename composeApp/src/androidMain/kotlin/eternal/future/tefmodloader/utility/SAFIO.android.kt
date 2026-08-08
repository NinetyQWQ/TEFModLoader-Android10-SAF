package eternal.future.tefmodloader.utility

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import eternal.future.tefmodloader.MainApplication
import eternal.future.tefmodloader.configuration
import java.io.File

/**
 * Android 端 SAFIO 实际实现：对位于已授权 SAF 目录(KEY_MOD_DIR_PATH)下的路径，
 * 一律通过 DocumentFile(content URI) 读写/复制/创建/移动；其余路径回退为普通 File 操作。
 */
actual object SAFIO {

    private fun rootUri(): Uri? {
        val s = configuration.getString(KEY_MOD_DIR_URI, "")
        return if (s.isNotEmpty()) runCatching { Uri.parse(s) }.getOrNull() else null
    }

    private fun rootReal(): String = configuration.getString(KEY_MOD_DIR_PATH, "")

    /** 返回绝对路径在授权目录下的相对路径；不在授权目录内则返回 null */
    private fun relOf(abs: String): String? {
        val root = rootReal()
        if (root.isEmpty()) return null
        return if (abs.startsWith(root)) abs.removePrefix(root).trimStart('/', '\\') else null
    }

    private fun tree(): DocumentFile? {
        val uri = rootUri() ?: return null
        return DocumentFile.fromTreeUri(MainApplication.getContext(), uri)
    }

    private fun navigate(tree: DocumentFile, rel: String, create: Boolean = false): DocumentFile? {
        var cur: DocumentFile? = tree
        for (seg in rel.split('/').filter { it.isNotEmpty() }) {
            if (cur == null) break
            val found = cur.findFile(seg)
            cur = found ?: if (create) cur.createDirectory(seg) else null
        }
        return cur
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "jar" -> "application/java-archive"
        "so" -> "application/octet-stream"
        "toml" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    private fun readDocBytes(doc: DocumentFile): ByteArray? = runCatching {
        MainApplication.getContext().contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
    }.getOrNull()

    private fun writeDocBytes(doc: DocumentFile, bytes: ByteArray): Boolean = runCatching {
        MainApplication.getContext().contentResolver.openOutputStream(doc.uri)?.use { it.write(bytes) } != null
    }.getOrDefault(false)

    /** 把真实文件/目录递归复制进 DocumentFile 树的 rel 位置 */
    private fun copyRealIntoTree(treeRoot: DocumentFile, rel: String, src: File): Boolean {
        val segs = rel.split('/').filter { it.isNotEmpty() }
        var cur = treeRoot
        for (seg in segs.dropLast(1)) {
            cur = cur.findFile(seg) ?: cur.createDirectory(seg) ?: return false
        }
        val name = segs.lastOrNull() ?: return false
        val dest = cur.findFile(name) ?: (
            if (src.isDirectory) cur.createDirectory(name)
            else cur.createFile(guessMime(name), name)
        ) ?: return false

        if (src.isDirectory) {
            var ok = true
            src.listFiles()?.forEach { child ->
                if (!copyRealIntoTree(dest, child.name, child)) ok = false
            }
            return ok
        }
        val bytes = runCatching { src.readBytes() }.getOrNull() ?: return false
        return writeDocBytes(dest, bytes)
    }

    /** 把 SAF 内的 srcDoc 递归复制到 dstParent 下，命名为 newName */
    private fun copyDocInto(dstParent: DocumentFile, srcDoc: DocumentFile, newName: String): Boolean {
        val created = if (srcDoc.isDirectory) dstParent.createDirectory(newName)
            else dstParent.createFile(guessMime(newName), newName)
        if (created == null) return false
        if (srcDoc.isDirectory) {
            var ok = true
            srcDoc.listFiles().forEach { child ->
                val n = child.name ?: return@forEach
                if (!copyDocInto(created, child, n)) ok = false
            }
            return ok
        }
        val bytes = readDocBytes(srcDoc) ?: return false
        return writeDocBytes(created, bytes)
    }

    actual fun copyRecursively(sourceReal: String, targetAbs: String): Boolean {
        val rel = relOf(targetAbs)
        if (rel != null) {
            val t = tree() ?: return false
            return copyRealIntoTree(t, rel, File(sourceReal))
        }
        return runCatching {
            FileUtils.copyRecursivelyEfficient(File(sourceReal), File(targetAbs))
        }.isSuccess
    }

    /** 把 DocumentFile 树递归复制到真实路径 */
    private fun copyDocToReal(srcDoc: DocumentFile, dest: File): Boolean {
        if (srcDoc.isDirectory) {
            dest.mkdirs()
            var ok = true
            srcDoc.listFiles().forEach { child ->
                val n = child.name ?: return@forEach
                if (!copyDocToReal(child, File(dest, n))) ok = false
            }
            return ok
        }
        val bytes = readDocBytes(srcDoc) ?: return false
        return runCatching { dest.parentFile?.mkdirs(); dest.writeBytes(bytes) }.isSuccess
    }

    actual fun moveRecursively(sourceAbs: String, targetAbs: String): Boolean {
        val srcRel = relOf(sourceAbs)
        val dstRel = relOf(targetAbs)
        return when {
            srcRel != null && dstRel != null -> {
                val t = tree() ?: return false
                val srcDoc = navigate(t, srcRel) ?: return false
                val dstSegs = dstRel.split('/').filter { it.isNotEmpty() }
                val dstParent = navigate(t, dstSegs.dropLast(1).joinToString("/"), create = true) ?: return false
                val dstName = dstSegs.last()
                dstParent.findFile(dstName)?.delete()
                val moved = copyDocInto(dstParent, srcDoc, dstName)
                if (moved) srcDoc.delete()
                moved
            }
            srcRel == null && dstRel != null -> {
                val t = tree() ?: return false
                val ok = copyRealIntoTree(t, dstRel, File(sourceAbs))
                if (ok) runCatching { FileUtils.deleteDirectory(File(sourceAbs)) }
                ok
            }
            srcRel != null && dstRel == null -> {
                val t = tree() ?: return false
                val srcDoc = navigate(t, srcRel) ?: return false
                val ok = copyDocToReal(srcDoc, File(targetAbs))
                if (ok) srcDoc.delete()
                ok
            }
            else -> runCatching { FileUtils.moveRecursivelyEfficient(File(sourceAbs), File(targetAbs)) }.isSuccess
        }
    }

    actual fun mkdirs(abs: String): Boolean {
        val rel = relOf(abs)
        if (rel != null) {
            val t = tree() ?: return false
            return navigate(t, rel, create = true) != null
        }
        return runCatching { File(abs).mkdirs() }.getOrDefault(false)
    }

    actual fun rename(absFrom: String, absTo: String): Boolean {
        val fromRel = relOf(absFrom)
        val toRel = relOf(absTo)
        return when {
            fromRel != null && toRel != null -> {
                val t = tree() ?: return false
                val srcDoc = navigate(t, fromRel) ?: return false
                val toSegs = toRel.split('/').filter { it.isNotEmpty() }
                srcDoc.renameTo(toSegs.last())
            }
            else -> runCatching { File(absFrom).renameTo(File(absTo)) }.getOrDefault(false)
        }
    }

    actual fun delete(abs: String): Boolean {
        val rel = relOf(abs)
        if (rel != null) {
            val t = tree() ?: return false
            val doc = navigate(t, rel) ?: return false
            return doc.delete()
        }
        return runCatching { FileUtils.deleteDirectory(File(abs)); true }.getOrDefault(false)
    }

    actual fun exists(abs: String): Boolean {
        val rel = relOf(abs)
        if (rel != null) {
            val t = tree() ?: return false
            return navigate(t, rel) != null
        }
        return File(abs).exists()
    }

    actual fun readBytes(abs: String): ByteArray? {
        val rel = relOf(abs)
        if (rel != null) {
            val t = tree() ?: return null
            val doc = navigate(t, rel) ?: return null
            if (doc.isDirectory) return null
            return readDocBytes(doc)
        }
        return runCatching { File(abs).readBytes() }.getOrNull()
    }

    actual fun writeBytes(abs: String, bytes: ByteArray): Boolean {
        val rel = relOf(abs)
        if (rel != null) {
            val t = tree() ?: return false
            val segs = rel.split('/').filter { it.isNotEmpty() }
            val parent = navigate(t, segs.dropLast(1).joinToString("/"), create = true) ?: return false
            val name = segs.lastOrNull() ?: return false
            val doc = parent.findFile(name) ?: parent.createFile(guessMime(name), name) ?: return false
            return writeDocBytes(doc, bytes)
        }
        return runCatching { File(abs).apply { parentFile?.mkdirs() }.writeBytes(bytes) }.isSuccess
    }

    actual fun list(abs: String): List<String> {
        val rel = relOf(abs)
        if (rel != null) {
            val t = tree() ?: return emptyList()
            val doc = navigate(t, rel) ?: return emptyList()
            if (!doc.isDirectory) return emptyList()
            return doc.listFiles().mapNotNull { it.name }
        }
        return runCatching { File(abs).listFiles()?.mapNotNull { it.name } ?: emptyList() }.getOrDefault(emptyList())
    }
}