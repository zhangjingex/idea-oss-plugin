package space.zhangjing.oss.utils

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Desktop
import java.io.File


fun VirtualFile.toIoFile(): File = VfsUtil.virtualToIoFile(this)


fun File.revealSmart() {
    if (!exists()) return
    if (isDirectory) {
        // 目录：直接打开
        Desktop.getDesktop().open(this)
    } else {
        revealInExplorer()
    }
}

fun VirtualFile.revealSmart() {
    toIoFile().revealSmart()
}

fun File.revealInExplorer() {
    RevealFileAction.openFile(this)
}

fun VirtualFile.revealInExplorer() {
    toNioPath().toFile().revealInExplorer()
}

fun File.toVirtualFile(): VirtualFile? = LocalFileSystem.getInstance()
    .refreshAndFindFileByIoFile(this)