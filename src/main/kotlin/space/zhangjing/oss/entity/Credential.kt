package space.zhangjing.oss.entity

import java.util.*

data class Credential(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var endpoint: String = "",
    var accessKeyId: String = "",
    var bucketName: String = "",
    var cdnUrl: String = "",
    var region: String = ""
) {
    override fun toString(): String = name

    companion object {
        fun List<Credential>.findById(id: String?): Credential? {
            if (id.isNullOrBlank()) return null
            return firstOrNull { it.id == id }
        }

    }
}
