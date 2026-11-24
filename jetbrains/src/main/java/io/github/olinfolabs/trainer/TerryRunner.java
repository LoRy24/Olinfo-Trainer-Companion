package io.github.olinfolabs.trainer;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class TerryRunner {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final int MAX_OUTPUT = 1_000_000;
    private static final int DIAGNOSTIC_LIMIT = 16 * 1024 * 1024;

    @FunctionalInterface
    interface GetInput { TerryClient.Input request() throws IOException; }
    record Result(TerryClient.Input input, byte[] output) {}

    private TerryRunner() {}

    static Result run(ProjectLink link, byte[] source, GetInput getInput, Consumer<String> progress,
                      Path bundledToolchain) throws IOException {
        Path directory = Files.createTempDirectory("olinfo-terry-");
        try {
            Path solution = directory.resolve(link.source());
            Path inputFile = directory.resolve("input.txt");
            Path executable = directory.resolve(WINDOWS ? "solution.exe" : "solution");
            Files.write(solution, source, StandardOpenOption.CREATE_NEW);
            List<List<String>> command = List.of(List.of(executable.toString()));
            List<String> args = List.of();
            String compilerPath = null;
            switch (link.language()) {
                case "cpp", "c" -> {
                    boolean cpp = link.language().equals("cpp");
                    String compiler = cpp ? "g++" : "gcc";
                    var defaults = new ArrayList<>(List.of(List.of(compiler), List.of(cpp ? "clang++" : "clang")));
                    if (bundledToolchain != null && WINDOWS && Files.isExecutable(bundledToolchain.resolve(compiler + ".exe"))) {
                        defaults.add(List.of(bundledToolchain.resolve(compiler + ".exe").toString()));
                    }
                    progress.accept("Compilazione locale…");
                    compilerPath = execute(candidates(cpp ? "CXX" : "CC", defaults), List.of(cpp ? "-std=c++20" : "-std=c17",
                            "-O2", solution.toString(), "-o", executable.toString()), directory, "Compilazione", null, null);
                }
                case "pas" -> {
                    progress.accept("Compilazione locale…");
                    compilerPath = execute(candidates("OLINFO_FPC", List.of(List.of("fpc"))),
                            List.of("-O2", "-o" + executable, solution.toString()), directory, "Compilazione", null, null);
                }
                case "py" -> {
                    List<List<String>> defaults = WINDOWS ? List.of(List.of("py", "-3"), List.of("python"), List.of("python3"))
                            : List.of(List.of("python3"), List.of("python"));
                    command = runtime(candidates("OLINFO_PYTHON", defaults), directory, "Python 3", version -> version.startsWith("Python 3."));
                    args = List.of(solution.toString());
                }
                case "java" -> {
                    String binary = WINDOWS ? "java.exe" : "java";
                    var defaults = new ArrayList<List<String>>();
                    String javaHome = System.getenv("JAVA_HOME");
                    if (javaHome != null && !javaHome.isBlank()) defaults.add(List.of(Path.of(javaHome, "bin", binary).toString()));
                    defaults.add(List.of(Path.of(System.getProperty("java.home"), "bin", binary).toString()));
                    defaults.add(List.of("java"));
                    command = runtime(candidates("OLINFO_JAVA", defaults), directory, "JDK 11 o successivo",
                            version -> version.matches("(?s)(?:java|openjdk) (?:1[1-9]|[2-9]\\d|\\d{3,})(?:[. +\\-].*|$)"));
                    args = List.of("-Dfile.encoding=UTF-8", solution.toString());
                }
                default -> throw new IOException("Linguaggio Territoriali non supportato.");
            }
            progress.accept("Preparazione e download dell’input…");
            TerryClient.Input input = getInput.request();
            Files.write(inputFile, input.bytes(), StandardOpenOption.CREATE_NEW);
            progress.accept("Esecuzione locale…");
            Path compiler = compilerPath == null ? null : Path.of(compilerPath);
            Path runtimePath = WINDOWS && compiler != null && compiler.isAbsolute() ? compiler.getParent() : null;
            execute(command, args, directory, "Esecuzione", inputFile, runtimePath);
            Path output = directory.resolve("output.txt");
            if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) output = directory.resolve("stdout.txt");
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) > MAX_OUTPUT) {
                throw new IOException("Il file di output supera il limite di 1.000.000 byte o non è valido.");
            }
            try (var stream = Files.newInputStream(output, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = stream.readNBytes(MAX_OUTPUT + 1);
                if (bytes.length > MAX_OUTPUT || new String(bytes, StandardCharsets.UTF_8).isBlank()) {
                    throw new IOException("Il programma deve produrre un output non vuoto entro 1.000.000 byte.");
                }
                return new Result(input, bytes);
            }
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            } catch (IOException | java.io.UncheckedIOException ignored) {
                // The toolchain can still hold a file after Windows terminates a process.
            }
        }
    }

    private static List<List<String>> candidates(String variable, List<List<String>> defaults) {
        String override = System.getenv(variable);
        return override == null || override.isBlank() ? defaults : List.of(List.of(override));
    }

    private static List<List<String>> runtime(List<List<String>> candidates, Path directory, String label,
                                              Predicate<String> supported) throws IOException {
        for (List<String> candidate : candidates) {
            try {
                execute(List.of(candidate), List.of("--version"), directory, label, null, null);
                if (supported.test(Files.readString(directory.resolve("stdout.txt")).strip())) return List.of(candidate);
            } catch (IOException exception) {
                if (Thread.currentThread().isInterrupted()) throw exception;
            }
        }
        throw new IOException(label + " non disponibile. Controlla il PATH e le impostazioni dell’IDE.");
    }

    private static String execute(List<List<String>> candidates, List<String> args, Path directory,
                                  String label, Path input, Path toolDirectory) throws IOException {
        Path stdout = directory.resolve("stdout.txt");
        Path stderr = directory.resolve("stderr.txt");
        Path outputFile = directory.resolve("output.txt");
        IOException startupFailure = null;
        for (List<String> candidate : candidates) {
            var command = new ArrayList<>(candidate);
            command.addAll(args);
            var builder = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            if (toolDirectory != null) {
                String pathKey = builder.environment().keySet().stream().filter(key -> key.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
                builder.environment().put(pathKey, toolDirectory + File.pathSeparator + builder.environment().getOrDefault(pathKey, ""));
            }
            if (input != null) builder.redirectInput(input.toFile());
            Process process;
            try {
                process = builder.start();
            } catch (IOException exception) {
                startupFailure = exception;
                continue;
            }
            try {
                if (input == null) process.getOutputStream().close();
                long start = System.nanoTime();
                while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    if (System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(120)) {
                        throw new IOException(label + " · Tempo limite superato (120 secondi).");
                    }
                    for (Path file : List.of(stdout, stderr, outputFile)) {
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > DIAGNOSTIC_LIMIT) {
                            throw new IOException(label + " · Il programma ha prodotto troppi dati.");
                        }
                    }
                }
                if (process.exitValue() != 0) {
                    try (var stream = Files.newInputStream(stderr)) {
                        var message = new String(stream.readNBytes(1800), StandardCharsets.UTF_8).strip();
                        throw new IOException(label + " non riuscita (uscita " + process.exitValue() + ")."
                                + (message.isEmpty() ? "" : "\n" + message));
                    }
                }
                return candidate.getFirst();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(label + " interrotta.", exception);
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }
        }
        var names = candidates.stream().map(candidate -> candidate.getFirst()).toList();
        throw new IOException("Strumento non trovato (" + String.join(", ", names) + "). Verifica il PATH dell’IDE.", startupFailure);
    }
}
