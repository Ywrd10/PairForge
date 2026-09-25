#!/usr/bin/env bash
# Run only on the administrator's local Linux/WSL environment; never on EC2.
set -euo pipefail
directory="${1:?output directory required}"
if test -e "$directory"; then echo 'PKI directory exists; refusing overwrite' >&2; exit 1; fi
mkdir -m 0700 "$directory"
cd "$directory"
umask 077
openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 365 \
  -subj '/CN=PairForge private demo CA' \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -keyout ca.key -out ca.crt >/dev/null 2>&1
for service in postgres rabbitmq; do
  mkdir -m 0700 "$service"
  case "$service" in postgres) name=db.pairforge.internal ;; rabbitmq) name=rabbit.pairforge.internal ;; esac
  cat > "$service/server.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:$name
EOF
  openssl req -new -newkey rsa:3072 -nodes -sha256 -subj "/CN=$name" \
    -keyout "$service/server.key" -out "$service/server.csr" >/dev/null 2>&1
  openssl x509 -req -in "$service/server.csr" -CA ca.crt -CAkey ca.key \
    -CAcreateserial -days 90 -sha256 -extfile "$service/server.ext" \
    -out "$service/server.crt" >/dev/null 2>&1
  cp ca.crt "$service/ca.crt"
  openssl verify -CAfile ca.crt -verify_hostname "$name" "$service/server.crt"
done
openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 365 \
  -subj '/CN=PairForge backup recipient' \
  -keyout backup.key -out backup.crt >/dev/null 2>&1
chmod 0600 ca.key backup.key postgres/server.key rabbitmq/server.key
echo 'Generated verified service certificates and offline backup recipient. Do not transfer ca.key or backup.key to either EC2 host.'
