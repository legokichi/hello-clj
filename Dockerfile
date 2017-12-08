FROM ubuntu:16.04
#ENV DEBIAN_FRONTEND "noninteractive"

RUN rm -rf /var/lib/apt/lists/*
RUN apt-get update -y
RUN apt-get -y \
  -o Dpkg::Options::="--force-confdef" \
  -o Dpkg::Options::="--force-confold" dist-upgrade


RUN apt-get update
RUN apt-get install -y apt-transport-https software-properties-common ppa-purge apt-utils ca-certificates git
RUN add-apt-repository ppa:webupd8team/java
RUN apt-get update
RUN apt-get install -y default-jre
RUN dpkg-reconfigure debconf

RUN echo debconf shared/accepted-oracle-license-v1-1 select true | \
    debconf-set-selections
RUN echo debconf shared/accepted-oracle-license-v1-1 seen true | \
    debconf-set-selections
RUN apt-get install -f
RUN apt-get purge oracle-java8-installer
RUN apt-get -y install oracle-java8-installer
RUN apt-get -y install oracle-java8-set-default

RUN wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein -P /usr/local/bin/ && chmod +x /usr/local/bin/lein

COPY ./* /opt/media-redactor/
WORKDIR /opt/media-redactor/

RUN lein deps