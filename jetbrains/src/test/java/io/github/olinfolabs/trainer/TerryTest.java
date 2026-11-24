package io.github.olinfolabs.trainer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TerryTest {
    private static final String TASK = "terry/demo";
    private static final String FORM = "/task/" + TASK + "/submit";
    private static final String INPUT_ID = "ab120000-0000-4000-8000-000000000001";
    private static final String SUBMISSION_ID = "ab120000-0000-4000-8000-000000000002";
    private static final String EXPIRED_ID = "ab120000-0000-4000-8000-000000000099";
    private static final String SESSION = "better-auth.session_token=demo.value";
    private static final String REQUEST = "1".repeat(40);
    private static final String CHANGE = "2".repeat(40);
    private static final String UPLOAD = "3".repeat(40);
    @TempDir Path root;

    @Test
    void replacesExpiredInputRunsJavaAndSubmitsTheMatchingFiles() throws IOException {
        String ready = fixture("terry-submit");
        String empty = ready.replaceFirst("\\\\\"input\\\\\":\\{[^}]*}", java.util.regex.Matcher.quoteReplacement("\\\"input\\\":\\\"$undefined\\\""));
        var state = new AtomicReference<>(ready.replace("submissionTimeout\\\":null", "submissionTimeout\\\":60")
                .replace(INPUT_ID, EXPIRED_ID));
        var called = new ArrayList<String>();
        var api = new SubmissionClient((location, session, headers, body) -> {
            if (location.endsWith("/terry-demo.js")) {
                assertNull(session);
                return ok(reference(REQUEST, "requestInput") + reference(CHANGE, "changeInput") + reference(UPLOAD, "uploadAndSubmit"));
            }
            assertEquals(SESSION, session);
            if (location.equals("/task/" + TASK + "/submissions/" + SUBMISSION_ID)) return ok(fixture("terry-result"));
            assertEquals(FORM, location);
            if (body == null) return ok(state.get());
            String action = headers.get("Next-Action");
            called.add(action);
            String text = new String(body, StandardCharsets.UTF_8);
            if (action.equals(CHANGE)) {
                assertEquals("[\"" + EXPIRED_ID + "\"]", text);
                state.set(empty);
            } else if (action.equals(REQUEST)) {
                assertEquals("[\"demo\"]", text);
                state.set(ready);
            } else {
                assertEquals(UPLOAD, action);
                assertTrue(headers.get("Content-Type").startsWith("multipart/form-data; boundary="));
                assertTrue(text.contains("name=\"_1_file\"; filename=\"Main.java\""));
                assertTrue(text.contains("name=\"_2_file\"; filename=\"output.txt\"\r\nContent-Type: text/plain\r\n\r\nCase #1: 5"));
                assertTrue(text.contains("[\"demo\",\"" + INPUT_ID + "\",\"$K1\",\"$K2\"]"));
                return new SubmissionClient.Response(303,
                        Map.of("x-action-redirect", List.of("/task/" + TASK + "/submissions/" + SUBMISSION_ID + ";push")), "");
            }
            return ok("0:{\"a\":\"$@1\"}\n1:\"$undefined\"\n");
        });
        var client = new TerryClient(api, (location, limit, locale) -> {
            assertEquals("/files/input/ab/12/" + INPUT_ID + "/demo_input_1.txt", location);
            return bytes("1\n5\n");
        });
        Files.write(root.resolve("olinfo.xml"), ProjectLink.document(TASK, "java", "Main.java"));
        Files.writeString(root.resolve("Main.java"), """
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        var input = new Scanner(System.in);
                        input.nextInt();
                        System.out.println("Case #1: " + input.nextInt());
                    }
                }
                """);
        var link = ProjectLink.read(root);
        byte[] source = link.readSource();
        var run = TerryRunner.run(link, source, () -> client.input(TASK, SESSION), ignored -> {}, null);
        assertEquals(INPUT_ID, run.input().id());
        assertEquals(INPUT_ID, client.input(TASK, SESSION).id());
        assertEquals(SUBMISSION_ID, client.submit(link, run.input(), source, run.output(), SESSION));
        assertEquals(List.of(CHANGE, REQUEST, UPLOAD), called);
        var report = client.result(TASK, SUBMISSION_ID, SESSION);
        assertEquals("17/50 punti · 1/3 casi corretti", report.summary());
        assertEquals(List.of("Corretto", "Errato", "Non inviato"), report.cases().stream().map(TerryClient.TestCase::outcome).toList());
        assertEquals("Valore atteso diverso", report.cases().get(1).detail());
        assertEquals(List.of("Nota: Risposte parziali."), report.notes());
    }

    @Test
    void validatesInputAndPayloadAndKeepsScoreInHistory() throws IOException {
        String html = fixture("terry-submit");
        assertThrows(IOException.class, () -> TerryClient.formInput(html.replace("/ab/12/", "/../12/"), TASK));
        assertThrows(SubmissionClient.LoginRequired.class, () -> TerryClient.formInput("Accedi per inviare soluzioni", TASK));
        var results = SubmissionClient.submissionList("""
                <ul><li><a href="/task/%s/submissions/%s"><div>Data</div><div><span class="bg-orange-400">17 / 50</span></div></a></li></ul>
                """.formatted(TASK, SUBMISSION_ID), TASK);
        assertEquals(17, results.best());
        assertEquals("17 / 50 pt", results.scoreLabel());
        var client = new TerryClient(new SubmissionClient((location, session, headers, body) -> {
            if (location.endsWith(".js")) return ok(reference(UPLOAD, "uploadAndSubmit"));
            throw new AssertionError("Invio oltre il limite");
        }), TrainingClient::download);
        var link = new ProjectLink(root, TASK, "py", "demo.py");
        var input = new TerryClient.Input(INPUT_ID, bytes("1\n"), html);
        assertTrue(assertThrows(IOException.class, () -> client.submit(link, input, new byte[100_000], new byte[950_000], SESSION))
                .getMessage().contains("limite di 1 MiB"));
    }

    @Test
    void importsPublicRegionalProject() throws IOException {
        assumeTrue("1".equals(System.getenv("OLINFO_LIVE_TEST")));
        var client = new TrainingClient();
        var search = client.search("terry/download", 1, true);
        assertEquals("terry/download", search.tasks().getFirst().id());
        var details = client.details("terry/download");
        var project = ProjectCreator.create(client, details, "cpp", "original", root.toString(), "download");
        assertEquals("download.cpp", project.solutionName());
        assertTrue(Files.isRegularFile(project.target().resolve("testo.md")));
        assertTrue(Files.readString(project.target().resolve("output.txt")).startsWith("Case #1:"));
        assertEquals("terry/download", ProjectLink.read(project.target()).task());
        var newer = client.details("terry/escursione");
        assertTrue(new String(client.asset(TrainingClient.samplePair(newer.assets()).input()), StandardCharsets.UTF_8).startsWith("3"));
    }

    private static String reference(String key, String name) {
        return "createServerReference)(\"" + key + "\",null,null,\"" + name + "\");";
    }

    private static SubmissionClient.Response ok(String text) { return new SubmissionClient.Response(200, Map.of(), text); }

    private static String fixture(String name) throws IOException {
        try (var input = TerryTest.class.getResourceAsStream("/" + name + ".html")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
