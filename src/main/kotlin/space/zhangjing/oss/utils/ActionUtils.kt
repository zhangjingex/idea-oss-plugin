package space.zhangjing.oss.utils

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.entity.Credential.Companion.findById
import space.zhangjing.oss.settings.CredentialSettings
import space.zhangjing.oss.settings.ProjectSettings
import space.zhangjing.oss.ui.OSSBrowserDialog
import space.zhangjing.oss.utils.PluginBundle.message
import space.zhangjing.oss.utils.VariablesUtils.formatVariables
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JButton

fun String.showUrlDialog(project: Project) {
    val result = Messages.showDialog(
        project,
        this,
        message("upload.success_title"),
        arrayOf(message("copy.url"), message("close")),
        0,
        Messages.getInformationIcon()
    )
    if (result == 0) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(this), null)
    }
}

fun String.showUrlNotification(project: Project) {
    val notification = Notification(
        "uploadUrl",
        message("upload.success_title"),
        this,
        NotificationType.INFORMATION
    )
    // 添加“复制地址”按钮
    notification.addAction(NotificationAction.createSimple(message("copy.url")) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(this@showUrlNotification), null)
    })
    Notifications.Bus.notify(notification, project)
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

fun String.ensureEndsWithSlash(): String {
    return if (this.endsWith("/")) this else "$this/"
}


inline fun Project.createS3SelectButton(
    crossinline dialogName: () -> String,
    crossinline credential: () -> Credential,
    crossinline validityPeriod: () -> Int,
    crossinline onSelect: ((String) -> Unit),
    crossinline onError: (Exception) -> Unit,
    isBrowse: Boolean = false,
    name: String = message("select"),
): JButton {
    return JButton(name).apply {
        addActionListener {
            try {
                val dialog = OSSBrowserDialog(
                    dialogName(),
                    isBrowse = isBrowse,
                    project = this@createS3SelectButton,
                    credential = credential(),
                    validityPeriod = validityPeriod()
                )

                if (dialog.showAndGet()) {
                    val selected = dialog.selectedPath
                    onSelect(selected ?: "")
                }
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}

inline fun Logger.debug(log: () -> String) {
    if (isDebugEnabled) {
        debug(log())
    }
}

fun String.truncateWithEllipsis(maxLength: Int = 50): String {
    if (length <= maxLength) return this
    if (maxLength <= 1) return "…"
    return substring(0, maxLength - 1) + "…"
}

fun String.copy() {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(this), null)
}

fun ComboBox<Credential>.refreshCredentials() {
    val selectedId = (selectedItem as? Credential)?.id
    val newCredentials = CredentialSettings.getInstance().state.credentials
    this.removeAllItems()
    newCredentials.forEach { this.addItem(it) }
    val defaultCredential = newCredentials.findById(selectedId)
    if (defaultCredential != null) {
        this.selectedItem = defaultCredential
    }
}

fun Project.config(): Pair<Int, Credential?> {
    val settings = ProjectSettings.getInstance(this).state
    val credentialSettings = CredentialSettings.getInstance()
    val credentials = credentialSettings.state.credentials
    val defaultCredential = credentials.findById(settings.selectedCredentialId) ?: if (credentials.isEmpty()) {
        null
    } else {
        credentials[0]
    }
    return settings.validityPeriod to defaultCredential
}


fun isAndroidPluginAvailable(): Boolean {
    return PluginManagerCore.isPluginInstalled(PluginId.getId("org.jetbrains.android"))
}
