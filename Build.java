import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.*;

/** Build, testes e empacotamento usando somente o JDK. Execute: java Build.java */
public class Build {
    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "all" : args[0];
        if (!Set.of("all", "test", "package").contains(command)) {
            System.err.println("Uso: java Build.java [all|test|package]"); System.exit(2);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Instale um JDK 17 ou superior, não apenas o JRE.");
        Path classes = Path.of("build/classes"), tests = Path.of("build/test-classes");
        clean(classes); clean(tests); Files.createDirectories(classes); Files.createDirectories(tests);
        compile(compiler, Path.of("src/main/java"), classes, null);
        if (!command.equals("package")) {
            compile(compiler, Path.of("src/test/java"), tests, classes);
            String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process process = new ProcessBuilder(javaExe, "-Djava.awt.headless=true", "-cp",
                    classes + File.pathSeparator + tests, "br.com.sentinela.SecurityTests").inheritIO().start();
            if (process.waitFor() != 0) throw new IllegalStateException("Os testes falharam; pacote não gerado.");
        }
        if (!command.equals("test")) {
            Path output = Path.of("dist/sentinela-vault-0.1.0.jar"); Files.createDirectories(output.getParent());
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "br.com.sentinela.Main");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output), manifest);
                 var files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, jar); jar.closeEntry();
                }
            }
            System.out.println("Pacote: " + output + "\nAbrir: java -jar " + output);
        }
    }

    private static void compile(JavaCompiler compiler, Path source, Path output, Path classpath) throws IOException {
        List<String> options = new ArrayList<>(List.of("--release", "17", "-encoding", "UTF-8", "-d", output.toString()));
        if (classpath != null) options.addAll(List.of("-classpath", classpath.toString()));
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null);
             var paths = Files.walk(source)) {
            var units = manager.getJavaFileObjectsFromPaths(paths.filter(p -> p.toString().endsWith(".java")).sorted().toList());
            if (!compiler.getTask(null, manager, null, options, null, units).call())
                throw new IllegalStateException("Falha na compilação.");
        }
    }

    private static void clean(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
