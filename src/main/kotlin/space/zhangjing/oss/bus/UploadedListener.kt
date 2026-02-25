package space.zhangjing.oss.bus

import space.zhangjing.oss.entity.Credential

interface UploadedListener {
    fun uploaded(
        credential: Credential,
        path: String
    )
}