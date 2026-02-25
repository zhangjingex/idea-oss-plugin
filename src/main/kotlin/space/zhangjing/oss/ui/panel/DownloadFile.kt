package space.zhangjing.oss.ui.panel

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

data class DownloadFile(
    val key: String,
    val relativePath: Path
)

data class UploadFile(
    val key: String,
    val file: VirtualFile
)