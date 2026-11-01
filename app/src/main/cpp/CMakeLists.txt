cmake_minimum_required(VERSION 3.22.1)
project(echomute)
add_library(echomute SHARED native-lib.cpp fingerprint.cpp)
