package space.zhangjing.oss.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.ui.panel.OSSBrowserPanel
import java.awt.Dimension
import javax.swing.JComponent

class OSSBrowserDialog(
    name: String,
    project: Project,
    credential: Credential,
    validityPeriod: Int,
    isBrowse: Boolean
) : DialogWrapper(project) {

    private val browserPanel = OSSBrowserPanel(project, credential, validityPeriod, isBrowse)

    init {
        title = name
        init()
    }

    val selectedPath: String? get() = browserPanel.selectedPath

    override fun createCenterPanel(): JComponent = browserPanel

    override fun getInitialSize(): Dimension {
        return Dimension(400, 600)
    }

    override fun dispose() {
        browserPanel.dispose()
        super.dispose()
    }
}
