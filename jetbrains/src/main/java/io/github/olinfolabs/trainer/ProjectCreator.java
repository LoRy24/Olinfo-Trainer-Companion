package io.github.olinfolabs.trainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class ProjectCreator {
    private static final Pattern BAD_NAME = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
    private static final Pattern DEVICE_NAME = Pattern.compile("(?:con|prn|aux|nul|com[1-9¹²³]|lpt[1-9¹²³])(?:\\..*)?", Pattern.CASE_INSENSITIVE);

    record Result(Path target, String solutionName) {}

    record Prepared(Path target, String solutionName, Map<String, byte[]> files) {
        Result create() throws IOException {
            try {
                Files.createDirectory(target);
            } catch (FileAlreadyExistsException exception) {
                throw existing(exception);
            }
            try {
                return populate();
            } catch (IOException exception) {
                try { Files.deleteIfExists(target); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
                throw exception;
            }
        }

        Result populate() throws IOException {
            var created = new ArrayList<Path>();
            try {
                for (var entry : files.entrySet()) {
                    Path location = target.resolve(entry.getKey());
                    try (var stream = Files.newOutputStream(location, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        created.add(location);
                        stream.write(entry.getValue());
                    }
                }
            } catch (IOException exception) {
                for (Path file : created) {
                    try { Files.deleteIfExists(file); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
                }
                throw exception;
            }
            return new Result(target, solutionName);
        }
    }

    private ProjectCreator() {}

    static Path projectPath(String parent, String name) throws IOException {
        Path directory;
        try {
            directory = Path.of(parent);
        } catch (InvalidPathException exception) {
            throw new IOException("Scegli una cartella di destinazione valida.", exception);
        }
        if (!directory.isAbsolute()) throw new IOException("Scegli una cartella di destinazione.");
        if (name.isBlank() || name.length() > 100 || !name.equals(name.strip()) || name.endsWith(".")
                || BAD_NAME.matcher(name).find() || DEVICE_NAME.matcher(name).matches()) {
            throw new IOException("Inserisci un nome cartella valido (fino a 100 caratteri).");
        }
        return directory.resolve(name);
    }

    static Result create(TrainingClient client, TrainingClient.Details details, String language, String locale,
                         String parent, String name) throws IOException {
        return prepare(client, details, language, locale, parent, name).create();
    }

    static Prepared prepare(TrainingClient client, TrainingClient.Details details, String language, String locale,
                            String parent, String name) throws IOException {
        Path target = projectPath(parent, name);
        var option = details.languages().stream().filter(item -> item.choice().id().equals(language)).findFirst()
                .orElseThrow(() -> new IOException("Linguaggio non disponibile per questo problema."));
        if (!Files.isDirectory(target.getParent())) throw new IOException("La destinazione deve essere una cartella.");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw existing(null);

        var examples = TrainingClient.samplePair(details.assets());
        Map<String, byte[]> content = new LinkedHashMap<>();
        content.put("testo.pdf", client.pdf(details, locale));
        content.put("input.txt", client.asset(examples.input()));
        content.put("output.txt", client.asset(examples.output()));

        String solutionName = null;
        if (!language.equals("output")) {
            solutionName = language.equals("java")
                    ? option.starter() == null ? "Main.java" : option.starter().name()
                    : "file." + language;
            if (language.equals("cpp") && !details.mode().equals("grader") && option.starter() == null && option.grader() == null) {
                solutionName = details.id() + ".cpp";
            }
            if (!TrainingClient.SAFE_FILE.matcher(solutionName).matches()) throw new IOException("Nome del sorgente non valido.");
            String graderName = option.grader() == null ? null : option.grader().name();
            content.put(solutionName, option.starter() == null
                    ? utf8(starter(language, details.mode())) : client.asset(option.starter()));
            if (graderName != null) content.put(graderName, client.asset(option.grader()));
            if (language.equals("cpp") || language.equals("c")) {
                for (var file : details.assets()) {
                    if (TrainingClient.isHeader(file.name()) && !content.containsKey(file.name())) {
                        content.put(file.name(), client.asset(file));
                    }
                }
                if (!details.mode().equals("grader") || graderName != null) {
                    content.put("CMakeLists.txt", utf8(cmake(language, solutionName, graderName)));
                }
            }
        }

        content.put("olinfo.xml", ProjectLink.document(details.id(), language, solutionName));
        return new Prepared(target, solutionName, content);
    }

    private static IOException existing(IOException cause) {
        return new IOException("Esiste già un file o una cartella con questo nome.", cause);
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static String starter(String language, String mode) {
        if (mode.equals("grader")) {
            String instruction = "Consulta il PDF per la firma richiesta dal grader.";
            return switch (language) {
                case "pas" -> "{ " + instruction + " }\n";
                case "py" -> "# " + instruction + "\n";
                default -> "// " + instruction + "\n";
            };
        }
        boolean useFiles = mode.equals("file");
        String redirect = useFiles ? """
                    freopen("input.txt", "r", stdin);
                    freopen("output.txt", "w", stdout);
                """ : "";
        return switch (language) {
            case "cpp" -> "#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n" + redirect
                    + "    // Scrivi qui la soluzione.\n    return 0;\n}\n";
            case "c" -> "#include <stdio.h>\n\nint main(void) {\n" + redirect
                    + "    /* Scrivi qui la soluzione. */\n    return 0;\n}\n";
            case "py" -> useFiles ? """
                    import sys

                    with open("input.txt") as sys.stdin, open("output.txt", "w") as sys.stdout:
                        # Scrivi qui la soluzione.
                        pass
                    """ : "# Scrivi qui la soluzione.\n";
            case "java" -> useFiles ? """
                    import java.io.*;

                    public class Main {
                        public static void main(String[] args) throws IOException {
                            try (var in = new BufferedInputStream(new FileInputStream("input.txt"));
                                 var out = new PrintStream("output.txt")) {
                                // Scrivi qui la soluzione usando in e out.
                            }
                        }
                    }
                    """ : """
                    public class Main {
                        public static void main(String[] args) {
                            // Scrivi qui la soluzione.
                        }
                    }
                    """;
            case "pas" -> useFiles ? """
                    program Solution;
                    begin
                      Assign(Input, 'input.txt'); Reset(Input);
                      Assign(Output, 'output.txt'); Rewrite(Output);
                      { Scrivi qui la soluzione. }
                    end.
                    """ : """
                    program Solution;
                    begin
                      { Scrivi qui la soluzione. }
                    end.
                    """;
            default -> throw new IllegalArgumentException("Linguaggio non supportato: " + language);
        };
    }

    private static String cmake(String language, String source, String grader) {
        String compiler = language.equals("cpp") ? "CXX" : "C";
        int standard = language.equals("cpp") ? 20 : 17;
        String sources = "\"" + source + "\"" + (grader == null ? "" : " \"" + grader + "\"");
        return """
                cmake_minimum_required(VERSION 3.16)
                project(olinfo_solution LANGUAGES %1$s)
                set(CMAKE_%1$s_STANDARD %2$d)
                set(CMAKE_%1$s_STANDARD_REQUIRED ON)
                add_executable(solution %3$s)
                """.formatted(compiler, standard, sources)
                + "set_property(TARGET solution PROPERTY VS_DEBUGGER_WORKING_DIRECTORY \"${CMAKE_SOURCE_DIR}\")\n";
    }
}
