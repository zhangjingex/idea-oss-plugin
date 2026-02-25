package space.zhangjing.oss.utils

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import net.dongliu.apk.parser.ApkFile
import net.dongliu.apk.parser.bean.ApkMeta
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object VariablesUtils {

    private val nullStr = "unknown"

    // ================= 时间变量 =================
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd")

    private val dateVariables = mapOf<String, (LocalDateTime) -> String>(
        "DATE" to { it.format(formatter) },
        "YEAR" to { it.year.toString() },
        "MONTH" to { "%02d".format(it.monthValue) },
        "DAY" to { "%02d".format(it.dayOfMonth) },
        "HOUR" to { "%02d".format(it.hour) },
        "MINUTE" to { "%02d".format(it.minute) },
        "SECOND" to { "%02d".format(it.second) },
        "TIMESTAMP" to { it.toInstant(ZoneOffset.UTC).toEpochMilli().toString() },
    )

    // ================= 用户变量 =================
    private val userVariables = mapOf(
        "AUTHOR" to { System.getProperty("user.name") ?: "unknown" },
        "USER" to { System.getProperty("user.name") ?: "unknown" },
    )

    // ================= 文件变量 =================
    private val fileVariables = mapOf<String, (String) -> String>(
        "FILE_NAME" to { it },
        "FILE_NAME_NO_EXT" to {
            val index = it.lastIndexOf('.')
            if (index > 0) it.substring(0, index) else it
        }
    )

    // ================= Project 变量 =================
    private val projectVariables = mapOf<String, (Project) -> String>(
        "PROJECT_NAME" to { it.name },
        "PROJECT_PATH" to { it.basePath ?: "unknown" },
    )

    // ================= Module 变量 =================
    private val moduleVariables = mapOf<String, (Module) -> String>(
        "MODULE_NAME" to { it.name },
    )

    // ================= Apk 变量 =================
    private val apkVariables = mapOf<String, (ApkMeta) -> String>(
        "VERSION_NAME" to { it.versionName ?: "unknown" },
        "VERSION_CODE" to { it.versionCode.toString() },
        "APPLICATION_ID" to { it.packageName ?: "unknown" },
        "MIN_SDK" to { it.minSdkVersion },
        "TARGET_SDK" to { it.targetSdkVersion },
        "MAX_SDK" to { it.maxSdkVersion ?: "unknown" },
    )


    val variablePattern = "\\$(\\w+)\\$".toRegex()


    fun String.formatVariables(
        project: Project,
        file: VirtualFile,
        module: Module? = ModuleUtilCore.findModuleForFile(file, project),
        now: LocalDateTime = LocalDateTime.now()
    ): String = formatVariables(project, File(file.path), module, now)


    internal fun String.formatVariables(
        project: Project,
        file: File? = null,
        module: Module? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): String {
        if (this.isBlank() || !this.contains("$")) return this
        val apkMeta = file?.takeIf { it.isApk() }?.let {
            ApkFile(it).use { apk -> apk.apkMeta }
        }
        return variablePattern.replace(this) { matchResult ->
            val key = matchResult.groupValues[1]
            key.variables(project, file, module, now, apkMeta) ?: matchResult.value
        }
    }

    internal fun String.variables(
        project: Project,
        file: File? = null,
        module: Module? = null,
        now: LocalDateTime = LocalDateTime.now(),
        apkMeta: ApkMeta? = file?.takeIf { it.isApk() }?.let {
            ApkFile(it).use { apk -> apk.apkMeta }
        }
    ): String? {
        return when {
            dateVariables.containsKey(this) -> dateVariables[this]!!(now)
            userVariables.containsKey(this) -> userVariables[this]!!()
            projectVariables.containsKey(this) -> projectVariables[this]!!(project)
            moduleVariables.containsKey(this) -> module?.let { moduleVariables[this]!!(it) } ?: nullStr
            apkVariables.containsKey(this) -> apkMeta?.let { apkVariables[this]!!(it) } ?: nullStr
            file != null && fileVariables.containsKey(this) -> fileVariables[this]!!(file.name)
            else -> null
        }
    }

    internal fun String.variables(
        project: Project,
        file: VirtualFile
    ) = variables(project, File(file.path), ModuleUtilCore.findModuleForFile(file, project))


    private fun File.isApk(): Boolean {
        return this.isFile && this.name.endsWith(".apk", ignoreCase = true)
    }

    fun allVariableKeys(): Set<String> {
        return dateVariables.keys +
                userVariables.keys +
                fileVariables.keys +
                projectVariables.keys +
                moduleVariables.keys +
                apkVariables.keys
    }

}
