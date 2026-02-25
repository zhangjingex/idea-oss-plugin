package space.zhangjing.oss.ui.panel

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import kotlinx.coroutines.*
import space.zhangjing.oss.bus.OSS_UPLOADED_TOPIC
import space.zhangjing.oss.bus.UploadedListener
import space.zhangjing.oss.bus.subscribe
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.entity.Credential.Companion.eq
import space.zhangjing.oss.ui.ObjectPropertiesDialog
import space.zhangjing.oss.utils.*
import space.zhangjing.oss.utils.OSSUtils.createUrl
import space.zhangjing.oss.utils.PluginBundle.message
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.absolutePathString

class OSSBrowserPanel(
    private val project: Project,
    private val credential: Credential,
    private val validityPeriod: Int,
    private val isBrowse: Boolean
) : JBPanel<OSSBrowserPanel>(BorderLayout()), Disposable {

    companion object {
        private val LOG = Logger.getInstance(OSSBrowserPanel::class.java)
        private val DELETE_CUSTOM_ICON = IconLoader.getIcon("/icons/delete.svg", OSSBrowserPanel::class.java)
    }

    private val service = OSSService(project, credential)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    private val treeRoot = DefaultMutableTreeNode(TreeNodeData.Root(credential.bucketName))
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)

    private val popupMenu = JBPopupMenu()
    private val newFolderItem = JBMenuItem(message("oss.browse.new.folder"))
    private val deleteItem = JBMenuItem(message("oss.browse.remove"))
    private val refreshItem = JBMenuItem(message("oss.browse.refresh"))
    private val downloadItem = JBMenuItem(message("oss.browse.download"))
    private val copyUrlItem = JBMenuItem(message("oss.browse.copy.url"))
    private val uploadItem = JBMenuItem(message("oss.browse.upload"))
    private val propertiesItem = JBMenuItem(message("oss.browse.properties"))

    val selectedPath: String?
        get() = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)
            ?.userObject
            ?.let { it as? TreeNodeData.Folder }
            ?.prefix

    init {
        add(JBScrollPane(tree), BorderLayout.CENTER)
        tree.isRootVisible = true
        tree.showsRootHandles = true
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        setupTree()
        setupPopupMenu()
        setupListeners()
        setupDrag()
        loadRoot()
        OSS_UPLOADED_TOPIC.subscribe(this, object : UploadedListener {
            override fun uploaded(credential: Credential, path: String) {
                if (!credential.eq(this@OSSBrowserPanel.credential)) {
                    return
                }
                scope.launch {
                    val parts = path
                        .trim('/')
                        .split("/")
                        .filter { it.isNotBlank() } + null
                    var current: DefaultMutableTreeNode = treeRoot
                    for (part in parts) {
                        val child: DefaultMutableTreeNode
                        try {
                            child = findChild(current, part) ?: run {
                                val items = suspendLoadNode(current).getOrNull() ?: return@launch
                                SwingUtilities.invokeAndWait {
                                    current.loadedNode(items)
                                }
                                findChild(current, part)
                            } ?: return@launch
                        } finally {
                            SwingUtilities.invokeAndWait {
                                val treePath = TreePath(current.path)
                                if (!tree.isExpanded(treePath)) {
                                    tree.expandPath(treePath)
                                }
                            }
                        }
                        current = child
                    }
                }
            }
        })
    }

    fun findChild(
        parent: DefaultMutableTreeNode,
        name: String?
    ): DefaultMutableTreeNode? {
        if (name == null) {
            return null
        }
        val enumeration = parent.children()
        while (enumeration.hasMoreElements()) {
            val child = enumeration.nextElement() as DefaultMutableTreeNode
            if ((child.userObject as TreeNodeData).displayName == name) {
                return child
            }
        }
        return null
    }

    private val TreeNodeData.canProperties get() = this is TreeNodeData.File
    private val TreeNodeData.canDelete get() = this is TreeNodeData.Folder || this is TreeNodeData.File
    private val TreeNodeData.canDownload get() = this is TreeNodeData.File || this is TreeNodeData.Folder
    private val TreeNodeData.canUpload get() = this is TreeNodeData.Folder || this is TreeNodeData.Root
    private val TreeNodeData.canCopy get() = this is TreeNodeData.File
    private val TreeNodeData.canRefresh get() = this is TreeNodeData.Folder || this is TreeNodeData.Root
    private val TreeNodeData.canCreateFolder get() = this is TreeNodeData.Folder || this is TreeNodeData.Root


    /** 加载节点内容 */
    private fun loadNode(node: DefaultMutableTreeNode) {
        node.showLoading(treeModel)
        scope.launch {
            suspendLoadNode(node).onSuccess { items ->
                SwingUtilities.invokeLater {
                    node.loadedNode(items)
                }
            }.onFailure { ex ->
                LOG.warn("List objects error", ex)
                SwingUtilities.invokeLater { node.showError(treeModel, ex.message ?: "Load failed") }
            }
        }
    }


    private suspend fun suspendLoadNode(node: DefaultMutableTreeNode): Result<List<TreeNodeData>> {
        val data = node.userObject as? TreeNodeData ?: return Result.failure(RuntimeException("Invalid node"))
        return service.listObjects(data.objectPrefix, isBrowse)
    }

    private fun DefaultMutableTreeNode.loadedNode(
        items: List<TreeNodeData>
    ) {
        val data = userObject as? TreeNodeData ?: return
        LOG.debug {
            "List objects: $items"
        }
        removeAllChildren()
        items.forEach { item ->
            val childNode = when (item) {
                is TreeNodeData.Folder -> DefaultMutableTreeNode(item).apply { add(createPlaceholderNode()) }
                is TreeNodeData.File -> DefaultMutableTreeNode(item)
                else -> null
            }
            if (childNode != null) {
                add(childNode)
            }
        }
        treeModel.reload(this)
        if (data is TreeNodeData.Folder) data.loaded = true
    }

    private fun setupTree() {
        tree.selectionModel.selectionMode =
            if (isBrowse) TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION else TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean,
                expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
            ) {
                val node = value as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? TreeNodeData ?: return
                append(data.displayName)
                icon = when (data) {
                    is TreeNodeData.File -> FileTypeManager.getInstance().getFileTypeByFileName(data.displayName).icon
                        ?: AllIcons.FileTypes.Any_type

                    else -> AllIcons.Nodes.Folder
                }
            }
        }

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject as? TreeNodeData.Folder ?: return
                if (!data.loaded) loadNode(node)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {}

        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val data = node.userObject as? TreeNodeData ?: return
                    if (data is TreeNodeData.Placeholder) {
                        val parent = node.parent as? DefaultMutableTreeNode ?: return
                        loadNode(parent)
                    } else if (data.canProperties) {
                        showProperties()
                    }
                }
            }
        })


    }

    private fun setupDrag() {
        tree.dragEnabled = true
        tree.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                if (!support.isDrop) return false
                if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                val dropLocation = support.dropLocation as JTree.DropLocation
                val path: TreePath = dropLocation.path ?: return false
                val targetNode = path.lastPathComponent as DefaultMutableTreeNode
                val nodeData = targetNode.userObject as TreeNodeData
                return nodeData.canUpload
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val dropLocation = support.dropLocation as JTree.DropLocation
                val path: TreePath = dropLocation.path ?: return false
                val targetNode = path.lastPathComponent as DefaultMutableTreeNode
                val transferable = support.transferable
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                    ?: return false
                val fileArrays = files.filterIsInstance<File>().mapNotNull { it.toVirtualFile() }.toTypedArray()
                scope.launch {
                    uploadFiles(fileArrays, targetNode.userObject as TreeNodeData)
                }
                return true
            }
        }
    }


    private fun setupPopupMenu() {
        popupMenu.add(newFolderItem)
        popupMenu.add(deleteItem)
        if (isBrowse) {
            popupMenu.add(downloadItem)
            popupMenu.add(uploadItem)
            popupMenu.add(copyUrlItem)
            downloadItem.icon = AllIcons.Actions.Download
            copyUrlItem.icon = AllIcons.Actions.Copy
            uploadItem.icon = AllIcons.Actions.Upload
        }
        popupMenu.addSeparator()
        popupMenu.add(propertiesItem)
        popupMenu.add(refreshItem)
        newFolderItem.icon = AllIcons.Nodes.Folder
        deleteItem.icon = DELETE_CUSTOM_ICON
        refreshItem.icon = AllIcons.Actions.Refresh
        propertiesItem.icon = AllIcons.Actions.Properties

        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y) ?: return

                // 右键未选中节点时，切换为单选
                if (!tree.isPathSelected(path)) {
                    tree.selectionPath = path
                }
                val paths = tree.selectionPaths ?: return

                val nodes = paths
                    .mapNotNull { it.lastPathComponent as? DefaultMutableTreeNode }
                    .mapNotNull { it.userObject as? TreeNodeData }

                if (nodes.isEmpty()) return

                // 全部满足才显示
                fun all(predicate: (TreeNodeData) -> Boolean) =
                    nodes.all(predicate)

                popupMenu.removeAll()

                // ===== 创建文件夹 =====
                if (nodes.size == 1 && all { it.canCreateFolder }) {
                    popupMenu.add(newFolderItem)
                }

                // ===== 删除 =====
                if (all { it.canDelete }) {
                    popupMenu.add(deleteItem)
                }

                if (isBrowse) {
                    // ===== 下载 =====
                    if (nodes.size == 1 && nodes[0] is TreeNodeData.File) {
                        popupMenu.add(downloadItem)
                    } else if (all { it.canDownload }) {
                        popupMenu.add(downloadItem)
                    }

                    // ===== 上传 =====
                    if (nodes.size == 1 && all { it.canUpload }) {
                        popupMenu.add(uploadItem)
                    }

                    // ===== 复制链接 =====
                    if (nodes.size == 1 && all { it.canCopy }) {
                        popupMenu.add(copyUrlItem)
                    }
                }

                if (nodes.size == 1) {
                    popupMenu.addSeparator()
                }

                // ===== 属性 / 刷新（允许 Root）=====
                if (nodes.size == 1 && all { it.canProperties }) {
                    popupMenu.add(propertiesItem)
                }

                if (nodes.size == 1 && all { it.canRefresh }) {
                    popupMenu.add(refreshItem)
                }

                popupMenu.show(comp, x, y)
            }
        })

    }

    private fun setupListeners() {
        newFolderItem.addActionListener { createFolder() }
        deleteItem.addActionListener { deleteFolder() }
        refreshItem.addActionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            node?.let {
                (it.userObject as TreeNodeData).let { data ->
                    if (data is TreeNodeData.Folder || data is TreeNodeData.Root) {
                        loadNode(it)
                    } else {
                        val parent = node.parent as? DefaultMutableTreeNode
                        refreshNode(parent)
                    }
                }
            }
        }
        propertiesItem.addActionListener { showProperties() }
        if (isBrowse) {
            downloadItem.addActionListener { downloadFile() }
            copyUrlItem.addActionListener { copyUrl() }
            uploadItem.addActionListener { uploadFile() }
        }
    }

    private fun loadRoot() {
        loadNode(treeRoot)
    }


    private fun createFolder() {
        val parentNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val name = Messages.showInputDialog(
            project,
            message("oss.browse.folder.name"),
            message("oss.browse.new.folder"),
            Messages.getQuestionIcon()
        )?.trim() ?: return
        val prefix = when (val parentData = parentNode.userObject) {
            is TreeNodeData.Folder -> {
                parentData.prefix + name + "/"
            }

            is TreeNodeData.Root -> {
                "$name/"
            }

            else -> {
                return
            }
        }
        scope.launch {
            service.createFolder(prefix) {
                SwingUtilities.invokeLater {
                    val node = DefaultMutableTreeNode(TreeNodeData.Folder(name, prefix))
                    node.add(createPlaceholderNode())
                    parentNode.add(node)
                    treeModel.reload(parentNode)
                    tree.expandPath(TreePath(parentNode.path))
                }
            }.onDefFailure {}
        }
    }

    private fun uploadFile() {
        val parentNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val nodeData = parentNode.userObject as TreeNodeData
        // 创建文件选择器描述符，明确指定只能选择文件
        val fileChooserDescriptor = FileChooserDescriptor(
            true,  // chooseFiles -选择文件
            true, // chooseFolders -选择目录
            false, // chooseJars - 选择JAR文件
            false, // chooseJarsAsFiles - 将JAR作为文件选择
            false, // chooseJarContents - 选择JAR内容
            true  // chooseMultiple - 选择多个文件
        ).withTitle(message("oss.browse.file.chooser.title")) // 可选：添加标题
            .withDescription(message("oss.browse.file.chooser.desc")) // 可选：添加描述
        val files = FileChooser.chooseFiles(
            fileChooserDescriptor,
            project,
            null
        )
        if (files.isEmpty()) {
            return
        }
        scope.launch {
            uploadFiles(files, nodeData)
        }
    }

    private suspend fun uploadFiles(files: Array<VirtualFile>, nodeData: TreeNodeData) {
        val title: String
        if (files.size == 1 && files.first().isFile) {
            val file = files.first()
            title = message("oss.browse.upload.progress", file.name)

        } else {
            title = message("upload.progress")
        }
        project.withProgressBackground(title) { indicator ->
            service.uploadFile(files, nodeData.objectPrefix, indicator)
                .onSuccess {
                    if (it > 0) {
                        project.notification(
                            message("upload.success_title"),
                            message("upload.success_title.multi", it)
                        )
                    }
                }.onDefFailure {}
        }
    }


    private fun deleteFolder() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val nodes = tree.selectionPaths?.mapNotNull { it.lastPathComponent as? DefaultMutableTreeNode }
            ?: return
        val title: String
        val message = if (nodes.size == 1) {
            val data = node.userObject as TreeNodeData
            title = message("oss.browse.delete.prepare", data.displayName)
            message("oss.browse.delete.folder.confirm", data.displayName)
        } else {
            title = message("oss.browse.delete.prepare.multiple", nodes.size)
            message("oss.browse.delete.folder.confirm.multiple", nodes.size)
        }
        if (Messages.showYesNoDialog(
                message, message("oss.browse.delete_btn_ok"),
                Messages.getQuestionIcon()
            ) != Messages.YES
        ) return

        scope.launch {
            project.withProgressBackground(title) { indicator ->
                indicator.text = message("oss.browse.counting")
                service.deleteObjects(nodes, indicator)
                    .onFailure {
                        if (it is CancellationException) {
                            return@withProgressBackground
                        }
                        LOG.warn(it)
                        project.notification(
                            message("error"),
                            it.message ?: "",
                            NotificationType.ERROR
                        )
                    }.onSuccess { totalCount ->
                        project.notification(
                            message("oss.browse.delete.success"),
                            message("oss.browse.delete.result", totalCount),
                        )
                        SwingUtilities.invokeLater {
                            nodes.mapNotNull { it.parent as? DefaultMutableTreeNode }.distinct().forEach { parent ->
                                refreshNode(parent)
                            }
                        }
                    }
            }
        }


    }

    private fun refreshNode(node: DefaultMutableTreeNode?) {
        if (node == null) return
        loadNode(node)
    }

    private fun downloadFile() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val paths = tree.selectionPaths ?: return
        if (paths.size > 1 || node.userObject is TreeNodeData.Folder) {
            downloadSelectedNodes()
            return
        }
        val data = node.userObject as TreeNodeData.File
        val descriptor = FileSaverDescriptor(
            message("oss.browse.save.dialog.title"),
            message("oss.browse.save.dialog.desc"),
            data.displayName.substringAfterLast('.', "")
        )
        val dialog = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
        val result = dialog.save(null as VirtualFile?, data.displayName) ?: return
        val targetFile = result.file.toPath()
        scope.launch {
            project.withProgressBackground(message("oss.browse.download")) {
                service.downloadFile(targetFile, data)
                    .onSuccess {
                        val openPath = targetFile.parent.absolutePathString()
                        project.notification(
                            message("oss.browse.download.title"),
                            message("oss.browse.download.success", data.displayName),
                            message("open.folder") to {
                                File(openPath).revealSmart()
                            }
                        )
                    }
                    .onFailure {
                        if (it.isCancel) {
                            return@withProgressBackground
                        }
                        LOG.warn(it)
                        project.notification(
                            message("error"),
                            message("oss.browse.download.failed", it.message ?: ""),
                        )
                    }
            }
        }
    }


    private fun downloadSelectedNodes() {
        val paths = tree.selectionPaths ?: return

        val nodes = paths
            .mapNotNull { it.lastPathComponent as? DefaultMutableTreeNode }
            .mapNotNull { it.userObject as? TreeNodeData }
            .filter { it !is TreeNodeData.Placeholder && it !is TreeNodeData.Root }

        if (nodes.isEmpty()) return

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

        scope.launch {
            project.withProgressBackground(message("oss.browse.download")) { indicator ->
                service.downloadSelectedNodes(nodes, folder, indicator).onSuccess {
                    val path = folder.path
                    project.notification(
                        message("oss.browse.download.title"),
                        message("oss.browse.download.success.multi", it),
                        message("open.folder") to {
                            File(path).revealSmart()
                        }
                    )
                }.onFailure {
                    if (it.isCancel) return@withProgressBackground
                    LOG.warn(it)
                    project.notification(
                        message("error"),
                        message("oss.browse.download.failed", it.message ?: "")
                    )
                }
            }
        }
    }


    private fun copyUrl() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as TreeNodeData.File
        val url = data.key.createUrl(credential, validityPeriod)
        url.copy()
    }

    private fun showProperties() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as TreeNodeData.File
        scope.launch {
            runCatching {
                val objectResponse = service.getHead(data)
                SwingUtilities.invokeLater {
                    ObjectPropertiesDialog(project, credential.bucketName, data.key, objectResponse).show()
                }
            }.onDefFailure {}
        }
    }


    private inline fun <T> Result<T>.onDefFailure(action: (exception: Throwable) -> Unit): Result<T> {
        exceptionOrNull()?.let {
            if (it.isCancel) return@let
            if (it !is CustomError) {
                LOG.warn(it)
            }
            project.notification(
                message("error"),
                it.message ?: "",
                NotificationType.ERROR
            )
            action(it)
        }
        return this
    }

    override fun dispose() {
        service.dispose()
        try {
            scope.cancel()
        } catch (e: Exception) {
            LOG.warn(e)
        }
    }

}
