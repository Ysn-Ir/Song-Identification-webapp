#include "processing.h"
#include <string>
#include <cstdlib>


int main(int argc , char* argv[]) {

    if (argc < 3)return -1;
    std::string command = argv[1];
    std::string path = argv[2];
    initHamming();
    FFT fft(WINDOW_SIZE);

    // run this once to create the database or append to it , commented cuz it doesnt need to run everytime its all stored in a binary file
    if(command=="build")
        processInBulk(path.c_str(), fft);

    // load the database in memory
    else if (command == "recognize")
    {
        auto db = loadDatabase("database.bin");
        //this filename just for developing and testing purposes
		//const char* filename = "C:/Users/khali/OneDrive/Bureau/learning/datascience/test2.ogg";
        // this one is what path actually passed to the file 
		const char* filename = path.c_str();
        //the core call
        int songID=recognize(filename, db, fft);
		std::cerr << "{\"song_id\": " << songID << "}";   
    }
    else if(command=="getFingerprint")
    {
        double threshold = 2.0;
        if (argc >= 4) {
            try {
                threshold = std::stod(argv[3]);
            } catch (...) {
                threshold = 2.0;
            }
        }
        getFingerPrint(path.c_str(), fft, threshold);
	}

    return 0;
}