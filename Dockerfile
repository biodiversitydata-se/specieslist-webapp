FROM tomcat:9.0-jdk11-temurin

RUN mkdir -p \
	/data/specieslist-webapp/config

COPY sbdi/data/config/logback.xml /data/specieslist-webapp/config/logback.xml
COPY build/libs/specieslist-webapp-[0-9].[0-9].[0-9].war $CATALINA_HOME/webapps/ROOT.war

ENV DOCKERIZE_VERSION=v0.9.3

RUN apt-get update \
    && apt-get install -y wget \
    && wget -O - https://github.com/jwilder/dockerize/releases/download/$DOCKERIZE_VERSION/dockerize-linux-amd64-$DOCKERIZE_VERSION.tar.gz | tar xzf - -C /usr/local/bin \
    && apt-get autoremove -yqq --purge wget && rm -rf /var/lib/apt/lists/*
