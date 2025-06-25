import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A utility to download images listed in a CSV file located at res/test.csv.
 * Respects robots.txt "Disallow" directives for the User-Agent.
 * Images are stored under `output/<ID>` directories.
 * Requires a CSV file with ID and URLs columns in the "res" directory.
 * Create a "res" directory if there is none.
 * Output directory will be auto created upon task completion.
 */

public class ImageDownloader {
    private static final String CSV_PATH = "res/input.csv";
    private static final String OUTPUT_BASE_DIR = "output";
    private static final long DEFAULT_ID_DELAY_MS = 3000;
    private static final long IMAGE_DELAY_MS = 1000;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; ImageDownloader/1.0; +http://example.com/bot)";
    private static final Map<String, List<String>> robotsCache = new HashMap<>();

    public static void main(String[] args) {
        long idDelayMs = DEFAULT_ID_DELAY_MS;
        if (args.length > 1) {
            System.err.println("Usage: java ImageDownloader [idDelayMillis]");
            System.exit(1);
        }
        if (args.length == 1) {
            try {
                idDelayMs = Long.parseLong(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid delay; using default.");
            }
        }

        List<String> allLines;
        try {
            allLines = Files.readAllLines(Paths.get(CSV_PATH));
        } catch (IOException e) {
            System.err.println("Error reading CSV file for counting: " + e.getMessage());
            return;
        }
        int totalIds = 0;
        for (int i = 1; i < allLines.size(); i++) {
            if (!allLines.get(i).trim().isEmpty()) {
                totalIds++;
            }
        }
        System.out.println("Total IDs to process: " + totalIds);

        try {
            Files.createDirectories(Paths.get(OUTPUT_BASE_DIR));
        } catch (IOException e) {
            System.err.println("Cannot create out dir: " + e.getMessage());
            System.exit(2);
        }

        int completedIds = 0;
        int totalRequests = 0;
        int totalFailures = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(CSV_PATH))) {
            String header = reader.readLine();
            if (header == null) {
                System.err.println("CSV empty.");
                System.exit(2);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", 2);
                String id = parts[0].trim();
                if (id.startsWith("\uFEFF")) id = id.substring(1);
                if (id.isEmpty() || id.equalsIgnoreCase("ID")) continue;

                String urlsPart = parts.length > 1 ? parts[1].trim() : "";
                if (urlsPart.startsWith("\"") && urlsPart.endsWith("\"")) {
                    urlsPart = urlsPart.substring(1, urlsPart.length() - 1);
                }
                if (urlsPart.isEmpty()) continue;

                Path outputDir = Paths.get(OUTPUT_BASE_DIR, id);
                try {
                    Files.createDirectories(outputDir);
                } catch (IOException e) {
                    continue;
                }

                for (String urlString : urlsPart.split(",")) {
                    urlString = urlString.trim();
                    if (urlString.isEmpty()) continue;
                    try {
                        totalRequests++;
                        URI uri = new URI(urlString);
                        String host = uri.getHost();
                        List<String> disallows = robotsCache.computeIfAbsent(host, ImageDownloader::fetchRobotsDisallows);
                        String path = uri.getPath();
                        boolean blocked = disallows.stream().anyMatch(path::startsWith);
                        if (blocked) {
                            System.err.println("Skipping per robots.txt: " + urlString);
                            continue;
                        }

                        URL url = uri.toURL();
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestProperty("User-Agent", USER_AGENT);
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);

                        int code = conn.getResponseCode();
                        if (code == 429) {
                            String retry = conn.getHeaderField("Retry-After");
                            long wait = retry != null ? Long.parseLong(retry) : 60;
                            System.err.println("Hit 429; waiting " + wait + "s");
                            TimeUnit.SECONDS.sleep(wait);
                            conn.disconnect();
                            continue;
                        }
                        if (code >= 400) {
                            totalFailures++;
                            conn.disconnect();
                            continue;
                        }

                        String fileName = Paths.get(uri.getPath()).getFileName().toString();
                        if (fileName.isEmpty()) fileName = System.currentTimeMillis() + ".img";
                        Path outPath = outputDir.resolve(fileName);
                        try (InputStream in = conn.getInputStream()) {
                            Files.copy(in, outPath, StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("Downloaded: " + urlString + " -> " + outPath);
                        }
                        conn.disconnect();
                        TimeUnit.MILLISECONDS.sleep(IMAGE_DELAY_MS);
                    } catch (URISyntaxException e) {
                        totalFailures++;
                        System.err.println("Invalid URI syntax for " + urlString + ": " + e.getMessage());
                    } catch (IOException e) {
                        totalFailures++;
                        System.err.println("Error fetching " + urlString + ": " + e.getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                completedIds++;
                System.out.println("Completed " + completedIds + " of " + totalIds + " IDs.");
                try {
                    TimeUnit.MILLISECONDS.sleep(idDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("Finished processing. Total requests: " + totalRequests + ", total failures: " + totalFailures + ", total IDs: " + completedIds);
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static List<String> fetchRobotsDisallows(String host) {
        List<String> list = new ArrayList<>();
        try {
            URI robotsUri = new URI("https", host, "/robots.txt", null);
            HttpURLConnection conn = (HttpURLConnection) robotsUri.toURL().openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                boolean uaSection = false;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.toLowerCase().startsWith("user-agent:")) {
                        uaSection = line.substring(11).trim().equals("*");
                    } else if (uaSection && line.toLowerCase().startsWith("disallow:")) {
                        String path = line.substring(9).trim();
                        if (!path.isEmpty()) list.add(path);
                    }
                }
            }
            conn.disconnect();
        } catch (URISyntaxException | IOException e) {
            // ignore and allow all
        }
        return list;
    }
}
