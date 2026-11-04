FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=${PATH}:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/gradle/bin

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk curl unzip wget ca-certificates bash git && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses >/dev/null && \
    sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

RUN wget -q https://services.gradle.org/distributions/gradle-8.13-bin.zip -O /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    mv /opt/gradle-8.13 /opt/gradle && \
    rm /tmp/gradle.zip

WORKDIR /workspace
