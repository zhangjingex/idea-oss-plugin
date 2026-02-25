package space.zhangjing.oss.settings


import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@State(
    name = "AndroidOSSPluginProjectSettings",
    storages = [Storage("idea-oss-plugin.xml")]
)
@Service(Service.Level.PROJECT)
class ProjectSettings : PersistentStateComponent<ProjectSettings.State> {

    data class State(
        var selectedCredentialId: String = "",
        var uploadPath: String = "",
        var validityPeriod: Int = 600
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): ProjectSettings =
            project.getService(ProjectSettings::class.java)
    }
}
