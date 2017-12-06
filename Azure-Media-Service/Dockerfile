FROM ubuntu
ENV DEBIAN_FRONTEND "noninteractive"

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
RUN apt-get install -y oracle-java8-installer oracle-java8-set-default
RUN apt-get insatll -y maven

COPY ./* /opt/media-redactor/
WORKDIR /opt/media-redactor/

RUN mvn clean package
RUN mvn compile
RUN mvn package

CMD [mvn exec:java]
