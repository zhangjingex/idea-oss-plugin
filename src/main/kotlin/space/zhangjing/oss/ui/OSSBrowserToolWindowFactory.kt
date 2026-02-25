package space.zhangjing.oss.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import space.zhangjing.oss.bus.CREDENTIAL_CHANGED_TOPIC
import space.zhangjing.oss.bus.CredentialChangedListener
import space.zhangjing.oss.bus.subscribe
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.settings.ui.CredentialConfigurable
import space.zhangjing.oss.utils.PluginBundle.message
import space.zhangjing.oss.utils.config
import space.zhangjing.oss.utils.refreshCredentials
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel

class OSSBrowserToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        fun createContent() {
            val content = ContentFactory.getInstance()
                .createContent(null, message("oss.browse.title.no.credential"), false)

            val mainPanel = createMainPanel(project, content)
            content.component = mainPanel

            toolWindow.contentManager.addContent(content)
        }

        if (toolWindow.contentManager.isEmpty) {
            createContent()
        }

        project.messageBus.connect(toolWindow.disposable)
            .subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                override fun toolWindowShown(shownToolWindow: ToolWindow) {
                    if (shownToolWindow.id == toolWindow.id &&
                        shownToolWindow.contentManager.isEmpty
                    ) {
                        createContent()
                    }
                }
            })
    }

    private fun createMainPanel(
        project: Project,
        content: Content
    ): JPanel {

        val (validityPeriod, defaultCredential) = project.config()

        val mainPanel = JPanel(BorderLayout())
        val comboBox = ComboBox<Credential>()

        var currentCenterPanel: JPanel? = null
        var currentBrowserPanel: OSSBrowserPanel? = null

        lateinit var updateCenterPanel: () -> Unit

        // ---------- Credential 监听 ----------
        CREDENTIAL_CHANGED_TOPIC.subscribe(content, object : CredentialChangedListener {

            override fun credentialsChanged(credential: Credential) {
                val old = comboBox.selectedItem as? Credential
                comboBox.repaint()
                if (old?.id == credential.id) {
                    updateCenterPanel()
                }
            }

            override fun credentialsAdded(credential: Credential) {
                comboBox.addItem(credential)
            }

            override fun credentialsDeleted(credential: Credential) {
                val old = comboBox.selectedItem as? Credential
                comboBox.removeItem(credential)
                if (old?.id == credential.id) {
                    updateCenterPanel()
                }
            }
        })

        comboBox.refreshCredentials()
        comboBox.selectedItem = defaultCredential
        mainPanel.add(comboBox, BorderLayout.NORTH)

        fun createNoCredentialPanel(): JPanel =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                val center = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.CENTER))
                val messagePanel = JBPanel<JBPanel<*>>(BorderLayout(0, 10))

                val icon = JBLabel(AllIcons.General.Warning)
                val text = JBLabel(message("oss.browse.no.credential")).apply {
                    font = font.deriveFont(Font.PLAIN, 13f)
                }
                val button = JButton(message("oss.browse.go.to.settings")).apply {
                    addActionListener {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, CredentialConfigurable::class.java)
                    }
                }

                messagePanel.add(icon, BorderLayout.NORTH)
                messagePanel.add(text, BorderLayout.CENTER)
                messagePanel.add(button, BorderLayout.SOUTH)

                center.add(messagePanel)
                add(center, BorderLayout.CENTER)
            }

        fun createBrowserPanel(credential: Credential): OSSBrowserPanel =
            OSSBrowserPanel(project, credential, validityPeriod, true)

        // ---------- updateCenterPanel 实现 ----------
        updateCenterPanel = {

            val selected = comboBox.selectedItem as? Credential

            val newPanel: JPanel =
                if (selected != null) createBrowserPanel(selected)
                else createNoCredentialPanel()

            // 立即销毁旧 BrowserPanel
            currentBrowserPanel?.let {
                Disposer.dispose(it)
                currentBrowserPanel = null
            }

            // 移除旧 UI
            currentCenterPanel?.let { mainPanel.remove(it) }

            // 添加新 UI
            mainPanel.add(newPanel, BorderLayout.CENTER)
            currentCenterPanel = newPanel

            // Disposable 到 Content
            if (newPanel is OSSBrowserPanel) {
                currentBrowserPanel = newPanel
                Disposer.register(content, newPanel)
            }

            // 更新标题
            content.displayName =
                if (selected != null)
                    message("oss.browse.title", selected.name)
                else
                    message("oss.browse.title.no.credential")

            mainPanel.revalidate()
            mainPanel.repaint()
        }

        comboBox.addActionListener { updateCenterPanel() }

        // 初始渲染
        updateCenterPanel()

        return mainPanel
    }
}
