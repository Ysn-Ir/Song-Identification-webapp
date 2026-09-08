#include "processing.h"

// spectogram to run fft on the audio file 
std::vector<std::vector<double>> Spectogram(std::vector<double>& audio, FFT& fft) {

    int MAX_BIN = MAX_FREQ * WINDOW_SIZE / SAMPLE_RATE;
    std::vector<std::vector<double>> spectogram;
    spectogram.reserve(audio.size() / (WINDOW_SIZE - OVERLAPPING_SIZE));
    for (int index = 0; index < audio.size(); index += WINDOW_SIZE - OVERLAPPING_SIZE) {
        std::vector<double>frame = windowing(audio.data(), audio.size(), index);
        fft.execute(frame.data());
        std::vector<double>magnitude(MAX_BIN);
        for (size_t i = 0; i < MAX_BIN; i++) {
            magnitude[i] = fft.getMagnitude(i);
        }
        spectogram.push_back(magnitude);

    }
    return spectogram;
}
// Getting peak amps in 3x3 surrounding of a spectrogram (frequency f-1, f, f+1 and time t-1, t, t+1)
// Enhanced with adaptive energy floor and dynamic fallback for low-volume ambient mic captures
std::vector<Peak> findPeaks(const std::vector<std::vector<double>>& spectrogram, double threshold) {
    std::vector<Peak> peaks;
    if (spectrogram.empty() || spectrogram[0].empty()) return peaks;

    // 1. Determine peak dynamic range across the spectrogram
    double maxMag = 0.0;
    for (size_t t = 0; t < spectrogram.size(); t++) {
        for (size_t f = 0; f < spectrogram[t].size(); f++) {
            if (spectrogram[t][f] > maxMag) maxMag = spectrogram[t][f];
        }
    }

    // 2. Adaptive threshold: for studio masters (maxMag > 8.0), enforce requested threshold (e.g. 2.0).
    // For quiet ambient mic audio (e.g. maxMag ~ 0.5), automatically scale threshold down
    // so salient peaks are cleanly discovered regardless of input gain.
    double effectiveThreshold = threshold;
    if (maxMag > 0.01 && maxMag < threshold * 2.5) {
        effectiveThreshold = std::max(0.15, maxMag * 0.25);
    }

    for (int t = 1; t < (int)spectrogram.size() - 1; t++) {
        for (int f = 1; f < (int)spectrogram[t].size() - 1; f++) {
            double mag = spectrogram[t][f];
            if (mag > effectiveThreshold &&
                mag > spectrogram[t][f - 1] && mag > spectrogram[t][f + 1] &&
                mag > spectrogram[t - 1][f] && mag > spectrogram[t - 1][f - 1] && mag > spectrogram[t - 1][f + 1] &&
                mag > spectrogram[t + 1][f - 1] && mag > spectrogram[t + 1][f + 1] && mag > spectrogram[t + 1][f]) {
                peaks.push_back({ t, f, mag });
            }
        }
    }

    // 3. Fallback: If audio has content but yielded very sparse peaks (< 10 peaks/sec),
    // relax threshold down to 10% of maxMag to ensure adequate constellation points
    double durationSec = (double)spectrogram.size() * (WINDOW_SIZE - OVERLAPPING_SIZE) / SAMPLE_RATE;
    size_t minExpectedPeaks = (size_t)(durationSec * 10.0);
    if (peaks.size() < minExpectedPeaks && maxMag > 0.02) {
        double lowerThresh = std::max(0.05, maxMag * 0.10);
        peaks.clear();
        for (int t = 1; t < (int)spectrogram.size() - 1; t++) {
            for (int f = 1; f < (int)spectrogram[t].size() - 1; f++) {
                double mag = spectrogram[t][f];
                if (mag > lowerThresh &&
                    mag > spectrogram[t][f - 1] && mag > spectrogram[t][f + 1] &&
                    mag > spectrogram[t - 1][f] && mag > spectrogram[t - 1][f - 1] && mag > spectrogram[t - 1][f + 1] &&
                    mag > spectrogram[t + 1][f - 1] && mag > spectrogram[t + 1][f + 1] && mag > spectrogram[t + 1][f]) {
                    peaks.push_back({ t, f, mag });
                }
            }
        }
    }

    std::cerr << peaks.size();
    return peaks;
}

