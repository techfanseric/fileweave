cmake_minimum_required(VERSION 3.6)

project(MaterialFiles C)

add_library(hiddenapi SHARED
        src/main/jni/hiddenapi.c)
target_compile_options(hiddenapi
        PRIVATE
        -Wall
        -Werror)

find_library(LOG_LIBRARY log)
add_library(syscall SHARED
        src/main/jni/syscall.c)
target_compile_options(syscall
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(syscall
        PRIVATE
        ${LOG_LIBRARY})

add_library(gocryptfs SHARED IMPORTED)
set_target_properties(gocryptfs PROPERTIES IMPORTED_LOCATION
    ${PROJECT_SOURCE_DIR}/../third_party/libgocryptfs/build/${ANDROID_ABI}/libgocryptfs.so)
add_library(fileweave_vault SHARED src/main/jni/vault.c)
target_include_directories(fileweave_vault PRIVATE
    ${PROJECT_SOURCE_DIR}/../third_party/libgocryptfs/build/${ANDROID_ABI})
target_link_libraries(fileweave_vault PRIVATE gocryptfs)
