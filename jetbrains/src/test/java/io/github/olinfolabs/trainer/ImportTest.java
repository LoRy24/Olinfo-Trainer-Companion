package io.github.olinfolabs.trainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ImportTest {
    @TempDir Path folder;

    @Test
    void importsSearchArchiveAndSelectedStatementWithoutOverwriting() throws IOException {
        var client = mockClient();
        var result = client.search("demo & prova", 1);
        assertEquals(List.of(new TrainingClient.Task("demo_grader", "Somma & differenza"),
                new TrainingClient.Task("demo_stdio", "Programma autonomo")), result.tasks());
        assertTrue(result.hasNext());

        var details = client.details("demo_grader");
        assertEquals(List.of("it-IT", "en-GB"), details.pdf().stream().map(TrainingClient.Selection::id).toList());
        assertEquals(List.of("cpp"), details.languages().stream().map(item -> item.choice().id()).toList());
        var project = ProjectCreator.create(client, details, "cpp", "en-GB", folder.toString(), "Demo progetto");
        assertEquals("file.cpp", project.solutionName());
        assertEquals("2 2\n", Files.readString(project.target().resolve("input.txt")));
        assertEquals("4\n", Files.readString(project.target().resolve("output.txt")));
        assertEquals("%PDF-English", Files.readString(project.target().resolve("testo.pdf")));
        assertEquals("int main() {}\n", Files.readString(project.target().resolve("grader.cpp")));
        assertTrue(Files.readString(project.target().resolve("CMakeLists.txt")).contains("file.cpp\" \"grader.cpp"));
        assertThrows(IOException.class, () -> ProjectCreator.create(client, details, "cpp", "it-IT", folder.toString(), "Demo progetto"));
        assertThrows(IOException.class, () -> ProjectCreator.create(client, details, "cpp", "it-IT", folder.toString(), "../escape"));
        assertThrows(IOException.class, () -> ProjectCreator.create(client, details, "cpp", "de-DE", folder.toString(), "NoFiles"));
        try (var files = Files.list(folder)) {
            assertEquals(List.of("Demo progetto"), files.map(item -> item.getFileName().toString()).toList());
        }
    }

    @Test
    void createsEmptyInputAndOutputWithAnAutonomousStarter() throws IOException {
        String html = new String(resource("problem.html"), StandardCharsets.UTF_8)
                .replace(">grader<", ">stdin / stdout<")
                .replaceAll("(?s)<aside>.*?</aside>", "");
        var client = new TrainingClient((path, limit, locale) -> utf8(path.endsWith("/testo.pdf") ? "%PDF-IT" : html));
        var details = client.details("demo_stdio");
        var project = ProjectCreator.create(client, details, "py", "it-IT", folder.toString(), "Python");
        try (var files = Files.list(project.target())) {
            assertEquals(List.of("file.py", "input.txt", "olinfo.xml", "output.txt", "testo.pdf"),
                    files.map(item -> item.getFileName().toString()).sorted().toList());
        }
        assertEquals(0, Files.size(project.target().resolve("input.txt")));
        assertEquals(0, Files.size(project.target().resolve("output.txt")));

        var cpp = ProjectCreator.create(client, details, "cpp", "it-IT", folder.toString(), "Nome del progetto");
        assertEquals("demo_stdio.cpp", cpp.solutionName());
        try (var files = Files.list(cpp.target())) {
            assertEquals(List.of("CMakeLists.txt", "demo_stdio.cpp", "input.txt", "olinfo.xml", "output.txt", "testo.pdf"),
                    files.map(item -> item.getFileName().toString()).sorted().toList());
        }
        String source = Files.readString(cpp.target().resolve(cpp.solutionName()));
        assertTrue(source.startsWith("#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {"));
        assertTrue(Files.readString(cpp.target().resolve("CMakeLists.txt")).contains("add_executable(solution \"demo_stdio.cpp\")"));
    }

    @Test
    void populatesTheNativeWizardDirectoryAndPreservesExistingFiles() throws IOException {
        var client = mockClient();
        var details = client.details("demo_grader");
        var prepared = ProjectCreator.prepare(client, details, "cpp", "it-IT", folder.toString(), "Native wizard");
        assertFalse(Files.exists(prepared.target()));
        Files.createDirectories(prepared.target().resolve(".idea"));
        Path expectedOutput = prepared.target().resolve("output.txt");
        Files.writeString(expectedOutput, "existing\n");

        assertThrows(IOException.class, prepared::populate);
        assertEquals("existing\n", Files.readString(expectedOutput));
        assertTrue(Files.isDirectory(prepared.target().resolve(".idea")));
        assertFalse(Files.exists(prepared.target().resolve("testo.pdf")));

        Files.delete(expectedOutput);
        assertEquals(prepared.target(), prepared.populate().target());
        assertEquals("4\n", Files.readString(expectedOutput));
        assertTrue(Files.exists(prepared.target().resolve("grader.cpp")));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OLINFO_LIVE_TEST", matches = "1")
    void createsProjectsFromLiveCatalog() throws IOException {
        var client = new TrainingClient();
        assertTrue(client.search("oii_datazione", 1).tasks().stream().anyMatch(task -> task.id().equals("oii_datazione")));
        var details = client.details("oii_datazione");
        assertEquals(List.of("cpp"), details.languages().stream().map(item -> item.choice().id()).toList());
        assertFalse(java.util.Arrays.equals(client.pdf(details, "it-IT"), client.pdf(details, "en-GB")));
        var project = ProjectCreator.create(client, details, "cpp", "en-GB", folder.toString(), "grado");
        assertTrue(Files.size(project.target().resolve("grader.cpp")) > 0);
        assertTrue(Files.size(project.target().resolve("input.txt")) > 0);
        var autonomous = ProjectCreator.create(client, client.details("easy2"), "cpp", "it-IT", folder.toString(), "programma");
        assertEquals("easy2.cpp", autonomous.solutionName());
        assertTrue(Files.size(autonomous.target().resolve("input.txt")) > 0);
        assertTrue(Files.size(autonomous.target().resolve("output.txt")) > 0);
        assertFalse(Files.exists(autonomous.target().resolve("grader.cpp")));
        var missing = client.details("oii_z");
        assertNull(missing.languages().stream().filter(item -> item.choice().id().equals("cpp")).findFirst().orElseThrow().grader());
    }

    private static TrainingClient mockClient() throws IOException {
        byte[] catalog = resource("catalog.html");
        String problem = new String(resource("problem.html"), StandardCharsets.UTF_8);
        byte[] zip = exampleZip();
        return new TrainingClient((path, limit, locale) -> {
            if (path.startsWith("/tasks/1")) {
                assertEquals("/tasks/1?search=demo+%26+prova", path);
                return catalog;
            }
            if (path.equals("/task/demo_grader")) {
                return utf8(locale.equals("en-GB") ? problem.replace("lang=\"it-IT\"", "lang=\"en-GB\"")
                        .replace("b".repeat(40), "c".repeat(40)) : problem);
            }
            if (path.endsWith("/attachments.zip")) return zip;
            if (path.contains("c".repeat(40))) return utf8("%PDF-English");
            if (path.contains("b".repeat(40))) return utf8("%PDF-Italiano");
            throw new IOException("Inatteso: " + path);
        });
    }

    private static byte[] exampleZip() throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            for (var entry : Map.of(
                    "starter/demo_grader.cpp", "int add(int a, int b) { return 0; }\n",
                    "grader/grader.cpp", "int main() {}\n",
                    "input/input10.txt", "10 10\n",
                    "output/output10.txt", "20\n",
                    "input/input2.txt", "2 2\n",
                    "output/output2.txt", "4\n",
                    "private-solution.py", "raise RuntimeError()\n"
            ).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(utf8(entry.getValue()));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] resource(String name) throws IOException {
        try (var stream = ImportTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
