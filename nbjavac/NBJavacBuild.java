package nbjavac;

import rife.bld.BaseProject;
import rife.bld.BuildCommand;
import rife.bld.extension.ExecOperation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/// Builds nb-javac.
///
/// The pipeline clones and builds the pinned JDK, copies the compiler
/// sources with the Jackpot hints applied, compiles them with the pinned
/// `javac`, and splits the API and implementation JARs with the built JDK's
/// `ct.sym` embedded.
///
/// Available commands:
///
/// - `./bld jdk` - clone and build the pinned JDK (`jdk.git.commit` in `jdk.properties`)
/// - `./bld jar` - build the API and implementation JARs using the pinned JDK
/// - `./bld test` - build the JARs, then run the test suite under the current JVM
/// - `./bld clean` - remove all build products
/// - `./bld cleanAll` - clean and remove the JDK clone and image for a rebuild from scratch
///
/// Project layout:
///
/// - `src/` - project sources
/// - `test/` - test suite
/// - `jdk-src/` - Jackpot output
/// - `build/` and `dist/` - build products
/// - `jdk/` - JDK clone
/// - `lib/` - tool JARs
///
public class NBJavacBuild extends BaseProject {

    // Matches the "Classpath exception" clause in a file's license header.
    private static final Pattern CLASSPATH_EXCEPTION =
            Pattern.compile("particular *file *as *subject *to *the.*Classpath.*exception");

    private final Path root;
    private final Path jdk;
    private final Path src;
    private final Path jdkSrc;
    private final Path tests;
    private final Path lib;
    private final Path buildDir;
    private final Path distDir;

    /** Pinned JDK tag, from jdk.properties. */
    private final String jdkTag;
    /** Clone URL for the pinned JDK, from jdk.properties. */
    private final String jdkUrl;

    public NBJavacBuild() {
        pkg = "nbjavac";
        name = "nb-javac";
        version = version(0, 1, 0);

        Path w = workDirectory().toPath();
        root = w;
        jdk = w.resolve("jdk");
        src = w.resolve("src");
        jdkSrc = w.resolve("jdk-src");
        tests = w.resolve("test");
        lib = w.resolve("lib");
        buildDir = w.resolve("build");
        distDir = w.resolve("dist");

        // The version pins live in jdk.properties so CI can key its JDK
        // cache on that file (plus the patch and hint) without invalidating
        // the cache when this build script changes.
        Properties pins = loadPins(root.resolve("jdk.properties"));
        jdkTag = pins.getProperty("jdk.git.commit");
        jdkUrl = pins.getProperty("jdk.git.url");
        if (jdkTag == null || jdkUrl == null) {
            throw new IllegalStateException("Missing jdk.git.commit/jdk.git.url in jdk.properties");
        }
    }

