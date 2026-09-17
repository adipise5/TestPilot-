FROM node:22-bookworm-slim AS node
FROM maven:3.9-eclipse-temurin-17

COPY --from=node /usr/local/ /opt/node/
ENV PATH="/opt/python/bin:/opt/node/bin:${PATH}"
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/python \
    && /opt/python/bin/pip install --no-cache-dir pytest==8.4.2
COPY worker/polyglot/package*.json /opt/testpilot-js/
RUN cd /opt/testpilot-js && npm ci --ignore-scripts --no-audit --no-fund
COPY worker/polyglot/default-pom.xml /opt/testpilot/default-pom.xml
COPY worker/polyglot/bootstrap /opt/bootstrap
RUN mvn -B --no-transfer-progress -f /opt/testpilot/default-pom.xml -Dmaven.repo.local=/opt/m2 dependency:go-offline \
    && cp /opt/testpilot/default-pom.xml /opt/bootstrap/pom.xml \
    && mvn -B --no-transfer-progress -f /opt/bootstrap/pom.xml -Dmaven.repo.local=/opt/m2 test \
    && chmod -R a+rX /opt/m2
COPY worker/polyglot/*.py worker/polyglot/*.ini worker/polyglot/*.mjs worker/polyglot/*.cjs /opt/testpilot/
RUN groupadd --gid 10001 testpilot && useradd --uid 10001 --gid 10001 --no-create-home testpilot
USER 10001:10001
WORKDIR /work
ENTRYPOINT ["/opt/python/bin/python", "-I", "/opt/testpilot/runner.py"]
