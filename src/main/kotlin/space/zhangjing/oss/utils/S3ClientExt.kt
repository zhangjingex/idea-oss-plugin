package space.zhangjing.oss.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*


/**
 * 获取文件信息
 */
suspend fun S3Client.getHead(
    bucket: String,
    key: String
): HeadObjectResponse = withContext(Dispatchers.IO) {
    ensureActive()
    headObject(
        HeadObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build()
    )
}


/**
 * 获取文件列表
 */
suspend fun S3Client.listObjects(
    bucket: String,
    prefix: String
): ListObjectsV2Response = withContext(Dispatchers.IO) {
    listObjectsV2(
        ListObjectsV2Request.builder()
            .bucket(bucket)
            .prefix(prefix)
            .delimiter("/")
            .build()
    )
}

/**
 * 创建文件夹
 */
suspend fun S3Client.createFolder(
    bucket: String,
    prefix: String
): Unit = withContext(Dispatchers.IO) {
    putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(prefix)
            .build(),
        RequestBody.empty()
    )
}

/**
 * 批量删除文件
 */
suspend fun S3Client.deleteObjects(
    bucket: String,
    delete: Delete
): Unit = withContext(Dispatchers.IO) {
    deleteObjects(
        DeleteObjectsRequest.builder()
            .bucket(bucket)
            .checksumAlgorithm(ChecksumAlgorithm.SHA256)
            .delete(delete)
            .build(),
    )
}

/**
 * 删除文件
 */
suspend fun S3Client.deleteObject(
    bucket: String,
    key: String
): Unit = withContext(Dispatchers.IO) {
    deleteObject(
        DeleteObjectRequest
            .builder()
            .bucket(bucket)
            .key(key)
            .build()
    )
}

suspend fun S3Client.objectExists(bucket: String, key: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            headObject { b -> b.bucket(bucket).key(key) }
            true
        }.getOrElse {
            if (it is NoSuchKeyException) false else throw it
        }
    }


suspend fun S3Client.generateUniqueKey(
    bucket: String,
    originalKey: String
): String {
    val dotIndex = originalKey.lastIndexOf('.')
    val base = if (dotIndex > 0) originalKey.substring(0, dotIndex) else originalKey
    val ext = if (dotIndex > 0) originalKey.substring(dotIndex) else ""
    var index = 1
    var newKey: String
    do {
        newKey = "${base}($index)$ext"
        index++
    } while (objectExists(bucket, newKey))

    return newKey
}
