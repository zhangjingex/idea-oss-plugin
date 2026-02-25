package space.zhangjing.oss.ui.panel

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import space.zhangjing.oss.bus.OSS_UPLOADED_TOPIC
import space.zhangjing.oss.entity.Credential
import space.zhangjing.oss.utils.*
import space.zhangjing.oss.utils.PluginBundle.message
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode


class OSSService(
    private val project: Project,
    private val credential: Credential
) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(OSSService::class.java)
    }

    private var s3Client: S3Client? = null

    /** 获取或创建 S3Client */
    private suspend fun <T> withS3Client(action: suspend (S3Client) -> T) = withContext(Dispatchers.IO) {
        runCatching {
            LOG.debug {
                "withContext"
            }
            val client = s3Client ?: OSSUtils.buildS3Client(credential).also { s3Client = it }
            try {
                action(client)
            } catch (e: SdkClientException) {
                LOG.warn("S3Client error, reconnecting", e)
                closeS3()
                val newClient = OSSUtils.buildS3Client(credential).also { s3Client = it }
                action(newClient)
            }
        }
    }

    suspend fun createFolder(prefix: String, action: () -> Unit) = withS3Client {
        it.createFolder(credential.bucketName, prefix)
        action()
    }


    suspend fun uploadFile(
        file: VirtualFile,
        objectName: String,
        action: ((objectKey: String) -> Unit)
    ) = withS3Client { client ->
        val finalKey = if (client.objectExists(credential.bucketName, objectName)) {
            val choice = runInEdtAndWait {
                Messages.showDialog(
                    project,
                    message("oss.browse.file.exists", file.name),
                    message("oss.browse.upload"),
                    arrayOf(
                        message("overwrite"),
                        message("rename"),
                        message("skip"),
                        message("cancel")
                    ),
                    0,
                    Messages.getWarningIcon()
                )
            }
            when (choice) {
                0 -> objectName // 覆盖
                1 -> client.generateUniqueKey(credential.bucketName, objectName)
                2 -> return@withS3Client // 跳过
                else -> return@withS3Client // 取消
            }
        } else {
            objectName
        }
        client.putObject(
            PutObjectRequest.builder()
                .bucket(credential.bucketName)
                .key(finalKey)
                .build(),
            RequestBody.fromFile(file.toIoFile())
        )
        action(finalKey)
    }


    suspend fun uploadFile(
        files: Array<VirtualFile>,
        prefix: String,
        indicator: ProgressIndicator,
        action: ((total: Int, done: Int, objectKey: String) -> Unit)? = null
    ) = withS3Client { client ->
        val objectPrefix = prefix.trim('/')
        fun objectKey(basePath: String = ""): String =
            listOfNotNull(
                objectPrefix.trim('/').takeIf { it.isNotBlank() },
                basePath.trim('/').takeIf { it.isNotBlank() }
            ).joinToString("/")

        val allFiles = files.flatMap {
            if (it.isFile) {
                listOf(UploadFile(objectKey(it.name), it))
            } else {
                val result = mutableListOf<UploadFile>()
                VfsUtilCore.iterateChildrenRecursively(it, null) { child ->
                    if (child.isFile) {
                        val relativePath = "${it.name}/${VfsUtilCore.getRelativePath(child, it)}"
                        result.add(UploadFile(objectKey(relativePath), child))
                    }
                    true
                }
                result
            }
        }
        val completed = AtomicInteger(0)
        if (allFiles.size == 1) {
            val file = files[0]
            val key = if (objectPrefix.isBlank())
                file.name
            else
                "$objectPrefix/${file.name}"
            uploadFile(files[0], key) {
                completed.incrementAndGet()
                ApplicationManager.getApplication().messageBus.syncPublisher(OSS_UPLOADED_TOPIC)
                    .uploaded(credential, prefix)
                action?.invoke(1, 1, key)
            }
            return@withS3Client completed.get()
        }
        var conflictStrategy: ConflictStrategy? = null
        val uploadFiles = mutableSetOf<UploadFile>()
        suspend fun isExists(objectKey: String): Boolean {
            return client.objectExists(credential.bucketName, objectKey) || uploadFiles.any { it.key == objectKey }
        }
        for (upload in allFiles) {
            var objectKey = upload.key
            while (isExists(objectKey)) {
                val choice = conflictStrategy ?: runInEdtAndWait {
                    project.askConflictStrategy().apply {
                        conflictStrategy = this
                    }
                } ?: return@withS3Client completed.get()
                objectKey = when (choice) {
                    ConflictStrategy.OVERWRITE -> {
                        uploadFiles.removeIf {
                            it.key == objectKey
                        }
                        objectKey
                    }

                    ConflictStrategy.RENAME -> client.generateUniqueKey(credential.bucketName, objectKey)
                    ConflictStrategy.SKIP -> continue
                }
            }
            uploadFiles.add(UploadFile(objectKey, upload.file))
        }
        val total = uploadFiles.size
        if (total == 0) {
            throw CustomError.message(message("upload.error.emptyFile"))
        }
        val semaphore = Semaphore(4)
        coroutineScope {
            uploadFiles.map {
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        indicator.checkCanceled()
                        uploadFile(it.file, it.key) { key ->
                            val done = completed.incrementAndGet()
                            indicator.fraction = done.toDouble() / total
                            action?.invoke(total, done, key)
                        }
                    }
                }
            }.awaitAll()
        }
        val count = completed.get()
        if (count > 0) {
            ApplicationManager.getApplication().messageBus.syncPublisher(OSS_UPLOADED_TOPIC)
                .uploaded(credential, prefix)
        }
        return@withS3Client count
    }


    suspend fun deleteObjects(nodes: List<DefaultMutableTreeNode>, indicator: ProgressIndicator): Result<Int> {
        val objectsToDelete = mutableListOf<String>()
        var totalCount: Int
        var isDangerous: Boolean
        return withS3Client { client ->
            nodes.forEach { node ->
                when (val data = node.userObject as TreeNodeData) {
                    is TreeNodeData.File -> {
                        objectsToDelete.add(data.key)
                    }

                    is TreeNodeData.Folder -> {
                        val paginator = client.listObjectsV2Paginator(
                            ListObjectsV2Request.builder()
                                .bucket(credential.bucketName)
                                .prefix(data.prefix)
                                .build()
                        )
                        paginator.forEach { page ->
                            page.contents().forEach { objectsToDelete.add(it.key()) }
                        }
                    }

                    else -> {
                        throw IllegalArgumentException("Invalid node data type")
                    }
                }
            }
            totalCount = objectsToDelete.size
            isDangerous = totalCount >= credential.requiredDeleteThreshold
            if (isDangerous) {
                SwingUtilities.invokeAndWait {
                    if (Messages.showYesNoDialog(
                            message("oss.browse.delete.risk.confirm", totalCount),
                            message("oss.browse.delete.risk.title"),
                            Messages.getWarningIcon()
                        ) != Messages.YES
                    ) {
                        indicator.cancel()
                    }
                }
            }
            if (indicator.isCanceled) {
                throw CancellationException("User cancels deletion")
            }
            indicator.text = message("oss.browse.delete.start")
            val chunks = objectsToDelete.chunked(1000)
            chunks.forEachIndexed { index, batch ->
                if (indicator.isCanceled) throw CancellationException()
                if (batch.size < 2) {
                    client.deleteObject(credential.bucketName, batch[0])
                } else {
                    client.deleteObjects(credential.bucketName, Delete.builder()
                        .objects(
                            batch.map {
                                ObjectIdentifier.builder().key(it).build()
                            }
                        )
                        .build())
                }
                indicator.isIndeterminate = false
                indicator.text = message(
                    "oss.browse.delete.progress",
                    (index + 1) * batch.size,
                    totalCount
                )
                indicator.fraction = (index + 1).toDouble() / chunks.size
            }
            totalCount
        }
    }

    suspend fun downloadFile(targetFile: Path, data: TreeNodeData.File) = withS3Client { client ->
        val path = targetFile.toFile()
        val finalPath = if (path.exists()) {
            val choice = project.askConflictStrategy() ?: return@withS3Client
            resolveByStrategy(targetFile, { choice })
                ?: return@withS3Client
        } else {
            targetFile
        }
        client.getObject(
            { it.bucket(credential.bucketName).key(data.key) },
            finalPath
        )
    }


    suspend fun downloadSelectedNodes(
        nodes: List<TreeNodeData>,
        folder: VirtualFile,
        indicator: ProgressIndicator
    ) = runCatching {
        val completed = AtomicInteger(0)
        val targetDir = Paths.get(folder.path)
        val strategyRef = AtomicReference<ConflictStrategy?>(null)
        val strategyMutex = Mutex()
        suspend fun getOrAskStrategy(): ConflictStrategy? {
            strategyRef.get()?.let { return it }
            return strategyMutex.withLock {
                strategyRef.get()?.let { return it }
                val chosen = project.askConflictStrategy()
                strategyRef.set(chosen)
                chosen
            }
        }

        val resultFiles = withS3Client { client ->
            // 1递归展开所有文件
            val allFiles = expandNodesRecursively(client, nodes)
            if (allFiles.isEmpty()) return@withS3Client allFiles
            val newPaths = allFiles.map { it.relativePath }
            val hasDuplicateOrExist = newPaths.size != newPaths.toSet().size ||
                    newPaths.any { Files.isRegularFile(targetDir.resolve(it)) }
            if (hasDuplicateOrExist) {
                return@withS3Client when (getOrAskStrategy()) {
                    ConflictStrategy.SKIP -> {
                        val usedPaths = mutableSetOf<Path>()
                        allFiles.filter { file ->
                            usedPaths.add(file.relativePath)
                        }
                    }

                    ConflictStrategy.OVERWRITE -> {
                        val usedPaths = mutableSetOf<Path>()
                        val resultReversed = mutableListOf<DownloadFile>()
                        for (file in allFiles.asReversed()) {
                            if (usedPaths.add(file.relativePath)) {
                                resultReversed.add(file)
                            }
                        }
                        resultReversed.asReversed()
                    }

                    ConflictStrategy.RENAME -> {
                        val usedPaths = mutableSetOf<Path>()
                        val result = mutableListOf<DownloadFile>()
                        allFiles.forEach { file ->
                            fun isExits(path: Path): Boolean {
                                return !usedPaths.add(path) || Files.isRegularFile(targetDir.resolve(path))
                            }

                            val originalPath = file.relativePath
                            // 第一次出现，直接占用
                            if (!isExits(originalPath)) {
                                result.add(file)
                                return@forEach
                            }
                            val parent = originalPath.parent
                            val fileName = originalPath.fileName.toString()
                            val dotIndex = fileName.lastIndexOf('.')
                            val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
                            val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
                            var index = 1
                            while (true) {
                                val newFileName = "$baseName($index)$extension"
                                val newPath = parent?.resolve(newFileName) ?: Path.of(newFileName)
                                if (!isExits(newPath)) {
                                    result.add(DownloadFile(file.key, newPath))
                                    break
                                }
                                index++
                            }
                        }
                        result
                    }

                    else -> {
                        return@withS3Client null
                    }
                }
            } else {
                allFiles
            }
        }.getOrThrow()

        if (resultFiles.isNullOrEmpty()) {
            return@runCatching completed.get()
        }
        withS3Client { client ->
            val total = resultFiles.size
            val semaphore = Semaphore(4) // 最大并发数
            coroutineScope {
                resultFiles.map { file ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            indicator.checkCanceled()
                            val targetFile =
                                targetDir.resolve(file.relativePath)

                            val finalPath = resolveByStrategy(targetFile) {
                                getOrAskStrategy()
                            } ?: return@withPermit

                            Files.createDirectories(finalPath.parent)

                            client.getObject(
                                { it.bucket(credential.bucketName).key(file.key) },
                                finalPath
                            )

                            val done = completed.incrementAndGet()
                            indicator.fraction = done.toDouble() / total
                        }
                    }
                }.awaitAll()
            }
        }
        return@runCatching completed.get()
    }


    suspend fun getHead(data: TreeNodeData.File): HeadObjectResponse {
        val objectResponse = data.objectHead ?: withS3Client {
            it.getHead(credential.bucketName, data.key).apply {
                data.objectHead = this
            }
        }.getOrThrow()
        return objectResponse
    }


    suspend fun listObjects(prefix: String, loadFile: Boolean = true) =
        withS3Client { client ->
            LOG.debug {
                "listObjects: $prefix"
            }
            val result = client.listObjects(credential.bucketName, prefix)
            val folders =
                result.commonPrefixes().map { TreeNodeData.Folder(extractName(it.prefix()), it.prefix()) }
            if (loadFile) {
                val files = result.contents().filterNot { it.key().endsWith("/") }
                    .map { TreeNodeData.File(it.key().substringAfterLast("/"), it.key()) }
                folders + files
            } else {
                folders
            }
        }

    private fun extractName(prefix: String): String = prefix.removeSuffix("/").substringAfterLast("/")


    private suspend fun expandNodesRecursively(
        client: S3Client,
        nodes: List<TreeNodeData>
    ): List<DownloadFile> {
        val result = mutableListOf<DownloadFile>()

        for (node in nodes) {
            when (node) {
                is TreeNodeData.File -> {
                    result += DownloadFile(
                        key = node.key,
                        relativePath = Paths.get(node.displayName)
                    )
                }

                is TreeNodeData.Folder -> {
                    listFolderRecursively(
                        client = client,
                        prefix = node.prefix,
                        basePath = Paths.get(node.displayName),
                        result = result
                    )
                }

                else -> Unit
            }
        }
        return result
    }


    private suspend fun listFolderRecursively(
        client: S3Client,
        prefix: String,
        basePath: Path,
        result: MutableList<DownloadFile>
    ) {
        var continuationToken: String? = null

        do {
            val response = withContext(Dispatchers.IO) {
                client.listObjectsV2 {
                    it.bucket(credential.bucketName)
                        .prefix(prefix)
                        .continuationToken(continuationToken)
                }
            }

            response.contents().forEach { obj ->
                val key = obj.key()
                if (key.endsWith("/")) return@forEach

                val relative = key.removePrefix(prefix)
                result += DownloadFile(
                    key = key,
                    relativePath = basePath.resolve(relative)
                )
            }

            continuationToken = response.nextContinuationToken()
        } while (continuationToken != null)
    }

    private fun closeS3() {
        try {
            s3Client?.close()
        } catch (e: Exception) {
            LOG.warn(e)
        }
        s3Client = null
    }

    override fun dispose() {
        closeS3()
    }

}