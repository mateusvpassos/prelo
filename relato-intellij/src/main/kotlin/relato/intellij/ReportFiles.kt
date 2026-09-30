package relato.intellij

import com.intellij.openapi.vfs.VirtualFile

object ReportFiles {
    const val SUFFIX = ".report.json"
    const val SAMPLE_SUFFIX = ".sample.json"

    fun isReport(file: VirtualFile): Boolean = file.name.endsWith(SUFFIX, ignoreCase = true)

    fun baseName(file: VirtualFile): String = file.name.dropLast(SUFFIX.length)

    fun sampleOf(file: VirtualFile): VirtualFile? = file.parent?.findChild(baseName(file) + SAMPLE_SUFFIX)
}
