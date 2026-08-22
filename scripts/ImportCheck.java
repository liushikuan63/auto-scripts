package scripts;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

// Java 版脚本：检查项目内所有 import 是否对应到已有类。
public class ImportCheck {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        // 脚本通常在 scripts/ 下运行，但我们也支持从项目根运行
        while (root != null) {
            if (Files.exists(root.resolve("settings.gradle.kts"))
                    || Files.exists(root.resolve("settings.gradle"))
                    || Files.exists(root.resolve("gradlew.bat"))) break;
            root = root.getParent();
        }
        Path src = root.resolve("app/src/main/java");
        Path testSrc = root.resolve("app/src/test/java");
        if (!Files.isDirectory(src)) {
            System.err.println("src not found: " + src);
            System.exit(2);
        }
        Set<String> skipPrefix = new HashSet<>(Arrays.asList(
                "android.", "androidx.", "java.", "javax.", "kotlin.",
                "kotlinx.", "com.google.mlkit.", "com.google.accompanist.",
                "com.google.android.material.", "org.opencv.", "timber.",
                "org.junit.", "org.robolectric.", "org.jetbrains."
        ));
        Pattern pkgP = Pattern.compile("^package\\s+([\\w\\.]+)");
        Pattern clsP = Pattern.compile("^(?:(?:sealed\\s+)?(?:data\\s+)?(?:class|object|interface|enum\\s+class))\\s+(\\w+)");
        Pattern impP = Pattern.compile("^import\\s+(?:static\\s+)?([\\w\\.\\*]+)");
        Pattern valP = Pattern.compile("^(?:(?:const\\s+)?val|fun)\\s+(\\w+)\\s*[(:=({<].*$");
        Map<String, String> cls = new LinkedHashMap<>();

        for (Path s : new Path[]{src, testSrc}) {
            if (!Files.isDirectory(s)) continue;
            Files.walk(s).filter(p -> p.toString().endsWith(".kt") || p.toString().endsWith(".java"))
                    .forEach(f -> {
                        try {
                            String pkg = "";
                            List<String> lines = Files.readAllLines(f);
                            for (String line : lines) {
                                Matcher pm = pkgP.matcher(line.trim());
                                if (pm.find()) pkg = pm.group(1);
                                Matcher cm = clsP.matcher(line.trim());
                                if (cm.find()) {
                                    String fqn = pkg.isEmpty() ? cm.group(1) : pkg + "." + cm.group(1);
                                    cls.put(fqn, f.toAbsolutePath().toString());
                                    continue;
                                }
                                Matcher vm = valP.matcher(line.trim());
                                if (vm.find()) {
                                    String fqn = pkg.isEmpty() ? vm.group(1) : pkg + "." + vm.group(1);
                                    cls.putIfAbsent(fqn, f.toAbsolutePath().toString());
                                }
                            }
                        } catch (IOException e) { throw new UncheckedIOException(e); }
                    });
        }
        System.out.println("Project classes found: " + cls.size());
        int missing = 0;
        int total = 0;
        for (Path s : new Path[]{src, testSrc}) {
            if (!Files.isDirectory(s)) continue;
            List<Path> files = Files.walk(s)
                    .filter(p -> p.toString().endsWith(".kt")).sorted().toList();
            for (Path f : files) {
                Path rel = root.relativize(f);
                List<int[]> issues = new ArrayList<>();
                List<String> lines = Files.readAllLines(f);
                for (int idx = 0; idx < lines.size(); idx++) {
                    String line = lines.get(idx).trim();
                    Matcher m = impP.matcher(line);
                    if (!m.find()) continue;
                    total++;
                    String fqn = m.group(1);
                    if (fqn.endsWith(".*")) continue;
                    boolean skip = false;
                    for (String p : skipPrefix) if (fqn.startsWith(p)) { skip = true; break; }
                    if (skip) continue;
                    if (cls.containsKey(fqn)) continue;
                    // 支持 xxx.ClassName.Companion / 伴生对象 / 内部成员访问
                    String sub = fqn;
                    boolean ok = false;
                    while (sub.contains(".")) {
                        sub = sub.substring(0, sub.lastIndexOf('.'));
                        if (cls.containsKey(sub)) { ok = true; break; }
                    }
                    if (ok) continue;
                    // 允许 xxx.kt 默认包下同名(极少)
                    issues.add(new int[]{idx + 1, fqn.length(), 0});
                    System.out.printf("  !! %s L%d unresolved: %s%n", rel, idx + 1, fqn);
                    missing++;
                }
            }
        }
        System.out.println("\nTotal imports: " + total + ", MISSING intra-project: " + missing);
        if (missing > 0) { System.out.println("STATUS: FAIL"); System.exit(1); }
        System.out.println("STATUS: PASS (intra-project imports all OK)");
    }
}
