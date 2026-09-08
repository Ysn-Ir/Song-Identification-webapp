# ==========================================
# Multi-Stage Dockerfile for Shazam Clone
# Includes:
# 1. React Vite Frontend Build
# 2. C++ FFTW3/libsndfile Engine Compilation
# 3. Spring Boot 4 Backend Packaging
# 4. Lean Debian/Temurin JRE 21 Runtime with ffmpeg & yt-dlp
# ==========================================

# ----------------------------------------------------
# Stage 1: Build React Frontend
# ----------------------------------------------------
FROM node:20-alpine AS frontend-builder
WORKDIR /app/frontend

COPY frontend/package*.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# ----------------------------------------------------
# Stage 2: Build C++ Engine & Spring Boot Backend
# ----------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS backend-builder

# Install C++ build tools and FFTW3/sndfile dev libraries
RUN apt-get update && apt-get install -y --no-install-recommends \
    g++ \
    make \
    libfftw3-dev \
    libsndfile1-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Compile C++ Engine for Linux x86_64
COPY backend/cpp_engine/ ./cpp_engine/
RUN cd cpp_engine && make

# Build Spring Boot Application with bundled static frontend
WORKDIR /app/backend
COPY backend/mvnw backend/mvnw.cmd backend/pom.xml ./
COPY backend/.mvn ./.mvn
RUN chmod +x ./mvnw

COPY backend/src ./src
# Copy static frontend bundle into Spring Boot resources
COPY --from=frontend-builder /app/frontend/dist ./src/main/resources/static/

RUN sh ./mvnw clean package -DskipTests -B

# ----------------------------------------------------
# Stage 3: Production Runtime
# ----------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# Install runtime dependencies: ffmpeg, python3, curl, libfftw3, libsndfile, gnupg, ca-certificates
# Also install MongoDB 7.0 Community Server so no external database account is required
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    python3 \
    curl \
    gnupg \
    ca-certificates \
    libfftw3-double3 \
    libsndfile1 \
    && curl -fsSL https://www.mongodb.org/static/pgp/server-7.0.asc | gpg -o /usr/share/keyrings/mongodb-server-7.0.gpg --dearmor \
    && echo "deb [ arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/7.0 multiverse" | tee /etc/apt/sources.list.d/mongodb-org-7.0.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends mongodb-org-server \
    && rm -rf /var/lib/apt/lists/*

# Install official yt-dlp binary
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp

WORKDIR /app

# Copy compiled C++ engine
COPY --from=backend-builder /app/cpp_engine/shazam /app/cpp_engine/shazam
RUN chmod +x /app/cpp_engine/shazam

# Copy Spring Boot runnable JAR
COPY --from=backend-builder /app/backend/target/*.jar /app/app.jar

# Setup runtime entrypoint and data directories
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh && mkdir -p /tmp/shazam_uploads /data/db /var/log

# Default Environment Variables
ENV PORT=8080 \
    SHAZAM_EXECUTABLE_PATH=/app/cpp_engine/shazam \
    FFMPEG_PATH=/usr/bin/ffmpeg \
    UPLOAD_DIR=/tmp/shazam_uploads \
    MONGODB_URI=mongodb://127.0.0.1:27017/shazamdb

EXPOSE 8080

ENTRYPOINT ["/app/docker-entrypoint.sh"]
