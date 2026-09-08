#pragma once
#include <fftw3.h>
#include <stdexcept>
#include <cmath>
class FFT {
private:
    int N;
	fftw_plan plan;
    double* in;
    fftw_complex* out;
public:
    
    FFT(int N):N(N) {
        //allocate memory for both the in and out vector
        in = (double*)fftw_malloc(sizeof(double) * N);
        out = (fftw_complex*)fftw_malloc(sizeof(fftw_complex) * (N / 2 + 1));

        //create the execution plan
        plan = fftw_plan_dft_r2c_1d(N, in, out, FFTW_ESTIMATE);
    }
    ~FFT() {
		fftw_destroy_plan(plan);
        fftw_free(in);
        fftw_free(out);
    }
    void execute(double * data) {
        setInput(data);
        fftw_execute(plan);
    }
    fftw_complex* getOutput() {
        return out;
	}
    void setInput(const double* data) {
        //map the data to the in vector
        for (int i = 0; i < N; i++) {
            in[i] = data[i];
        }
    }
    double* getInput() {
		return in;
    }
    float getMagnitude(int k) {
        if (k < 0 || k > N / 2) {
            throw std::out_of_range("k is out of range");
        }
        return sqrt(out[k][0] * out[k][0] + out[k][1] * out[k][1]);
	}
};


