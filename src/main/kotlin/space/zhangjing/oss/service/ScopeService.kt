package space.zhangjing.oss.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope


@Service(Service.Level.PROJECT)
class ScopeService(
    private val project: Project,
    val scope: CoroutineScope
) {

}