#!/bin/sh
JAVA_HOME="/usr/lib/jvm/java"
V8_HOME="/opt/v8/v8"
CXX="g++ -shared -fPIC"
INCLUDES="-I$JAVA_HOME/include -I$JAVA_HOME/include/linux -I$V8_HOME/include -I./native_src"
LDFLAGS="-L$V8_HOME/out.gn/x64.release/obj"
LIBS="-lv8_monolith"
CFLAGS="-pthread -std=c++17 -DV8_COMPRESS_POINTERS"
EXEC="$CXX $INCLUDES native_src/org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma.cpp -o native/linux/x86_64/libnativeecma.so $LDFLAGS $LIBS $CFLAGS"
echo $EXEC
eval $EXEC
