FROM python@sha256:2325bb286ec344af3e5898cc224b5844e2707ac6e26b1632516fd3edc84a5e26
RUN mkdir /source /work && chmod 755 /source /work \
    && rm -rf /usr/local/lib/python3.13/site-packages/* /usr/local/lib/python3.13/ensurepip /usr/local/bin/pip*
USER 10001:10001
WORKDIR /work
ENTRYPOINT ["/bin/sleep"]
CMD ["infinity"]
