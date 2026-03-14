package org.ysn.shazam.Service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ysn.shazam.Repository.AudioHashRepository;
import org.ysn.shazam.model.AudioHash;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AudioHashService {

    private final AudioHashRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper(); // Jackson

    public void addHashesToDatabase(String hashesJson, Long songId)  {

        // Parse JSON into a list of objects
        List<HashEntryDTO> entries = objectMapper.readValue(
                hashesJson,
                new TypeReference<List<HashEntryDTO>>() {}
        );

        // Load or create AudioHash document (for simplicity, one document)
        AudioHash db = repository.findAll().stream().findFirst().orElse(new AudioHash());

        for (HashEntryDTO entry : entries) {
            long hash = entry.getHash();
            double t1 = entry.getT1();

            db.getHashMap()
                    .computeIfAbsent(hash, k -> new ArrayList<>())
                    .add(new AudioHash.Occurrence(songId, t1));
        }

        // Save updated database
        repository.save(db);
    }

    // DTO for parsing JSON
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class HashEntryDTO {
        private Long hash;
        private Double t1;
    }
}