package org.ysn.shazam.Repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.ysn.shazam.model.Song;
public interface SongRepository extends MongoRepository<Song, Long> {
}
