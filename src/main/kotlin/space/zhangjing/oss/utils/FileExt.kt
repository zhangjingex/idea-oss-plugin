package space.zhangjing.oss.utils

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.ui.panel.showUrlDialog
import space.zhangjing.oss.ui.panel.showUrlNotification
import space.zhangjing.oss.utils.PluginBundle.message
import space.zhangjing.oss.utils.VariablesUtils.formatVariables
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


fun VirtualFile.upload(
    project: Project,
    credential: Credential,
    ossPath: String?,
    useDialog: Boolean,
    validityPeriod: Int = 600
) {
    File(path).upload(project, credential, ossPath?.formatVariables(project, this@upload), useDialog, validityPeriod)
}

fun File.upload(
    project: Project,
    credential: Credential,
    ossPath: String?,
    useDialog: Boolean,
    validityPeriod: Int = 600
) {
    ProgressManager.getInstance().run(
        object : Task.Backgroundable(project, message("upload.progress"), true) {

            private lateinit var url: String

            override fun run(indicator: ProgressIndicator) {
                indicator.text = message("prepare.upload.ing")
                indicator.isIndeterminate = true
                url = OSSUploader.uploadFile(
                    this@upload, credential,
                    ossPath?.formatVariables(project, this@upload), validityPeriod
                )
            }

            override fun onSuccess() {
                if (useDialog) {
                    url.showUrlDialog(project)
                } else {
                    url.showUrlNotification(project)
                }
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(
                    project,
                    message("upload.failed", error.message ?: ""),
                    message("plugin.title")
                )
            }
        }
    )
}
