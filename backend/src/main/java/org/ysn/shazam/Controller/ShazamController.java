package org.ysn.shazam.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.ysn.shazam.Repository.AudioHashRepository;
import org.ysn.shazam.Service.AudioHashService;
import org.ysn.shazam.Service.CounterService;
import org.ysn.shazam.Service.ShazamService;
import org.ysn.shazam.Service.UserService;
import org.ysn.shazam.model.AudioHash;
import org.ysn.shazam.model.file;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("api")
public class ShazamController {
    @Autowired
    private ShazamService shazamService;
    @Autowired
    private AudioHashService audioHashService;
    @Autowired
    private AudioHashRepository  audioHashRepository;
    @Autowired
    private CounterService counterService;

    @PostMapping("/file")
    public Integer testexec(@RequestBody file f) {
        String hash = shazamService.runProgram(f.getFilepath().toString(), f.getCommand().toString());

        // Generate a new songId
        long songId = counterService.getNextSongId();

        audioHashService.addHashesToDatabase(hash, songId);
        return hash.length();
    }
    @PostMapping("/recognize")
    public Long recognizeFileOptimized(@RequestBody file f) throws Exception {

        // 1️⃣ Generate fingerprints JSON
        String hashJson = shazamService.runProgram(f.getFilepath().toString(), f.getCommand().toString());

        // 2️⃣ Parse JSON into entries
        List<AudioHashService.HashEntryDTO> entries = audioHashService.parseHashJson(hashJson);

        // 3️⃣ Collect all unique hashes
        Set<Long> uniqueHashes = entries.stream()
                .map(AudioHashService.HashEntryDTO::getHash)
                .collect(Collectors.toSet());

        // 4️⃣ Bulk fetch all matching AudioHash documents from the repository
        List<AudioHash> allMatches = audioHashRepository.findByHashIn(uniqueHashes);

        // 5️⃣ Map hash -> list of occurrences
        Map<Long, List<AudioHash>> hashToOccurrences = new HashMap<>();
        for (AudioHash match : allMatches) {
            hashToOccurrences.computeIfAbsent(match.getHash(), k -> new ArrayList<>())
                    .add(match);
        }

        // 6️⃣ Recognition logic
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

        System.out.println(bestSongId != -1
                ? "Found song_ID: " + bestSongId + " (Confidence: " + highestScore + ")"
                : "No match found in the database.");

        return bestSongId;
    }
}
