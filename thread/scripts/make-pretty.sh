#!/usr/bin/env bash

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)

ANDROID_ROOT_DIR=$(realpath $SCRIPT_DIR/../../../../../)

GOOGLE_JAVA_FORMAT=$ANDROID_ROOT_DIR/prebuilts/tools/common/google-java-format/google-java-format
ANDROID_BP_FORMAT=$ANDROID_ROOT_DIR/prebuilts/build-tools/linux-x86/bin/bpfmt
AIDL_FORMAT=$ANDROID_ROOT_DIR/system/tools/aidl/aidl-format.sh

CONNECTIVITY_THREAD_DIR=$ANDROID_ROOT_DIR/packages/modules/Connectivity/thread/
OPENTHREAD_DIR=$ANDROID_ROOT_DIR/external/openthread/
OTBR_POSIX_DIR=$ANDROID_ROOT_DIR/external/ot-br-posix/

JAVA_DIRS=(
    $CONNECTIVITY_THREAD_DIR
    $OTBR_POSIX_DIR
)

BP_DIRS=(
    $CONNECTIVITY_THREAD_DIR
    $OPENTHREAD_DIR
    $OTBR_POSIX_DIR
)

AIDL_DIRS=(
    $CONNECTIVITY_THREAD_DIR
    $OTBR_POSIX_DIR
)

for DIR in "${JAVA_DIRS[@]}"; do
    $GOOGLE_JAVA_FORMAT --aosp -i $(find $DIR -name "*.java")
done

for DIR in "${BP_DIRS[@]}"; do
    $ANDROID_BP_FORMAT -w $(find $DIR -name "*.bp")
done

for DIR in "${AIDL_DIRS[@]}"; do
    $AIDL_FORMAT -w $(find $DIR -name "*.aidl")
done
