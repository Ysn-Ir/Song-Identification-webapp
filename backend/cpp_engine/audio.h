#pragma once
#include <vector>
#include <iostream>
#include <cmath>
#include <sndfile.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#define WINDOW_SIZE 1024
#define OVERLAPPING_SIZE 512

void printAudioInfo(SF_INFO& info);
std::vector<double> readAudioFile(const char* filename);

// hamming to be extern and just created at the first of the program to avoid recomputing each time
extern std::vector<double> hamming;

void initHamming();
std::vector<double> windowing(double* audio, int N, int index);

