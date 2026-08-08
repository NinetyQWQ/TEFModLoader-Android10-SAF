package eternal.future.tefmodloader.utility

import java.io.File

/** 桌面端 SAFIO 实际实现：退化为普通 java.io.File 操作。 */
actual object SAFIO {

    actual fun copyRecursively(sourceReal: String, targetAbs: String): Boolean =
        runCatching { FileUtils.copyRecursivelyEfficient(File(sourceReal), File(targetAbs)) }.isSuccess

    actual fun moveRecursively(sourceAbs: String, targetAbs: String): Boolean =
        runCatching { FileUtils.moveRecursivelyEfficient(File(sourceAbs), File(targetAbs)) }.isSuccess

    actual fun mkdirs(abs: String): Boolean =
        runCatching { File(abs).mkdirs() }.getOrDefault(false)

    actual fun rename(absFrom: String, absTo: String): Boolean =
        runCatching { File(absFrom).renameTo(File(absTo)) }.getOrDefault(false)

    actual fun delete(abs: String): Boolean =
        runCatching { FileUtils.deleteDirectory(File(abs)); true }.getOrDefault(false)

    actual fun exists(abs: String): Boolean = File(abs).exists()

    actual fun readBytes(abs: String): ByteArray? =
        runCatching { File(abs).readBytes() }.getOrNull()

    actual fun writeBytes(abs: String, bytes: ByteArray): Boolean =
        runCatching { File(abs).apply { parentFile?.mkdirs() }.writeBytes(bytes) }.isSuccess

    actual fun list(abs: String): List<String> =
        runCatching { File(abs).listFiles()?.mapNotNull { it.name } ?: emptyList() }.getOrDefault(emptyList())
}