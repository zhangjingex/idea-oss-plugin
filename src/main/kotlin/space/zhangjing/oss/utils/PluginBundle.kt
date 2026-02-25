package space.zhangjing.oss.utils

import com.intellij.AbstractBundle
import org.jetbrains.annotations.PropertyKey

object PluginBundle : AbstractBundle("messages.PluginBundle") {
    fun message(@PropertyKey(resourceBundle = "messages.PluginBundle")key: String, vararg params: Any): String = getMessage(key, *params)
}
