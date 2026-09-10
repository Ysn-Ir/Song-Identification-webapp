#!/bin/bash
set -e

# If MONGODB_URI is not set or points to localhost/127.0.0.1, start internal mongod
if [ -z "$MONGODB_URI" ] || [[ "$MONGODB_URI" == *"localhost"* ]] || [[ "$MONGODB_URI" == *"127.0.0.1"* ]]; then
    echo ">> Starting internal MongoDB daemon (127.0.0.1:27017)..."
    mkdir -p /data/db /var/log
    if ! mongod --fork --logpath /var/log/mongod.log --bind_ip 127.0.0.1 --wiredTigerCacheSizeGB 0.15; then
        echo ">> mongod failed to start. Printing log:"
        cat /var/log/mongod.log
        exit 1
    fi
    export MONGODB_URI="mongodb://127.0.0.1:27017/shazamdb"
    echo ">> Internal MongoDB daemon started successfully."
else
    echo ">> Using external MongoDB connection: $MONGODB_URI"
fi

# Execute Spring Boot application (tuned for Render 512MB container)
exec java -Xmx280m -Xms128m -jar /app/app.jar