// here we link the peaks with each other to create the fingerprints 
std::vector<FingerPrint> anchors(std::vector<Peak>& peaks) {


    std::vector<FingerPrint> fingerprints;
    for (size_t i = 0; i < peaks.size(); i++) {
        const Peak peak = peaks[i];
        int pairsCount = 0;
        for (int t = i + 1; t < peaks.size(); t++) {
            int dt = peaks[t].time - peak.time;
            if (dt > MAX_AHEAD_TIME)
                break;
            fingerprints.push_back({ peak.freq, peaks[t].freq, dt,peak.time });
            pairsCount++;
            if (pairsCount >= MAX_PEAKS)
                break;
        }
    }

    return fingerprints;

}

//basic hashing function to convert the 3 parameters of the fingerprint into a single 64 bit integer to be used as a key in the database
uint64_t hashFingerPrints(FingerPrint& fingerPrint) {
    return (((uint64_t)fingerPrint.f1 << 32) | ((uint64_t)fingerPrint.f2 << 16) | ((uint64_t)fingerPrint.dt));
}




void saveSpectrogram(const std::vector<std::vector<double>>& spectrogram)
{
    std::ofstream file("spectrogram.csv");

    for (const auto& frame : spectrogram)
    {
        for (size_t i = 0; i < frame.size(); i++)
        {
            file << frame[i];
            if (i != frame.size() - 1)
                file << ",";
        }
        file << "\n";
    }

    file.close();
}



// functions to process data , store it update it and recognize and all 
// all using binary files nothing else 
void processInBulk(const char* directory, FFT& fft) {
    namespace fs = std::filesystem;
    fs::path p(directory);
    int songID = 0;

    //storing all the info of the database in mem in a map of hash and the id and the dt time of each song
    std::unordered_map<uint64_t, std::vector<std::pair<int, int>>> database;

    for (const auto& entry : fs::directory_iterator(p)) {
        std::string filename = entry.path().string();
        std::cerr << "Processing: " << filename << "\n";
        std::vector<double> audio = readAudioFile(filename.c_str());

        std::vector<std::vector<double>> spectrogram = Spectogram(audio, fft);
        std::vector<Peak> peaks = findPeaks(spectrogram, 2.0);
        std::vector<FingerPrint> fingerprints = anchors(peaks);

        songID++;
        for (auto& i : fingerprints) {
            uint64_t hashed = hashFingerPrints(i);
            // Store the absolute time 
            database[hashed].push_back({ songID, i.t1 });
        }
    }
    std::cerr << "Final database size : " << database.size() << "\n";
    // saving everything
    saveDataBase(database, "database.bin");
}

void saveDataBase(std::unordered_map<uint64_t, std::vector<std::pair<int, int>>>& database, const char* filename) {
	std::ofstream out(filename, std::ios::binary); // add  std::ios::app to append to the file instead of overwriting it
    if (!out) { std::cerr << "Couldn't open file: " << filename << "\n"; return; }
    //basically we stor the size then the info
    uint64_t map_size = database.size();
    out.write(reinterpret_cast<const char*>(&map_size), sizeof(map_size));

    for (auto& data : database) {
        uint64_t key = data.first;
        uint64_t vector_size = data.second.size();
        // we store the key the the size of how many pairs we have  
        out.write(reinterpret_cast<const char*>(&key), sizeof(key));
        out.write(reinterpret_cast<const char*>(&vector_size), sizeof(vector_size));

        for (const auto& pair : data.second) {
            int songID = pair.first;
            int t1 = pair.second;
            //we just store the info of each pair , meaning song id and the time of the first peak in the anchor 
            out.write(reinterpret_cast<const char*>(&songID), sizeof(songID));
            out.write(reinterpret_cast<const char*>(&t1), sizeof(t1));
        }
    }
}

