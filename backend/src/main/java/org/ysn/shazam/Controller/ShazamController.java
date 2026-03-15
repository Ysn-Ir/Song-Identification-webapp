package org.ysn.shazam.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class ShazamController {

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

    @Value("${file.upload-dir}")
    private String uploadDir;

    // ==========================================
    // TASK 1: UPLOAD & STORE
    // ==========================================
    @PostMapping("/file")
    public ResponseEntity<?> testexec(@RequestParam("file") MultipartFile[] files,
                                      @RequestParam(value = "command", defaultValue = "getFingerprint") String command) throws Exception {

        List<Integer> hashLengths = new ArrayList<>();

        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        // Loop through every file sent by React
        for (MultipartFile file : files) {

            // 1. Save this specific file
            Path filePath = uploadPath.resolve(file.getOriginalFilename());
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            String savedFilePath = filePath.normalize().toAbsolutePath().toString();

            // 2. Run Shazam for this specific file
            String hash = shazamService.runProgram(savedFilePath, command);

            if (hash == null || hash.trim().isEmpty()) {
                throw new RuntimeException("C++ PROGRAM FAILED! File: [" + savedFilePath + "]");
            }

            // 3. Save Song and Hashes to DB
            long songId = counterService.getNextSongId();
            songRepository.save(new Song(songId, file.getOriginalFilename(), "Artist"));
            audioHashService.addHashesToDatabase(hash, songId);

            hashLengths.add(hash.length());
        }

        // Return a list of all the hash lengths processed
        return ResponseEntity.ok("Successfully processed " + files.length + " files. Hash lengths: " + hashLengths);
    }

    // ==========================================
    // TASK 2: UPLOAD & RECOGNIZE
    // ==========================================
    @PostMapping("/recognize")
    public String recognizeFileOptimized(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "command", defaultValue = "") String command) throws Exception {

        // 1. YOUR EXACT FILE SAVING LOGIC
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        Path filePath = uploadPath.resolve(file.getOriginalFilename());
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
        String savedFilePath = filePath.normalize().toAbsolutePath().toString();

        // 2. YOUR EXACT RECOGNITION LOGIC
        // Generate fingerprints JSON
        String hashJson = shazamService.runProgram(savedFilePath, command);

        // Parse JSON into entries
        List<AudioHashService.HashEntryDTO> entries = audioHashService.parseHashJson(hashJson);

        // Collect all unique hashes
        Set<Long> uniqueHashes = entries.stream()
                .map(AudioHashService.HashEntryDTO::getHash)
                .collect(Collectors.toSet());

        // Bulk fetch all matching AudioHash documents from the repository
        List<AudioHash> allMatches = audioHashRepository.findByHashIn(uniqueHashes);

        // Map hash -> list of occurrences
        Map<Long, List<AudioHash>> hashToOccurrences = new HashMap<>();
        for (AudioHash match : allMatches) {
            hashToOccurrences.computeIfAbsent(match.getHash(), k -> new ArrayList<>())
                    .add(match);
        }

        // Recognition logic
        Map<Long, Map<Integer, Integer>> matchScores = new HashMap<>();
        long bestSongId = -1;
        int highestScore = 0;

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

                if (offsetMap.get(offset) > highestScore) {
                    highestScore = offsetMap.get(offset);
                    bestSongId = songId;
                }
            }
        }

        String song = songRepository.findById(bestSongId).toString();
        String response = bestSongId != -1
                ? "Found song_ID: " + bestSongId + " which is : " + song + " (Confidence: " + highestScore + ")"
                : "No match found in the database.";

        // (Optional) Delete the file after recognizing so your hard drive doesn't fill up
        Files.deleteIfExists(filePath);

        return response;
    }
}