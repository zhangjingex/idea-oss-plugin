package space.zhangjing.oss.settings.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import space.zhangjing.oss.bus.CREDENTIAL_CHANGED_TOPIC
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.settings.CredentialSettings
import space.zhangjing.oss.utils.CredentialSecretStore
import space.zhangjing.oss.utils.PluginBundle.message
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class CredentialConfigurable : Configurable {

    private val settings = CredentialSettings.getInstance()
    private val list = JBList<Credential>()

    override fun getDisplayName(): String = message("credential")

    override fun createComponent(): JComponent {
        list.setListData(settings.state.credentials.toTypedArray())

        val panel = JPanel(BorderLayout())
        val decorator = ToolbarDecorator.createDecorator(list)

        decorator.setAddAction {
            val credential = Credential(name = message("new.credential.name"))
            if (CredentialEditorDialog(credential).showAndGet()) {
                settings.state.credentials.add(credential)
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(CREDENTIAL_CHANGED_TOPIC)
                    .credentialsAdded(credential)
                refresh()
            }
        }

        decorator.setEditAction {
            val selected = list.selectedValue ?: return@setEditAction
            if (CredentialEditorDialog(selected).showAndGet()) {
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(CREDENTIAL_CHANGED_TOPIC)
                    .credentialsChanged(selected)
                refresh()
            }
        }

        decorator.setRemoveAction {
            val selected = list.selectedValue ?: return@setRemoveAction
            if (Messages.showYesNoDialog(
                    message("confirm.delete.credential", selected.name),
                    message("confirm.delete.title"),
                    null
                ) == Messages.YES
            ) {
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(CREDENTIAL_CHANGED_TOPIC)
                    .credentialsDeleted(selected)
                settings.state.credentials.remove(selected)
                CredentialSecretStore.removeSecret(selected.id)
                refresh()
            }
        }

        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        return panel
    }

    private fun refresh() {
        list.setListData(settings.state.credentials.toTypedArray())
    }

    override fun isModified(): Boolean = false
    override fun apply() {}


}
