package eternal.future.tefmodloader.utility

/**
 * SAF 文件操作抽象。
 *
 * 外部模式下，modDir 由用户通过 SAF 授权（content:// tree URI），
 * 对其内部文件的读取/复制/创建/移动在 Android 上通过 DocumentFile(content URI) 完成，
 * 无需"所有文件访问"权限；桌面端退化为普通 java.io.File 操作。
 *
 * 所有方法都接收绝对路径：若路径位于已授权的 SAF 目录(KEY_MOD_DIR_PATH)下，
 * 则 Android 实现会通过 DocumentFile 走 content URI；否则回退到普通 File 操作。
 */
expect object SAFIO {
    /** 把真实路径的源文件/目录递归复制到目标路径(目标可能在 SAF 目录内) */
    fun copyRecursively(sourceReal: String, targetAbs: String): Boolean

    /** 递归移动/重命名源到目标(二者都可能在 SAF 目录内) */
    fun moveRecursively(sourceAbs: String, targetAbs: String): Boolean

    /** 创建目录(含父级) */
    fun mkdirs(abs: String): Boolean

    /** 重命名 */
    fun rename(absFrom: String, absTo: String): Boolean

    /** 递归删除 */
    fun delete(abs: String): Boolean

    /** 判断路径是否存在 */
    fun exists(abs: String): Boolean

    /** 读取文件字节 */
    fun readBytes(abs: String): ByteArray?

    /** 写入文件字节 */
    fun writeBytes(abs: String, bytes: ByteArray): Boolean

    /** 列出路径下的直接子项名 */
    fun list(abs: String): List<String>
}
