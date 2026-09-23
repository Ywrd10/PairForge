FROM eclipse-temurin@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438
RUN mkdir /source /work && chmod 755 /source /work
USER 10001:10001
WORKDIR /work
ENTRYPOINT ["/bin/sleep"]
CMD ["infinity"]
