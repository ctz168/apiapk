FROM node:20-slim

# 安装JDK和Android SDK构建工具
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    wget \
    unzip \
    git \
    && rm -rf /var/lib/apt/lists/*

# 安装Android SDK
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN mkdir -p $ANDROID_HOME/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d $ANDROID_HOME/cmdline-tools && \
    mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses && \
    sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# 安装Gradle
ENV GRADLE_HOME=/opt/gradle
RUN wget -q https://services.gradle.org/distributions/gradle-8.2-bin.zip -O /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    rm /tmp/gradle.zip && \
    ln -s /opt/gradle-8.2/bin/gradle /usr/local/bin/gradle

WORKDIR /app

# 复制项目文件
COPY . .

# 构建APK
RUN cd android-app && gradle assembleDebug --no-daemon

# 安装NPM依赖
RUN cd npm-cli && npm install

# 输出
RUN echo "Build complete!" && \
    echo "APK: android-app/app/build/outputs/apk/debug/app-debug.apk" && \
    echo "NPM: npm-cli/"

CMD ["bash"]
