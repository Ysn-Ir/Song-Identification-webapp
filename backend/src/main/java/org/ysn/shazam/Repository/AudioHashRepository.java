package org.ysn.shazam.Repository;

import com.mongodb.client.MongoDatabase;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.ysn.shazam.model.AudioHash;

import java.util.List;
import java.util.Set;

public interface AudioHashRepository extends MongoRepository<AudioHash,String> {

    List<AudioHash> findByHashIn(Set<Long> hashes);}
