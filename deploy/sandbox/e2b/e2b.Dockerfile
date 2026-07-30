FROM e2bdev/base:latest

USER root
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       ca-certificates curl gcc g++ git maven python3 \
    && curl -fsSL \
       'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20_8.tar.gz' \
       -o /tmp/temurin.tar.gz \
    && echo 'be7668bc030d578b83d6d5ef9221d6d6729bbbca8cf94a7d52e16ac68b5a5a35  /tmp/temurin.tar.gz' \
       | sha256sum -c - \
    && install -d -m 0555 /opt/yanban/temurin-17 \
    && tar -xzf /tmp/temurin.tar.gz -C /opt/yanban/temurin-17 \
       --strip-components=1 \
    && rm -f /tmp/temurin.tar.gz \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/opt/yanban/temurin-17
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN update-ca-certificates -f \
    && "$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 \
       | grep -q 'java.vendor = Eclipse Adoptium' \
    && "$JAVA_HOME/bin/keytool" -list -cacerts -storepass changeit \
       | grep -q trustedCertEntry \
    && mvn -version | grep -q 'Java version: 17'
COPY yanban_runner.py /usr/local/lib/yanban/yanban_runner.py
COPY yanban_java_dependencies.py /usr/local/lib/yanban/yanban_java_dependencies.py
RUN printf '%s\n' \
      '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">' \
      '<mirrors><mirror><id>central-only</id><name>Maven Central only</name>' \
      '<url>https://repo.maven.apache.org/maven2</url><mirrorOf>*</mirrorOf>' \
      '</mirror></mirrors></settings>' \
      > /opt/yanban/maven-central-settings.xml \
    && chmod 0444 /opt/yanban/maven-central-settings.xml
RUN chmod 0555 /usr/local/lib/yanban/yanban_runner.py \
    && chmod 0555 /usr/local/lib/yanban/yanban_java_dependencies.py \
    && ln -s /usr/local/lib/yanban/yanban_runner.py /usr/local/bin/yanban-runner \
    && ln -s /usr/local/lib/yanban/yanban_java_dependencies.py /usr/local/bin/yanban-java-dependencies
USER user
WORKDIR /home/user/project
