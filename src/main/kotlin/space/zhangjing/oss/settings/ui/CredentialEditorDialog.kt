package space.zhangjing.oss.settings.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.utils.CredentialSecretStore
import space.zhangjing.oss.utils.OSSUploader
import space.zhangjing.oss.utils.OSSUploader.region
import space.zhangjing.oss.utils.OSSUploader.scheme
import space.zhangjing.oss.utils.PluginBundle.message
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent

class CredentialEditorDialog(
    private val credential: Credential
) : DialogWrapper(true) {


    companion object {
        private val LOG = Logger.getInstance(CredentialEditorDialog::class.java)
        private const val SECRET_VIEW = "view"
        private const val SECRET_EDIT = "edit"
        private const val AK_MASKED = "masked"
        private const val AK_PLAIN = "plain"
    }


    private val nameField = JBTextField(credential.name)
    private val endpointField = JBTextField(credential.endpoint)
    private val regionCombo = ComboBox(
        Region.regions().map { it.id() }.sorted().toTypedArray()
    ).apply {
        isEditable = true
        selectedItem = credential.region
        toolTipText = message("credential.filed.region.tip")
    }

    private val bucketField = JBTextField(credential.bucketName)
    private val cdnField = JBTextField(credential.cdnUrl)

    /** ===== AccessKeyId（可显示/隐藏） ===== */
    private val akPlainField = JBTextField(credential.accessKeyId)
    private val akMaskedField = JBPasswordField().apply {
        text = credential.accessKeyId
    }
    private val akPanel = JPanel(CardLayout())
    private val akShowCheckBox = JCheckBox(message("credential.ak.show"))

    /** ===== AccessKeySecret（重置式） ===== */
    private val skField = JBPasswordField()
    private val resetLink = JLabel(
        "<html>&nbsp;&nbsp;<a href=''>${message("credential.secret.reset")}</a></html>"
    )
    val resetLabel = JLabel(message("credential.secret.memory.hint"))
    private val savePwd = JCheckBox(message("credential.secret.save.ide"))
    private val secretPanel = JPanel(CardLayout())

    init {
        title = message("credential")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(10)
        panel.preferredSize = JBUI.size(620, 380)

        fun row(label: String, field: JComponent, extra: JComponent?, y: Int) {
            val labelGbc = GridBagConstraints().apply {
                gridx = 0
                gridy = y
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(4)
            }
            val fieldGbc = GridBagConstraints().apply {
                gridx = 1
                gridy = y
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = JBUI.insets(4)
            }
            panel.add(JLabel(label), labelGbc)
            panel.add(field, fieldGbc)

            if (extra != null) {
                val extraGbc = GridBagConstraints().apply {
                    gridx = 2
                    gridy = y
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(4)
                }
                panel.add(extra, extraGbc)
            }
        }

        /** ===== AK Panel ===== */
        akPanel.add(akMaskedField, AK_MASKED)
        akPanel.add(akPlainField, AK_PLAIN)

        (akPanel.layout as CardLayout).show(akPanel, AK_MASKED)

        akShowCheckBox.addActionListener {
            val layout = akPanel.layout as CardLayout
            if (akShowCheckBox.isSelected) {
                akPlainField.text = String(akMaskedField.password)
                layout.show(akPanel, AK_PLAIN)
            } else {
                akMaskedField.text = akPlainField.text
                layout.show(akPanel, AK_MASKED)
            }
        }

        /** ===== Secret Panel ===== */
        resetLink.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        resetLink.toolTipText = message("credential.secret.reset.tip")

        resetLink.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showSecretEditor()
            }
        })

        val memoryOnly = CredentialSecretStore.isMemoryOnly(credential.id)
        val hasSecret = CredentialSecretStore.loadSecret(credential.id) != null
        val resetPanel = JPanel(GridBagLayout()).apply {
            val gbcLabel = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = JBUI.insetsRight(4)
            }
            val gbcLink = GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                anchor = GridBagConstraints.WEST
            }
            if (memoryOnly) {
                add(resetLabel, gbcLabel)
            }
            add(resetLink, gbcLink)
        }

        secretPanel.add(resetPanel, SECRET_VIEW)
        secretPanel.add(skField, SECRET_EDIT)

        val secretLayout = secretPanel.layout as CardLayout
        if (hasSecret) {
            secretLayout.show(secretPanel, SECRET_VIEW)
        } else {
            secretLayout.show(secretPanel, SECRET_EDIT)
        }
        savePwd.isVisible = !hasSecret
        if (hasSecret && !memoryOnly) {
            savePwd.isSelected = true
        }
        endpointField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val endpoint = endpointField.text
                val inferred = inferRegionFromEndpoint(endpoint) ?: return
                val current = regionCombo.editor.item?.toString().orEmpty()
                if (current.isBlank()) {
                    regionCombo.selectedItem = inferred
                }
            }
        })

        /** ===== Rows ===== */
        row(message("credential.filed.name"), nameField, null, 0)
        row(message("credential.filed.endpoint"), endpointField, null, 1)
        row(message("credential.filed.region"), regionCombo, null, 2)
        row(message("credential.filed.access.id"), akPanel, akShowCheckBox, 3)
        row(message("credential.filed.access.secret"), secretPanel, savePwd, 4)
        row(message("credential.filed.bucket"), bucketField, null, 5)
        row(message("credential.filed.cdn.url"), cdnField, null, 6)

        return panel
    }

    private fun showSecretEditor() {
        skField.text = ""
        val layout = secretPanel.layout as CardLayout
        layout.show(secretPanel, SECRET_EDIT)
        savePwd.isVisible = true
        skField.requestFocusInWindow()
    }

    override fun doOKAction() {
        credential.name = nameField.text
        credential.endpoint = endpointField.text.scheme()
        credential.accessKeyId =
            if (akShowCheckBox.isSelected) akPlainField.text else String(akMaskedField.password)
        credential.bucketName = bucketField.text
        credential.cdnUrl = cdnField.text
        credential.region = regionCombo.editor.item?.toString()?.trim().orEmpty()
        val newSecret = String(skField.password)
        if (newSecret.isNotBlank() || !savePwd.isSelected) {
            CredentialSecretStore.removeSecretFromDisk(credential.id)
        }
        if (newSecret.isNotBlank()) {
            CredentialSecretStore.saveSecret(credential.id, newSecret, memoryOnly = !savePwd.isSelected)
        }

        skField.text = ""
        super.doOKAction()
    }

    override fun createActions(): Array<Action> {
        val testAction = object : AbstractAction(message("credential.test.connection")) {
            override fun actionPerformed(e: ActionEvent?) {
                testConnection()
            }
        }
        return arrayOf(testAction, okAction, cancelAction)
    }

    private fun testConnection() {
        val endpoint = endpointField.text.trim()
        val accessKeyId =
            if (akShowCheckBox.isSelected) akPlainField.text else String(akMaskedField.password)
        val bucket = bucketField.text.trim()

        if (endpoint.isEmpty() || accessKeyId.isEmpty()) {
            Messages.showWarningDialog(
                message("credential.test.missing.fields"),
                message("credential.test.connection")
            )
            return
        }
        val newSecret = String(skField.password)
        val secret = newSecret.ifBlank { CredentialSecretStore.loadSecret(credential.id) }
        if (secret.isNullOrEmpty()) {
            Messages.showErrorDialog(
                message("credential.test.invalid.credentials"),
                message("credential.test.connection")
            )
            return
        }
        val region = regionCombo.editor.item?.toString()?.trim().orEmpty()

        ProgressManager.getInstance().run(object : Task.Modal(
            null, message("credential.test.connection"), true
        ) {
            var success = false
            var errorMessage: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    OSSUploader.buildS3Client(endpoint, accessKeyId, secret, region.region()).use {
                        it.headBucket(
                            HeadBucketRequest.builder()
                                .bucket(bucket)
                                .build()
                        )
                        success = true
                    }
                } catch (e: S3Exception) {
                    errorMessage = when (e.statusCode()) {
                        400 -> message("credential.test.invalid.region")
                        403 -> message("credential.test.invalid.credentials")
                        404 -> message("credential.test.bucket.not.exists")
                        else -> e.message
                    }
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            }

            override fun onSuccess() {
                if (success) {
                    Messages.showInfoMessage(
                        message("credential.test.success"),
                        message("credential.test.connection")
                    )
                } else {
                    Messages.showErrorDialog(
                        errorMessage ?: message("credential.test.failed"),
                        message("credential.test.connection")
                    )
                }
            }
        })
    }

    private fun inferRegionFromEndpoint(endpoint: String): String? {
        val regex = Regex("""^(?:https?://)?s3[.-]([a-z0-9-]+)\.amazonaws\.com(?:/.*)?$""")
        return regex.find(endpoint)?.groupValues?.get(1)
    }


}
