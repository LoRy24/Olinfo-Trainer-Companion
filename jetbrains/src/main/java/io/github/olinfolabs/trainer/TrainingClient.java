package io.github.olinfolabs.trainer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

final class TrainingClient {
    private static final Properties CONFIG = new Properties();

    static {
        try (var reader = new InputStreamReader(
                TrainingClient.class.getResourceAsStream("/catalog.properties"), StandardCharsets.UTF_8)) {
            CONFIG.load(reader);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    static final String ORIGIN = CONFIG.getProperty("origin");
    private static final List<String> LOCALES = List.of(CONFIG.getProperty("pdf.locales").split(","));
    private static final List<String> LANGUAGES = List.of(CONFIG.getProperty("code.languages").split(","));
    private static final Pattern FILE_PATH = Pattern.compile("/files/[a-f0-9]{40}/[^/?#]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATEMENT = Pattern.compile("/files/[a-f0-9]{40}/testo\\.pdf(?=[\"'\\\\#\\s]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GRADER = Pattern.compile("(?:grader|stub)(?:[._-][a-z0-9]+)?\\.[a-z0-9]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER = Pattern.compile("[a-z0-9_][a-z0-9_.-]*\\.(?:h|hh|hpp|hxx)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAMPLE = Pattern.compile("(.*?)(?:^|[._-])(input|output)(?:[._-]?(\\d+))?\\.txt", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_SAMPLE = Pattern.compile("(.*?)(?:[._-]?(\\d+))?\\.(in|out)", Pattern.CASE_INSENSITIVE);
    static final Pattern SAFE_FILE = Pattern.compile("[a-z0-9_][a-z0-9_.-]*", Pattern.CASE_INSENSITIVE);
    private static final int ASSET_LIMIT = 4 * 1024 * 1024;
    private static final int ARCHIVE_LIMIT = 24 * 1024 * 1024;
    private static final int PDF_LIMIT = 32 * 1024 * 1024;

    record Task(String id, String title) {
        @Override public String toString() { return title + " · " + id; }
    }
    record SearchPage(List<Task> tasks, boolean hasNext) {}
    record Asset(String name, String url, String group, byte[] data) {}
    record Selection(String id, String label) {
        @Override public String toString() { return label; }
    }
    record Language(Selection choice, Asset grader, Asset starter) {}
    record TaskPage(String title, String mode, List<Selection> pdf, List<Asset> attachments) {}
    record Details(String id, String title, String mode, List<Selection> pdf,
                   List<Language> languages, List<Asset> assets, List<String> notes, String defaultPdf, byte[] statement) {}

    @FunctionalInterface
    interface Request {
        byte[] read(String path, int limit, String locale) throws IOException;
    }

    private final Request request;

    TrainingClient() { this(TrainingClient::download); }

    TrainingClient(Request request) { this.request = request; }

    static String languageLabel(String id) { return CONFIG.getProperty("code." + id, id); }

    static byte[] download(String path, int limit, String locale) throws IOException {
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('\\') >= 0) {
            throw new IOException("Indirizzo di download non valido.");
        }
        var connection = (HttpURLConnection) URI.create(ORIGIN + path.replace(" ", "%20")).toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(25_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept-Language", locale);
        connection.setRequestProperty("Cookie", "lang=" + locale);
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Training Olinfo ha risposto con HTTP " + status + ".");
            if (connection.getContentLengthLong() > limit) throw new IOException("File troppo grande.");
            try (var stream = connection.getInputStream()) {
                byte[] data = stream.readNBytes(limit + 1);
                if (data.length > limit) throw new IOException("File troppo grande.");
                return data;
            }
        } finally {
            connection.disconnect();
        }
    }

    SearchPage search(String query, int page) throws IOException {
        return search(query, page, false);
    }

    SearchPage search(String query, int page, boolean regional) throws IOException {
        String text = query.strip();
        String location = "/tasks/" + (regional ? "terry/" : "") + page
                + "?search=" + URLEncoder.encode(regional ? ProjectLink.shortTaskId(text) : text, StandardCharsets.UTF_8);
        return searchPage(html(location, "it-IT"), page, regional);
    }

    static SearchPage searchPage(String html, int page) {
        return searchPage(html, page, false);
    }

    static SearchPage searchPage(String html, int page, boolean regional) {
        Map<String, Task> tasks = new LinkedHashMap<>();
        boolean hasNext = false;
        String next = "/tasks/" + (regional ? "terry/" : "") + (page + 1);
        for (Element link : Jsoup.parse(html).select("a[href]")) {
            String url = link.attr("href");
            String id = url.startsWith("/task/") ? url.substring(6) : "";
            if (ProjectLink.TASK_ID.matcher(id).matches() && ProjectLink.isTerry(id) == regional && !link.text().isBlank()) {
                tasks.put(id, new Task(id, link.text()));
            }
            if (url.equals(next) || url.startsWith(next + "?")) hasNext = true;
        }
        return new SearchPage(List.copyOf(tasks.values()), hasNext);
    }

    static TaskPage taskPage(String html) throws IOException {
        return taskPage(html, "");
    }

    static TaskPage taskPage(String html, String id) throws IOException {
        var document = Jsoup.parse(html);
        var header = document.selectFirst("header:has(h1)");
        if (header == null) throw new IOException("La pagina del problema ha un formato imprevisto.");
        String title = header.select("h1").text();
        if (ProjectLink.isTerry(id) && !title.isBlank()) {
            String base = terryStatementBase(id);
            var templates = new ArrayList<Asset>();
            for (Element link : document.select("main a[download][href]")) {
                String url = link.attr("href");
                if (!url.startsWith(base)) continue;
                String name = url.substring(base.length());
                if (SAFE_FILE.matcher(name).matches()) templates.add(new Asset(name, url, "", null));
            }
            return new TaskPage(title, "terry", List.of(new Selection("original", "Originale (Markdown)")), templates);
        }
        String mode = header.select("span").stream().filter(span -> span.text().equals("Input/output:"))
                .findFirst().map(span -> span.parent().text().substring("Input/output:".length()).strip()).orElse("");
        if (title.isBlank() || !Set.of("grader", "output-only", "file", "stdin / stdout").contains(mode)) {
            throw new IOException("La pagina del problema ha un formato imprevisto.");
        }
        Set<String> flags = new LinkedHashSet<>(header.select("svg title").eachText());
        var pdf = LOCALES.stream().filter(locale -> flags.contains(CONFIG.getProperty("pdf." + locale)))
                .map(locale -> new Selection(locale, CONFIG.getProperty("pdf." + locale))).toList();
        Map<String, Asset> attachments = new LinkedHashMap<>();
        for (Element link : document.select("a[download][href]")) {
            String url = link.attr("href");
            String name = link.text();
            if (FILE_PATH.matcher(url).matches() && !name.isBlank() && !name.matches(".*[\\\\/\\p{Cntrl}].*")) {
                attachments.put(name, new Asset(name, url, "", null));
            }
        }
        return new TaskPage(title, mode, pdf, List.copyOf(attachments.values()));
    }

    static String statementPath(String html) throws IOException {
        var parsed = Jsoup.parse(html);
        var object = parsed.selectFirst("main object[data], main iframe[src]");
        if (object != null) {
            String url = object.attr(object.hasAttr("data") ? "data" : "src").split("#", 2)[0];
            if (url.matches("/files/[a-fA-F0-9]{40}/testo\\.pdf")) return url;
        }
        // The public page may render the PDF client-side. Its URL is in the server-rendered data.
        Set<String> urls = new LinkedHashSet<>();
        for (Element script : parsed.select("script")) {
            var matcher = STATEMENT.matcher(script.data());
            while (matcher.find()) urls.add(matcher.group());
        }
        if (urls.size() != 1) throw new IOException("Il PDF del problema non è disponibile.");
        return urls.iterator().next();
    }

    Details details(String id) throws IOException {
        String url = "/task/" + id;
        if (!ProjectLink.TASK_ID.matcher(id).matches()) throw new IOException("Identificativo del problema non valido.");
        String html = html(url, "it-IT");
        TaskPage page = taskPage(html, id);
        if (ProjectLink.isTerry(id)) {
            String base = terryStatementBase(id);
            String markdown = html(base + "statement.md", "it-IT");
            List<Asset> examples = new ArrayList<>();
            for (String kind : List.of("input", "output")) {
                var example = Pattern.compile("^\\*\\*" + kind + ":\\*\\*[^\\r\\n]*\\r?\\n"
                                + "(?:[ \\t]*\\r?\\n)*[ \\t]*`{3}[^\\r\\n]*\\r?\\n(.*?)^[ \\t]*`{3}",
                        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL).matcher(markdown);
                if (example.find()) examples.add(new Asset(kind + ".txt", null, "", example.group(1).getBytes(StandardCharsets.UTF_8)));
            }
            var statement = markdown.replaceAll("(\\]\\()([\\w][\\w.-]*(?:/[\\w][\\w.-]*)*)(?=[\\s)])", "$1" + ORIGIN + base + "$2");
            var options = LANGUAGES.stream().map(language -> new Language(new Selection(language, languageLabel(language)),
                    null, page.attachments().stream().filter(file -> codeLanguage(file.name()).equals(language)).findFirst().orElse(null)))
                    .toList();
            return new Details(id, page.title(), page.mode(), page.pdf(), options, examples, List.of(), null,
                    statement.getBytes(StandardCharsets.UTF_8));
        }
        List<Asset> files = new ArrayList<>(page.attachments());
        List<String> notes = new ArrayList<>();
        List<Asset> archives = files.stream().filter(asset -> asset.name().toLowerCase(Locale.ROOT).endsWith(".zip")).toList();
        for (Asset archive : archives.stream().limit(6).toList()) {
            try {
                files.addAll(unpack(request.read(archive.url(), ARCHIVE_LIMIT, "it-IT"), archive.name()));
            } catch (IOException | IllegalArgumentException exception) {
                notes.add("Archivio non disponibile o oltre il limite di importazione: " + archive.name());
            }
        }
        if (archives.size() > 6) notes.add("Sono stati consultati i primi sei archivi del problema.");
        List<Language> options = new ArrayList<>();
        if (page.mode().equals("output-only")) {
            options.add(new Language(new Selection("output", "Solo output"), null, null));
        } else {
            for (String language : LANGUAGES) {
                Asset grader = files.stream()
                        .filter(file -> isGrader(file.name()) && codeLanguage(file.name()).equals(language))
                        .findFirst().orElse(null);
                Asset starter = starterFor(files, id, language);
                if (!page.mode().equals("grader") || grader != null || starter != null) {
                    options.add(new Language(new Selection(language, languageLabel(language)), grader, starter));
                }
            }
        }
        String defaultPdf = null;
        try {
            defaultPdf = statementPath(html);
        } catch (IOException ignored) {
            // Check the selected translation on creation.
        }
        List<Selection> pdf = new ArrayList<>(page.pdf());
        if (pdf.isEmpty() && defaultPdf != null) pdf.add(new Selection("original", "Originale"));
        return new Details(id, page.title(), page.mode(), pdf, options, files, notes, defaultPdf, null);
    }

    private static String terryStatementBase(String id) {
        return "/files/statements/secret/" + ProjectLink.shortTaskId(id) + "/";
    }

    byte[] pdf(Details details, String locale) throws IOException {
        if (details.pdf().stream().noneMatch(item -> item.id().equals(locale))) {
            throw new IOException("Traduzione non disponibile.");
        }
        String url = details.defaultPdf();
        if (url == null || !Set.of("it-IT", "original").contains(locale)) {
            String page = html("/task/" + details.id(), locale);
            if (!locale.equals("original") && !Jsoup.parse(page).select("html").attr("lang").equals(locale)) {
                throw new IOException("Training Olinfo non ha restituito la lingua richiesta.");
            }
            url = statementPath(page);
        }
        byte[] bytes = request.read(url, PDF_LIMIT, "it-IT");
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new IOException("Il testo scaricato non è un PDF.");
        }
        return bytes;
    }

    byte[] asset(Asset file) throws IOException {
        if (file == null) return new byte[0];
        return file.data() != null ? file.data() : request.read(file.url(), ASSET_LIMIT, "it-IT");
    }

    private String html(String path, String locale) throws IOException {
        return new String(request.read(path, ASSET_LIMIT, locale), StandardCharsets.UTF_8);
    }

    private static List<Asset> unpack(byte[] bytes, String archiveName) throws IOException {
        var temporary = Files.createTempFile("olinfo-attachments-", ".zip");
        try {
            Files.write(temporary, bytes);
            try (var zip = new ZipFile(temporary.toFile())) {
                List<Asset> assets = new ArrayList<>();
                int expanded = 0;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || entry.getName().contains("__MACOSX/")) continue;
                    int separator = entry.getName().lastIndexOf('/');
                    String name = entry.getName().substring(separator + 1);
                    if (!SAFE_FILE.matcher(name).matches() || entry.getSize() > ASSET_LIMIT
                            || (!LANGUAGES.contains(codeLanguage(name)) && !isHeader(name) && sampleInfo(name) == null)) continue;
                    String parent = separator < 0 ? "" : entry.getName().substring(0, separator);
                    String group = archiveName + "/" + parent.replaceFirst("(?i)(?:^|/)(?:input|output)s?$", "");
                    try (var stream = zip.getInputStream(entry)) {
                        byte[] data = stream.readNBytes(ASSET_LIMIT + 1);
                        if (data.length > ASSET_LIMIT) continue;
                        expanded += data.length;
                        if (expanded > 12 * 1024 * 1024) throw new IOException("Archivio troppo grande.");
                        assets.add(new Asset(name, null, group, data));
                    }
                }
                return assets;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static boolean isHeader(String name) { return HEADER.matcher(name).matches(); }

    private static boolean isGrader(String name) { return GRADER.matcher(name).matches(); }

    private static String codeLanguage(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "cc", "cxx" -> "cpp";
            case "pp" -> "pas";
            case "py2", "py3" -> "py";
            default -> extension;
        };
    }

    private static Asset starterFor(List<Asset> files, String id, String language) {
        String shortId = id.replaceFirst("(?i)^(?:(?:oii|ois|ioi|weoi|ceoi|eioi|egoi|itoi|sortilegio)_)+", "");
        List<Asset> candidates = files.stream()
                .filter(file -> codeLanguage(file.name()).equals(language) && !isGrader(file.name())).toList();
        for (String alias : List.of(shortId, id, "template", "starter", "skeleton", "file", "main")) {
            for (Asset file : candidates) {
                String stem = file.name().substring(0, file.name().lastIndexOf('.'));
                if (stem.equalsIgnoreCase(alias)) return file;
            }
        }
        return null;
    }

    private record Sample(String key, boolean input) {}
    record Examples(Asset input, Asset output) {}

    private static Sample sampleInfo(String name) {
        var match = SAMPLE.matcher(name);
        if (match.matches()) return new Sample(sampleKey(match.group(1), match.group(3)), match.group(2).equalsIgnoreCase("input"));
        match = SHORT_SAMPLE.matcher(name);
        if (match.matches()) return new Sample(sampleKey(match.group(1), match.group(2)), match.group(3).equalsIgnoreCase("in"));
        return null;
    }

    private static String sampleKey(String prefix, String index) {
        return prefix.toLowerCase(Locale.ROOT) + ":" + (index == null ? "0" : index);
    }

    static Examples samplePair(List<Asset> files) {
        Map<String, Asset[]> pairs = new LinkedHashMap<>();
        var ordered = files.stream().sorted((a, b) -> naturalKey(a.name()).compareTo(naturalKey(b.name()))).toList();
        for (Asset file : ordered) {
            Sample sample = sampleInfo(file.name());
            if (sample == null) continue;
            var pair = pairs.computeIfAbsent(file.group() + "|" + sample.key(), ignored -> new Asset[2]);
            int index = sample.input() ? 0 : 1;
            if (pair[index] == null) pair[index] = file;
        }
        for (var pair : pairs.values()) {
            if (pair[0] != null && pair[1] != null) return new Examples(pair[0], pair[1]);
        }
        return new Examples(null, null);
    }

    private static String naturalKey(String name) {
        return Pattern.compile("\\d+").matcher(name.toLowerCase(Locale.ROOT))
                .replaceAll(match -> "0".repeat(Math.max(0, 12 - match.group().length())) + match.group());
    }
}
