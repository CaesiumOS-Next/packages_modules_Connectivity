#!/usr/bin/env bash

ANDROID_ROOT_DIR=$(
    while [ ! -d ".repo" ]; do cd ..; done
    pwd
)

GOOGLE_JAVA_FORMAT=$ANDROID_ROOT_DIR/prebuilts/tools/common/google-java-format/google-java-format
ANDROID_BP_FORMAT=$ANDROID_ROOT_DIR/prebuilts/build-tools/linux-x86/bin/bpfmt
AIDL_FORMAT=$ANDROID_ROOT_DIR/system/tools/aidl/aidl-format.sh

CONNECTIVITY_DIR=$ANDROID_ROOT_DIR/packages/modules/Connectivity
OPENTHREAD_DIR=$ANDROID_ROOT_DIR/external/openthread
OTBR_POSIX_DIR=$ANDROID_ROOT_DIR/external/ot-br-posix

CODE_DIR=$(git rev-parse --show-toplevel)
if [[ $CODE_DIR == $CONNECTIVITY_DIR ]]; then
    CODE_DIR=$CODE_DIR"/thread"
fi

$GOOGLE_JAVA_FORMAT --aosp -i $(find $CODE_DIR -name "*.java")
$ANDROID_BP_FORMAT -w $(find $CODE_DIR -name "*.bp")
$AIDL_FORMAT -w $(find $CODE_DIR -name "*.aidl")
