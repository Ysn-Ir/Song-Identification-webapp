package org.ysn.shazam.Controller;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.ysn.shazam.Repository.AudioHashRepository;
import org.ysn.shazam.Repository.SongRepository;
import org.ysn.shazam.Service.AudioHashService;
import org.ysn.shazam.Service.CounterService;
import org.ysn.shazam.Service.ShazamService;
import org.ysn.shazam.model.AudioHash;
import org.ysn.shazam.model.Song;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = "*")
public class ShazamController {

    private static final Logger log = LoggerFactory.getLogger(ShazamController.class);

    @Autowired
    private ShazamService shazamService;
    @Autowired
    private AudioHashService audioHashService;
    @Autowired
    private AudioHashRepository audioHashRepository;
    @Autowired
    private CounterService counterService;
    @Autowired
    private SongRepository songRepository;
    @Autowired
    private org.ysn.shazam.Service.YouTubeService youTubeService;
    @Autowired
    private org.ysn.shazam.Service.IndexingLogService logService;

    @Value("${file.upload-dir}")
    private String uploadDir;

    // ==========================================
    // REAL-TIME SSE LOG STREAM
    // ==========================================
    @GetMapping(value = "/indexing/logs", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamIndexingLogs() {
        return logService.subscribe();
    }

    // ==========================================
    // YOUTUBE & WEB INGESTION
    // ==========================================
    @PostMapping("/youtube/index")
    public ResponseEntity<?> indexFromYouTube(@RequestBody YouTubeIndexRequestDTO request) {
        if (request.getUrls() == null || request.getUrls().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please provide at least one YouTube URL or playlist link."));
        }
        int limit = request.getMaxTracks() > 0 ? Math.min(request.getMaxTracks(), 50) : 10;
        try {
            List<Map<String, Object>> results = youTubeService.ingestYouTubeUrls(request.getUrls(), limit, request.isQuickSampleOnly());
            return ResponseEntity.ok(Map.of(
                    "message", "Successfully ingested and indexed " + results.size() + " tracks from YouTube",
                    "songs", results
            ));
        } catch (Exception e) {
            log.error("Failed YouTube batch ingestion", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "YouTube ingestion failed: " + e.getMessage()));
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class YouTubeIndexRequestDTO {
        private List<String> urls;
        private int maxTracks = 10;
        private boolean quickSampleOnly = true; // Default to optimized 90s sample for speed
    }

    // ==========================================
    // TASK 1: UPLOAD & INDEX LOCAL SONGS
    // ==========================================
    @PostMapping("/file")
    public ResponseEntity<?> indexSongs(
            @RequestParam("file") MultipartFile[] files,
            @RequestParam(value = "title", required = false) String[] titles,
            @RequestParam(value = "artist", required = false) String[] artists,
            @RequestParam(value = "link", required = false) String[] links,
            @RequestParam(value = "command", defaultValue = "getFingerprint") String command) {

        logService.log("INFO", "Receiving local batch upload of " + files.length + " audio file(s)...");

        Path uploadPath = Paths.get(uploadDir);
        try {
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
        } catch (IOException e) {
            logService.log("ERROR", "Could not create upload directory: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not create upload directory: " + e.getMessage()));
        }

        List<Map<String, Object>> processedSongs = new ArrayList<>();

        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            if (file.isEmpty()) continue;

            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio_sample";
            logService.log("STAGE", "Staging audio file [" + originalName + "] (" + (file.getSize() / 1024) + " KB)...");

            String fileExtension = "";
            int dotIdx = originalName.lastIndexOf('.');
            if (dotIdx > 0) {
                fileExtension = originalName.substring(dotIdx);
            }

            String songTitle = (titles != null && titles.length > i && titles[i] != null && !titles[i].trim().isEmpty())
                    ? titles[i].trim()
                    : (dotIdx > 0 ? originalName.substring(0, dotIdx) : originalName);

            String artistName = (artists != null && artists.length > i && artists[i] != null && !artists[i].trim().isEmpty())
                    ? artists[i].trim()
                    : "Unknown Artist";

            String songLink = (links != null && links.length > i && links[i] != null && !links[i].trim().isEmpty())
                    ? links[i].trim()
                    : "";

            Path tempFilePath = null;
            try {
                String uniqueFilename = "index_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + fileExtension;
                tempFilePath = uploadPath.resolve(uniqueFilename);
                Files.copy(file.getInputStream(), tempFilePath, StandardCopyOption.REPLACE_EXISTING);

                String savedFilePath = tempFilePath.normalize().toAbsolutePath().toString();

                logService.log("FFTW3", "Executing C++ FFTW3 constellation extraction on [" + songTitle + "]...");
                long startTime = System.currentTimeMillis();

                String hashJson = shazamService.runProgramWithNormalization(savedFilePath, command);
                if (hashJson == null || hashJson.trim().isEmpty()) {
                    throw new RuntimeException("C++ analyzer produced empty fingerprints for: " + originalName);
                }

                List<AudioHashService.HashEntryDTO> entries = audioHashService.parseHashJson(hashJson);
                long duration = System.currentTimeMillis() - startTime;
                logService.log("DSP", "Extracted " + entries.size() + " constellation hashes in " + duration + " ms");

                long songId = counterService.getNextSongId();
                Song savedSong = songRepository.save(new Song(songId, songTitle, artistName, songLink));
                audioHashService.addHashesToDatabase(hashJson, songId);

                logService.log("MONGO", "Persisted Track #" + songId + " and " + entries.size() + " hashes to database");

                Map<String, Object> songMeta = new HashMap<>();
                songMeta.put("id", savedSong.getId());
                songMeta.put("name", savedSong.getName());
                songMeta.put("artist", savedSong.getArtist());
                songMeta.put("link", savedSong.getLink());
                songMeta.put("hashCount", entries.size());
                processedSongs.add(songMeta);

                logService.log("SUCCESS", "Indexed track: " + songTitle + " (Track #" + songId + ")");

            } catch (Exception e) {
                logService.log("ERROR", "Failed to index " + originalName + ": " + e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Error processing " + originalName + ": " + e.getMessage()));
            } finally {
                if (tempFilePath != null) {
                    try {
                        Files.deleteIfExists(tempFilePath);
                    } catch (IOException ignored) {}
                }
            }
        }

        logService.log("COMPLETE", "Batch local indexing complete. " + processedSongs.size() + " track(s) indexed.");

        return ResponseEntity.ok(Map.of(
                "message", "Successfully indexed " + processedSongs.size() + " songs",
                "songs", processedSongs
        ));
    }

    // ==========================================
    // TASK 2: UPLOAD & RECOGNIZE
    // ==========================================
    @PostMapping("/recognize")
    public ResponseEntity<RecognitionResponseDTO> recognizeFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "command", defaultValue = "getFingerprint") String command) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(new RecognitionResponseDTO(false, null, 0, "No audio file received."));
        }

        Path uploadPath = Paths.get(uploadDir);
        Path tempFile = null;

        try {
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "sample.wav";
            String extension = "";
            int dotIdx = originalName.lastIndexOf('.');
            if (dotIdx > 0) {
                extension = originalName.substring(dotIdx);
            }
            if (extension.isEmpty()) {
                extension = ".wav";
            }

            tempFile = uploadPath.resolve("recog_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + extension);
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            String savedFilePath = tempFile.normalize().toAbsolutePath().toString();

            // Run Shazam C++ binary with automatic audio normalization & format transcoding
            String hashJson = shazamService.runProgramWithNormalization(savedFilePath, command);
            if (hashJson == null || hashJson.trim().isEmpty()) {
                return ResponseEntity.ok(new RecognitionResponseDTO(false, null, 0, "No audio features could be extracted from this sample."));
            }

            // Parse fingerprint entries
            List<AudioHashService.HashEntryDTO> entries = audioHashService.parseHashJson(hashJson);
            if (entries.isEmpty()) {
                return ResponseEntity.ok(new RecognitionResponseDTO(false, null, 0, "No audio hashes detected."));
            }

            Set<Long> uniqueHashes = entries.stream()
                    .map(AudioHashService.HashEntryDTO::getHash)
                    .collect(Collectors.toSet());

            // Bulk fetch matching hashes from MongoDB
            List<AudioHash> allMatches = audioHashRepository.findByHashIn(uniqueHashes);

            Map<Long, List<AudioHash>> hashToOccurrences = new HashMap<>();
            for (AudioHash match : allMatches) {
                hashToOccurrences.computeIfAbsent(match.getHash(), k -> new ArrayList<>()).add(match);
            }

            // Time-coherency scoring algorithm with jitter smoothing (+-1 frame window)
            Map<Long, Map<Integer, Integer>> matchScores = new HashMap<>();

            for (AudioHashService.HashEntryDTO entry : entries) {
                long hash = entry.getHash();
                int t1 = entry.getT1().intValue();

                List<AudioHash> matches = hashToOccurrences.getOrDefault(hash, Collections.emptyList());
                for (AudioHash match : matches) {
                    long songId = match.getSongId();
                    int dbT1 = match.getT1().intValue();
                    int offset = dbT1 - t1;

                    matchScores.computeIfAbsent(songId, k -> new HashMap<>());
                    Map<Integer, Integer> offsetMap = matchScores.get(songId);
                    offsetMap.put(offset, offsetMap.getOrDefault(offset, 0) + 1);
                }
            }

            // Group adjacent time offsets (+-1 frame) to handle STFT window boundary jitter in ambient mic input
            long bestSongId = -1;
            int highestScore = 0;

            for (Map.Entry<Long, Map<Integer, Integer>> songEntry : matchScores.entrySet()) {
                long songId = songEntry.getKey();
                Map<Integer, Integer> offsetMap = songEntry.getValue();

                for (Map.Entry<Integer, Integer> offsetEntry : offsetMap.entrySet()) {
                    int centerOffset = offsetEntry.getKey();
                    int clusterScore = 0;
                    for (int delta = -1; delta <= 1; delta++) {
                        clusterScore += offsetMap.getOrDefault(centerOffset + delta, 0);
                    }

                    if (clusterScore > highestScore) {
                        highestScore = clusterScore;
                        bestSongId = songId;
                    }
                }
            }

            // A threshold of >= 3 coherently aligned hashes ensures noise rejection
            if (bestSongId != -1 && highestScore >= 3) {
                Optional<Song> songOpt = songRepository.findById(bestSongId);
                if (songOpt.isPresent()) {
                    Song foundSong = songOpt.get();
                    return ResponseEntity.ok(new RecognitionResponseDTO(
                            true,
                            foundSong,
                            highestScore,
                            "Match found with confidence " + highestScore
                    ));
                }
            }

            return ResponseEntity.ok(new RecognitionResponseDTO(
                    false,
                    null,
                    highestScore,
                    "No match found in database."
            ));

        } catch (Exception e) {
            log.error("Recognition failure", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new RecognitionResponseDTO(false, null, 0, "Recognition error: " + e.getMessage()));
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {}
            }
        }
    }

    // ==========================================
    // TASK 3: SONG CATALOG & MANAGEMENT
    // ==========================================
    @GetMapping("/songs")
    public ResponseEntity<List<SongSummaryDTO>> getAllSongs() {
        List<Song> songs = songRepository.findAll();
        List<SongSummaryDTO> summaries = new ArrayList<>();

        for (Song s : songs) {
            long count = audioHashRepository.countBySongId(s.getId());
            summaries.add(new SongSummaryDTO(s.getId(), s.getName(), s.getArtist(), s.getLink(), count));
        }

        return ResponseEntity.ok(summaries);
    }

    @DeleteMapping("/songs/{id}")
    public ResponseEntity<?> deleteSong(@PathVariable("id") Long id) {
        if (!songRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        songRepository.deleteById(id);
        audioHashRepository.deleteBySongId(id);
        log.info("Deleted song {} and cleared its audio hashes", id);

        return ResponseEntity.ok(Map.of("message", "Song and all corresponding fingerprints deleted successfully."));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long songCount = songRepository.count();
        long hashCount = audioHashRepository.count();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSongs", songCount);
        stats.put("totalHashes", hashCount);
        stats.put("status", "ONLINE");
        return ResponseEntity.ok(stats);
    }

    // ==========================================
    // DTOs
    // ==========================================
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecognitionResponseDTO {
        private boolean matched;
        private Song song;
        private int confidence;
        private String message;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SongSummaryDTO {
        private Long id;
        private String name;
        private String artist;
        private String link;
        private long hashCount;
    }
}