package space.zhangjing.oss.ui.panel


import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

enum class ConflictStrategy {
    OVERWRITE,
    RENAME,
    SKIP
}


inline fun resolveByStrategy(
    target: Path,
    strategy: () -> ConflictStrategy?
): Path? {
    if (!Files.isRegularFile(target)) return target
    return when (strategy()) {
        ConflictStrategy.OVERWRITE -> {
            Files.delete(target)
            target
        }

        ConflictStrategy.SKIP -> null
        ConflictStrategy.RENAME -> {
            var index = 1
            var newPath: Path
            do {
                val name = target.fileName.toString()
                val dot = name.lastIndexOf('.')
                val newName = if (dot > 0) {
                    "${name.substring(0, dot)}($index)${name.substring(dot)}"
                } else {
                    "$name($index)"
                }
                newPath = target.parent.resolve(newName)
                index++
            } while (Files.exists(newPath))
            newPath
        }

        null -> throw CancellationException("Canceled")
    }
}



