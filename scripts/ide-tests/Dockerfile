FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    xvfb xauth openbox dbus-x11 libxtst6 libxi6 libxrender1 libxrandr2 libasound2 \
    libnss3 libgbm1 libgtk-3-0 libxss1 libx11-xcb1 libfontconfig1 \
    fonts-dejavu-core fonts-noto-color-emoji procps unzip zip curl git bash x11-utils \
    && rm -rf /var/lib/apt/lists/*

ARG TEST_UID=1000
RUN useradd --create-home --uid "$TEST_UID" --shell /bin/bash tester
USER tester
WORKDIR /work
ENV GRADLE_USER_HOME=/home/tester/.gradle SHELL=/bin/bash
