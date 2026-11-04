import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolutionException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JarCheck {
    public static void main(String[] args) throws IOException {
        Path jar = Path.of(args[0]);
        int release = Integer.parseInt(args[1]);
        int expectedMajor = release + 44;
        String runtimeClasspath = args[2];
        List<String> expectedClasses = Arrays.asList(args).subList(3, args.length);
        List<String> problems = new ArrayList<>();

        TreeMap<Integer, List<String>> byMajor = new TreeMap<>();
        ModuleDescriptor descriptor = null;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            for (String expected : expectedClasses) {
                if (jarFile.getJarEntry(expected.replace('.', '/') + ".class") == null) {
                    problems.add("The jar has no " + expected + ". Check <sourceDirectory> in pom.xml: Kotlin and Java sources live in src/main/kotlin.");
                }
            }
            for (JarEntry entry : Collections.list(jarFile.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                try (DataInputStream data = new DataInputStream(jarFile.getInputStream(entry))) {
                    data.readInt();
                    data.readUnsignedShort();
                    int major = data.readUnsignedShort();
                    byMajor.computeIfAbsent(major, k -> new ArrayList<>()).add(entry.getName());
                }
            }
            JarEntry moduleInfo = jarFile.getJarEntry("module-info.class");
            if (moduleInfo != null) {
                try (InputStream in = jarFile.getInputStream(moduleInfo)) {
                    descriptor = ModuleDescriptor.read(in);
                }
            }
        }
        byMajor.forEach((major, classes) -> {
            if (major != expectedMajor) {
                problems.add(classes.size() + " classes are Java " + (major - 44) + " bytecode (major " + major + "), expected Java " + release
                    + " (major " + expectedMajor + "), e.g. " + classes.get(0)
                    + ". Set <jvmTarget>${java.version}</jvmTarget> on kotlin-maven-plugin and <release>${java.version}</release> on maven-compiler-plugin.");
            }
        });

        if (descriptor == null) {
            problems.add("The jar has no module-info.class.");
        } else {
            List<Path> modulePath = new ArrayList<>();
            modulePath.add(jar);
            for (String entry : runtimeClasspath.split(File.pathSeparator)) {
                if (!entry.isEmpty() && !Path.of(entry).equals(jar)) modulePath.add(Path.of(entry));
            }
            try {
                Configuration.resolve(ModuleFinder.of(modulePath.toArray(Path[]::new)), List.of(ModuleLayer.boot().configuration()),
                    ModuleFinder.of(), Set.of(descriptor.name()));
            } catch (FindException | ResolutionException e) {
                problems.add("A JPMS application with only this jar's compile and runtime dependencies can't start: " + e.getMessage()
                    + ". A module from a provided or optional dependency must be 'requires static' in module-info.java.");
            }
        }

        if (!problems.isEmpty()) {
            System.err.println("Jar check failed for " + jar.getFileName() + ":");
            problems.forEach(p -> System.err.println("  " + p));
            System.exit(1);
        }
        int classCount = byMajor.values().stream().mapToInt(List::size).sum();
        System.out.println("Jar check passed for " + jar.getFileName() + ": " + classCount + " classes, all major " + expectedMajor
            + ", module " + descriptor.name() + " resolves against the runtime classpath.");
    }
}
