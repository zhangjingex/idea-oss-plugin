package space.zhangjing.oss.utils

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger
import space.zhangjing.oss.utils.PluginBundle.message

object CredentialSecretStore {

    private val LOG = Logger.getInstance(CredentialSecretStore::class.java)

    private const val SERVICE_NAME = "AndroidOSSPlugin"

    /** 记录哪些 credentialId 是 memoryOnly */
    private val memoryOnlySet = mutableSetOf<String>()

    /**
     * 保存 Secret
     *
     * @param memoryOnly true = 只存内存（IDE 重启即失效）
     */
    fun saveSecret(
        credentialId: String,
        secret: String,
        user: String? = null,
        memoryOnly: Boolean = false
    ) {
        LOG.debug { "saveSecret: $credentialId memoryOnly=$memoryOnly" }
        if (memoryOnly) {
            memoryOnlySet.add(credentialId)
        } else {
            memoryOnlySet.remove(credentialId)
        }
        PasswordSafe.instance.set(
            attributes(credentialId, memoryOnly),
            Credentials(user, secret)
        )
    }

    /**
     * 加载 Secret
     *
     * 加载顺序：
     * 1. 内存
     * 2. 持久化
     */
    fun loadSecret(credentialId: String): String? {
        LOG.debug { "loadSecret: $credentialId" }

        loadCredentials(credentialId, memoryOnly = true)?.password?.toString()
            ?.let { return it }

        return loadCredentials(credentialId, memoryOnly = false)
            ?.password
            ?.toString()
    }


    /**
     * 当前 Secret 是否仅存在于内存中
     */
    fun isMemoryOnly(credentialId: String): Boolean {
        return credentialId in memoryOnlySet
    }

    /**
     * 获取用户名（如果有）
     */
    fun loadUser(credentialId: String): String? {
        loadCredentials(credentialId, memoryOnly = true)?.userName
            ?.let { return it }
        return loadCredentials(credentialId, memoryOnly = false)?.userName
    }

    /**
     * 删除所有位置的 Secret
     */
    fun removeSecret(credentialId: String) {
        LOG.debug { "removeSecret: $credentialId" }
        memoryOnlySet.remove(credentialId)
        PasswordSafe.instance.set(attributes(credentialId, true), null)
        PasswordSafe.instance.set(attributes(credentialId, false), null)
    }

    /**
     * 删除磁盘中 Secret
     */
    fun removeSecretFromDisk(credentialId: String) {
        LOG.debug { "removeSecretFromDisk: $credentialId" }
        PasswordSafe.instance.set(attributes(credentialId, false), null)
    }

    /**
     * 强制获取 Secret，不存在直接抛异常
     */
    fun requestSecret(credentialId: String): String {
        return loadSecret(credentialId)
            ?: throw IllegalArgumentException(
                message("credential.secret.get.fail")
            )
    }

    /* ================== 内部方法 ================== */

    private fun loadCredentials(
        credentialId: String,
        memoryOnly: Boolean
    ): Credentials? =
        PasswordSafe.instance.get(
            attributes(credentialId, memoryOnly)
        )

    private fun attributes(
        credentialId: String,
        memoryOnly: Boolean
    ) = CredentialAttributes(
        serviceName = generateServiceName(SERVICE_NAME, credentialId),
        userName = null,
        requestor = null,
        isPasswordMemoryOnly = memoryOnly,
        cacheDeniedItems = true
    )
}
