package org.ysn.shazam.Service;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class CounterService {

    private final MongoTemplate mongoTemplate;

    public CounterService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public long getNextSongId() {
        Query query = new Query(Criteria.where("_id").is("songId"));
        Update update = new Update().inc("seq", 1);

        // Atomically increment and return the new value
        Counter counter = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                Counter.class
        );
        return counter.getSeq();
    }

    // Map counter document
    public static class Counter {
        private String id;
        private long seq;
        public long getSeq() { return seq; }
        public void setSeq(long seq) { this.seq = seq; }
    }
}