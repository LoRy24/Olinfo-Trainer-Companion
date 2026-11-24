package io.github.olinfolabs.trainer;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

record ProjectLink(Path root, String task, String language, String source) {
    static final Pattern TASK_ID = Pattern.compile("(?:terry/)?[A-Za-z0-9][A-Za-z0-9_-]{0,119}");
    private static final Map<String, Set<String>> EXTENSIONS = Map.of(
            "cpp", Set.of("cpp", "cc", "cxx"), "c", Set.of("c"), "py", Set.of("py"),
            "java", Set.of("java"), "pas", Set.of("pas", "pp"));
    private static final Pattern GRADER = Pattern.compile("(?:grader|stub)(?:[._-].*)?", Pattern.CASE_INSENSITIVE);
    private static final int MAX_SOURCE = 100_000;

    static boolean isTerry(String task) { return task.startsWith("terry/"); }

    static String shortTaskId(String task) { return isTerry(task) ? task.substring(6) : task; }

    static byte[] document(String task, String language, String source) throws IOException {
        validate(task, language, source);
        String attribute = source == null ? "" : " source=\"" + source + "\"";
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<olinfo version=\"1\" task=\"" + task
                + "\" language=\"" + language + "\"" + attribute + "/>\n").getBytes(StandardCharsets.UTF_8);
    }

    static ProjectLink read(Path root) throws IOException {
        Path file = root.resolve("olinfo.xml");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8192) {
            throw new IOException("olinfo.xml non valido.");
        }
        var xml = Jsoup.parse(Files.readString(file), "", Parser.xmlParser());
        var entry = xml.children().first();
        if (xml.childrenSize() != 1 || entry == null || !entry.tagName().equals("olinfo") || !entry.attr("version").equals("1")) {
            throw new IOException("olinfo.xml non valido.");
        }
        String task = entry.attr("task");
        String language = entry.attr("language");
        String source = entry.hasAttr("source") ? entry.attr("source") : null;
        validate(task, language, source);
        return new ProjectLink(root, task, language, source);
    }

    byte[] readSource() throws IOException {
        if (source == null) throw new IOException("Questo problema richiede un invio di soli output dal sito.");
        Path realRoot = root.toRealPath();
        Path file = realRoot.resolve(source);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file)) {
            throw new IOException("Scegli un sorgente effettivo nella cartella del progetto.");
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_SOURCE + 1);
            if (bytes.length == 0 || bytes.length > MAX_SOURCE) throw new IOException("Il sorgente deve contenere da 1 a 100.000 byte.");
            return bytes;
        }
    }

    private static void validate(String task, String language, String source) throws IOException {
        if (!TASK_ID.matcher(task).matches() || !(EXTENSIONS.containsKey(language)
                || (language.equals("output") && !isTerry(task)))) {
            throw new IOException("Problema o linguaggio non valido in olinfo.xml.");
        }
        if (language.equals("output") && source == null) return;
        int dot = source == null ? -1 : source.lastIndexOf('.');
        if (dot < 0 || !TrainingClient.SAFE_FILE.matcher(source).matches() || GRADER.matcher(source).matches()
                || !EXTENSIONS.getOrDefault(language, Set.of()).contains(source.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            throw new IOException("Indica il file della soluzione in olinfo.xml.");
        }
    }
}
