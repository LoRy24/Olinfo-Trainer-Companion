package io.github.olinfolabs.trainer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

final class SubmissionClient {
    private static final Pattern COOKIE = Pattern.compile("(?:__Secure-)?better-auth\\.session_token=[A-Za-z0-9._%+~/=\\-]+");
    private static final Pattern ACTION = Pattern.compile("createServerReference[^\\w\"]{0,12}\"([a-f0-9]{40,64})\"[^;]{0,180}?\"loginPassword\"");
    private static final Pattern FLIGHT = Pattern.compile("self\\.__next_f\\.push\\(\\[1,(\"(?:\\\\.|[^\"\\\\])*\")\\]\\)");
    private static final Pattern FIELDS = Pattern.compile("\"submissionFormat\"\\s*:\\s*(\\[[^\\]]*])");
    private static final Pattern LANGUAGES = Pattern.compile("\"languages\"\\s*:\\s*(\\{[^{}]*})");
    private static final Pattern GUIDELINES = Pattern.compile("\"guidelinesAccepted\"\\s*:\\s*true\\b");
    private static final Pattern GRADE_COLOR = Pattern.compile("\\bbg-(red|orange|yellow|lime|green)-400\\b");
    private static final Gson GSON = new Gson();
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    // Training returns compiled Lingui descriptors with an id and no message.
    private static final Map<String, String> SUBMISSION_ERRORS = Map.of(
            "0RqsvY", "Hai inviato troppe soluzioni. Riprova tra un minuto.",
            "9EnL6q", "Training Olinfo richiede un linguaggio per questo problema.",
            "GZ0ShV", "Training Olinfo non è riuscito a registrare l’invio.",
            "M9KQCp", "Training Olinfo non accetta questo linguaggio per il problema.",
            "ZO9-51", "Training Olinfo non ha trovato il problema.",
            "Zw8V2_", "Training Olinfo non autorizza l’invio con questo account. Controlla la registrazione sul sito.",
            "b6ffpb", "Training Olinfo non è riuscito a salvare il sorgente.",
            "v16cFz", "Il file supera il limite di Training Olinfo.",
            "x-JU4k", "Training Olinfo non ha ricevuto il sorgente richiesto.");

    record Row(String id, String outcome, Integer score, String color, boolean pending) {}
    record Results(List<Row> rows, Integer best) {
        String scoreLabel() { return rows.isEmpty() ? "0 su 0" : best == null ? "—" : best + " pt"; }
    }
    record Options(String field, String compiler) {}

    static final class LoginRequired extends IOException {
        LoginRequired() { super("Accedi a Training Olinfo per continuare."); }
    }