std::unordered_map<uint64_t, std::vector<std::pair<int, int>>> loadDatabase(const char* filename) {
    std::unordered_map<uint64_t, std::vector<std::pair<int, int>>> database;
    std::ifstream in(filename, std::ios::binary);
    if (!in.is_open()) { std::cerr << "Could not open: " << filename << "\n"; return database; }

    uint64_t map_size;
    if (!in.read(reinterpret_cast<char*>(&map_size), sizeof(map_size))) return database;

    for (uint64_t i = 0; i < map_size; i++) {
        uint64_t key, vecSize;
        in.read(reinterpret_cast<char*>(&key), sizeof(key));
        in.read(reinterpret_cast<char*>(&vecSize), sizeof(vecSize));

        std::vector<std::pair<int, int>> values(vecSize);
        for (size_t j = 0; j < vecSize; j++) {
            int songID, t1;
            in.read(reinterpret_cast<char*>(&songID), sizeof(songID));
            in.read(reinterpret_cast<char*>(&t1), sizeof(t1));
            values[j] = { songID, t1 };
        }
        database[key] = values;
    }
    in.close();
    std::cerr << "Loaded " << database.size() << " hashes.\n";
    return database;
}
void addSongToDatabase(const char* newSongPath, const char* dbPath, FFT& fft) {
    // 1. Load the existing database into memory
    auto db = loadDatabase(dbPath);

    // 2. Process the new song
    std::cout << "Processing new song...\n";
    std::vector<double> audio = readAudioFile(newSongPath);
    std::vector<std::vector<double>> spectrogram = Spectogram(audio, fft);
    std::vector<Peak> peaks = findPeaks(spectrogram, 5.0); // Using the lowered threshold
    std::vector<FingerPrint> fingerprints = anchors(peaks);

    // We need a unique ID for this new song. 
    int newSongID = 999;

    // 3. Merge the new fingerprints into the existing map
    for (auto& fp : fingerprints) {
        uint64_t hash = hashFingerPrints(fp);
        // This safely adds the new pair to the vector, even if the hash already exists!
        db[hash].push_back({ newSongID, fp.t1 });
    }

    // 4. Overwrite the old database file with the newly updated map
    saveDataBase(db, dbPath);
    std::cout << "Song   added and database updated successfully!\n";
}
int recognize(const char* samplePath, std::unordered_map<uint64_t, std::vector<std::pair<int, int>>>& db, FFT& fft) {
    std::cerr << "\n Analyzing sample: " << samplePath << "\n";
    std::vector<double> audio = readAudioFile(samplePath);
    std::vector<std::vector<double>> spectrogram = Spectogram(audio, fft);
    std::vector<Peak> peaks = findPeaks(spectrogram, 2.0);
    std::vector<FingerPrint> fingerprints = anchors(peaks);

    // Map: SongID -> (TimeOffset -> NumberOfMatches)
    std::unordered_map<int, std::unordered_map<int, int>> matchScores;

    int bestSongID = -1;
    int highestScore = 0;

    for (auto& fp : fingerprints) {
        uint64_t hash = hashFingerPrints(fp);

        // if the hash exists in our database
        if (db.find(hash) != db.end()) {
            for (auto& match : db[hash]) {
                int songID = match.first;
                int db_t1 = match.second;

                // calc the  offset
                int timeOffset = db_t1 - fp.t1;

                // add a point to this specific alignment
                matchScores[songID][timeOffset]++;

                // track the highest score
                if (matchScores[songID][timeOffset] > highestScore) {
                    highestScore = matchScores[songID][timeOffset];
                    bestSongID = songID;
                }
            }
        }
    }
    
    if (bestSongID != -1) {
        std::cerr << "Found the song with song_ID: " << bestSongID << " (Confidence: " << highestScore << " aligned points)";
    }
    else {
        std::cerr << "No match found in the database.";
    }

    return bestSongID;
}


//extern database code
void getFingerPrint(const char* samplePath, FFT& fft, double threshold) {
    std::vector<double> audio = readAudioFile(samplePath);
    std::vector<std::vector<double>> spectrogram = Spectogram(audio, fft);
    std::vector<Peak> peaks = findPeaks(spectrogram, threshold);
    std::vector<FingerPrint> fingerprints = anchors(peaks);
    std::cout << "[";
    for (size_t i = 0; i < fingerprints.size(); i++) {
		uint64_t hash = hashFingerPrints(fingerprints[i]);
		std::cout << "{ \"hash\" : " << hash
                  << ", \"t1\" : " << fingerprints[i].t1 
                  << "}";
		if (i != fingerprints.size() - 1) 
            std::cout << ",";
    }
    std::cout << "]";
}