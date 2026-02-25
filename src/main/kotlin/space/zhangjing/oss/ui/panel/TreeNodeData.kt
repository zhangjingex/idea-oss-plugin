package space.zhangjing.oss.ui.panel

import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import space.zhangjing.oss.utils.PluginBundle.message

/** 节点类型封装 */
sealed class TreeNodeData(val displayName: String) {
    class Root(name: String) : TreeNodeData(name)
    class Folder(name: String, val prefix: String, var loaded: Boolean = false) : TreeNodeData(name)
    class File(name: String, val key: String, var objectHead: HeadObjectResponse? = null) : TreeNodeData(name)
    class Placeholder(msg: String = message("loading")) : TreeNodeData(msg)

    override fun toString() = displayName

    val objectPrefix
        get() = when (this) {
            is Folder -> prefix
            is Root -> ""
            else -> throw IllegalStateException("can't get objectPrefix from $this")
        }
}