package space.zhangjing.oss.settings.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import space.zhangjing.oss.bus.CREDENTIAL_CHANGED_TOPIC
import space.zhangjing.oss.bus.CredentialChangedListener
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.settings.CredentialSettings
import space.zhangjing.oss.settings.DigitsOnlyFilter
import space.zhangjing.oss.settings.ProjectSettings
import space.zhangjing.oss.ui.TemplateTextField
import space.zhangjing.oss.utils.PluginBundle.message
import space.zhangjing.oss.utils.VariablesUtils.variables
import space.zhangjing.oss.utils.createS3SelectButton
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.text.AbstractDocument

class ProjectSettingsConfigurable(
    private val project: Project
) : Configurable {

    private val projectSettings = ProjectSettings.getInstance(project)
    private val credentialSettings = CredentialSettings.getInstance()

    private val credentialModel = DefaultComboBoxModel(credentialSettings.state.credentials.toTypedArray())
    private val credentialCombo = ComboBox(credentialModel)
    private val pathField = TemplateTextField({ it.variables(project, null) ?: it }
    )
    private val validityPeriodField = JBTextField()

    private val useDialogRadio = JRadioButton(message("use.dialog"))
    private val useNotificationRadio = JRadioButton(message("use.notification"))
    private val notificationGroup = ButtonGroup()

    private lateinit var panel: JPanel

    private val connection = ApplicationManager.getApplication()
        .messageBus
        .connect()

    init {
        connection.subscribe(
            CREDENTIAL_CHANGED_TOPIC,
            object : CredentialChangedListener {
                override fun credentialsChanged() {
                    reloadCredentials()
                }
            }
        )
    }


    override fun getDisplayName(): String = message("plugin.title")

    override fun createComponent(): JComponent {
        // 只允许数字
        (validityPeriodField.document as? AbstractDocument)
            ?.documentFilter = DigitsOnlyFilter()

        val state = projectSettings.state
        credentialSettings.findById(state.selectedCredentialId)
            ?.let { credentialCombo.selectedItem = it }

        // ===== 字段初始值 =====
        pathField.text = state.uploadPath
        validityPeriodField.text = state.validityPeriod.toString()

        // ===== 单选按钮 =====
        notificationGroup.add(useDialogRadio)
        notificationGroup.add(useNotificationRadio)
        useDialogRadio.isSelected = state.useDialog
        useNotificationRadio.isSelected = !state.useDialog

        val credential = {
            requireCredential()
        }

        val validityPeriod = {
            val text = validityPeriodField.text
            if (text.isNotEmpty()) text.toInt() else 600
        }

        val onError = { e: Throwable ->
            JOptionPane.showMessageDialog(
                panel,
                message("oss_connect_failed", e.message ?: ""),
                message("error"),
                JOptionPane.ERROR_MESSAGE
            )
        }

        // ===== 选择路径按钮 =====
        val choosePathButton = project.createS3SelectButton(
            {
                val credential = requireCredential()
                message("oss.browse.select.title", credential.name)
            }, credential, validityPeriod,
            { select -> pathField.text = select },
            onError
        )

        //跳转凭证配置
        val configureCredentialButton = JButton(message("settings")).apply {
            addActionListener {
                ShowSettingsUtil.getInstance()
                    .editConfigurable(project, CredentialConfigurable())
            }
        }

        // 当未选择凭证时禁用按钮
        fun updateChoosePathState() {
            choosePathButton.isEnabled = credentialCombo.selectedItem != null
        }
        credentialCombo.addActionListener { updateChoosePathState() }
        updateChoosePathState()

        // ===== 主面板 =====
        panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(10)
        }

        fun addRow(
            label: String,
            field: JComponent,
            row: Int,
            extra: JComponent? = null
        ) {
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(5)
            }

            gbc.gridx = 0
            gbc.gridy = row
            gbc.weightx = 0.0
            panel.add(JLabel(label), gbc)

            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(field, gbc)

            extra?.let {
                gbc.gridx = 2
                gbc.weightx = 0.0
                panel.add(it, gbc)
            }
        }

        var row = 0
        addRow(message("credential"), credentialCombo, row++, configureCredentialButton)
        addRow(message("upload.path"), pathField, row++, choosePathButton)
        addRow(message("expires"), validityPeriodField, row++)
        val radioPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        radioPanel.add(useDialogRadio)
        radioPanel.add(useNotificationRadio)
        addRow(message("upload.success"), radioPanel, row++)

        return panel
    }

    private fun getSelectedCredentialOrNull(): Credential? {
        return credentialCombo.selectedItem as? Credential

    }

    private fun requireCredential(): Credential {
        return getSelectedCredentialOrNull()
            ?: throw ConfigurationException(message("no.selected.credential"))
    }


    override fun isModified(): Boolean {
        val s = projectSettings.state
        val selected = credentialCombo.selectedItem as? Credential

        return selected?.id != s.selectedCredentialId ||
                pathField.text != s.uploadPath ||
                validityPeriodField.text.toIntOrNull() != s.validityPeriod ||
                useDialogRadio.isSelected != s.useDialog
    }

    override fun apply() {
        val credential = requireCredential()
        val validityText = validityPeriodField.text

        if (validityText.isBlank()) {
            throw ConfigurationException(message("expires.empty"))
        }

        val validity = validityText.toIntOrNull()
            ?: throw ConfigurationException(message("expires.not.number"))

        if (validity < 1 || validity > 86400) {
            throw ConfigurationException(message("expires.out.of.range"))
        }
        val s = projectSettings.state
        s.selectedCredentialId = credential.id
        s.uploadPath = pathField.text
        s.validityPeriod = validity
        s.useDialog = useDialogRadio.isSelected
    }

    override fun reset() {
        val s = projectSettings.state

        credentialSettings.findById(s.selectedCredentialId)
            ?.let { credentialCombo.selectedItem = it }

        pathField.text = s.uploadPath
        validityPeriodField.text = s.validityPeriod.toString()
        useDialogRadio.isSelected = s.useDialog
        useNotificationRadio.isSelected = !s.useDialog
    }

    override fun disposeUIResources() {
        connection.disconnect()
    }

    private fun reloadCredentials() {
        val selectedId = (credentialCombo.selectedItem as? Credential)?.id
        credentialModel.removeAllElements()
        credentialSettings.state.credentials.forEach {
            credentialModel.addElement(it)
        }
        credentialSettings.findById(selectedId)
            ?.let { credentialCombo.selectedItem = it }
    }

}
