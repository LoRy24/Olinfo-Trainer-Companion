package io.github.olinfolabs.trainer;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SubmissionTest {
    @TempDir Path root;

    @Test
    void attachesSourceAndRejectsPathsOutsideTheProject() throws IOException {
        Path manifest = root.resolve("olinfo.xml");
        Files.write(manifest, ProjectLink.document("demo", "cpp", "demo.cpp"));
        Files.writeString(root.resolve("demo.cpp"), "int main() {}");
        assertEquals("int main() {}", new String(ProjectLink.read(root).readSource(), StandardCharsets.UTF_8));
        Files.writeString(manifest, "<olinfo version=\"1\" task=\"demo\" language=\"cpp\" source=\"../demo.cpp\"/>");
        assertThrows(IOException.class, () -> ProjectLink.read(root));
    }

    @Test
    void readsProfileAndFindsRankAcrossTiedPages() throws IOException {
        String html = fixture("profile.html");
        String user = AccountData.username(html);
        assertEquals("Fixture.User", user);
        var profile = AccountData.profile(html);
        assertEquals(275, profile.points());
        assertEquals(14, profile.solved());
        assertEquals("https://www.gravatar.com/avatar/demo?s=256", profile.avatar().toString());
        assertNull(AccountData.profile(html.replace("www.gravatar.com", "example.com")).avatar());
        var pages = Map.of(
                "/ranking/1", "<a href='/ranking/3?'>Ultima</a><ul>" + rankingRow("prima", 1, 400) + "</ul>",
                "/ranking/2", "<ul>" + rankingRow("seconda", 2, 275) + "</ul>",
                "/ranking/3", "<ul>" + rankingRow(user, 3, 275) + rankingRow("quarta", 4, 120) + "</ul>");
        assertEquals(3, AccountData.rank(pages::get, user, profile.points()));
        assertNull(AccountData.rank(pages::get, "assente", profile.points()));
        assertNull(AccountData.rank(location -> { throw new AssertionError(location); }, user, 0));
        assertEquals("red", AccountData.scoreColor(0, null));
        assertEquals("lime", AccountData.scoreColor(75, null));
        assertEquals("green", AccountData.scoreColor(100, null));
        assertEquals("orange", AccountData.scoreColor(25, "orange"));
    }

    private static String rankingRow(String user, int rank, int points) {
        return "<li><a href='/user/%s'><div><div>%d</div></div><div>%d</div></a></li>"
                .formatted(user, rank, points);
    }

    @Test
    void signsInSubmitsAndParsesEvaluation() throws IOException {
        String form = fixture("submit.html");
        String history = fixture("submissions.html");
        String action = "a".repeat(42);
        String cookie = "__Secure-better-auth.session_token=mock-token.test%2Bsignature";
        var uploads = new AtomicInteger();
        var client = new SubmissionClient((location, session, headers, body) -> {
            if (location.equals("/login") && body == null) return ok("<script src=\"/_next/static/chunks/login.js\"></script>");
            if (location.endsWith("/login.js")) {
                return ok("let u=(0,d.createServerReference)(\"" + action + "\",d.callServer,void 0,d.findSourceMapURL,\"loginPassword\");");
            }
            if (location.equals("/login")) {
                assertEquals(action, headers.get("Next-Action"));
                var arguments = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonArray();
                assertEquals("demo", arguments.get(0).getAsString());
                assertEquals("$$mock-password", arguments.get(1).getAsString());
                return new SubmissionClient.Response(200, Map.of("set-cookie", List.of(cookie + "; HttpOnly; Secure")), "");
            }
            assertEquals(cookie, session);
            if (location.equals("/task/demo/submit")) return ok(form);
            if (location.equals("/task/demo/submissions")) return ok(history);
            assertEquals("/files/submit?task=demo&language=C%2B%2B20", location);
            String multipart = new String(body, StandardCharsets.UTF_8);
            assertTrue(multipart.contains("name=\"demo.%l\"; filename=\"demo.cpp\""));
            assertTrue(multipart.contains("\r\n\r\nint main() {}"));
            uploads.incrementAndGet();
            return ok("{\"submissionId\":4}");
        });

        String session = client.login("demo", "$mock-password");
        assertEquals(cookie, session);
        assertThrows(SubmissionClient.LoginRequired.class, () -> client.results("demo", null));
        String id = client.submit(new ProjectLink(root, "demo", "cpp", "demo.cpp"), "int main() {}".getBytes(StandardCharsets.UTF_8), session);
        var result = client.results("demo", session);
        assertEquals("4", id);
        assertEquals(1, uploads.get());
        assertEquals(73, result.best());
        assertTrue(result.rows().getFirst().pending());
        assertEquals(0, result.rows().get(1).score());
        assertEquals("red", result.rows().get(1).color());
        assertFalse(result.rows().getLast().pending());
    }

    @Test
    void detectsMissingSessionAndGuidelines() throws IOException {
        assertThrows(SubmissionClient.LoginRequired.class, () ->
                SubmissionClient.submissionList("<div>Accedi per vedere le tue sottoposizioni</div>", "demo"));
        assertNull(SubmissionClient.submissionList("<div>Nessuna sottoposizione inviata</div>", "demo").best());
        String form = fixture("submit.html").replace("Accepted\\\":true", "Accepted\\\":false");
        assertTrue(assertThrows(IOException.class, () -> SubmissionClient.submitOptions(form, "cpp")).getMessage().contains("linee guida"));
    }

    private static SubmissionClient.Response ok(String text) {
        return new SubmissionClient.Response(200, Map.of(), text);
    }

    private static String fixture(String name) throws IOException {
        try (var input = SubmissionTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
