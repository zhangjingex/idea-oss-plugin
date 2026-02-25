package space.zhangjing.oss.entity

import java.util.*

data class Credential(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var endpoint: String = "",
    var accessKeyId: String = "",
    var bucketName: String = "",
    var cdnUrl: String = "",
    var region: String = "",
    var deleteThreshold: Int? = null
) {
    override fun toString(): String = name

    companion object {
        fun List<Credential>.findById(id: String?): Credential? {
            if (id.isNullOrBlank()) return null
            return firstOrNull { it.id == id }
        }

        fun Credential.eq(other: Credential) = other.bucketName == bucketName && other.endpoint == endpoint

        const val DEF_DELETE_THRESHOLD = 50
    }

    val requiredDeleteThreshold get() = deleteThreshold ?: DEF_DELETE_THRESHOLD
}
