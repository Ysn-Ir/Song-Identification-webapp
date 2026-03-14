package org.ysn.shazam.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "fingerPrints")
public class AudioHash {

    @Id
    private String id; // MongoDB document ID

    private Long hash;      // fingerprint
    private Long songId;    // song this fingerprint belongs to
    private Double t1;      // time offset
}