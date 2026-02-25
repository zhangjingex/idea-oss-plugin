package space.zhangjing.oss.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.IconUtil.toImage
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import space.zhangjing.oss.utils.PluginBundle.message
import java.awt.Dimension
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class ObjectPropertiesDialog(
    project: Project,
    private val bucket: String,
    private val key: String,
    private val head: HeadObjectResponse
) : DialogWrapper(project) {

    init {
        title = message("oss.browse.properties")
        init()
        window?.setIconImage(toImage(AllIcons.Actions.Properties))
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12)
        }

        mainPanel.add(TitledSeparator(message("oss.browse.properties.general")))
        mainPanel.add(createGeneralPanel())

        if (head.metadata().isNotEmpty()) {
            mainPanel.border = JBUI.Borders.empty(12)
            mainPanel.add(TitledSeparator(message("oss.browse.properties.metadata")))
            mainPanel.add(createMetadataPanel())
        }

        return mainPanel
    }

    override fun createActions() = arrayOf(okAction)

    override fun getPreferredSize(): Dimension =
        JBUI.size(540, -1)


    private fun createGeneralPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(
                message("oss.browse.properties.bucket"),
                valueLabel(bucket)
            )
            .addLabeledComponent(
                message("oss.browse.properties.key"),
                valueLabel(key)
            )
            .addLabeledComponent(
                message("oss.browse.properties.size"),
                valueLabel(
                    formatSize(head.contentLength()) +
                            head.contentLength()?.let { " ($it bytes)" }.orEmpty()
                )
            )
            .addLabeledComponent(
                message("oss.browse.properties.modified"),
                valueLabel(
                    head.lastModified()
                        ?.atZone(ZoneId.systemDefault())
                        ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                )
            )
            .addLabeledComponent(
                message("oss.browse.properties.etag"),
                valueLabel(head.eTag())
            )
            .addLabeledComponent(
                message("oss.browse.properties.type"),
                valueLabel(head.contentType())
            )
            .addLabeledComponent(
                message("oss.browse.properties.storageClass"),
                valueLabel(head.storageClassAsString())
            )
            .panel
    }

    private fun createMetadataPanel(): JComponent {
        val columnNames = arrayOf("Key", "Value")

        val model = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        head.metadata().forEach { (k, v) ->
            model.addRow(arrayOf(k, v))
        }

        val table = JBTable(model).apply {
            tableHeader.reorderingAllowed = false
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            rowHeight = JBUI.scale(22)
            setShowGrid(false)
        }

        return JBScrollPane(table).apply {
            preferredSize = JBUI.size(480, 160)
        }
    }


    private fun valueLabel(text: String?): JBLabel =
        JBLabel(text ?: "").apply {
            setAllowAutoWrapping(true)
        }

    companion object {

        private fun formatSize(size: Long?): String {
            if (size == null) return ""
            val kb = 1024.0
            val mb = kb * 1024
            val gb = mb * 1024
            return when {
                size >= gb -> String.format("%.2f GB", size / gb)
                size >= mb -> String.format("%.2f MB", size / mb)
                size >= kb -> String.format("%.2f KB", size / kb)
                else -> "$size B"
            }
        }
    }
}
