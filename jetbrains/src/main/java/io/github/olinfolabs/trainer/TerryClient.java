package io.github.olinfolabs.trainer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

final class TerryClient {
    private static final Gson GSON = new Gson();
    private static final Pattern ACTION = Pattern.compile(
            "createServerReference[^\\w\"]{0,12}\"([a-f0-9]{40,64})\"[^;]{0,180}?\"(requestInput|changeInput|uploadAndSubmit)\"");
    private static final Pattern INPUT_PATH = Pattern.compile("input/(?:[\\w.-]+/)*[\\w.-]+\\.txt", Pattern.CASE_INSENSITIVE);
    private static final Pattern CASE = Pattern.compile("Case #(\\d{1,9})");
    private static final Map<String, String> ERRORS = Map.of(
            "CGREVm", "L’input è scaduto. Avvia un nuovo invio per richiederne un altro.",
            "RDQh2-", "Questo input è già stato usato. Controlla gli invii e riprova.",
            "Zw8V2_", "Training Olinfo non autorizza l’invio con questo account. Controlla la registrazione sul sito.");
    private static final String UNSURE = "Esito dell’invio incerto. Controlla lo storico prima di riprovare.";
    private final SubmissionClient api;
    private final TrainingClient.Request download;
    private final Map<String, Map<String, String>> references = new HashMap<>();

    record FormInput(String id, String path, boolean expired) {}
    record Input(String id, byte[] bytes, String html) {}
    record TestCase(int number, String outcome, String detail) {}
    record Report(int score, int maxScore, List<TestCase> cases, List<String> notes) {
        String summary() {
            long correct = cases.stream().filter(item -> item.outcome().equals("Corretto")).count();
            return score + "/" + maxScore + " punti · "
                    + (cases.isEmpty() ? "Nessun caso valutato" : correct + "/" + cases.size() + " casi corretti");
        }
    }
    private record FilesForUpload(String name, byte[] source, byte[] output) {}

    TerryClient(SubmissionClient api, TrainingClient.Request download) {
        this.api = api;
        this.download = download;
    }

    static FormInput formInput(String html, String task) throws IOException {
        var document = Jsoup.parse(html);
        if (document.body().text().contains("Accedi per inviare soluzioni")) throw new SubmissionClient.LoginRequired();
        try {
            for (String row : SubmissionClient.flightData(document).toString().split("\n")) {
                String data = row.substring(row.indexOf(':') + 1);
                if (!data.startsWith("[\"$\"") || !data.contains("\"submissionTimeout\"")) continue;
                var value = JsonParser.parseString(data);
                if (!(value instanceof JsonArray array) || array.size() < 4
                        || !(array.get(3) instanceof JsonObject props) || !(props.get("task") instanceof JsonObject details)
                        || !string(details.get("name")).equals(ProjectLink.shortTaskId(task)) || !props.has("input")) continue;
                var input = props.get("input");
                if (input.isJsonNull() || string(input).equals("$undefined")) return null;
                if (!(input instanceof JsonObject fields)) break;
                String id = string(fields.get("id"));
                String path = string(fields.get("path")).replaceFirst("^/+", "");
                if (!SubmissionClient.TERRY_ID.matcher(id).matches() || !INPUT_PATH.matcher(path).matches()
                        || List.of(path.split("/")).stream().anyMatch(part -> part.equals(".") || part.equals(".."))) break;
                var date = Instant.parse(string(fields.get("date")).replaceFirst("^\\$D", ""));
                var timeout = details.get("submissionTimeout");
                long seconds = timeout == null || timeout.isJsonNull() ? 0 : timeout.getAsLong();
                return new FormInput(id, "/files/" + path, seconds > 0 && !Instant.now().isBefore(date.plusSeconds(seconds)));
            }
        } catch (JsonParseException | DateTimeParseException | IllegalStateException | NumberFormatException exception) {
            throw new IOException("Impossibile leggere il modulo Territoriali aggiornato di Training Olinfo.", exception);
        }
        throw new IOException("Impossibile leggere il modulo Territoriali aggiornato di Training Olinfo.");
    }

