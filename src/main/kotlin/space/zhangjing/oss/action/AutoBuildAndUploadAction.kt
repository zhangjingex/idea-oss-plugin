package space.zhangjing.oss.action

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.use
import kotlinx.coroutines.launch
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaProject
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.service.ScopeService
import space.zhangjing.oss.settings.CredentialSettings
import space.zhangjing.oss.settings.ProjectSettings
import space.zhangjing.oss.ui.UploadPathDialog
import space.zhangjing.oss.ui.panel.OSSService
import space.zhangjing.oss.ui.panel.showUrlNotification
import space.zhangjing.oss.ui.panel.withProgressBackground
import space.zhangjing.oss.utils.*
import space.zhangjing.oss.utils.OSSUtils.createUrl
import space.zhangjing.oss.utils.PluginBundle.message
import space.zhangjing.oss.utils.VariablesUtils.formatVariables
import java.io.File

class AutoBuildAndUploadAction : AnAction() {

    private val LOG = Logger.getInstance(AutoBuildAndUploadAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return
        val projectSettings = ProjectSettings.getInstance(project)
        val credentialSettings = CredentialSettings.getInstance()

        val credentials = credentialSettings.state.credentials
        if (credentials.isEmpty()) {
            Messages.showErrorDialog(project, message("oss.credentials.empty"), message("plugin.title"))
            return
        }

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, message("build.task.get"), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = message("build.task.fetching")
                val assembleTasks = getAssembleTasks(basePath)
                if (assembleTasks.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, message("build.task.not.found"), message("error"))
                    }
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    val selectedTask = Messages.showEditableChooseDialog(
                        "",
                        message("build.task.select"),
                        null,
                        assembleTasks.toTypedArray(),
                        assembleTasks.first(),
                        null
                    ) ?: return@invokeLater
                    LOG.debug { "Selected task: $selectedTask" }
                    val gradleSystemId = ProjectSystemId("GRADLE")
                    val taskSettings = ExternalSystemTaskExecutionSettings().apply {
                        externalProjectPath = basePath
                        taskNames = listOf(selectedTask)
                        externalSystemIdString = gradleSystemId.id
                    }

                    ExternalSystemUtil.runTask(
                        taskSettings,
                        DefaultRunExecutor.EXECUTOR_ID,
                        project,
                        gradleSystemId,
                        object : TaskCallback {
                            override fun onSuccess() {
                                val apk = findApkPath(basePath, selectedTask)
                                handleApkUpload(
                                    project,
                                    apk,
                                    projectSettings.state,
                                    credentialSettings.state.credentials
                                )
                            }

                            override fun onFailure() {
                                Messages.showErrorDialog(project, message("gradle.build.failed"), message("error"))
                            }
                        },
                        com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode.IN_BACKGROUND_ASYNC,
                        false
                    )
                }

            }
        })
    }

    private fun getAssembleTasks(projectPath: String): List<String> {
        val regex = Regex("^assemble.*(Debug|Release)$")
        val connector = GradleConnector.newConnector()
            .forProjectDirectory(File(projectPath))
        return connector.connect().use { connection ->
            val project = connection.getModel(IdeaProject::class.java)
            project.modules
                .flatMap { module ->
                    val path = module.gradleProject.path
                    LOG.debug { "Module: ${module.name}, path: $path" }
                    module.gradleProject.tasks
                        .asSequence()
                        .map { it.name }
                        .filter { taskName ->
                            val matched = regex.matches(taskName)
                            LOG.debug { "Task: $taskName, matched: $matched" }
                            matched
                        }
                        .map { taskName -> "$path:$taskName" }
                        .toList()
                }
                .distinct()
        }
    }

    data class AssembleTaskInfo(
        val module: String,
        val flavor: String,
        val buildType: String
    )


    private fun parseTaskName(taskName: String): AssembleTaskInfo? {
        LOG.debug { "Parsing task name: $taskName" }
        val regex = Regex("^([^:]+):assemble(.*?)(Release|Debug)$")
        val matchResult = regex.find(taskName) ?: return null
        val (module, flavor, buildType) = matchResult.destructured
        return AssembleTaskInfo(
            module = module,
            flavor = flavor.replaceFirstChar { it.lowercaseChar() },
            buildType = buildType.lowercase()
        )
    }


    private fun findApkPath(projectPath: String, taskName: String): File? {
        val info = parseTaskName(taskName.removePrefix(":")) ?: return null
        val dir = if (info.flavor.isEmpty()) info.buildType else "${info.flavor}/${info.buildType}"
        val path = "$projectPath/${info.module}/build/outputs/apk/${dir}"
        LOG.debug { "APK path: $path" }
        val apkDir = File(path)
        return apkDir.walkTopDown().firstOrNull { it.isFile && it.extension == "apk" }
    }

    private fun handleApkUpload(
        project: Project,
        apk: File?,
        settings: ProjectSettings.State,
        credentials: List<Credential>,
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (apk == null) {
                Messages.showErrorDialog(project, message("apk.not.found"), message("plugin.title"))
                return@invokeLater
            }
            val dialog = UploadPathDialog(project, settings, credentials, settings.uploadPath, apk)
            if (!dialog.showAndGet()) return@invokeLater
            val credential = dialog.credential
            val ossPath = dialog.ossPath
            val virtualFile = apk.toVirtualFile() ?: return@invokeLater
            project.service<ScopeService>().scope.launch {
                project.withProgressBackground(message("upload.progress")) {
                    OSSService(project, credential).use {
                        val objectPrefix = ossPath.formatVariables(project, virtualFile).trim('/')
                        val key = if (objectPrefix.isBlank())
                            virtualFile.name
                        else
                            "$objectPrefix/${virtualFile.name}"
                        it.uploadFile(virtualFile, key) {
                            val url = key.createUrl(
                                credential,
                                settings.validityPeriod
                            )
                            url.showUrlNotification(project)
                        }.onFailure { error ->
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
}