    private static Properties loadPins(Path f) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read version pins from " + f, e);
        }
        return p;
    }

    // ---------------------------------------------------------------- commands

    @BuildCommand(summary = "Clone and build the pinned JDK")
    public void jdk() throws Exception {
        // The image is the gate: an existing image means everything (clone,
        // patch, build) is already in place, so a CI cache restore is a
        // no-op. Only a missing image requires source, hence the clone.
        if (jdkImage() == null) {
            if (Files.notExists(jdk.resolve(".git"))) {
                // Depth-1 clone of the pinned tag
                exec(root, "git", "clone", "--depth", "5", "--branch", jdkTag, jdkUrl, jdk.toString());
                // Make the pinned sources --release 21 compatible (renames
                // the unnamed variables). A failure here means the patch is
                // stale and needs fixing; an existing clone is assumed
                // patched.
                exec(jdk, "git", "apply", root.resolve("patches/jdk-src-release-21.patch").toString());
            }
            exec(jdk, "bash", "configure", "--disable-warnings-as-errors", "--with-native-debug-symbols=none");
            exec(jdk, "make", "images");
        }
        System.out.println("JDK image: " + jdkImage());
    }

    @BuildCommand(summary = "Build the api/impl jars (compiles under the pinned JDK)")
    public void jar() throws Exception {
        jdk();
        downloadTools();
        jackpot();
        generateFiles();
        reflectShims();
        compileMain();
        buildJars();
    }

    @Override
    public void test() throws Exception {
        jar();
        Path testClasses = buildDir.resolve("test/classes");
        deleteRecursively(testClasses);
        List<Path> testSources = collectSources(tests);
        String cp = classPath(
                buildDir.resolve("classes"),
                implJar(),
                lib.resolve("junit-4.12.jar"),
                lib.resolve("hamcrest-core-1.3.jar"));
        List<String> cmd = new ArrayList<>(List.of(
                builtTool("javac"),
                "-g", "-encoding", "UTF-8",
                "-cp", cp,
                "-d", testClasses.toString(),
                "--release", "21",
                "--limit-modules", "java.base"));
        for (Path f : testSources) {
            cmd.add(f.toString());
        }
        exec(root, cmd.toArray(String[]::new));

        // Run the suite in the JVM that runs this build (CI: JDK 21+, the floor).
        // --limit-modules is required: without it the JVM refuses to load
        // classpath classes from packages owned by a resolved module
        // (com.sun.source.*, com.sun.tools.*).
        List<String> tests = new ArrayList<>();
        try (Stream<Path> s = Files.walk(testClasses)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Test.class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .forEach(p -> tests.add(
                            testClasses.relativize(p).toString().replace('\\', '/')
                                    .replace(".class", "").replace('/', '.')));
        }
        tests.sort(String::compareTo);
        List<String> run = new ArrayList<>(List.of(
                currentJava(),
                "--limit-modules", "java.base,java.xml,jdk.zipfs",
                "-cp", classPath(buildDir.resolve("classes"), implJar(),
                        lib.resolve("junit-4.12.jar"), lib.resolve("hamcrest-core-1.3.jar"), testClasses),
                "org.junit.runner.JUnitCore"));
        run.addAll(tests);
        exec(root, run.toArray(String[]::new));
    }

    @Override
    public void clean() throws Exception {
        deleteRecursively(buildDir);
        deleteRecursively(distDir);
        deleteRecursively(jdkSrc);
    }

    @BuildCommand(summary = "clean plus the JDK clone and image (full rebuild from scratch)")
    public void cleanAll() throws Exception {
        clean();
        deleteRecursively(jdk);
    }

    // ---------------------------------------------------------------- pipeline

    private void downloadTools() throws Exception {
        download("https://repo1.maven.org/maven2/org/apache/netbeans/modules/jackpot30/tool/28.0/tool-28.0.jar", lib.resolve("jackpot.jar"));
        download("https://repo1.maven.org/maven2/com/dukescript/nbjavac/nb-javac/jdk-21u/nb-javac-jdk-21u.jar", lib.resolve("nb-javac-jdk-21u.jar"));
        download("https://repo1.maven.org/maven2/junit/junit/4.12/junit-4.12.jar", lib.resolve("junit-4.12.jar"));
        download("https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar", lib.resolve("hamcrest-core-1.3.jar"));
    }

    private void jackpot() throws Exception {
        if (Files.exists(jdkSrc.resolve("jackpot-done"))) {
            System.out.println("jackpot already applied");
            return;
        }
        deleteRecursively(jdkSrc);
        Files.createDirectories(jdkSrc);

        for (String module : List.of("java.compiler", "jdk.compiler")) {
            Path moduleDir = jdk.resolve("src").resolve(module);
            try (Stream<Path> s = Files.walk(moduleDir)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    if (hasLicenseException(f)) {
                        Path target = jdkSrc.resolve(module).resolve(moduleDir.relativize(f));
                        Files.createDirectories(target.getParent());
                        Files.copy(f, target);
                    }
                }
            }
        }

        Path ecs = jdk.resolve("src/java.base/share/classes/java/lang/runtime/ExactConversionsSupport.java");
        if (Files.exists(ecs) && hasLicenseException(ecs)) {
            String content = Files.readString(ecs, UTF_8).replace("package java.lang.runtime;", "package nbjavac;");
            Path target = jdkSrc.resolve("java.base/share/classes/java/lang/runtime/ExactConversionsSupport.java");
            Files.createDirectories(target.getParent());
            Files.write(target, content.getBytes(UTF_8));
        }

        String sourcePath = classPath(
                src,
                jdkSrc.resolve("jdk.compiler/share/classes"),
                jdkSrc.resolve("java.compiler/share/classes"),
                jdkSrc.resolve("java.base/share/classes"));
        exec(root, builtJava(),
                "-cp", lib.resolve("jackpot.jar").toString(),
                "org.netbeans.modules.jackpot30.cmdline.Main",
                "-hint-file", src.resolve("META-INF/upgrade/nbjavac.hint").toString(),
                "-sourcepath", sourcePath,
                "-source", "8",
                "--apply",
                jdkSrc.resolve("java.base/share/classes").toString(),
                jdkSrc.resolve("java.compiler/share/classes").toString(),
                jdkSrc.resolve("jdk.compiler/share/classes").toString());
        Files.writeString(jdkSrc.resolve("jackpot-done"), "Jackpot done!");
    }

    private void generateFiles() throws Exception {
        Path flags = jdkSrc.resolve("jdk.compiler/share/classes/com/sun/tools/javac/code/Flags.java");
        Path flagsEnum = flags.resolveSibling("FlagsEnum.java");
        if (Files.exists(flags) && Files.exists(flagsEnum) && Files.getLastModifiedTime(flagsEnum).compareTo(Files.getLastModifiedTime(flags)) >= 0) {
            System.out.println("generated files up to date");
            return;
        }
        Path tools = jdk.resolve("make/langtools/tools");
        Path ppDir = lib.resolve("propertiesparser");
        deleteRecursively(ppDir);
        Files.createDirectories(ppDir);
        List<String> cmd = new ArrayList<>(List.of(
                builtTool("javac"), "-g", "-d", ppDir.toString(), "--release", "21")
        );
        for (Path f : collectSources(tools)) {
            String name = f.toString();
            if (name.contains("/propertiesparser/") || name.contains("/flagsgenerator/")) {
                cmd.add(f.toString());
            }
        }
        exec(root, cmd.toArray(String[]::new));

        String cp = classPath(ppDir, tools);
        Path resources = jdkSrc.resolve("jdk.compiler/share/classes/com/sun/tools/javac/resources");
        exec(root, builtJava(), "-cp", cp, "propertiesparser.PropertiesParser", "-compile", resources.resolve("compiler.properties").toString(), resources.toString());
        exec(root, builtJava(), "-cp", cp, "propertiesparser.PropertiesParser", "-compile", resources.resolve("launcher.properties").toString(), resources.toString());
        Path code = jdkSrc.resolve("jdk.compiler/share/classes/com/sun/tools/javac/code");
        exec(root, builtJava(), "-cp", cp, "flagsgenerator.FlagsGenerator", code.resolve("Flags.java").toString(), code.resolve("FlagsEnum.java").toString());
    }

    private void reflectShims() throws Exception {
        Path reflect = lib.resolve("reflect");
        deleteRecursively(reflect);
        unzipInclude(lib.resolve("nb-javac-jdk-21u.jar"), reflect,
                "nbjavac/sun/annotation/", "com/sun/tools/classfile/", "com/sun/tools/javap/");
    }

    private void compileMain() throws Exception {
        Path classes = buildDir.resolve("classes");
        deleteRecursively(classes);
        List<Path> files = collectSources(
                src,
                jdkSrc.resolve("java.base/share/classes"),
                jdkSrc.resolve("java.compiler/share/classes"),
                jdkSrc.resolve("jdk.compiler/share/classes"));
        // Compiled with the pinned javac at --release 21: the patched
        // sources (patches/jdk-src-release-21.patch) are release-21 clean.
        List<String> cmd = new ArrayList<>(List.of(
                builtTool("javac"),
                "-g",
                "-encoding", "UTF-8",
                "-cp", lib.resolve("reflect").toString(),
                "-d", classes.toString(),
                "--release", "21",
                "--limit-modules", "java.base"));
        for (Path f : files) {
            cmd.add(f.toString());
        }
        exec(root, cmd.toArray(String[]::new));

        // Non-Java files (META-INF/services, .properties resources)
        // join build/classes.
        copyResources(src, classes);
        copyResources(jdkSrc.resolve("java.compiler/share/classes"), classes);
        copyResources(jdkSrc.resolve("jdk.compiler/share/classes"), classes);
    }

    private void copyResources(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> s = Files.walk(from)) {
            List<Path> all = s.filter(Files::isRegularFile).sorted().toList();
            for (Path f : all) {
                String name = f.getFileName().toString();
                if (name.endsWith(".java") || name.endsWith(".form")) {
                    continue;
                }
                Path target = to.resolve(from.relativize(f));
                Files.createDirectories(target.getParent());
                Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void buildJars() throws Exception {
        Path ctSym = ctSymFile();
        System.out.println("Embedding ct.sym from " + ctSym);
        Path ctSymDir = buildDir.resolve("ct.sym");
        deleteRecursively(ctSymDir);
        unzipAll(ctSym, ctSymDir);
        Files.createDirectories(distDir);

        Path classes = buildDir.resolve("classes");
        String ver = jdkTag;
        try (JarOutputStream z = new JarOutputStream(Files.newOutputStream(distDir.resolve("nb-javac-" + ver + "-api.jar")))) {
            writeManifest(z, true);
            addMatching(z, classes, NBJavacBuild::apiEntry);
            addDoc(z, "README.md");
        }
        try (JarOutputStream z = new JarOutputStream(Files.newOutputStream(distDir.resolve("nb-javac-" + ver + "-impl.jar")))) {
            writeManifest(z, false);
            addMatching(z, classes, NBJavacBuild::implEntry);
            addTree(z, ctSymDir, "META-INF/ct.sym/");
            addDoc(z, "README.md");
            addTree(z, lib.resolve("reflect"), "");
        }
    }

    // ---------------------------------------------------------------- ct.sym

    /**
     * The ct.sym always comes from the pinned JDK image (jar depends on
     * jdk, which builds it); -Dct.sym.file overrides for special cases.
     */
    private Path ctSymFile() throws Exception {
        String override = System.getProperty("ct.sym.file");
        if (override != null && !override.isEmpty()) {
            return Path.of(override);
        }
        Path image = jdkImage();
        if (image != null) {
            Path inImage = image.resolve("images/jdk/lib/ct.sym");
            if (Files.exists(inImage)) {
                return inImage;
            }
            Path support = image.resolve("support/symbols/ct.sym");
            if (Files.exists(support)) {
                return support;
            }
        }
        throw new IOException("No ct.sym found. Build the pinned JDK first: ./bld jdk");
    }

    // ---------------------------------------------------------------- helpers

    private Path jdkImage() {
        Path build = jdk.resolve("build");
        if (!Files.isDirectory(build)) {
            return null;
        }
        try (Stream<Path> s = Files.list(build)) {
            return s.filter(p -> Files.isDirectory(p.resolve("images/jdk"))).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String builtImage() {
        Path image = jdkImage();
        if (image == null) {
            throw new IllegalStateException("JDK image not built, run: ./bld jdk");
        }
        return image.resolve("images/jdk").toString();
    }

    private String builtTool(String tool) {
        return builtImage() + "/bin/" + tool;
    }

    private String builtJava() {
        return builtTool("java");
    }

    private String currentJava() {
        return System.getProperty("java.home") + "/bin/java";
    }

    private Path implJar() {
        return distDir.resolve("nb-javac-" + jdkTag + "-impl.jar");
    }

    private static String classPath(Path... paths) {
        StringBuilder sb = new StringBuilder();
        for (Path p : paths) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparatorChar);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private List<Path> collectSources(Path... roots) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path r : roots) {
            if (!Files.isDirectory(r)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(r)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !excluded(r.relativize(p)))
                        .sorted()
                        .forEach(out::add);
            }
        }
        return out;
    }

    /** Source files excluded from compilation. */
    private static boolean excluded(Path rel) {
        String p = rel.toString().replace('\\', '/');
        if (p.endsWith("module-info.java")) {
            return true;
        }
        if (p.startsWith("com/sun/tools/javac/launcher/")
                && !p.substring("com/sun/tools/javac/launcher/".length()).contains("/")) {
            return true;
        }
        if (p.equals("com/sun/tools/javac/main/JavacToolProvider.java")) {
            return true;
        }
        if (p.startsWith("com/sun/tools/sjavac/")) {
            return true;
        }
        return false;
    }

    private static boolean hasLicenseException(Path f) throws IOException {
        String content = Files.readString(f, ISO_8859_1);
        return CLASSPATH_EXCEPTION.matcher(content).find();
    }

    private void download(String url, Path dest) throws Exception {
        if (Files.exists(dest)) {
            return;
        }
        System.out.println("Downloading: " + url);
        Files.deleteIfExists(dest);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(dest));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(dest);
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
    }

    private void unzipAll(Path zip, Path dest) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                extractEntry(zipFile, entries.nextElement(), dest);
            }
        }
    }

    private void unzipInclude(Path zip, Path dest, String... prefixes) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                for (String prefix : prefixes) {
                    if (name.startsWith(prefix)) {
                        extractEntry(zipFile, entry, dest);
                        break;
                    }
                }
            }
        }
    }

    private static void extractEntry(ZipFile zipFile, ZipEntry entry, Path dest) throws IOException {
        Path target = dest.resolve(entry.getName());
        if (entry.isDirectory()) {
            Files.createDirectories(target);
        } else {
            Files.createDirectories(target.getParent());
            try (var in = zipFile.getInputStream(entry)) {
                Files.copy(in, target);
            }
        }
    }

    /** Entries that belong to the api jar. */
    private static boolean apiEntry(String p) {
        if (p.startsWith("com/sun/source/")) {
            return true;
        }
        if (p.startsWith("javax/lang/")) {
            return true;
        }
        if (p.startsWith("javax/tools/") && !p.substring("javax/tools/".length()).contains("/")) {
            return true;
        }
        if (p.startsWith("jdk/internal/") && !p.substring("jdk/internal/".length()).contains("/")
                && p.substring("jdk/internal/".length()).startsWith("PreviewFeature")) {
            return true;
        }
        if (p.startsWith("javax/annotation/")) {
            return true;
        }
        if (p.startsWith("META-INF/versions/")) {
            return true;
        }
        return false;
    }

    /** Entries that belong to the impl jar. */
    private static boolean implEntry(String p) {
        if (p.startsWith("META-INF/services/")) {
            return true;
        }
        if (p.startsWith("com/sun/tools/")) {
            return true;
        }
        if (p.startsWith("jdk/internal/shellsupport/")) {
            return true;
        }
        if (p.startsWith("jdk/internal/") && !p.substring("jdk/internal/".length()).contains("/")
                && p.substring("jdk/internal/".length()).startsWith("PreviewFeature")) {
            return true;
        }
        return p.startsWith("nbjavac/");
    }

    private void writeManifest(JarOutputStream z, boolean multiRelease) throws IOException {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            m.getMainAttributes().putValue("Multi-Release", "true");
        }
        z.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
        m.write(z);
        z.closeEntry();
    }

    private void addMatching(JarOutputStream z, Path dir, Predicate<String> filter) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            List<Path> all = s.filter(Files::isRegularFile).sorted().toList();
            for (Path f : all) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                if (filter.test(rel)) {
                    z.putNextEntry(new JarEntry(rel));
                    Files.copy(f, z);
                    z.closeEntry();
                }
            }
        }
    }

    private void addTree(JarOutputStream z, Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            List<Path> all = s.sorted().toList();
            for (Path f : all) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                if (Files.isDirectory(f)) {
                    if (!rel.isEmpty()) {
                        z.putNextEntry(new JarEntry(prefix + rel + "/"));
                        z.closeEntry();
                    }
                } else {
                    z.putNextEntry(new JarEntry(prefix + rel));
                    Files.copy(f, z);
                    z.closeEntry();
                }
            }
        }
    }

    private void addDoc(JarOutputStream z, String name) throws IOException {
        Path f = root.resolve(name);
        if (Files.notExists(f)) {
            return;
        }
        z.putNextEntry(new JarEntry(name));
        Files.copy(f, z);
        z.closeEntry();
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.notExists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.delete(f);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private void exec(Path workDir, String... cmd) throws Exception {
        new ExecOperation().fromProject(this).workDir(workDir.toFile())
                .timeout(-1).command(cmd).execute();
    }

    public static void main(String[] args) throws Exception {
        new NBJavacBuild().start(args);
    }
}
