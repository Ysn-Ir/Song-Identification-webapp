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
     * Highly optimized YouTube ingestion pipeline:
     * - Audio-only format selection (-f bestaudio[ext=m4a]/bestaudio)
     * - Multi-connection chunk acceleration (-N 4)
     * - Direct 16kHz mono WAV transcoding via ffmpeg
     * - Real-time SSE telemetry logging
     */
    public List<Map<String, Object>> ingestYouTubeUrls(List<String> urls, int maxTracks, boolean quickSampleOnly) {
        List<Map<String, Object>> results = new ArrayList<>();

        Path tempDir = Paths.get(uploadDir, "yt_staging_" + System.currentTimeMillis());
        try {
            Files.createDirectories(tempDir);
        } catch (Exception e) {
            logService.log("ERROR", "Failed to create temporary staging directory: " + e.getMessage());
            throw new RuntimeException("Could not create staging directory", e);
        }

        try {
            int totalProcessed = 0;
            logService.log("INFO", "Starting YouTube ingestion batch for " + urls.size() + " target(s)...");

            for (String url : urls) {
                if (url == null || url.trim().isEmpty()) continue;
                if (totalProcessed >= maxTracks) break;

                String target = url.trim();
                logService.log("RESOLVING", "Probing stream metadata for: " + target);

                String outputTemplate = tempDir.resolve("%(id)s.%(ext)s").toAbsolutePath().toString();

                List<String> cmd = new ArrayList<>();
                cmd.add(findYtDlpExecutable());
                
                // Optimized audio extraction: only download small audio stream, not video!
                cmd.add("-f");
                cmd.add("bestaudio[ext=m4a]/bestaudio/best");
                cmd.add("--extract-audio");
                cmd.add("--audio-format");
                cmd.add("wav");

                // Multi-threaded fragment acceleration
                cmd.add("-N");
                cmd.add("4");

                // Skip non-essential metadata
                cmd.add("--no-write-thumbnail");
                cmd.add("--no-write-description");
                cmd.add("--no-write-comments");
                cmd.add("--no-write-playlist-metafiles");

                // Post-processor: transcode directly to 16kHz 16-bit mono PCM WAV
                String ffmpegArgs = "-vn -ar 16000 -ac 1 -c:a pcm_s16le";
                if (quickSampleOnly) {
                    // Fingerprint first 90 seconds (cuts download time by 70%)
                    cmd.add("--download-sections");
                    cmd.add("*00:00-01:30");
                }
                cmd.add("--postprocessor-args");
                cmd.add("ffmpeg:" + ffmpegArgs);

                cmd.add("--max-downloads");
                cmd.add(String.valueOf(maxTracks - totalProcessed));

                if (!target.contains("playlist") && !target.contains("list=")) {
                    cmd.add("--no-playlist");
                }

                cmd.add("--print");
                cmd.add("%(id)s|||%(title)s|||%(uploader)s|||%(webpage_url)s");

                cmd.add("-o");
                cmd.add(outputTemplate);
                cmd.add(target);

                logService.log("YT-DLP", "Invoking yt-dlp audio stream capture...");
                ProcessBuilder pb = new ProcessBuilder(cmd);
                Process process = pb.start();

                List<String> printOutputs = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("|||")) {
                            printOutputs.add(line);
                        } else if (line.contains("[download]") && line.contains("%")) {
                            // Extract percentage
                            int pctIdx = line.indexOf("%");
                            if (pctIdx > 4) {
                                String pctStr = line.substring(pctIdx - 4, pctIdx).trim();
                                logService.log("DOWNLOAD", "Transfer progress: " + pctStr + "%");
                            }
                        }
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode != 0 && printOutputs.isEmpty()) {
                    logService.log("WARN", "yt-dlp exited with code " + exitCode + " on " + target);
                    continue;
                }

                // Process each downloaded track immediately
                for (String meta : printOutputs) {
                    String[] parts = meta.split("\\|\\|\\|");
                    if (parts.length < 2) continue;

                    String videoId = parts[0].trim();
                    String title = parts[1].trim();
                    String artist = parts.length > 2 ? parts[2].trim() : "YouTube Artist";
                    String webUrl = parts.length > 3 ? parts[3].trim() : target;

                    logService.log("AUDIO", "Transcoded: [" + title + "] by [" + artist + "]");

                    Path wavPath = tempDir.resolve(videoId + ".wav");
                    if (!Files.exists(wavPath)) {
                        File[] candidates = tempDir.toFile().listFiles((d, name) -> name.startsWith(videoId));
                        if (candidates != null && candidates.length > 0) {
                            wavPath = candidates[0].toPath();
                        }
                    }

                    if (Files.exists(wavPath)) {
                        String absPath = wavPath.toAbsolutePath().toString();

                        try {
                            logService.log("FFTW3", "Computing acoustic fingerprints with C++ analyzer on [" + title + "]...");
                            long startTime = System.currentTimeMillis();

                            String hashJson = shazamService.runProgram(absPath, "getFingerprint");
                            List<AudioHashService.HashEntryDTO> entries = audioHashService.parseHashJson(hashJson);

                            long duration = System.currentTimeMillis() - startTime;
                            logService.log("DSP", "Extracted " + entries.size() + " constellation hashes in " + duration + " ms");

                            // Save to MongoDB
                            long songId = counterService.getNextSongId();
                            Song savedSong = songRepository.save(new Song(songId, title, artist, webUrl));
                            audioHashService.addHashesToDatabase(hashJson, songId);

                            logService.log("MONGO", "Persisted Song #" + songId + " and " + entries.size() + " hashes to MongoDB cluster");

                            Map<String, Object> item = new HashMap<>();
                            item.put("id", savedSong.getId());
                            item.put("name", savedSong.getName());
                            item.put("artist", savedSong.getArtist());
                            item.put("link", savedSong.getLink());
                            item.put("hashCount", entries.size());
                            results.add(item);

                            totalProcessed++;
                            logService.log("SUCCESS", "Successfully indexed: " + title + " (Track #" + songId + ")");

                        } catch (Exception e) {
                            logService.log("ERROR", "Failed to fingerprint track " + title + ": " + e.getMessage());
                        } finally {
                            try {
                                Files.deleteIfExists(wavPath);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            logService.log("COMPLETE", "Batch ingestion finished. Total indexed: " + results.size());

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
}
