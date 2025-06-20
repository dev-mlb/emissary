ARG target_os=ubi8



FROM redhat/ubi8 AS base_ubi8

ENV JAVA_TOOL_OPTIONS -Dfile.encoding=UTF8
ENV PROJECT_BASE=/opt/emissary

ARG java_version=11
ARG user=emissary
ARG group=emissary
ARG uid=1000
ARG gid=1000
RUN rpm --import https://yum.corretto.aws/corretto.key \
     && curl -L -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo \
     && dnf install -y java-${java_version}-amazon-corretto-devel \
     && dnf install -y glibc-langpack-en lsof git \
     && groupadd -g ${gid} ${group} \
     && useradd -u ${uid} -g ${group} -m -s /bin/sh ${user} \
     && dnf clean all -y \
     && rm -rf /var/cache/yum



FROM centos:7 AS base_centos7

ENV JAVA_TOOL_OPTIONS -Dfile.encoding=UTF8
ENV PROJECT_BASE=/opt/emissary

ARG java_version=11
ARG user=emissary
ARG group=emissary
ARG uid=1000
ARG gid=1000
RUN sed -i s/mirrorlist.centos.org/vault.centos.org/g /etc/yum.repos.d/CentOS-*.repo \
     && sed -i s/mirror.centos.org/vault.centos.org/g /etc/yum.repos.d/CentOS-*.repo \
     && sed -i s/#baseurl/baseurl/g /etc/yum.repos.d/CentOS-*.repo \
     && yum update -y \
     && yum upgrade -y \
     && rpm --import https://yum.corretto.aws/corretto.key \
     && curl -L -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo \
     && yum install -y java-${java_version}-amazon-corretto-devel \
     && yum install -y which lsof git \
     && groupadd -g ${gid} ${group} \
     && useradd -u ${uid} -g ${group} -m -s /bin/bash ${user} \
     && yum clean all -y \
     && rm -rf /var/cache/yum



FROM alpine:3 AS base_alpine3

ENV JAVA_TOOL_OPTIONS -Dfile.encoding=UTF8
ENV PROJECT_BASE=/opt/emissary

ARG java_version=11
ARG user=emissary
ARG group=emissary
ARG uid=1000
ARG gid=1000
RUN  apk update \
     && apk upgrade \
     && apk add ca-certificates \
     && update-ca-certificates \
     && apk add --update coreutils && rm -rf /var/cache/apk/*   \
     && apk add --update openjdk${java_version} tzdata curl unzip bash which lsof git \
     && apk add --no-cache nss \
     && addgroup --gid ${gid} ${group} \
     && adduser --disabled-password --uid ${uid} --ingroup ${group} ${user} \
     && rm -rf /var/cache/apk/*



FROM base_${target_os} AS build

ENV MAVEN_OPTS -Xms512M -Xmx1024M -Xss1M -Djava.awt.headless=true
ENV MAVEN_HOME /opt/maven

ARG maven_version=3.9.10
RUN curl -L -o /tmp/maven.tar.gz https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${maven_version}/apache-maven-${maven_version}-bin.tar.gz \
     && tar xvf /tmp/maven.tar.gz -C /opt \
     && ln -s /opt/apache-maven-${maven_version} ${MAVEN_HOME} \
     && ln -s ${MAVEN_HOME}/bin/mvn /usr/bin/mvn

COPY . ${PROJECT_BASE}
RUN chown -R ${user}:${group} ${PROJECT_BASE} \
    && chmod -R 744 ${PROJECT_BASE}

USER ${user}
WORKDIR ${PROJECT_BASE}

RUN git init --quiet \
    && git config user.email "emissary@build.dne" \
    && git config user.name "emissary" \
    && git add . \
    && git commit --quiet -am.

ARG java_compiler=11
ARG maven_phases='clean verify'
ARG maven_profiles='-Pdist'
RUN --mount=type=cache,uid=${uid},gid=${gid},target=/home/${user}/.m2 \
    mvn -V -B -e -ntp "-Dstyle.color=always" -Dmaven.compiler.release=${java_compiler} ${maven_phases} ${maven_profiles}

ARG verify_build=true
RUN if ${verify_build} ; then ./contrib/ci/detect-changes.sh ; fi



FROM base_${target_os}

COPY --from=build ${PROJECT_BASE}/target/emissary-*-dist.tar.gz /tmp

RUN tar -xf /tmp/emissary-*-dist.tar.gz -C /opt/ \
    && version=`ls /opt | grep emissary- | awk '{ print $1 }'`  \
    && echo "Linking /opt/${version} to ${PROJECT_BASE}"  \
    && ln -s /opt/${version} ${PROJECT_BASE}  \
    && mkdir -p ${PROJECT_BASE}/localoutput  \
    && mkdir -p ${PROJECT_BASE}/target/data  \
    && chmod -R a+rw ${PROJECT_BASE} \
    && chown -R ${user}:${group} ${PROJECT_BASE}* \
    && rm -f /tmp/*

USER ${user}

WORKDIR ${PROJECT_BASE}

VOLUME ${PROJECT_BASE}/target/data
VOLUME ${PROJECT_BASE}/localoutput

EXPOSE 8001

ENTRYPOINT ["./emissary"]

CMD ["server", "-a", "2", "-p", "8001"]
