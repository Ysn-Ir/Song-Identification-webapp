package org.ysn.shazam.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "audiohashes")
public class AudioHash {

    @Id
    private String id; // Mongo document ID

    // Maps fingerprint hash → list of occurrences
    private Map<Long, List<Occurrence>> hashMap = new HashMap<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Occurrence {
        private Long songId;
        private Double t1;
    }
}