    static Report report(String html) throws IOException {
        var document = Jsoup.parse(html);
        var label = document.select("li > span").stream().filter(span -> span.text().equals("Esito:")).findFirst().orElse(null);
        var scoreCell = label == null ? null : label.nextElementSibling();
        var score = SubmissionClient.TERRY_SCORE.matcher(scoreCell == null ? "" : scoreCell.text());
        var cases = new ArrayList<TestCase>();
        for (var cell : document.select("div.font-mono")) {
            var match = CASE.matcher(cell.text());
            var outcome = cell.nextElementSibling();
            if (!match.matches() || outcome == null) continue;
            String status = outcome.text();
            if (!List.of("Corretto", "Errato", "Non inviato").contains(status)) {
                throw new IOException("Esito di un caso Territoriali in un formato imprevisto.");
            }
            var detail = outcome.nextElementSibling();
            cases.add(new TestCase(Integer.parseInt(match.group(1)), status, detail == null ? "" : detail.text()));
        }
        if (!score.matches()) throw new IOException("Dettagli della valutazione Territoriali non disponibili.");
        var notes = label.parent().parent().select("div.whitespace-pre-line > div").eachText();
        return new Report(Integer.parseInt(score.group(1)), Integer.parseInt(score.group(2)), List.copyOf(cases), notes);
    }

    Input input(String task, String session) throws IOException {
        if (session == null) throw new SubmissionClient.LoginRequired();
        String path = SubmissionClient.pathFor(task, "submit");
        String html = api.page(path, session);
        var input = formInput(html, task);
        if (input != null && input.expired()) {
            action(path, html, "changeInput", List.of(input.id()), session, null);
            html = api.page(path, session);
            input = formInput(html, task);
        }
        if (input == null) {
            action(path, html, "requestInput", List.of(ProjectLink.shortTaskId(task)), session, null);
            html = api.page(path, session);
            input = formInput(html, task);
        }
        if (input == null || input.expired()) throw new IOException("Training Olinfo non ha restituito un input utilizzabile.");
        byte[] bytes = download.read(input.path(), 64 * 1024 * 1024, "it-IT");
        if (bytes.length == 0) throw new IOException("Training Olinfo ha restituito un input vuoto.");
        return new Input(input.id(), bytes, html);
    }

    String submit(ProjectLink link, Input input, byte[] source, byte[] output, String session) throws IOException {
        if (session == null) throw new SubmissionClient.LoginRequired();
        return action(SubmissionClient.pathFor(link.task(), "submit"), input.html(), "uploadAndSubmit",
                List.of(ProjectLink.shortTaskId(link.task()), input.id()), session, new FilesForUpload(link.source(), source, output));
    }

    Report result(String task, String id, String session) throws IOException {
        if (session == null) throw new SubmissionClient.LoginRequired();
        if (!SubmissionClient.TERRY_ID.matcher(id).matches()) throw new IOException("Identificativo dell’invio non valido.");
        return report(api.page(SubmissionClient.pathFor(task, "submissions/" + id), session));
    }

    private String reference(String html, String name) throws IOException {
        for (String script : SubmissionClient.scripts(html).reversed()) {
            var found = references.get(script);
            if (found == null) {
                String chunk;
                try {
                    chunk = api.page(script, null);
                } catch (IOException exception) {
                    continue;
                }
                var matcher = ACTION.matcher(chunk);
                found = new HashMap<>();
                while (matcher.find()) found.put(matcher.group(2), matcher.group(1));
                if (references.size() >= 64) references.clear();
                references.put(script, found);
            }
            if (found.containsKey(name)) return found.get(name);
        }
        throw new IOException("Il modulo Territoriali di Training Olinfo è cambiato.");
    }

