package io.github.olinfolabs.trainer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class AccountData {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_.]{3,39}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE = Pattern.compile("/ranking/(\\d{1,7})(?:\\?.*)?");
    private static final Set<String> AVATAR_HOSTS = Set.of("training.olinfo.it", "www.gravatar.com", "secure.gravatar.com",
            "gravatar.com", "avatars.githubusercontent.com", "lh3.googleusercontent.com");
    private static final List<String> GRADES = List.of("red", "orange", "yellow", "lime", "green");

    record Profile(int points, int solved, URI avatar) {}
    private record Standing(String user, int rank, int score) {}
    private record Rankings(List<Standing> rows, int lastPage) {}

    @FunctionalInterface
    interface Pages { String get(String location) throws IOException; }

    private AccountData() {}

    static String username(String html) {
        var document = Jsoup.parse(html);
        for (Element link : document.select(".navbar a[href^=/user/], [class*=_navbar] a[href^=/user/], nav a[href^=/user/]")) {
            String candidate = link.attr("href").substring("/user/".length());
            if (link.text().equals("Profilo") && USERNAME.matcher(candidate).matches()) return candidate;
        }
        return null;
    }

    static Profile profile(String html) throws IOException {
        var document = Jsoup.parse(html);
        Integer points = null;
        Integer solved = null;
        for (var metric : document.select(".stat")) {
            String title = metric.select(".stat-title").text();
            String value = metric.select(".stat-value").text();
            if (!value.matches("\\d{1,9}")) continue;
            if (title.equals("Punti")) points = Integer.parseInt(value);
            if (title.equals("Problemi risolti")) solved = Integer.parseInt(value);
        }
        if (points == null || solved == null) throw new IOException("Statistiche profilo Olinfo non disponibili.");
        var photo = document.selectFirst("img[alt^='Foto profilo di ']");
        URI avatar = null;
        if (photo != null && photo.hasAttr("src")) {
            try {
                URI url = URI.create(TrainingClient.ORIGIN).resolve(photo.attr("src"));
                if ("https".equals(url.getScheme()) && url.getUserInfo() == null && url.getPort() == -1
                        && url.getHost() != null && AVATAR_HOSTS.contains(url.getHost())) avatar = url;
            } catch (IllegalArgumentException ignored) {
                // The account card can display initials.
            }
        }
        return new Profile(points, solved, avatar);
    }

    private static Rankings ranking(String html) {
        var document = Jsoup.parse(html);
        List<Standing> rows = new ArrayList<>();
        for (var link : document.select("li > a[href^=/user/]")) {
            String user = link.attr("href").substring("/user/".length());
            if (!USERNAME.matcher(user).matches() || link.childrenSize() < 2 || link.child(0).childrenSize() == 0) continue;
            String rank = link.child(0).child(0).text();
            String score = link.children().last().text();
            if (rank.matches("\\d{1,9}") && score.matches("\\d{1,9}")) {
                rows.add(new Standing(user, Integer.parseInt(rank), Integer.parseInt(score)));
            }
        }
        int last = 1;
        for (String url : document.select("a[href^=/ranking/]").eachAttr("href")) {
            var match = PAGE.matcher(url);
            if (match.matches()) last = Math.max(last, Integer.parseInt(match.group(1)));
        }
        return new Rankings(rows, last);
    }

    static Integer rank(Pages pages, String username, int points) throws IOException {
        if (points == 0) return null;
        var first = ranking(pages.get("/ranking/1"));
        if (first.rows().isEmpty()) return null;
        int lo = 1;
        int hi = first.lastPage();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            var rows = mid == 1 ? first.rows() : ranking(pages.get("/ranking/" + mid)).rows();
            Integer rank = matchingRank(rows, username);
            if (rank != null) return rank;
            if (!rows.isEmpty() && rows.getLast().score() > points) lo = mid + 1;
            else hi = mid;
        }
        for (int page = lo; page <= Math.min(first.lastPage(), lo + 31); page++) {
            var rows = page == 1 ? first.rows() : ranking(pages.get("/ranking/" + page)).rows();
            Integer rank = matchingRank(rows, username);
            if (rank != null) return rank;
            if (rows.isEmpty() || rows.getLast().score() < points) break;
        }
        return null;
    }

    private static Integer matchingRank(List<Standing> rows, String username) {
        for (var row : rows) if (row.user().equalsIgnoreCase(username)) return row.rank();
        return null;
    }

    static String scoreColor(Integer score, String reported) {
        if (reported != null && GRADES.contains(reported)) return reported;
        return score == null ? "" : GRADES.get(Math.min(4, Math.max(0, score) / 25));
    }

    static Color color(String grade) {
        return switch (grade) {
            case "red" -> new Color(0xf87171);
            case "orange" -> new Color(0xfb923c);
            case "yellow" -> new Color(0xfacc15);
            case "lime" -> new Color(0xa3e635);
            case "green" -> new Color(0x4ade80);
            default -> null;
        };
    }

    static BufferedImage photo(URI avatar) {
        if (avatar == null) return null;
        try {
            var connection = (HttpURLConnection) avatar.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(3500);
            try {
                if (connection.getResponseCode() != 200) return null;
                try (var stream = connection.getInputStream()) {
                    byte[] bytes = stream.readNBytes(500_001);
                    if (bytes.length > 500_000) return null;
                    try (var image = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                        if (image == null) return null;
                        var readers = ImageIO.getImageReaders(image);
                        if (!readers.hasNext()) return null;
                        var reader = readers.next();
                        try {
                            reader.setInput(image);
                            if (reader.getWidth(0) > 1024 || reader.getHeight(0) > 1024) return null;
                            return reader.read(0);
                        } finally {
                            reader.dispose();
                        }
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}
