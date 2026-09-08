package org.ysn.shazam.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ysn.shazam.Repository.SongRepository;
import org.ysn.shazam.model.Song;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class YouTubeService {

    private static final Logger log = LoggerFactory.getLogger(YouTubeService.class);

    @Autowired
    private ShazamService shazamService;
    @Autowired
    private AudioHashService audioHashService;
    @Autowired
    private CounterService counterService;
    @Autowired
    private SongRepository songRepository;
    @Autowired
    private IndexingLogService logService;

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${yt-dlp.executable.path:yt-dlp}")
    private String ytDlpPath;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static class SpotifyTrackDTO {
        private String title;
        private String artist;
        private String sourceUrl;

        public SpotifyTrackDTO(String title, String artist, String sourceUrl) {
            this.title = title;
            this.artist = artist;
            this.sourceUrl = sourceUrl;
        }

        public String getTitle() { return title; }
        public String getArtist() { return artist; }
        public String getSourceUrl() { return sourceUrl; }
    }

    public boolean isSpotifyUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("open.spotify.com/") || lower.contains("spotify.link/");
    }

    public boolean isPlaylistUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("playlist?list=")
                || lower.contains("&list=pl")
                || lower.contains("?list=pl")
                || lower.contains("&list=olak")
                || lower.contains("?list=olak")
                || lower.contains("&list=cl")
                || lower.contains("music.youtube.com/playlist");
    }

    /**
     * Extracts track metadata from a public Spotify playlist, album, or track URL
     * via Spotify's embed data payload.
     */
    public List<SpotifyTrackDTO> extractSpotifyTracks(String spotifyUrl) {
        List<SpotifyTrackDTO> tracks = new ArrayList<>();
        if (spotifyUrl == null || spotifyUrl.isBlank()) return tracks;

        try {
            String cleanUrl = spotifyUrl.split("[?#]")[0].trim();
            String embedUrl = cleanUrl.contains("/embed/") ? cleanUrl : cleanUrl.replace("open.spotify.com/", "open.spotify.com/embed/");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embedUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logService.log("WARN", "Spotify embed lookup returned HTTP " + response.statusCode());
                return tracks;
            }

            String html = response.body();
            Pattern pattern = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>([\\s\\S]*?)</script>");
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String jsonStr = matcher.group(1);
                JsonNode root = objectMapper.readTree(jsonStr);
                JsonNode entity = root.path("props").path("pageProps").path("state").path("data").path("entity");

                // Check for playlist / album tracklist
                if (entity.has("trackList") && entity.get("trackList").isArray()) {
                    for (JsonNode t : entity.get("trackList")) {
                        String title = t.path("title").asText("").trim();
                        String artist = t.path("subtitle").asText("").trim();
                        String uri = t.path("uri").asText("");
                        if (!title.isEmpty()) {
                            tracks.add(new SpotifyTrackDTO(title, artist, uri.isEmpty() ? spotifyUrl : uri));
                        }
                    }
                } else if (entity.has("name")) {
                    // Single track
                    String title = entity.path("name").asText("").trim();
                    String artist = "";
                    if (entity.has("artists") && entity.get("artists").isArray() && entity.get("artists").size() > 0) {
                        artist = entity.get("artists").get(0).path("name").asText("").trim();
                    }
                    if (!title.isEmpty()) {
                        tracks.add(new SpotifyTrackDTO(title, artist, spotifyUrl));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse Spotify embed metadata for " + spotifyUrl, e);
            logService.log("WARN", "Spotify metadata resolution notice: " + e.getMessage());
        }
        return tracks;
    }

    /**
     * High-speed, robust stream ingestion pipeline:
     * 1. Direct YouTube stream, YouTube Playlists & Spotify URL bridge
     * 2. Intelligent Artist & Title tag extraction from YouTube metadata & Spotify JSON
     * 3. Sub-second local FFmpeg transcoding to 16kHz mono 16-bit PCM WAV
     * 4. Turbo mode: 90s sample extraction (instantaneous fingerprinting)
     * 5. Accurate database persistence with verified streaming reference link
     */
    public List<Map<String, Object>> ingestYouTubeUrls(List<String> urls, int maxTracks, boolean quickSampleOnly) {
        List<Map<String, Object>> results = new ArrayList<>();

        Path tempDir = Paths.get(uploadDir, "yt_staging_" + System.currentTimeMillis());
        try {
            Files.createDirectories(tempDir);
        } catch (Exception e) {
            logService.log("ERROR", "Failed to create staging directory: " + e.getMessage());
            throw new RuntimeException("Could not create staging directory", e);
        }

        try {
            // Expand Spotify playlists and items to search targets
            List<String> expandedTargets = new ArrayList<>();
            for (String raw : urls) {
                if (raw == null || raw.trim().isEmpty()) continue;
                String trimmed = raw.trim();
                String cleanUrl = trimmed.contains("#") ? trimmed.split("#", 2)[0].trim() : trimmed;

                if (isSpotifyUrl(cleanUrl)) {
                    logService.log("SPOTIFY", "Resolving Spotify link: " + cleanUrl);
                    List<SpotifyTrackDTO> spTracks = extractSpotifyTracks(cleanUrl);
                    if (!spTracks.isEmpty()) {
                        logService.log("SPOTIFY", "Extracted " + spTracks.size() + " track(s) from Spotify metadata");
                        for (SpotifyTrackDTO st : spTracks) {
                            String search = "ytsearch1:" + (st.getArtist().isEmpty() ? "" : st.getArtist() + " - ") + st.getTitle() + " audio";
                            expandedTargets.add(search);
                            logService.log("QUEUE", "Mapped Spotify: \"" + st.getTitle() + "\" by " + st.getArtist());
                        }
                    } else {
                        logService.log("WARN", "No tracks extracted from Spotify URL. Skipping: " + cleanUrl);
                    }
                } else {
                    expandedTargets.add(trimmed);
                }
            }

            int totalProcessed = 0;
            logService.log("INFO", "Executing stream ingestion for " + expandedTargets.size() + " target(s)...");

            for (String url : expandedTargets) {
                if (url == null || url.trim().isEmpty()) continue;
                if (totalProcessed >= maxTracks) break;

                String target = url.trim();
                if (target.contains("#")) {
                    target = target.split("#", 2)[0].trim();
                }

                // Clean dynamic YouTube radio mix parameters (list=RD...) that block yt-dlp
                if (target.contains("watch?v=") && target.contains("list=RD")) {
                    target = target.replaceAll("[&?]list=RD[^&]*", "").replaceAll("[&?]index=\\d+", "").replaceAll("[&?]start_radio=1", "");
                    logService.log("NORMALIZE", "Cleaned dynamic YouTube radio mix parameter: " + target);
                }

                logService.log("RESOLVING", "Connecting to audio stream target: " + target);

                String outputTemplate = tempDir.resolve("%(id)s.%(ext)s").toAbsolutePath().toString();

                List<String> cmd = new ArrayList<>();
                cmd.add(findYtDlpExecutable());
                // CRITICAL: --no-simulate ensures download happens alongside metadata print
                cmd.add("--no-simulate");
                cmd.add("-f");
                cmd.add("ba/b"); // Fastest audio stream
                cmd.add("-N");
                cmd.add("4"); // 4 concurrent connections
                cmd.add("--no-check-certificates");
                cmd.add("--no-warnings");

                // Enforce --no-playlist UNLESS target is a recognized playlist or search query
                if (!isPlaylistUrl(target) && !target.startsWith("ytsearch")) {
                    cmd.add("--no-playlist");
                }

                cmd.add("--max-downloads");
                cmd.add(String.valueOf(maxTracks - totalProcessed));

                // Extract: ID, Title, Artist tag, Channel name, Webpage URL
                cmd.add("--print");
                cmd.add("%(id)s|||%(title)s|||%(artist)s|||%(channel)s|||%(webpage_url)s");

                cmd.add("-o");
                cmd.add(outputTemplate);
                cmd.add(target);

                logService.log("YT-DLP", "Downloading audio stream with 4-way acceleration...");
                long downloadStart = System.currentTimeMillis();

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                Process process = pb.start();

                List<String> printOutputs = new ArrayList<>();

                // Read output
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.contains("|||")) {
                            printOutputs.add(line);
                        } else if (line.contains("[download]") && line.contains("%")) {
                            int pctIdx = line.indexOf("%");
                            if (pctIdx > 4) {
                                String pctStr = line.substring(pctIdx - 4, pctIdx).trim();
                                logService.log("DOWNLOAD", "Transfer progress: " + pctStr + "%");
                            }
                        }
                    }
                }

                int exitCode = process.waitFor();
                long downloadDuration = System.currentTimeMillis() - downloadStart;

                if (printOutputs.isEmpty()) {
                    logService.log("WARN", "yt-dlp produced no stream output for: " + target + " (code " + exitCode + ")");
                    continue;
                }

                logService.log("SPEED", "Stream download finished in " + (downloadDuration / 1000.0) + "s");

                // Process each downloaded audio file
                for (String meta : printOutputs) {
                    String[] parts = meta.split("\\|\\|\\|");
                    if (parts.length < 2) continue;

                    String videoId = parts[0].trim();
                    String rawTitle = parts[1].trim();
                    String rawArtist = parts.length > 2 ? parts[2].trim() : "";
                    String channel = parts.length > 3 ? parts[3].trim() : "";
                    String webUrl = parts.length > 4 ? parts[4].trim() : "https://www.youtube.com/watch?v=" + videoId;

                    if (webUrl.isEmpty() || webUrl.equalsIgnoreCase("NA")) {
                        webUrl = "https://www.youtube.com/watch?v=" + videoId;
                    }

                    // Smart Artist and Title Parsing
                    ParsedMetadata parsed = parseMusicMetadata(rawTitle, rawArtist, channel);
                    String cleanTitle = parsed.title;
                    String cleanArtist = parsed.artist;

                    logService.log("METADATA", "Parsed Track: [" + cleanTitle + "] by Artist: [" + cleanArtist + "] (" + webUrl + ")");

                    // Locate the downloaded raw audio file
                    File[] candidates = tempDir.toFile().listFiles((d, name) -> name.startsWith(videoId) && !name.endsWith(".wav"));
                    if (candidates == null || candidates.length == 0) {
                        logService.log("WARN", "Downloaded audio container not found for ID: " + videoId);
                        continue;
                    }

                    File rawAudio = candidates[0];
                    Path wavPath = tempDir.resolve(videoId + ".wav");

                    // Sub-second local FFmpeg transcoding to 16kHz mono 16-bit PCM WAV
                    logService.log("FFMPEG", "Transcoding stream to 16kHz uncompressed WAV (Turbo: " + quickSampleOnly + ")...");
                    long transcodeStart = System.currentTimeMillis();

                    List<String> ffmpegCmd = new ArrayList<>();
                    ffmpegCmd.add(findFfmpegExecutable());
                    ffmpegCmd.add("-y");
                    ffmpegCmd.add("-i");
                    ffmpegCmd.add(rawAudio.getAbsolutePath());
                    if (quickSampleOnly) {
                        ffmpegCmd.add("-t");
                        ffmpegCmd.add("90"); // Turbo mode: 90 seconds
                    }
                    ffmpegCmd.add("-ar");
                    ffmpegCmd.add("16000");
                    ffmpegCmd.add("-ac");
                    ffmpegCmd.add("1");
                    ffmpegCmd.add("-c:a");
                    ffmpegCmd.add("pcm_s16le");
                    ffmpegCmd.add(wavPath.toAbsolutePath().toString());

                    Process ffmpegProc = new ProcessBuilder(ffmpegCmd).redirectErrorStream(true).start();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(ffmpegProc.getInputStream()))) {
                        while (r.readLine() != null) {} // Drain buffer
                    }
                    ffmpegProc.waitFor();

                    // Cleanup raw container
                    try { rawAudio.delete(); } catch (Exception ignored) {}

                    long transcodeDuration = System.currentTimeMillis() - transcodeStart;
                    logService.log("FFMPEG", "WAV transcoding completed in " + transcodeDuration + " ms");

                    if (Files.exists(wavPath)) {
                        String absPath = wavPath.toAbsolutePath().toString();

                        try {
                            logService.log("FFTW3", "Computing acoustic fingerprints with C++ engine on [" + cleanTitle + "]...");
                            long dspStart = System.currentTimeMillis();

                            String hashJson = shazamService.runProgram(absPath, "getFingerprint");
                            List<AudioHashService.HashEntryDTO> entries = audioHashService.parseHashJson(hashJson);

                            long dspDuration = System.currentTimeMillis() - dspStart;
                            logService.log("DSP", "Extracted " + entries.size() + " constellation hashes in " + dspDuration + " ms");

                            // Save to MongoDB with accurate Title, Artist, and direct YouTube Web Link
                            long songId = counterService.getNextSongId();
                            Song savedSong = songRepository.save(new Song(songId, cleanTitle, cleanArtist, webUrl));
                            audioHashService.addHashesToDatabase(hashJson, songId);

                            logService.log("MONGO", "Persisted Song #" + songId + " [" + cleanTitle + "] with Link [" + webUrl + "] to MongoDB");

                            Map<String, Object> item = new HashMap<>();
                            item.put("id", savedSong.getId());
                            item.put("name", savedSong.getName());
                            item.put("artist", savedSong.getArtist());
                            item.put("link", savedSong.getLink());
                            item.put("hashCount", entries.size());
                            results.add(item);

                            totalProcessed++;
                            logService.log("SUCCESS", "Indexed: \"" + cleanTitle + "\" by " + cleanArtist + " (Track #" + songId + ")");

                        } catch (Exception e) {
                            logService.log("ERROR", "Failed to fingerprint track " + cleanTitle + ": " + e.getMessage());
                        } finally {
                            try {
                                Files.deleteIfExists(wavPath);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            logService.log("COMPLETE", "Batch ingestion finished. Successfully cataloged " + results.size() + " track(s).");

        } catch (Exception e) {
            logService.log("FATAL", "Ingestion aborted: " + e.getMessage());
            throw new RuntimeException("YouTube ingestion failure", e);
        } finally {
            try {
                File[] leftovers = tempDir.toFile().listFiles();
                if (leftovers != null) {
                    for (File f : leftovers) f.delete();
                }
                Files.deleteIfExists(tempDir);
            } catch (Exception ignored) {}
        }

        return results;
    }

    /**
     * Helper to parse and separate Artist and Title accurately from YouTube video data.
     */
    private ParsedMetadata parseMusicMetadata(String rawTitle, String rawArtist, String channel) {
        String artist = "";
        String title = rawTitle != null ? rawTitle.trim() : "Unknown Track";

        // 1. Check if an explicit ID3/YouTube Music artist tag is provided
        if (rawArtist != null && !rawArtist.trim().isEmpty() && !rawArtist.equalsIgnoreCase("NA")) {
            artist = rawArtist.trim();
        }

        // 2. Intelligent Title Parsing: "Artist - Title"
        if (artist.isEmpty() || artist.equalsIgnoreCase("NA")) {
            if (title.contains(" - ")) {
                String[] split = title.split(" - ", 2);
                artist = split[0].trim();
                title = split[1].trim();
            } else if (title.contains(" – ")) { // En-dash
                String[] split = title.split(" – ", 2);
                artist = split[0].trim();
                title = split[1].trim();
            } else if (title.contains(" — ")) { // Em-dash
                String[] split = title.split(" — ", 2);
                artist = split[0].trim();
                title = split[1].trim();
            } else if (title.contains(": ")) {
                String[] split = title.split(": ", 2);
                artist = split[0].trim();
                title = split[1].trim();
            }
        }

        // 3. Fallback to channel if still no artist detected
        if (artist.isEmpty() || artist.equalsIgnoreCase("NA")) {
            if (channel != null && !channel.trim().isEmpty() && !channel.equalsIgnoreCase("NA")) {
                artist = cleanChannelName(channel.trim());
            } else {
                artist = "YouTube Artist";
            }
        }

        // 4. Clean up video title boilerplate
        title = cleanTitle(title);

        return new ParsedMetadata(artist, title);
    }

    private String cleanTitle(String s) {
        if (s == null) return "Unknown Track";
        // Remove (Official Video), [Official Audio], (Lyrics), (Visualizer), etc.
        String cleaned = s.replaceAll("(?i)\\s*[\\[\\(](official\\s*(music)?\\s*video|official\\s*audio|official\\s*hd|lyrics?|audio|hd|4k|visualizer|remastered|clean|explicit|prod\\..*?)[\\]\\)]", "");
        cleaned = cleaned.replaceAll("(?i)\\s*(official\\s*(music)?\\s*video|official\\s*audio|lyrics?|4k|hd)$", "");
        return cleaned.trim().isEmpty() ? s.trim() : cleaned.trim();
    }

    private String cleanChannelName(String ch) {
        if (ch == null) return "YouTube Artist";
        String s = ch.replaceAll("(?i)\\s*-\\s*topic$", "");
        s = s.replaceAll("(?i)vevo$", "");
        return s.trim().isEmpty() ? ch.trim() : s.trim();
    }

    private static class ParsedMetadata {
        final String artist;
        final String title;

        ParsedMetadata(String artist, String title) {
            this.artist = artist;
            this.title = title;
        }
    }

    /**
     * Inspects a URL and resolves playlist / album tracklists (Spotify or YouTube)
     * without starting heavy audio downloading.
     */
    public Map<String, Object> resolveLink(String url) {
        Map<String, Object> result = new HashMap<>();
        if (url == null || url.isBlank()) {
            result.put("error", "URL cannot be empty");
            return result;
        }

        String target = url.trim();
        if (target.contains("#")) {
            target = target.split("#", 2)[0].trim();
        }

        if (isSpotifyUrl(target)) {
            List<SpotifyTrackDTO> tracks = extractSpotifyTracks(target);
            result.put("type", "spotify");
            result.put("sourceUrl", target);
            result.put("count", tracks.size());
            List<Map<String, String>> items = new ArrayList<>();
            for (SpotifyTrackDTO st : tracks) {
                items.add(Map.of(
                        "title", st.getTitle(),
                        "artist", st.getArtist(),
                        "query", "ytsearch1:" + (st.getArtist().isEmpty() ? "" : st.getArtist() + " - ") + st.getTitle() + " audio"
                ));
            }
            result.put("tracks", items);
            return result;
        }

        if (isPlaylistUrl(target)) {
            result.put("type", "youtube_playlist");
            result.put("sourceUrl", target);
            List<String> cmd = List.of(
                    findYtDlpExecutable(),
                    "--flat-playlist",
                    "--print", "%(id)s|||%(title)s|||%(channel)s",
                    "--max-downloads", "25",
                    target
            );
            List<Map<String, String>> items = new ArrayList<>();
            try {
                Process proc = new ProcessBuilder(cmd).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("|||")) {
                            String[] parts = line.split("\\|\\|\\|");
                            String id = parts[0].trim();
                            String title = parts.length > 1 ? parts[1].trim() : "Unknown";
                            String channel = parts.length > 2 ? parts[2].trim() : "";
                            items.add(Map.of(
                                    "title", title,
                                    "artist", channel,
                                    "query", "https://www.youtube.com/watch?v=" + id
                            ));
                        }
                    }
                }
                proc.waitFor();
            } catch (Exception e) {
                log.warn("Fast playlist metadata extract error: " + e.getMessage());
            }
            result.put("count", items.size());
            result.put("tracks", items);
            return result;
        }

        // Single target
        result.put("type", "single");
        result.put("sourceUrl", target);
        result.put("count", 1);
        result.put("tracks", List.of(Map.of("title", target, "artist", "", "query", target)));
        return result;
    }

    private String findYtDlpExecutable() {
        String[] candidates = {
                ytDlpPath,
                "yt-dlp",
                "C:\\Users\\khali\\AppData\\Roaming\\Python\\Python312\\Scripts\\yt-dlp.exe",
                "C:\\Python312\\Scripts\\yt-dlp.exe"
        };
        for (String c : candidates) {
            try {
                File f = new File(c);
                if (f.exists() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            } catch (Exception ignored) {}
        }
        return "yt-dlp";
    }

    private String findFfmpegExecutable() {
        String[] candidates = {
                "ffmpeg",
                "C:\\Users\\khali\\AppData\\Local\\Microsoft\\WinGet\\Packages\\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\\ffmpeg-8.0.1-full_build\\bin\\ffmpeg.exe",
                "/usr/bin/ffmpeg"
        };
        for (String c : candidates) {
            try {
                File f = new File(c);
                if (f.exists() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            } catch (Exception ignored) {}
        }
        return "ffmpeg";
    }
}
