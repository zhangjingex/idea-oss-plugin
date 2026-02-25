package space.zhangjing.oss.utils


import com.intellij.openapi.diagnostic.Logger
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import space.zhangjing.oss.entity.Credential
import java.io.File
import java.net.URI
import java.time.Duration

object OSSUploader {

    private val LOG = Logger.getInstance(OSSUploader::class.java)

    fun String.scheme(): String {
        return if (this.startsWith("http")) {
            this
        } else {
            "https://$this"
        }
    }

    fun buildS3Client(credential: Credential): S3Client =
        buildS3Client(
            credential.endpoint,
            credential.accessKeyId,
            CredentialSecretStore.requestSecret(credential.id),
            credential.region.region()
        )

    fun buildS3Client(
        endpoint: String,
        accessKeyId: String,
        accessKeySecret: String,
        region: Region
    ): S3Client {
        return S3Client.builder()
            .endpointOverride(URI.create(endpoint.scheme()))
            .overrideConfiguration {
                it.addExecutionInterceptor(DeleteObjectsMd5Interceptor())
            }
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, accessKeySecret)
                )
            )
            // 非 AWS 场景不会校验 region，随便填
            .region(region)
            .build()
    }

    fun uploadFile(
        file: File,
        credential: Credential,
        uploadPath: String?,
        validityPeriod: Int = 600
    ): String {
        val objectName = if (uploadPath.isNullOrBlank()) {
            file.name
        } else {
            uploadPath.trim().ensureEndsWithSlash() + file.name
        }
        val client = buildS3Client(
            credential.endpoint,
            credential.accessKeyId,
            CredentialSecretStore.requestSecret(credential.id),
            credential.region.region()
        )
        client.use { s3 ->
            val request = PutObjectRequest.builder()
                .bucket(credential.bucketName)
                .key(objectName)
                .build()

            s3.putObject(request, RequestBody.fromFile(file))

            val url = objectName.createUrl(
                credential,
                validityPeriod
            )
            LOG.info("Uploaded to $url")
            return url
        }
    }

    fun String.createUrl(
        credential: Credential,
        validityPeriod: Int = 600
    ) = createUrl(
        credential.cdnUrl,
        credential.endpoint,
        credential.region.region(),
        credential.accessKeyId,
        CredentialSecretStore.requestSecret(credential.id),
        credential.bucketName,
        validityPeriod
    )

    fun String.createUrl(
        cdnUrl: String,
        endpoint: String,
        region: Region,
        accessKeyId: String,
        accessKeySecret: String,
        bucketName: String,
        validityPeriod: Int = 600
    ): String {
        return if (cdnUrl.isBlank()) {
            val presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, accessKeySecret)
                    )
                )
                .region(region)
                .build()

            presigner.presignGetObject {
                it.getObjectRequest(
                    GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(this)
                        .build()
                )
                val duration = Duration.ofSeconds(validityPeriod.toLong())
                it.signatureDuration(duration)
            }.url().toString()
        } else {
            "${cdnUrl.trim().ensureEndsWithSlash()}$this"
        }
    }

    fun String?.region(): Region = if (isNullOrBlank()) {
        Region.US_EAST_1
    } else {
        Region.of(this)
    }
}
