FROM docker.io/library/python@sha256:b2bf8fba2b1ccfc91c736c8dd80c4218a5c165d547d8983b7c5a47f3472fe167

WORKDIR /opt/fileagent

COPY --chown=65532:65532 runner.py /opt/fileagent/runner.py

RUN groupadd --gid 65532 sandbox \
    && useradd --uid 65532 --gid 65532 --no-create-home --shell /usr/sbin/nologin sandbox

USER 65532:65532

ENTRYPOINT ["python3", "-I", "-S", "/opt/fileagent/runner.py"]