    private String action(String path, String html, String name, List<String> args, String session, FilesForUpload files) throws IOException {
        var headers = new HashMap<>(Map.of("Next-Action", reference(html, name), "Accept", "text/x-component",
                "Referer", TrainingClient.ORIGIN + path));
        byte[] body = GSON.toJson(args).getBytes(StandardCharsets.UTF_8);
        if (files == null) {
            headers.put("Content-Type", "text/plain;charset=UTF-8");
        } else {
            String boundary = "olinfo-" + UUID.randomUUID();
            headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
            var multipart = new ByteArrayOutputStream();
            part(multipart, boundary, "_1_file", files.name(), "application/octet-stream", files.source());
            part(multipart, boundary, "_2_file", "output.txt", "text/plain", files.output());
            var manifest = new ArrayList<>(args);
            manifest.addAll(List.of("$K1", "$K2"));
            part(multipart, boundary, "0", null, null, GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
            multipart.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            body = multipart.toByteArray();
            if (body.length > 1024 * 1024) {
                throw new IOException("Sorgente e output insieme superano il limite di 1 MiB dell’invio Territoriali.");
            }
        }
        SubmissionClient.Response response;
        try {
            response = api.exchange(path, session, headers, body);
        } catch (SubmissionClient.LoginRequired exception) {
            throw exception;
        } catch (IOException exception) {
            if (files == null) throw exception;
            throw new IOException(UNSURE, exception);
        }
        String redirect = response.first("x-action-redirect");
        if (redirect.isEmpty()) redirect = response.first("location");
        redirect = redirect.split(";", 2)[0];
        if (response.loginRequired() || redirect.matches("/login(?:[?/].*)?")) throw new SubmissionClient.LoginRequired();
        if (!response.first("x-nextjs-action-not-found").isEmpty()) throw new IOException("Training Olinfo è stato aggiornato. Riprova.");
        var value = actionValue(response.text());
        String code = value instanceof JsonObject object ? string(object.get("id")) : "";
        if (code.equals("RAHXa3")) throw new SubmissionClient.LoginRequired();
        if (ERRORS.containsKey(code)) throw new IOException(ERRORS.get(code));
        String message = value instanceof JsonObject object ? string(object.get("message")) : string(value);
        if (!message.isBlank() && !message.startsWith("$")) throw new IOException(message.substring(0, Math.min(message.length(), 500)));
        if (files != null) {
            String prefix = path.substring(0, path.length() - "submit".length()) + "submissions/";
            String id = redirect.startsWith(prefix) ? redirect.substring(prefix.length()) : "";
            if (SubmissionClient.TERRY_ID.matcher(id).matches()) return id;
            if (!code.isEmpty()) throw new IOException("Training Olinfo ha segnalato un errore durante l’invio. Controlla lo storico.");
            throw new IOException(UNSURE);
        }
        if (!response.ok() || (!code.isEmpty() && !code.equals("oYs_5j"))) {
            throw new IOException("Training Olinfo non è riuscito a preparare l’input.");
        }
        return null;
    }

    private static void part(ByteArrayOutputStream body, String boundary, String field, String filename, String type, byte[] bytes) {
        String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\""
                + (filename == null ? "" : "; filename=\"" + filename + "\"\r\nContent-Type: " + type) + "\r\n\r\n";
        body.writeBytes(header.getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static JsonElement actionValue(String text) {
        // The root Flight row refers to the promise holding the action result.
        var rows = new HashMap<String, String>();
        for (String line : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) rows.put(line.substring(0, colon), line.substring(colon + 1));
        }
        try {
            if (!rows.containsKey("0") || !(JsonParser.parseString(rows.get("0")) instanceof JsonObject root)) return JsonNull.INSTANCE;
            var value = root.get("a");
            String reference = string(value);
            if (reference.matches("\\$@[a-f0-9]+") && rows.containsKey(reference.substring(2))) {
                return JsonParser.parseString(rows.get(reference.substring(2)));
            }
            return value;
        } catch (JsonParseException exception) {
            return JsonNull.INSTANCE;
        }
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : "";
    }
}
