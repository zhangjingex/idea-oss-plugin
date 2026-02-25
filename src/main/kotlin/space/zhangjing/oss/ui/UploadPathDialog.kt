package space.zhangjing.oss.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import space.zhangjing.oss.bus.CREDENTIAL_CHANGED_TOPIC
import space.zhangjing.oss.bus.CredentialChangedListener
import space.zhangjing.oss.bus.subscribe
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.entity.Credential.Companion.findById
import space.zhangjing.oss.settings.ProjectSettings
import space.zhangjing.oss.settings.ui.CredentialConfigurable
import space.zhangjing.oss.utils.PluginBundle.message
import space.zhangjing.oss.utils.VariablesUtils.variables
import space.zhangjing.oss.utils.createS3SelectButton
import space.zhangjing.oss.utils.refreshCredentials
import space.zhangjing.oss.utils.truncateWithEllipsis
import java.io.File
import javax.swing.*

class UploadPathDialog private constructor(
    val project: Project,
    settings: ProjectSettings.State,
    credentials: List<Credential>,
    lastPath: String?,
    val name: String,
    tooltipProvider: (String) -> String
) : DialogWrapper(project) {

    constructor(
        project: Project,
        settings: ProjectSettings.State,
        credentials: List<Credential>,
        lastPath: String?,
        virtualFile: VirtualFile
    ) : this(project, settings, credentials, lastPath, virtualFile.name, {
        it.variables(project, virtualFile) ?: it
    })

    constructor(
        project: Project,
        settings: ProjectSettings.State,
        credentials: List<Credential>,
        lastPath: String?,
        file: File
    ) : this(project, settings, credentials, lastPath, file.name, {
        it.variables(project, file) ?: it
    })


    private val textField = TemplateTextField(tooltipProvider)

    private val credentialComboBox = ComboBox<Credential>()
    private val setAsDefaultCheckBox =
        JCheckBox(message("set.as.project.default"))


    val ossPath: String
        get() = textField.text.trim()

    val credential: Credential
        get() = credentialComboBox.selectedItem as Credential


    init {
        CREDENTIAL_CHANGED_TOPIC.subscribe(
            disposable,
            object : CredentialChangedListener {
                override fun credentialsChanged() {
                    credentialComboBox.refreshCredentials()
                }
            }
        )
        title = message("path.dialog.title", name.truncateWithEllipsis(33))
        // 初始化下拉框
        credentials.forEach { credentialComboBox.addItem(it) }
        val defaultCredential = credentials.findById(settings.selectedCredentialId)
        if (defaultCredential != null) {
            credentialComboBox.selectedItem = defaultCredential
        }
        textField.text = lastPath ?: ""
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // ---- 凭证选择行 ----
        val credentialPanel = JPanel()
        credentialPanel.layout = BoxLayout(credentialPanel, BoxLayout.X_AXIS)

        credentialPanel.add(JLabel(message("select.credential")))
        credentialPanel.add(Box.createRigidArea(java.awt.Dimension(8, 0)))

        credentialPanel.add(credentialComboBox)
        credentialPanel.add(Box.createRigidArea(java.awt.Dimension(8, 0)))

        // 设置按钮
        val settingsButton = JButton(message("settings"))
        val buttonSize = java.awt.Dimension(60, settingsButton.preferredSize.height)
        settingsButton.preferredSize = buttonSize
        settingsButton.minimumSize = buttonSize
        settingsButton.maximumSize = buttonSize
        settingsButton.addActionListener {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, CredentialConfigurable::class.java)
        }
        credentialPanel.add(settingsButton)
        credentialPanel.add(Box.createRigidArea(java.awt.Dimension(4, 0)))

        credentialPanel.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        panel.add(credentialPanel)
        panel.add(Box.createVerticalStrut(12))

        // ---- 上传路径行 ----
        panel.add(JLabel(message("path.hint")))
        val inputPanel = JPanel()
        inputPanel.layout = BoxLayout(inputPanel, BoxLayout.X_AXIS)

        val button = project.createS3SelectButton(
            {
                message("oss.browse.select.title", credential.name)
            },
            { credential },
            { ProjectSettings.getInstance(project).state.validityPeriod },
            { select -> textField.text = select },
            { e ->
                JOptionPane.showMessageDialog(
                    panel,
                    message("oss.connect.failed", e.message ?: ""),
                    message("error"),
                    JOptionPane.ERROR_MESSAGE
                )
            }
        )

        inputPanel.add(textField)
        inputPanel.add(Box.createRigidArea(java.awt.Dimension(8, 0)))
        inputPanel.add(button)
        inputPanel.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        panel.add(inputPanel)

        panel.add(Box.createVerticalStrut(8))
        setAsDefaultCheckBox.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        panel.add(setAsDefaultCheckBox)

        panel.preferredSize = java.awt.Dimension(500, 140)
        return panel
    }


    override fun doOKAction() {
        if (setAsDefaultCheckBox.isSelected) {
            val projectSettings = ProjectSettings.getInstance(project)
            val state = projectSettings.state
            state.selectedCredentialId = credential.id
            state.uploadPath = ossPath
        }
        super.doOKAction()
    }

}
