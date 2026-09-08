CXX = g++
CXXFLAGS = -std=c++17 -O2 -Wall
INCLUDES = -Iinclude
LIBS = -lfftw3 -lsndfile

SRCS = main.cpp audio.cpp FFT.cpp processing.cpp
TARGET = shazam

all: $(TARGET)

$(TARGET): $(SRCS)
	$(CXX) $(CXXFLAGS) $(INCLUDES) $(SRCS) $(LIBS) -o $(TARGET)

clean:
	rm -f $(TARGET)
