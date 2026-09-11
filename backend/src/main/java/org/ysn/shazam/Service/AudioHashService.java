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
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper(); // Jackson

    public void addHashesToDatabase(String hashesJson, Long songId)  {

        // Parse JSON into a list of objects
        List<HashEntryDTO> entries = objectMapper.readValue(
                hashesJson,
                new TypeReference<List<HashEntryDTO>>() {}
        );

        List<AudioHash> audioHashes = new ArrayList<>();
        for (HashEntryDTO entry : entries) {
            audioHashes.add(new AudioHash(null, entry.getHash(), songId, entry.getT1()));
        }

        // High-performance bulk insert: bypasses single-document entity lifecycle overhead
        if (!audioHashes.isEmpty()) {
            mongoTemplate.bulkOps(org.springframework.data.mongodb.core.BulkOperations.BulkMode.UNORDERED, AudioHash.class)
                    .insert(audioHashes)
                    .execute();
        }
    }

    public List<HashEntryDTO> parseHashJson(String hashesJson) throws Exception {
        return objectMapper.readValue(hashesJson, new TypeReference<List<HashEntryDTO>>() {});
    }
    // DTO for parsing JSON
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HashEntryDTO {
        private Long hash;
        private Double t1;
    }
}