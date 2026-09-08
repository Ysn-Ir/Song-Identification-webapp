#pragma once
#include "FFT.h"
#include "audio.h"
#include <utility>
#include <fstream>
#include <unordered_map>
#include <filesystem>   



const int  SAMPLE_RATE = 16000;
const int  MAX_FREQ = 5000;

// looking at MAX_AHEAD_TIME frames looking for peaks to link to 
const int MAX_AHEAD_TIME = 50;
// maximum peaks to link to 
const int  MAX_PEAKS = 5;


struct Peak {
    int time;
    int freq;
    double magnitude;
};
struct FingerPrint {
    int f1;
    int f2;
    int dt;
    int t1;
};

std::vector<std::vector<double>> Spectogram(std::vector<double>& audio, FFT& fft);
std::vector<Peak> findPeaks(const std::vector<std::vector<double>>& spectrogram, double threshold);
std::vector<FingerPrint> anchors(std::vector<Peak>& peaks);
uint64_t hashFingerPrints(FingerPrint& fingerPrint);
void saveSpectrogram(const std::vector<std::vector<double>>& spectrogram);
void saveDataBase(std::unordered_map<uint64_t, std::vector<std::pair<int, int>>>& database, const char* filename);
std::unordered_map<uint64_t, std::vector<std::pair<int, int>>> loadDatabase(const char* filename);
void processInBulk(const char* directory, FFT& fft);
int recognize(const char* samplePath, std::unordered_map<uint64_t, std::vector<std::pair<int, int>>>& db, FFT& fft);
void getFingerPrint(const char* samplePath, FFT& fft, double threshold = 2.0);
void addSongToDatabase(const char* newSongPath, const char* dbPath, FFT& fft);


