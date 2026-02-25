package space.zhangjing.oss.utils

class CustomError(
    val code: Int = 500,
    override val message: String
) : RuntimeException() {

    companion object {
        fun message(message: String): CustomError = CustomError(message = message)
    }
}