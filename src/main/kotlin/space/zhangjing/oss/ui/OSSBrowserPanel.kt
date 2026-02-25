package space.zhangjing.oss.ui

import com.android.tools.idea.util.toIoFile
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.utils.OSSUploader
import space.zhangjing.oss.utils.OSSUploader.createUrl
import space.zhangjing.oss.utils.PluginBundle.message
import space.zhangjing.oss.utils.copy
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.coroutines.cancellation.CancellationException

class OSSBrowserPanel(
    private val project: Project,
    private val credential: Credential,
    private val validityPeriod: Int,
    private val isBrowse: Boolean
) : JBPanel<OSSBrowserPanel>(BorderLayout()), Disposable {

    companion object {
        private val LOG = Logger.getInstance(OSSBrowserPanel::class.java)
        private const val DANGEROUS_DELETE_THRESHOLD = 50
        private val DELETE_CUSTOM_ICON = IconLoader.getIcon("/icons/delete.svg", OSSBrowserPanel::class.java)
    }

    private var s3Client: S3Client? = null

    private val treeRoot = DefaultMutableTreeNode(TreeNodeData(credential.bucketName, "", isRoot = true))
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)

    private val popupMenu = JBPopupMenu()
    private val newFolderItem = JBMenuItem(message("oss.browse.new.folder"))
    private val deleteItem = JBMenuItem(message("oss.browse.remove"))
    private val refreshItem = JBMenuItem(message("oss.browse.refresh"))
    private val downloadItem = JBMenuItem(message("oss.browse.download"))
    private val copyUrlItem = JBMenuItem(message("oss.browse.copy.url"))
    private val uploadItem = JBMenuItem(message("oss.browse.upload"))

    val selectedPath: String?
        get() = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)
            ?.userObject
            ?.let { it as? TreeNodeData }
            ?.objectPrefix

    init {
        add(JBScrollPane(tree), BorderLayout.CENTER)
        tree.isRootVisible = true
        tree.showsRootHandles = true
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        setupTree()
        setupPopupMenu()
        setupListeners()
        loadRoot()
    }

    private data class TreeNodeData(
        val displayName: String,
        val objectPrefix: String,
        var loaded: Boolean = false,
        val isRoot: Boolean = false,
        val isError: Boolean = false
    ) {
        override fun toString() = displayName
        val isDirectory: Boolean get() = isRoot || objectPrefix.endsWith("/")
        val isFile: Boolean get() = !isDirectory
        val canCreateFolder: Boolean get() = isDirectory
        val canDelete: Boolean get() = !isRoot
        val canDownload: Boolean get() = isFile
        val canCopy get() = isFile
        val canUpload: Boolean get() = isDirectory
    }

    private fun createPlaceholderNode(text: String = message("loading")) =
        DefaultMutableTreeNode(TreeNodeData(text, "", true))

    /** 显示占位节点 */
    private fun showLoadingNode(node: DefaultMutableTreeNode, text: String = message("loading")) {
        node.removeAllChildren()
        node.add(createPlaceholderNode(text))
        treeModel.reload(node)
    }

    /** 显示错误节点 */
    private fun showErrorNode(node: DefaultMutableTreeNode, errorMsg: String) {
        node.removeAllChildren()
        node.add(DefaultMutableTreeNode(TreeNodeData(errorMsg, "", isError = true)))
        treeModel.reload(node)
    }


    /** 获取或创建 S3Client */
    private inline fun onCreateS3Client(action: (S3Client) -> Unit) {
        val client = s3Client ?: OSSUploader.buildS3Client(credential).also { s3Client = it }
        try {
            action(client)
        } catch (e: SdkClientException) {
            LOG.warn("S3Client error, reconnecting", e)
            dispose()
            OSSUploader.buildS3Client(credential).let {
                s3Client = it
                action(it)
            }
        }
    }


    /** 通用后台加载节点方法 */
    private fun loadNode(node: DefaultMutableTreeNode, prefix: String, taskTitle: String) {
        showLoadingNode(node)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, taskTitle, false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    onCreateS3Client { client ->
                        val result = client.listObjectsV2(
                            ListObjectsV2Request.builder()
                                .bucket(credential.bucketName)
                                .delimiter("/")
                                .prefix(prefix)
                                .build()
                        )

                        SwingUtilities.invokeLater {
                            node.removeAllChildren()
                            (node.userObject as? TreeNodeData)?.loaded = true
                            result.commonPrefixes().forEach {
                                val name = extractName(it.prefix())
                                if (it.prefix() != prefix) {
                                    val child = DefaultMutableTreeNode(TreeNodeData(name, it.prefix()))
                                    child.add(createPlaceholderNode())
                                    node.add(child)
                                }
                            }
                            loadFile(node, result)
                            treeModel.reload(node)
                        }
                    }
                } catch (e: Exception) {
                    LOG.error(e)
                    SwingUtilities.invokeLater {
                        showErrorNode(node, message("oss.browse.error.load", e.message ?: "Unknown error"))
                    }
                }
            }
        })
    }

    private fun setupTree() {
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ) {
                val node = value as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? TreeNodeData ?: return
                append(data.displayName)
                icon = if (data.isFile) {
                    FileTypeManager.getInstance().getFileTypeByFileName(data.displayName).icon
                        ?: AllIcons.FileTypes.Any_type
                } else AllIcons.Nodes.Folder
            }
        }

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? TreeNodeData ?: return
                if (!data.loaded) loadNode(node, data.objectPrefix, message("oss.browse.loading", data.displayName))
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val data = node.userObject as? TreeNodeData ?: return
                    if (data.isError) {
                        loadRoot() // 错误节点双击重试
                    } else if (data.isFile) {
                        downloadFile()
                    }
                }
            }
        })
    }

    private fun setupPopupMenu() {
        popupMenu.add(newFolderItem)
        popupMenu.add(deleteItem)
        if (isBrowse) {
            popupMenu.add(downloadItem)
            popupMenu.add(copyUrlItem)
            popupMenu.add(uploadItem)
            downloadItem.icon = AllIcons.Actions.Download
            copyUrlItem.icon = AllIcons.Actions.Copy
            uploadItem.icon = AllIcons.Actions.Upload
        }
        popupMenu.addSeparator()
        popupMenu.add(refreshItem)
        newFolderItem.icon = AllIcons.Nodes.Folder
        deleteItem.icon = DELETE_CUSTOM_ICON
        refreshItem.icon = AllIcons.Actions.Refresh

        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode
                val data = node?.userObject as? TreeNodeData
                newFolderItem.isEnabled = data?.canCreateFolder == true
                deleteItem.isEnabled = data?.canDelete == true
                if (isBrowse) {
                    downloadItem.isEnabled = data?.canDownload == true
                    copyUrlItem.isEnabled = data?.canCopy == true
                    uploadItem.isEnabled = data?.canUpload == true
                }
                refreshItem.isEnabled = true
                popupMenu.show(comp, x, y)
            }
        })
    }

    private fun setupListeners() {
        newFolderItem.addActionListener { createFolder() }
        deleteItem.addActionListener { deleteFolder() }
        refreshItem.addActionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            node?.let { loadNode(it, (it.userObject as TreeNodeData).objectPrefix, message("oss.browse.refresh")) }
        }
        if (isBrowse) {
            downloadItem.addActionListener { downloadFile() }
            copyUrlItem.addActionListener { copyUrl() }
            uploadItem.addActionListener { uploadFile() }
        }
    }

    private fun loadRoot() {
        loadNode(treeRoot, "", message("oss.browse.root"))
    }

    private fun loadFile(parent: DefaultMutableTreeNode, result: ListObjectsV2Response) {
        if (isBrowse) {
            result.contents().forEach {
                if (!it.key().endsWith("/")) {
                    val name = extractName(it.key())
                    val node = DefaultMutableTreeNode(TreeNodeData(name, it.key(), true))
                    parent.add(node)
                }
            }
        }
    }

    private fun createFolder() {
        val parentNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val parentData = parentNode.userObject as TreeNodeData
        val name = Messages.showInputDialog(
            project,
            message("oss.browse.folder.name"),
            message("oss.browse.new.folder"),
            Messages.getQuestionIcon()
        )?.trim() ?: return
        val prefix = parentData.objectPrefix + name + "/"

        ProgressManager.getInstance().run(object :
            Task.Modal(project, message("oss.browse.create.folder"), false) {
            override fun run(indicator: ProgressIndicator) {
                onCreateS3Client {
                    it.putObject(
                        PutObjectRequest.builder().bucket(credential.bucketName).key(prefix).build(),
                        software.amazon.awssdk.core.sync.RequestBody.empty()
                    )
                }
                SwingUtilities.invokeLater {
                    val node = DefaultMutableTreeNode(TreeNodeData(name, prefix))
                    node.add(createPlaceholderNode())
                    parentNode.add(node)
                    treeModel.reload(parentNode)
                    tree.expandPath(TreePath(parentNode.path))
                }
            }
        })
    }

    private fun uploadFile() {
        val parentNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val parentData = parentNode.userObject as TreeNodeData
        // 创建文件选择器描述符，明确指定只能选择文件
        val fileChooserDescriptor = FileChooserDescriptor(
            true,  // chooseFiles - 允许选择文件
            false, // chooseFolders - 不允许选择目录
            true, // chooseJars - 允许选择JAR文件
            true, // chooseJarsAsFiles - 允许将JAR作为文件选择
            true, // chooseJarContents - 允许选择JAR内容
            false  // chooseMultiple - 不允许选择多个文件
        ).withTitle(message("oss.browse.file.chooser.title")) // 可选：添加标题
            .withDescription(message("oss.browse.file.chooser.desc")) // 可选：添加描述
        val file = FileChooser.chooseFile(
            fileChooserDescriptor,
            project,
            null
        ) ?: return

        ProgressManager.getInstance().run(object :
            Task.Modal(project, message("oss.browse.upload.progress", file.name), true) {
            override fun run(indicator: ProgressIndicator) {
                val objectName = parentData.objectPrefix + file.name
                val request = PutObjectRequest.builder()
                    .bucket(credential.bucketName)
                    .key(objectName)
                    .build()
                onCreateS3Client {
                    it.putObject(request, RequestBody.fromFile(file.toIoFile()))
                    SwingUtilities.invokeLater {
                        refreshNode(parentNode)
                    }
                }
            }
        })
    }

    private fun deleteFolder() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as TreeNodeData
        if (Messages.showYesNoDialog(
                message("oss.browse.delete.folder.confirm", data.displayName),
                message("oss.browse.delete_btn_ok"),
                Messages.getQuestionIcon()
            ) != Messages.YES
        ) return

        ProgressManager.getInstance().run(object :
            Task.Modal(project, message("oss.browse.delete.prepare", data.displayName), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = message("oss.browse.counting")
                    var totalCount = 0
                    onCreateS3Client {
                        val paginator = it.listObjectsV2Paginator(
                            ListObjectsV2Request.builder().bucket(credential.bucketName).prefix(data.objectPrefix)
                                .build()
                        )

                        paginator.forEach { page ->
                            if (indicator.isCanceled) return@forEach
                            totalCount += page.contents().size
                        }

                        if (totalCount > DANGEROUS_DELETE_THRESHOLD) {
                            SwingUtilities.invokeAndWait {
                                if (Messages.showYesNoDialog(
                                        message("oss.browse.delete.risk.confirm", totalCount),
                                        message("oss.browse.delete.risk.title"),
                                        Messages.getWarningIcon()
                                    ) != Messages.YES
                                ) throw CancellationException("User cancels high-risk deletion")
                            }
                        }

                        var deletedCount = 0
                        indicator.text = message("oss.browse.delete.start")
                        paginator.forEach { page ->
                            for (obj in page.contents()) {
                                if (indicator.isCanceled) return@forEach
                                it.deleteObject(
                                    DeleteObjectRequest.builder().bucket(credential.bucketName).key(obj.key()).build()
                                )
                                deletedCount++
                                indicator.text = message("oss.browse.delete.progress", deletedCount, totalCount)
                                indicator.fraction = if (totalCount == 0) 1.0 else deletedCount.toDouble() / totalCount
                            }
                        }

                        SwingUtilities.invokeLater {
                            val parent = node.parent as? DefaultMutableTreeNode
                            refreshNode(parent)
                            if (deletedCount > 1) {
                                Messages.showInfoMessage(
                                    message("oss.browse.delete.result", deletedCount),
                                    message("oss.browse.delete.success")
                                )
                            }
                        }
                    }
                } catch (_: CancellationException) {
                } catch (e: Exception) {
                    LOG.error(e)
                    SwingUtilities.invokeLater { Messages.showErrorDialog(e.message ?: "", message("error")) }
                }
            }
        })
    }

    private fun refreshNode(node: DefaultMutableTreeNode?) {
        val data = node?.userObject as? TreeNodeData ?: return
        data.loaded = false
        if (tree.isExpanded(TreePath(node.path))) loadNode(node, data.objectPrefix, message("oss.browse.refresh"))
    }

    private fun downloadFile() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as TreeNodeData
        val parentNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val parentData = parentNode.userObject as TreeNodeData
        // 创建文件选择器描述符，明确指定只能选择文件
        val fileChooserDescriptor = FileChooserDescriptor(
            false,
            true,
            false,
            false,
            false,
            false
        ).withTitle(message("oss.browse.folder.chooser.title"))
            .withDescription(message("oss.browse.folder.chooser.desc"))
        val folder =
            FileChooser.chooseFile(fileChooserDescriptor, project, null) ?: return
        val targetPath = java.nio.file.Paths.get(folder.path, data.displayName)
        ProgressManager.getInstance().run(object :
            Task.Modal(project, message("oss.browse.download"), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    onCreateS3Client {
                        it.getObject(
                            { it.bucket(credential.bucketName).key(data.objectPrefix) },
                            targetPath
                        )
                        SwingUtilities.invokeLater {
                            Messages.showInfoMessage(
                                message("oss.browse.download.success", data.displayName),
                                message("oss.browse.download.title")
                            )
                        }
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(
                            message("oss.browse.download.failed", e.message ?: ""),
                            message("error")
                        )
                    }
                }
            }
        })
    }


    private fun copyUrl() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as TreeNodeData
        val url = data.objectPrefix.createUrl(credential, validityPeriod)
        url.copy()
    }

    private fun extractName(prefix: String): String = prefix.removeSuffix("/").substringAfterLast("/")

    override fun dispose() {
        try {
            s3Client?.close()
        } catch (e: Exception) {
            LOG.error(e)
        }
        s3Client = null
    }

}
