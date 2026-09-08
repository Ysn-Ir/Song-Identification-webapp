package org.ysn.shazam.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ysn.shazam.Repository.SongRepository;
import org.ysn.shazam.model.Song;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

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

    /**
     * High-speed, robust YouTube ingestion pipeline:
     * 1. Native compressed audio download via yt-dlp (ba/b, 4 parallel chunk threads)
     * 2. Intelligent Artist & Title tag extraction from YouTube metadata & title strings
     * 3. Sub-second local FFmpeg transcoding to 16kHz mono 16-bit PCM WAV
     * 4. Turbo mode: 90s sample extraction (instantaneous fingerprinting)
     * 5. Accurate database persistence with direct YouTube link
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
            int totalProcessed = 0;
            logService.log("INFO", "Initializing YouTube ingestion for " + urls.size() + " target(s)...");

            for (String url : urls) {
                if (url == null || url.trim().isEmpty()) continue;
                if (totalProcessed >= maxTracks) break;

                String target = url.trim();

                // Normalize YouTube URLs: If someone copies a video while listening to a mix/radio,
                // YouTube adds &list=RD... which stalls yt-dlp. Strip radio/mix parameters!
                if (target.contains("watch?v=") && target.contains("list=RD")) {
                    target = target.replaceAll("[&?]list=RD[^&]*", "").replaceAll("[&?]index=\\d+", "").replaceAll("[&?]start_radio=1", "");
                    logService.log("NORMALIZE", "Cleaned dynamic YouTube radio mix parameters: " + target);
                }

                logService.log("RESOLVING", "Connecting to YouTube stream: " + target);

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

                // Enforce --no-playlist unless explicitly a playlist URL
                if (!target.contains("playlist?list=")) {
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
                    logService.log("WARN", "yt-dlp produced no output for: " + target + " (code " + exitCode + ")");
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
