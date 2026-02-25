package space.zhangjing.oss.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.entity.Credential.Companion.findById

@State(
    name = "AndroidOSSPluginCredentials",
    storages = [Storage("idea-oss-plugin-credentials.xml")]
)
@Service(Service.Level.APP)
class CredentialSettings : PersistentStateComponent<CredentialSettings.State> {

    data class State(
        var credentials: MutableList<Credential> = mutableListOf()
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun findById(id: String?): Credential? = state.credentials.findById(id)

    companion object {
        fun getInstance(): CredentialSettings =
            ApplicationManager
                .getApplication()
                .getService(CredentialSettings::class.java)
    }

}
