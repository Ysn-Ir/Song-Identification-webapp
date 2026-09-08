@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
cl /EHsc /std:c++17 /O2 /I include main.cpp audio.cpp FFT.cpp processing.cpp /link /LIBPATH:lib fftw3.lib sndfile.lib /OUT:shazam.exe
