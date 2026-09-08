# Deploying Shazam Acoustic Engine to Render.com 🚀

This document outlines the step-by-step procedure to deploy the complete full-stack acoustic fingerprinting platform to [Render.com](https://render.com).

---

## Architecture Overview

1. **Frontend**: Render Static Site (Free Global CDN). Built with Vite & React.
2. **Backend**: Render Web Service (Docker Runtime). 
   - Compiles native Linux C++ Shazam binary with `libfftw3` and `libsndfile`.
   - Runs Spring Boot 3 on Java 21.
   - Bundles `ffmpeg` and `yt-dlp` for YouTube audio stream ingestion.
3. **Database**: MongoDB Atlas (Free M0 Shared Cluster).

---

## Step 1: Set Up Free MongoDB Atlas

Render does not host persistent MongoDB on its free tier. Use MongoDB Atlas:
1. Go to [mongodb.com/cloud/atlas](https://www.mongodb.com/cloud/atlas) and create a free M0 cluster.
2. Under **Database Access**, create a user (e.g. `shazamuser`) and copy the password.
3. Under **Network Access**, click **Add IP Address** -> Select **Allow Access From Anywhere** (`0.0.0.0/0`).
4. Click **Connect** -> **Drivers** (Java) and copy your connection string:
   ```
   mongodb+srv://shazamuser:<password>@cluster0.abcde.mongodb.net/shazamdb?retryWrites=true&w=majority
   ```

---

## Step 2: Push C++ Engine Sources

Render runs Linux containers (`amd64`), so the Windows `shazam.exe` will not run there. The `backend/Dockerfile` compiles the C++ code natively on Linux during deployment:

1. Copy your C++ engine files (`main.cpp`, `audio.cpp`, `processing.cpp`, `FFT.cpp`, `processing.h`, `FFT.h`) into `backend/cpp_engine/`.
2. Commit and push your changes to GitHub:
   ```bash
   git add .
   git commit -m "feat: render deployment configuration and dockerfile"
   git push origin main
   ```

---

## Step 3: Deploy with Render Blueprint (`render.yaml`)

1. Log in to [Render.com](https://render.com).
2. Click **New +** -> **Blueprint**.
3. Connect your GitHub repository (`Song-Identification-webapp`).
4. Render will automatically parse `render.yaml` and configure both services:
   - `shazam-dsp-backend` (Docker Web Service)
   - `shazam-studio-ui` (Static Site)
5. Under Environment Variables for `shazam-dsp-backend`, paste your `MONGODB_URI` from Step 1.
6. Click **Apply**.

---

## Manual Service Setup (Alternative)

If you prefer configuring manually in the Render dashboard:

### 1. Backend Web Service:
- **Environment**: Docker
- **Docker Command / Context**: `./backend`
- **Dockerfile Path**: `backend/Dockerfile`
- **Instance Type**: Starter ($7/mo recommended for FFmpeg / yt-dlp memory; Free tier also boots if careful with concurrent streams).
- **Environment Variables**:
  - `MONGODB_URI`: `mongodb+srv://...`
  - `PORT`: `8080`
  - `SHAZAM_EXECUTABLE_PATH`: `/app/shazam`
  - `UPLOAD_DIR`: `/app/uploads`

### 2. Frontend Static Site:
- **Root Directory**: `frontend`
- **Build Command**: `npm install && npm run build`
- **Publish Directory**: `dist`
- **Environment Variables**:
  - `VITE_API_BASE`: `https://your-shazam-backend.onrender.com` (your backend URL)
