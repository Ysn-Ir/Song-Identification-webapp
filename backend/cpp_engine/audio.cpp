#include "audio.h"
#include <cmath>
#include <sndfile.h>
#include <iostream>

// hamming defined
std::vector<double> hamming(WINDOW_SIZE);

//init the hamming func filter
void initHamming() {
    for (int i = 0; i < WINDOW_SIZE; i++) {
        hamming[i] = 0.54 - 0.46 * cos(2 * M_PI * i / (WINDOW_SIZE - 1));
    }
}
//resampling each audio to a specific frequency before windowing , here the standard for me is 16KHZ
std::vector<double> resampleAudio(const std::vector<double>& input, int originalRate, int targetRate) {
    if (originalRate == targetRate) {
        return input;
    }

    double ratio = static_cast<double>(originalRate) / targetRate;
    int newSize = static_cast<int>(input.size() / ratio);
    std::vector<double> output(newSize);

    for (int i = 0; i < newSize; i++) {
        double srcIdx = i * ratio;
        int idx1 = static_cast<int>(srcIdx);
        int idx2 = std::min(idx1 + 1, static_cast<int>(input.size() - 1));
        //linear interpolation for the new output we have
        double fraction = srcIdx - idx1;
        output[i] = input[idx1] * (1.0 - fraction) + input[idx2] * fraction;
    }

    return output;
}

// here we just read the audio
std::vector<double> readAudioFile(const char* filename) {
    const int TARGET_RATE = 16000; // the target rate we defined
    SF_INFO info{};
    SNDFILE* file = sf_open(filename, SFM_READ, &info);
    if (!file) {
        std::cerr << "Could not open the file: " << sf_strerror(NULL) << "\n";
        return {};
    }
    //getting basic info of the file
    printAudioInfo(info);

    // defining a buffer of size frames* channels to read all the data in each channel 
    std::vector<float> buffer(info.frames * info.channels);
    sf_readf_float(file, buffer.data(), info.frames);

    //now we just avg all the channels into one mono buffer
    std::vector<double> monoBuffer(info.frames);
    for (int i = 0; i < info.frames; i++) {
        double sum = 0;
        for (int c = 0; c < info.channels; c++)
            sum += buffer[info.channels * i + c];
        monoBuffer[i] = sum / info.channels;
    }

    sf_close(file);
    return resampleAudio(monoBuffer, info.samplerate, TARGET_RATE);
}

void printAudioInfo(SF_INFO& info) {
    std::cerr << " the number of frames = " << info.frames << "\n";
    std::cerr << " the channels = " << info.channels << "\n";
    std::cerr << " the samplerate = " << info.samplerate << "\n";
    std::cerr << " the file format = " << info.format << "\n";

}

// here we loop through our fixed window size from the audio applying the hamming function as we go , and starting from the index of the audio plus the idx i for seperate proceessing of the window at each time
std::vector<double> windowing(double* audio, int N, int index) {
    std::vector<double> window(WINDOW_SIZE);
    for (int i = 0; i < WINDOW_SIZE; i++) {
        if (index + i < N)
            window[i] = audio[index + i] * hamming[i];
        else
            window[i] = 0.0;
    }
    return window;
}