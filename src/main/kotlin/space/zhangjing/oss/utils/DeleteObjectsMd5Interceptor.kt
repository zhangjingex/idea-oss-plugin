package space.zhangjing.oss.utils

import com.intellij.openapi.diagnostic.Logger
import software.amazon.awssdk.core.interceptor.Context
import software.amazon.awssdk.core.interceptor.ExecutionAttributes
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.utils.BinaryUtils
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class DeleteObjectsMd5Interceptor : ExecutionInterceptor {

    private val logger = Logger.getInstance(DeleteObjectsMd5Interceptor::class.java)

    override fun modifyHttpRequest(
        context: Context.ModifyHttpRequest,
        executionAttributes: ExecutionAttributes
    ): SdkHttpRequest {

        val operationName =
            executionAttributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME)

        logger.debug {
            "modifyHttpRequest: $operationName"
        }

        if (operationName != "DeleteObjects") {
            return context.httpRequest()
        }

        val request = context.httpRequest()
        val builder = request.toBuilder()

        // 移除 x-amz-checksum-* 头
//        request.headers().keys
//            .filter { it.lowercase().startsWith("x-amz-checksum-") }
//            .forEach { builder.removeHeader(it) }

        val requestBody = context.requestBody().orElse(null)
        val streamProvider = requestBody
            ?.contentStreamProvider()

        streamProvider?.newStream()?.use { input ->
            val bytes = readAllBytes(input)
            val md5 = MessageDigest.getInstance("MD5")
            val base64Md5 = BinaryUtils.toBase64(md5.digest(bytes))
            builder.putHeader("Content-MD5", base64Md5)
        }

        return builder.build()
    }


    private fun readAllBytes(input: java.io.InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(8192)
        var nRead: Int

        while (input.read(data).also { nRead = it } != -1) {
            buffer.write(data, 0, nRead)
        }
        return buffer.toByteArray()
    }
}
