#!/usr/bin/env bash

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

ANDROID_ROOT_DIR=$(realpath $SCRIPT_DIR/../../../../../)

GOOGLE_JAVA_FORMAT=$ANDROID_ROOT_DIR/prebuilts/tools/common/google-java-format/google-java-format
ANDROID_BP_FORMAT=$ANDROID_ROOT_DIR/prebuilts/build-tools/linux-x86/bin/bpfmt
AIDL_FORMAT=$ANDROID_ROOT_DIR/system/tools/aidl/aidl-format.sh

$GOOGLE_JAVA_FORMAT --aosp -i $(find $SCRIPT_DIR/../ -name "*.java")
$ANDROID_BP_FORMAT -w $(find $SCRIPT_DIR/../ -name "*.bp")
$AIDL_FORMAT -w $(find $SCRIPT_DIR/../ -name "*.aidl")
