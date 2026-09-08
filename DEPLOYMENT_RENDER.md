# Deploying Shazam Clone to Render.com

This guide details how to deploy the entire full-stack application (React Frontend + Spring Boot Backend + C++ FFTW3 Audio Engine + yt-dlp + FFmpeg) to [Render.com](https://render.com) using Docker.

---

## Architecture Overview

```
                 Render.com Web Service (Docker Container)
       ┌────────────────────────────────────────────────────────┐
       │                                                        │
       │  [React 18 Studio Frontend] (Built into static files) │
       │                          │                             │
       │                          ▼                             │
       │  [Spring Boot 4.0.3 Backend API]                       │
       │     ├── /api/recognize (Multipart & PCM normalizer)    │
       │     ├── /api/youtube/index (yt-dlp multi-thread)       │
       │     └── /api/songs (Catalog & Hash stats)              │
       │                          │                             │
       │                          ▼                             │
       │  [C++ FFTW3 Constellation Engine] (Linux binary)       │
       │     ├── 16kHz Hamming windowing                        │
       │     ├── Adaptive 3x3 local maxima extraction           │
       │     └── Combinatorial peak pairing                     │
       └──────────────────────────┬─────────────────────────────┘
                                  │
                                  ▼
                     [MongoDB Atlas Cloud Database]
                     (Stores songs & acoustic hash fingerprints)
```

---

## Database Options

### Option 1: Built-in MongoDB (Zero Accounts, 100% Free) — Recommended
The Docker container now includes an **internal MongoDB 7.0 server** pre-installed:
- No external MongoDB Atlas account needed.
- No credit card needed.
- Render boots the database and web app together automatically in a single container.
- If `MONGODB_URI` is left default (`mongodb://127.0.0.1:27017/shazamdb`), the container starts `mongod` locally on boot.

### Option 2: Free MongoDB Atlas Cloud (For Persistent Remote Storage)
If you want persistent cloud storage across container reboots:
1. Go to [mongodb.com/atlas](https://www.mongodb.com/atlas) and sign in.
2. Select the **M0 Shared Free Tier** (512MB storage, 100% free forever, no credit card required).
3. Under **Security ➔ Database Access**, add a user with password.
4. Under **Security ➔ Network Access**, add `0.0.0.0/0` (Allow access from anywhere).
5. Copy your connection string (`mongodb+srv://...`) and set it as the `MONGODB_URI` environment variable.

---

## How to Deploy to Render (3 Clicks)

1. Push latest code to GitHub:
   ```bash
   git add .
   git commit -m "Add internal MongoDB and zero-config deployment"
   git push origin main
   ```
2. Go to your [Render Dashboard](https://dashboard.render.com).
3. Click **New +** ➔ **Blueprint** (or **Web Service**).
4. Select your repository: **`Song-Identification-webapp`**.
5. Click **Apply** (Render reads `render.yaml` and deploys automatically). That's it!

---

## Step 3: Deploy on Render

### Option A: Using the Render Blueprint (`render.yaml`) — Recommended
1. Go to your [Render Dashboard](https://dashboard.render.com).
2. Click **New +** in the top right and select **Blueprint**.
3. Connect your GitHub repository.
4. Render will read `render.yaml` and configure the Web Service automatically.
5. In the environment variable setup, paste your `MONGODB_URI` connection string from Step 1.
6. Click **Apply**.

### Option B: Manual Web Service Setup
1. On the Render Dashboard, click **New +** -> **Web Service**.
2. Connect your GitHub repository.
3. Configure the service:
   - **Name**: `acoustic-shazam`
   - **Region**: Oregon or Frankfurt
   - **Language / Runtime**: **Docker**
   - **Dockerfile Path**: `./Dockerfile`
   - **Instance Type**: **Free**
4. Under **Environment Variables**, add:
   - `MONGODB_URI`: `mongodb+srv://shazamuser:<PASSWORD>@cluster0.abcde.mongodb.net/shazamdb?retryWrites=true&w=majority`
   - `PORT`: `8080`
   - `SHAZAM_EXECUTABLE_PATH`: `/app/cpp_engine/shazam`
   - `FFMPEG_PATH`: `/usr/bin/ffmpeg`
   - `UPLOAD_DIR`: `/tmp/shazam_uploads`
5. Click **Create Web Service**.

---

## Step 4: Verification

Once the build finishes and the log displays `Started ShazamApplication in X.XXX seconds`:
1. Open the `.onrender.com` URL provided by Render.
2. The full-stack app will load directly:
   - **Live Identifier**: Test microphone input or click any of the 4 reference clips in the **Interactive Reference Clips** bench.
   - **Curated Music Packs**: Go to **Index Audio** -> **Curated Source Packs** and click **1-Click Ingest Pack** to populate your MongoDB Atlas cloud database.
   - **Master Catalog**: View all indexed songs, hash counts, and play reference tracks.

---

## Memory Optimization on Free Tier

Render's free tier provides 512MB of RAM. The provided `Dockerfile` is optimized specifically for this:
- Java heap is constrained via `-Xmx400m` to prevent out-of-memory errors.
- The C++ engine uses efficient FFTW3 single/double precision plans without memory bloat.
- Temporary WAV files are downsampled to 16kHz mono 16-bit PCM and cleaned up after indexing.
