package space.zhangjing.oss.utils

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.NlsContexts
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.entity.Credential.Companion.findById
import space.zhangjing.oss.settings.CredentialSettings
import space.zhangjing.oss.settings.ProjectSettings
import space.zhangjing.oss.ui.OSSBrowserDialog
import space.zhangjing.oss.utils.PluginBundle.message
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton


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

fun Project.notification(
    @NlsContexts.NotificationTitle title: String = message("plugin.title"),
    @NlsContexts.NotificationContent content: String,
    vararg actions: Pair<String, () -> Unit> = emptyArray()
) = notification(title, content, NotificationType.INFORMATION, "oss.browser", *actions)

fun Project.notification(
    @NlsContexts.NotificationTitle title: String = message("plugin.title"),
    @NlsContexts.NotificationContent content: String,
    type: NotificationType = NotificationType.INFORMATION,
    groupId: String = "oss.browser",
    vararg actions: Pair<String, () -> Unit> = emptyArray()
) {
    val notification = Notification(groupId, title, content, type)
    actions.forEach { (text, action) ->
        notification.addAction(NotificationAction.createSimple(text) {
            action()
        })
    }
    Notifications.Bus.notify(notification, this)
}
