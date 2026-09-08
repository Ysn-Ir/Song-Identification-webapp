# C++ Acoustic Fingerprinting Engine (Linux Build Sources)

Place your C++ source files (`main.cpp`, `audio.cpp`, `processing.cpp`, `FFT.cpp`, `processing.h`, `FFT.h`) in this folder.

When building with Docker (or deploying to Render.com), the multi-stage `backend/Dockerfile` will automatically compile them into a high-performance native Linux binary (`/app/shazam`) using:

```bash
g++ -O3 -std=c++17 *.cpp -lfftw3 -lsndfile -o /app/shazam
```

### Dependencies Installed in Docker:
- `libfftw3-dev` (Fast Fourier Transform library)
- `libsndfile1-dev` (Audio file decode/resample library)
- `build-essential` (GCC / G++ 11/12)
