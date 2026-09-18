# The native capture core. No STL, no libc++, no shared runtime: the smallest
# .so that can hold a signal handler, so the module's size stays a selling point.
cmake_minimum_required(VERSION 3.18.1)
project(atlas_native C)

add_library(atlas-native SHARED atlas_crash.c atlas_jni.c)

target_compile_options(atlas-native PRIVATE
        -Wall -Wextra -Werror -fvisibility=hidden -ffunction-sections -fdata-sections
        -fno-omit-frame-pointer -O2)

# -ldl for dl_iterate_phdr is not needed (we read /proc/self/maps); the unwinder
# is in the C runtime. Strip and drop unused sections: a few tens of KB per ABI.
target_link_options(atlas-native PRIVATE -Wl,--gc-sections -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384)

# The build id the server keys symbols on is the .so's own GNU build-id, read
# back out of the binary by the upload step.
