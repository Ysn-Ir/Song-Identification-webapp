#!/bin/bash
set -e

# If MONGODB_URI is not set or points to localhost/127.0.0.1, start internal mongod
if [ -z "$MONGODB_URI" ] || [[ "$MONGODB_URI" == *"localhost"* ]] || [[ "$MONGODB_URI" == *"127.0.0.1"* ]]; then
    echo ">> Starting internal MongoDB daemon (127.0.0.1:27017)..."
    mkdir -p /data/db /var/log
    mongod --fork --logpath /var/log/mongod.log --bind_ip 127.0.0.1 --wiredTigerCacheSizeGB 0.1
    export MONGODB_URI="mongodb://127.0.0.1:27017/shazamdb"
    echo ">> Internal MongoDB daemon started successfully."
else
    echo ">> Using external MongoDB connection: $MONGODB_URI"
fi

# Execute Spring Boot application
exec java -Xmx320m -jar /app/app.jar
