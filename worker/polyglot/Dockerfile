FROM node:22-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5 AS node
FROM maven:3.9-eclipse-temurin-17@sha256:1e539ad894cb86c5b87a9c81223732a90c6cb7c23e4ac206c2cea892538fd8d7

COPY --from=node /usr/local/ /opt/node/
ENV PATH="/opt/python/bin:/opt/node/bin:${PATH}"
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/python \
    && /opt/python/bin/pip install --no-cache-dir pytest==8.4.2 coverage==7.10.6
COPY worker/polyglot/package*.json /opt/testpilot-js/
RUN cd /opt/testpilot-js && npm ci --ignore-scripts --no-audit --no-fund
COPY worker/polyglot/default-pom.xml /opt/testpilot/default-pom.xml
COPY worker/polyglot/bootstrap /opt/bootstrap
RUN mvn -B --no-transfer-progress -f /opt/testpilot/default-pom.xml -Dmaven.repo.local=/opt/m2 dependency:go-offline \
    && cp /opt/testpilot/default-pom.xml /opt/bootstrap/pom.xml \
    && mvn -B --no-transfer-progress -f /opt/bootstrap/pom.xml -Dmaven.repo.local=/opt/m2 test \
    && mvn -B --no-transfer-progress -f /opt/bootstrap/pom.xml -Dmaven.repo.local=/opt/m2 dependency:copy -Dartifact=org.jacoco:org.jacoco.agent:0.8.12:jar:runtime -DoutputDirectory=/opt/coverage \
    && mvn -B --no-transfer-progress -f /opt/bootstrap/pom.xml -Dmaven.repo.local=/opt/m2 dependency:copy -Dartifact=org.jacoco:org.jacoco.cli:0.8.12:jar:nodeps -DoutputDirectory=/opt/coverage \
    && chmod -R a+rX /opt/m2 /opt/coverage
COPY worker/polyglot/*.py worker/polyglot/*.ini worker/polyglot/*.mjs worker/polyglot/*.cjs /opt/testpilot/
RUN groupadd --gid 10001 testpilot && useradd --uid 10001 --gid 10001 --no-create-home testpilot
USER 10001:10001
WORKDIR /work
ENTRYPOINT ["/opt/python/bin/python", "-I", "/opt/testpilot/runner.py"]
