package org.ysn.shazam.Repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.ysn.shazam.model.AudioHash;

import java.util.List;
import java.util.Set;

public interface AudioHashRepository extends MongoRepository<AudioHash, String> {

    List<AudioHash> findByHashIn(Set<Long> hashes);

    void deleteBySongId(Long songId);

    long countBySongId(Long songId);
}
