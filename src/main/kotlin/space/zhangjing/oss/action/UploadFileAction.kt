package space.zhangjing.oss.action


import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.use
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import kotlinx.coroutines.launch
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.service.ScopeService
import space.zhangjing.oss.settings.CredentialSettings
import space.zhangjing.oss.settings.ProjectSettings
import space.zhangjing.oss.ui.UploadPathDialog
import space.zhangjing.oss.ui.panel.OSSService
import space.zhangjing.oss.ui.panel.showUrlNotification
import space.zhangjing.oss.ui.panel.withProgressBackground
import space.zhangjing.oss.utils.CustomError
import space.zhangjing.oss.utils.OSSUtils.createUrl
import space.zhangjing.oss.utils.PluginBundle.message
import space.zhangjing.oss.utils.VariablesUtils.formatVariables
import space.zhangjing.oss.utils.debug
import space.zhangjing.oss.utils.isCancel
import space.zhangjing.oss.utils.notification

class UploadFileAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(UploadFileAction::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    private val credentials: List<Credential> = CredentialSettings.getInstance().state.credentials

    override fun update(e: AnActionEvent) {
        val presentation = e.presentation
        val enabled = if (credentials.isEmpty()) {
            false
        } else {
            val virtualFile = getSelectedFile(e)
            virtualFile != null
        }
        presentation.isEnabled = enabled
        presentation.isVisible = enabled
    }

    private fun getSelectedFile(e: AnActionEvent): VirtualFile? {
        // 优先尝试从 CommonDataKeys.VIRTUAL_FILE 获取
        var virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (virtualFile != null) {
            LOG.debug { "Got file from CommonDataKeys.VIRTUAL_FILE: $virtualFile" }
            return virtualFile
        }

        // 尝试从 PlatformDataKeys.SELECTED_ITEMS 获取（在 Project View 中通常有效）
        val selectedItems = e.getData(PlatformDataKeys.SELECTED_ITEMS)
        if (!selectedItems.isNullOrEmpty()) {
            LOG.debug { "Got selected items: ${selectedItems.size}" }
            // 查找第一个 VirtualFile 对象
            for (item in selectedItems) {
                LOG.debug { "Selected item type: ${item::class.java.name}" }
                when (item) {
                    is VirtualFile -> {
                        LOG.debug { "Found VirtualFile in selected items: $item" }
                        return item
                    }

                    is PsiFile -> {
                        virtualFile = item.virtualFile
                        if (virtualFile != null) {
                            LOG.debug { "Found PsiFile in selected items, converted to VirtualFile: $virtualFile" }
                            return virtualFile
                        }
                    }

                    is PsiElement -> {
                        val containingFile = item.containingFile
                        if (containingFile != null) {
                            virtualFile = containingFile.virtualFile
                            if (virtualFile != null) {
                                LOG.debug { "Found PsiElement in selected items, converted to VirtualFile: $virtualFile" }
                                return virtualFile
                            }
                        }
                    }

                    is PsiFileNode -> {
                        val file = item.virtualFile
                        if (file != null) {
                            LOG.debug { "Found PsiFileNode in selected items, converted to VirtualFile: $file" }
                            return file
                        }
                    }
                }
            }
        }

        // 尝试从 PSI_FILE 获取
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        if (psiFile != null) {
            virtualFile = psiFile.virtualFile
            if (virtualFile != null) {
                LOG.debug { "Got VirtualFile from PSI_FILE: $virtualFile" }
                return virtualFile
            }
        }

        // 尝试从 PSI_ELEMENT 获取
        val psiElement = e.getData(CommonDataKeys.PSI_ELEMENT)
        if (psiElement != null) {
            LOG.debug { "Got PSI_ELEMENT: ${psiElement::class.simpleName}" }
            // 如果 PSI_ELEMENT 是 PsiFile，获取其 VirtualFile
            if (psiElement is PsiFile) {
                virtualFile = psiElement.virtualFile
                if (virtualFile != null) {
                    LOG.debug { "Got VirtualFile from PSI_ELEMENT (PsiFile): $virtualFile" }
                    return virtualFile
                }
            }
            // 如果是其他 PsiElement，尝试获取其包含的文件
            val containingFile = psiElement.containingFile
            if (containingFile != null) {
                virtualFile = containingFile.virtualFile
                if (virtualFile != null) {
                    LOG.debug { "Got VirtualFile from PSI_ELEMENT.containingFile: $virtualFile" }
                    return virtualFile
                }
            }
        }

        // 尝试从 CommonDataKeys.VIRTUAL_FILE_ARRAY 获取
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!files.isNullOrEmpty()) {
            LOG.debug { "Got files from CommonDataKeys.VIRTUAL_FILE_ARRAY: ${files.size}" }
            // 只处理单个文件
            if (files.size == 1) {
                return files[0]
            }
        }

        LOG.debug { "No file found in any data context" }
        return null
    }


    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (virtualFiles.isNullOrEmpty()) {
            Messages.showErrorDialog(project, message("file.no.select"), message("plugin.title"))
            return
        }
        val settings = ProjectSettings.getInstance(project)
        val state = settings.state
        // 弹出输入路径的对话框
        val dialog = UploadPathDialog(
            project = project,
            state,
            credentials,
            lastPath = state.uploadPath,
            virtualFiles
        )
        if (!dialog.showAndGet()) {
            return
        }
        val credential = dialog.credential
        val ossPath = dialog.ossPath
        project.service<ScopeService>().scope.launch {
            project.withProgressBackground(message("upload.progress")) { indicator ->
                OSSService(project, credential).use {
                    it.uploadFile(virtualFiles, ossPath.formatVariables(project, virtualFiles[0]), indicator,
                        { total, done, key ->
                            if (total == 1) {
                                val url = key.createUrl(
                                    credential,
                                    state.validityPeriod
                                )
                                url.showUrlNotification(project)
                            } else if (total == done) {
                                project.notification(
                                    message("upload.success_title"),
                                    message("upload.success_title.multi", total)
                                )
                            }
                        }).onFailure { error ->
                        if (error.isCancel) return@onFailure
                        if (error !is CustomError) {
                            LOG.warn(error)
                        }
                        project.notification(
                            message("error"),
                            error.message ?: "",
                            NotificationType.ERROR
                        )
                    }
                }
            }
        }
    }
}
