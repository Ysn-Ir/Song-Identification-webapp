package org.ysn.shazam.Repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.ysn.shazam.model.User;

@Repository
public interface UserRepository extends MongoRepository<User, Integer> {
}
