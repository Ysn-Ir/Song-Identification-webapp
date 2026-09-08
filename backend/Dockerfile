# ==============================================================================
# Multi-stage Dockerfile for Shazam Acoustic Engine + Spring Boot Backend
# Designed for Linux deployment on Render.com
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build the C++ Shazam Audio Engine for Linux
# ------------------------------------------------------------------------------
FROM ubuntu:22.04 AS cpp-builder
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libfftw3-dev \
    libsndfile1-dev \
    cmake \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /cpp_build
# Copy C++ source files (if present in cpp_engine/)
COPY cpp_engine* /cpp_build/

RUN if [ -f "Makefile" ]; then \
        make; \
    elif [ -f "CMakeLists.txt" ]; then \
        cmake . && make; \
    elif [ -f "main.cpp" ]; then \
        g++ -O3 -std=c++17 *.cpp -lfftw3 -lsndfile -o /cpp_build/shazam; \
    else \
        echo "No C++ sources detected in cpp_engine. Using placeholder."; \
        touch /cpp_build/shazam_missing; \
    fi

# ------------------------------------------------------------------------------
# Stage 2: Build Spring Boot Application JAR
# ------------------------------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-21-jammy AS maven-builder
WORKDIR /app

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x ./mvnw
RUN ./mvnw dependency:go-offline -B || true

COPY src ./src
RUN ./mvnw clean package -DskipTests

# ------------------------------------------------------------------------------
# Stage 3: Final Production Runtime Image
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
ENV DEBIAN_FRONTEND=noninteractive

# Install libfftw3, libsndfile, FFmpeg, Python3, and yt-dlp
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libfftw3-3 \
    libsndfile1 \
    python3 \
    python3-pip \
    ca-certificates \
    curl \
    && pip3 install --no-cache-dir yt-dlp \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy compiled C++ binary from cpp-builder
COPY --from=cpp-builder /cpp_build/ /tmp/cpp/
RUN if [ -f "/tmp/cpp/shazam" ]; then \
        cp /tmp/cpp/shazam /app/shazam && chmod +x /app/shazam; \
    fi && rm -rf /tmp/cpp

# Copy packaged Spring Boot JAR
COPY --from=maven-builder /app/target/shazam-0.0.1-SNAPSHOT.jar /app/app.jar

# Setup uploads storage directory
RUN mkdir -p /app/uploads

# Set Render default environment variables
ENV PORT=8080
ENV SHAZAM_EXECUTABLE_PATH=/app/shazam
ENV UPLOAD_DIR=/app/uploads

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-8080} -jar /app/app.jar"]
