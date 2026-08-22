package scripts
// 静态检查脚本：验证所有 *.kt 文件中的 import 是否对应项目中存在的类
import java.io.File
import java.nio.file.Paths

val root = File(".").absoluteFile.parentFile.parentFile.parentFile // auto-scripts root
val src = File(root, "app/src/main/java")
require(src.exists()) { "src not found: $src" }

// 收集全项目的类名 → 文件
val classes = mutableMapOf<String, String>()
src.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
    var pkg = ""
    f.readLines().forEach { line ->
        val pl = Regex("""^package\s+([\w\.]+)""").find(line)
        if (pl != null) pkg = pl.groupValues[1]
        val cl = Regex("""^(?:class|object|interface|enum\s+class|data\s+class|sealed\s+(?:class|interface))\s+(\w+)""").find(line.trim())
        if (cl != null) {
            val fqn = if (pkg.isEmpty()) cl.groupValues[1] else "$pkg.${cl.groupValues[1]}"
            classes[fqn] = f.absolutePath
        }
    }
}

println("Found ${classes.size} classes in $src")

// 检查 import 语句 → 项目内包是否可用
var missing = 0
var totalImports = 0
src.walkTopDown().filter { it.extension == "kt" }.sortedBy { it.absolutePath }.forEach { f ->
    val rel = f.absolutePath.substring(root.absolutePath.length + 1)
    val issues = mutableListOf<Pair<Int, String>>()
    f.readLines().forEachIndexed { i, raw ->
        val line = raw.trim()
        val im = Regex("""^import\s+(static\s+)?([\w\.\*]+)""").find(line)
        if (im != null) {
            totalImports++
            var fqn = im.groupValues[2]
            if (fqn.endsWith(".*")) return@forEachIndexed
            if (fqn.startsWith("android.")) return@forEachIndexed
            if (fqn.startsWith("androidx.")) return@forEachIndexed
            if (fqn.startsWith("java.")) return@forEachIndexed
            if (fqn.startsWith("kotlin.")) return@forEachIndexed
            if (fqn.startsWith("org.junit.")) return@forEachIndexed
            if (fqn.startsWith("org.jetbrains.")) return@forEachIndexed
            if (fqn.startsWith("org.robolectric.")) return@forEachIndexed
            if (fqn.startsWith("com.google.mlkit.")) return@forEachIndexed
            if (fqn.startsWith("com.google.accompanist.")) return@forEachIndexed
            if (fqn.startsWith("com.google.android.material.")) return@forEachIndexed
            if (fqn.startsWith("org.opencv.")) return@forEachIndexed // stub/lib
            if (fqn.startsWith("timber.")) return@forEachIndexed
            if (fqn.startsWith("org.intellij.")) return@forEachIndexed
            if (fqn.startsWith("javax.annotation.")) return@forEachIndexed
            // 项目内 import 检查
            val path = classes[fqn]
            if (path == null) {
                // 处理内部类: 取前缀检查
                val outer = fqn.substringBeforeLast('.')
                if (classes[outer] == null) {
                    issues.add((i + 1) to fqn)
                }
            }
        }
    }
    if (issues.isNotEmpty()) {
        println("!! $rel")
        issues.forEach { (ln, im) -> println("   L$ln: unresolved: $im") }
        missing += issues.size
    }
}
println("\nTOTAL imports: $totalImports, MISSING intra-project imports: $missing")
if (missing > 0) {
    println("STATUS: FAIL")
    System.exit(1)
} else {
    println("STATUS: PASS (intra-project imports all OK)")
}
