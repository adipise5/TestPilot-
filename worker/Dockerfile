FROM maven:3.9-eclipse-temurin-17

RUN groupadd --gid 10001 testpilot \
    && useradd --uid 10001 --gid 10001 --create-home --home-dir /home/testpilot testpilot \
    && mkdir -p /workspace /home/testpilot/.m2/repository \
    && chown -R 10001:10001 /workspace /home/testpilot

COPY --chown=10001:10001 worker/isolation-probe.sh /usr/local/bin/testpilot-isolation-probe
RUN chmod 0555 /usr/local/bin/testpilot-isolation-probe

ENV HOME=/home/testpilot
WORKDIR /workspace
USER 10001:10001

ENTRYPOINT []
CMD ["mvn", "--version"]
