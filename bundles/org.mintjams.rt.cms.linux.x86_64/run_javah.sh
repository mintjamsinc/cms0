#!/bin/sh
JAVA_HOME="/usr/lib/jvm/java"
JAVAC="$JAVA_HOME/bin/javac"
CLASSES_DIR="-classpath ../org.mintjams.rt.cms/bin"
SRC_DIR="-h ./src"
EXEC="$JAVAC $CLASSES_DIR $SRC_DIR org.mintjams.rt.cms.internal.script.engine.nativeecma.NativeEcma"
echo $EXEC
eval $EXEC
