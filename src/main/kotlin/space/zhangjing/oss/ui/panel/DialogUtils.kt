package space.zhangjing.oss.ui.panel

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import space.zhangjing.oss.utils.PluginBundle.message
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.SwingUtilities
import kotlin.coroutines.resumeWithException

fun <T> runInEdtAndWait(action: () -> T): T {
    return if (SwingUtilities.isEventDispatchThread()) {
        action()
    } else {
        var result: T? = null
        SwingUtilities.invokeAndWait {
            result = action()
        }
        result!!
    }
}


fun Project.askConflictStrategy(): ConflictStrategy? {
    val choice = runInEdtAndWait {
        Messages.showDialog(
            this,
            message("oss.browse.local.exists.all"),
            message("oss.browse.download"),
            arrayOf(
                message("overwrite.all"),
                message("rename.all"),
                message("skip.all"),
                message("cancel")
            ),
            0,
            Messages.getWarningIcon()
        )
    }

    return when (choice) {
        0 -> ConflictStrategy.OVERWRITE
        1 -> ConflictStrategy.RENAME
        2 -> ConflictStrategy.SKIP
        else -> null
    }
}


@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <T> Project.withProgressModal(
    title: String,
    cancellable: Boolean = true,
    block: suspend (ProgressIndicator) -> T
): T = suspendCancellableCoroutine { cont ->
    ProgressManager.getInstance().run(object : Task.Modal(this@withProgressModal, title, cancellable) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val result = runBlocking { block(indicator) }
                cont.resume(result) {}
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    })
}

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <T> Project.withProgressBackground(
    title: String,
    cancellable: Boolean = true,
    block: suspend (ProgressIndicator) -> T
): T = suspendCancellableCoroutine { cont ->
    ProgressManager.getInstance().run(object : Task.Backgroundable(this@withProgressBackground, title, cancellable) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val result = runBlocking { block(indicator) }
                cont.resume(result) {}
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    })
}


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
        "oss.browser",
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
