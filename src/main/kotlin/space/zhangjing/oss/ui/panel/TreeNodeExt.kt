package space.zhangjing.oss.ui.panel

import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** 占位节点 */
fun createPlaceholderNode() = DefaultMutableTreeNode(TreeNodeData.Placeholder())

/** 显示占位节点 */
fun DefaultMutableTreeNode.showLoading(treeModel: DefaultTreeModel) {
    removeAllChildren()
    add(createPlaceholderNode())
    treeModel.reload(this)
}

/** 显示错误节点 */
fun DefaultMutableTreeNode.showError(treeModel: DefaultTreeModel, msg: String) {
    removeAllChildren()
    add(DefaultMutableTreeNode(TreeNodeData.Placeholder(msg)))
    treeModel.reload(this)
}