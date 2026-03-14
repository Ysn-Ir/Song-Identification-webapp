package org.ysn.shazam.Repository;

import com.mongodb.client.MongoDatabase;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.ysn.shazam.model.AudioHash;

public interface AudioHashRepository extends MongoRepository<AudioHash,String> {

}
