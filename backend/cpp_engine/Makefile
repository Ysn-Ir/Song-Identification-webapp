CXX = g++
CXXFLAGS = -std=c++17 -O2 -Wall -Wno-sign-compare -D_USE_MATH_DEFINES
INCLUDES = -Iinclude
LIBS = -lfftw3 -lsndfile -lm -lstdc++fs

SRCS = main.cpp audio.cpp FFT.cpp processing.cpp
TARGET = shazam

all: $(TARGET)

$(TARGET): $(SRCS)
	$(CXX) $(CXXFLAGS) $(INCLUDES) $(SRCS) -o $(TARGET) $(LIBS)

clean:
	rm -f $(TARGET)