    record Response(int status, Map<String, List<String>> headers, String text) {
        List<String> header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).toList();
        }
        String first(String name) { return header(name).stream().findFirst().orElse(""); }
        boolean ok() { return status >= 200 && status < 300; }
        boolean loginRequired() {
            return status == 401 || (status / 100 == 3 && first("location").matches("/login(?:[?/].*)?"));
        }
    }

    @FunctionalInterface
    interface Transport {
        Response exchange(String location, String session, Map<String, String> headers, byte[] body) throws IOException;
    }

    private final Transport transport;

    SubmissionClient() { this(new HttpTransport()::exchange); }
    SubmissionClient(Transport transport) { this.transport = transport; }

    private static final class HttpTransport {
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        Response exchange(String location, String session, Map<String, String> headers, byte[] body) throws IOException {
            URI uri = URI.create(TrainingClient.ORIGIN + location);
            if (!location.startsWith("/") || location.startsWith("//") || location.contains("\\")
                    || !TrainingClient.ORIGIN.equals(uri.getScheme() + "://" + uri.getAuthority())) {
                throw new IOException("Indirizzo Training Olinfo non valido.");
            }
            if (session != null && !COOKIE.matcher(session).matches()) throw new LoginRequired();
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(18))
                    .header("Accept-Language", "it-IT").header("Cache-Control", "no-cache")
                    .header("Origin", TrainingClient.ORIGIN)
                    .header("Cookie", session == null ? "lang=it-IT" : "lang=it-IT; " + session);
            headers.forEach(builder::header);
            var request = body == null ? builder.GET().build() : builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            try {
                var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var stream = response.body()) {
                    byte[] bytes = stream.readNBytes(MAX_RESPONSE + 1);
                    if (bytes.length > MAX_RESPONSE) throw new IOException("Risposta troppo grande.");
                    return new Response(response.statusCode(), response.headers().map(), new String(bytes, StandardCharsets.UTF_8));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Richiesta interrotta.", exception);
            }
        }
    }

    String login(String username, String password) throws IOException {
        String action = null;
        var scripts = Jsoup.parse(page("/login", null)).select("script[src]").eachAttr("src");
        for (String script : scripts.stream().distinct()
                .filter(src -> src.matches("/_next/static/chunks/[A-Za-z0-9_~.-]+\\.js")).limit(24).toList()) {
            String content;
            try { content = page(script, null); } catch (IOException exception) { continue; }
            var matcher = ACTION.matcher(content);
            if (matcher.find()) {
                action = matcher.group(1);
                break;
            }
        }
        if (action == null) throw new IOException("Il modulo di accesso di Training Olinfo è cambiato.");
        var response = transport.exchange("/login", null,
                Map.of("Next-Action", action, "Content-Type", "text/plain;charset=UTF-8", "Accept", "text/x-component"),
                GSON.toJson(List.of(flightArgument(username), flightArgument(password))).getBytes(StandardCharsets.UTF_8));
        if (response.first("x-action-redirect").startsWith("/signup") || response.first("location").startsWith("/signup")) {
            throw new IOException("Completa la registrazione sul sito di Training Olinfo.");
        }
        if (response.ok()) {
            for (String line : response.header("set-cookie")) {
                String cookie = line.split(";", 2)[0];
                if (COOKIE.matcher(cookie).matches()) return cookie;
            }
        }
        throw new IOException("Accesso non riuscito. Verifica username o email e password.");
    }

    Results results(String task, String session) throws IOException {
        if (session == null) throw new LoginRequired();
        return submissionList(page(pathFor(task, "submissions"), session), task);
    }

    String submit(ProjectLink link, byte[] source, String session) throws IOException {
        if (session == null) throw new LoginRequired();
        String formPath = pathFor(link.task(), "submit");
        Options options = submitOptions(page(formPath, session), link.language());
        String boundary = "olinfo-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + options.field()
                + "\"; filename=\"" + link.source() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(source);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        String query = "task=" + encode(link.task()) + "&language=" + encode(options.compiler());
        Response response;
        try {
            response = transport.exchange("/files/submit?" + query, session,
                    Map.of("Content-Type", "multipart/form-data; boundary=" + boundary,
                            "Accept", "application/json", "Referer", TrainingClient.ORIGIN + formPath), body.toByteArray());
        } catch (LoginRequired exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Esito dell’invio incerto. Controlla gli invii sul sito prima di riprovare.", exception);
        }
        if (response.loginRequired()) throw new LoginRequired();
        try {
            if (JsonParser.parseString(response.text()) instanceof JsonObject data) {
                if (data.get("error") instanceof JsonObject error) {
                    if (error.get("id") instanceof com.google.gson.JsonPrimitive descriptor) {
                        String code = descriptor.getAsString();
                        if (code.equals("RAHXa3")) throw new LoginRequired();
                        String reason = SUBMISSION_ERRORS.get(code);
                        if (reason != null) throw new IOException(reason);
                    }
                    if (error.get("message") instanceof com.google.gson.JsonPrimitive message && message.isString()) {
                        String value = message.getAsString();
                        throw new IOException(value.substring(0, Math.min(200, value.length())));
                    }
                }
                var id = data.get("submissionId");
                if (response.ok() && id != null && id.isJsonPrimitive() && id.getAsString().matches("[1-9]\\d*")) {
                    return id.getAsString();
                }
            }
        } catch (com.google.gson.JsonParseException ignored) {
            // A gateway may respond with HTML.
        }
        if (!response.ok()) throw new IOException("Training Olinfo ha risposto con HTTP " + response.status()
                + ". Controlla gli invii prima di riprovare.");
        throw new IOException("Training Olinfo ha rifiutato l’invio. Controlla account e limiti sul sito.");
    }

    static String pathFor(String task, String suffix) throws IOException {
        if (!ProjectLink.TASK_ID.matcher(task).matches()) throw new IOException("Identificativo del problema non valido.");
        return "/task/" + task + "/" + suffix;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static String flightArgument(String value) {
        return value.startsWith("$") ? "$" + value : value;
    }

    String page(String location, String session) throws IOException {
        var response = transport.exchange(location, session, Map.of(), null);
        if (response.loginRequired()) throw new LoginRequired();
        if (!response.ok()) throw new IOException("Training Olinfo ha risposto con HTTP " + response.status() + ".");
        return response.text();
    }

    static Options submitOptions(String html, String language) throws IOException {
        var document = Jsoup.parse(html);
        if (document.body().text().contains("Accedi per inviare soluzioni")) throw new LoginRequired();
        var data = new StringBuilder();
        for (var script : document.select("script")) {
            var matcher = FLIGHT.matcher(script.data());
            while (matcher.find()) data.append(GSON.fromJson(matcher.group(1), String.class));
        }
        var fields = FIELDS.matcher(data);
        var languages = LANGUAGES.matcher(data);
        if (!fields.find() || !languages.find()) throw new IOException("Il modulo di invio non è disponibile sul sito per questo problema.");
        if (!GUIDELINES.matcher(data).find()) {
            throw new IOException("Accetta le linee guida su training.olinfo.it/guidelines prima del primo invio.");
        }
        var names = JsonParser.parseString(fields.group(1)).getAsJsonArray();
        if (names.size() != 1 || !names.get(0).getAsString().matches("[\\w.%+-]+")) {
            throw new IOException("Apri il problema sul sito per inviare i file richiesti.");
        }
        String code = language.equals("pas") ? "pascal" : language;
        String compiler = JsonParser.parseString(languages.group(1)).getAsJsonObject().entrySet().stream()
                .filter(entry -> entry.getValue().getAsString().equals(code))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IOException("Training Olinfo non accetta questo linguaggio per il problema."));
        return new Options(names.get(0).getAsString(), compiler);
    }

    static Results submissionList(String html, String task) throws IOException {
        var document = Jsoup.parse(html);
        String prefix = pathFor(task, "submissions/");
        List<Row> rows = new ArrayList<>();
        Integer best = null;
        for (var anchor : document.select("li > a[href]")) {
            String href = anchor.attr("href");
            if (!href.startsWith(prefix) || anchor.children().isEmpty()) continue;
            String id = href.substring(prefix.length());
            if (!id.matches("[1-9]\\d*")) continue;
            var cell = anchor.children().last();
            String outcome = cell.text().strip();
            if (outcome.isEmpty()) continue;
            Integer score = outcome.matches("\\d{1,9}") ? Integer.parseInt(outcome) : null;
            var badge = cell.selectFirst("span");
            var grade = GRADE_COLOR.matcher(badge == null ? "" : badge.className());
            rows.add(new Row(id, outcome, score, grade.find() ? grade.group(1) : null,
                    outcome.toLowerCase(java.util.Locale.ROOT).contains("in corso")));
            if (score != null && (best == null || score > best)) best = score;
        }
        if (rows.isEmpty()) {
            String text = document.body().text();
            if (text.contains("Accedi per vedere le tue sottoposizioni")) throw new LoginRequired();
            if (!text.contains("Nessuna sottoposizione inviata")) throw new IOException("Formato degli invii di Training Olinfo imprevisto.");
        }
        return new Results(List.copyOf(rows), best);
    }
}
