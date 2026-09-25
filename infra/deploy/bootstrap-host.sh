#!/usr/bin/env bash
# Infrastructure bootstrap only. Never run submitted source on a host.
set -euo pipefail
role="${1:?app or worker required}"
case "$role" in app|worker) ;; *) exit 2 ;; esac
test "$(id -u)" = 0
if systemctl is-active --quiet pairforge-api || systemctl is-active --quiet pairforge-worker; then
    echo 'Close admission and stop PairForge before host maintenance' >&2
    exit 1
fi
. /etc/os-release
test "$ID" = ubuntu
test "$VERSION_ID" = 24.04
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl openssl openjdk-21-jre-headless docker.io docker-compose-v2
if ! command -v pwsh >/dev/null; then
    repository_package=$(mktemp)
    trap 'rm -f "$repository_package"' EXIT
    curl --fail --silent --show-error --location https://packages.microsoft.com/config/ubuntu/24.04/packages-microsoft-prod.deb -o "$repository_package"
    dpkg -i "$repository_package"
    rm -f "$repository_package"
    trap - EXIT
    apt-get update
    apt-get install -y --no-install-recommends powershell
fi
install -d -m 0755 /etc/docker /etc/pairforge /etc/pairforge/tls /opt/pairforge/releases
if ! test -f /etc/docker/daemon.json; then
    printf '%s\n' '{"log-driver":"local","log-opts":{"max-size":"10m","max-file":"3"}}' > /etc/docker/daemon.json
fi
systemctl enable --now docker
systemctl restart docker
if test "$role" = app; then
    # Ubuntu 24.04 does not provide an awscli apt candidate. Use the official
    # pinned v2 installer and verify its signature with AWS's published key.
    if ! command -v aws >/dev/null; then
        apt-get install -y --no-install-recommends unzip gnupg
        installer=$(mktemp -d)
        trap 'rm -rf -- "$installer"' EXIT
        export GNUPGHOME="$installer/gnupg"
        install -d -m 0700 "$GNUPGHOME"
        fingerprint=FB5DB77FD5C118B80511ADA8A6310ACC4672475C
        # AWS's published signing key, including its renewal through July 2027.
        # Source: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html
        cat > "$installer/aws-cli.asc" <<'AWS_CLI_PUBLIC_KEY'
-----BEGIN PGP PUBLIC KEY BLOCK-----

mQINBF2Cr7UBEADJZHcgusOJl7ENSyumXh85z0TRV0xJorM2B/JL0kHOyigQluUG
ZMLhENaG0bYatdrKP+3H91lvK050pXwnO/R7fB/FSTouki4ciIx5OuLlnJZIxSzx
PqGl0mkxImLNbGWoi6Lto0LYxqHN2iQtzlwTVmq9733zd3XfcXrZ3+LblHAgEt5G
TfNxEKJ8soPLyWmwDH6HWCnjZ/aIQRBTIQ05uVeEoYxSh6wOai7ss/KveoSNBbYz
gbdzoqI2Y8cgH2nbfgp3DSasaLZEdCSsIsK1u05CinE7k2qZ7KgKAUIcT/cR/grk
C6VwsnDU0OUCideXcQ8WeHutqvgZH1JgKDbznoIzeQHJD238GEu+eKhRHcz8/jeG
94zkcgJOz3KbZGYMiTh277Fvj9zzvZsbMBCedV1BTg3TqgvdX4bdkhf5cH+7NtWO
lrFj6UwAsGukBTAOxC0l/dnSmZhJ7Z1KmEWilro/gOrjtOxqRQutlIqG22TaqoPG
fYVN+en3Zwbt97kcgZDwqbuykNt64oZWc4XKCa3mprEGC3IbJTBFqglXmZ7l9ywG
EEUJYOlb2XrSuPWml39beWdKM8kzr1OjnlOm6+lpTRCBfo0wa9F8YZRhHPAkwKkX
XDeOGpWRj4ohOx0d2GWkyV5xyN14p2tQOCdOODmz80yUTgRpPVQUtOEhXQARAQAB
tCFBV1MgQ0xJIFRlYW0gPGF3cy1jbGlAYW1hem9uLmNvbT6JAlQEEwEIAD4CGwMF
CwkIBwIGFQoJCAsCBBYCAwECHgECF4AWIQT7Xbd/1cEYuAURraimMQrMRnJHXAUC
akV0ygUJDqP4lQAKCRCmMQrMRnJHXFHjD/9eyZLYcKuQOlLvtqSDtUBiEZf6ZZjM
i3ygYH8rJNtuToUH+HvSpe819urJCquXhDrlK6N+aqW0hCLtNABJG/vsafIgvIYJ
hSGgpgtNnQyMV1jViRWqPjbouw8OkYKBThUfT1i2Y+wn58ifs6ODBCmTexWtXspA
Si+Gt49xDOW0APmbOPnI+a4HJW6tVEo6MWS0WjzpiBayR3d1A4pt4YrPfSdDgpLo
h2SLQqlRqvvVZJaWBjhkErNFpfsBA06sDcPEOb0G8LBUbR4WOcdvhe5LubJbZuxC
AG9kNPCVeQP1ixwjgjXKysaxeQ6rv0VzIQgRp6tLVLWhy6AKDNvLjFSsmXZ1Wl08
Y/RlOHXlzLuQMRE6sR1wOdRxc9TsrNWTGiBK65cvSWOy03JeBkQQ8pesqltiyxI9
U21kkgiXtTSKNGfKK8pO27D81YANhRqPK7iTp6kuFiY2WtOg90KTMNlIT+Ff85Y2
b1rHj6Z0SrCkJujhWk3IBPic/wJgz01LEc/OAdUPlby90RJZcIBhSlWhT7mXnXIO
c0HWlNQrns2s3CTyYwZSiSlYe9ApeLwhjDo8NhbFuCAy61l6O5UsR4AfZxx/rGKv
2wFb1/RN/P4gNe6vmxZAPjR0AQcwD3tc2McimOLr/22kmPz8IH3I0X7WoSFr0Biz
E91G7bb0hOb/cA==
=knv7
-----END PGP PUBLIC KEY BLOCK-----
AWS_CLI_PUBLIC_KEY
        gpg --batch --import "$installer/aws-cli.asc"
        gpg --batch --with-colons --fingerprint "$fingerprint" | grep -q "^fpr:::::::::$fingerprint:"
        curl --fail --silent --show-error --location https://awscli.amazonaws.com/awscli-exe-linux-x86_64-2.37.1.zip -o "$installer/aws.zip"
        curl --fail --silent --show-error --location https://awscli.amazonaws.com/awscli-exe-linux-x86_64-2.37.1.zip.sig -o "$installer/aws.zip.sig"
        gpg --batch --status-fd 1 --verify "$installer/aws.zip.sig" "$installer/aws.zip" > "$installer/signature-status"
        grep -q "^\[GNUPG:\] VALIDSIG $fingerprint " "$installer/signature-status"
        if grep -Eq '^\[GNUPG:\] (EXPKEYSIG|EXPSIG|REVKEYSIG|BADSIG|ERRSIG|KEYEXPIRED|SIGEXPIRED)' "$installer/signature-status"; then
            echo 'AWS CLI signing key or signature is invalid or expired' >&2
            exit 1
        fi
        unzip -q "$installer/aws.zip" -d "$installer"
        "$installer/aws/install" --bin-dir /usr/local/bin --install-dir /usr/local/aws-cli
        rm -rf -- "$installer"
        unset GNUPGHOME
        trap - EXIT
    fi
    id pairforge-api >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin pairforge-api
    id caddy >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin caddy
    install -d -m 0750 -o caddy -g caddy /var/lib/caddy
    install -d -m 0700 /etc/pairforge/secrets /var/lib/pairforge-backups
    # Extract the reviewed Caddy binary from its immutable trusted image.
    image='caddy@sha256:6aeddd44c3078b0f9a35206472a11420648a79c184603ef95957d0a20044cb2b'
    docker pull "$image"
    container=$(docker create "$image")
    trap 'docker rm "$container" >/dev/null' EXIT
    docker cp "$container:/usr/bin/caddy" /usr/local/bin/caddy
    chmod 0755 /usr/local/bin/caddy
    docker rm "$container" >/dev/null
    trap - EXIT
else
    id pairforge-worker >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin pairforge-worker
    usermod -aG docker pairforge-worker
    install -d -m 0700 -o pairforge-worker -g pairforge-worker /var/lib/pairforge-executions
    # IMDS is disabled after initial cloud-init, so a later boot otherwise falls
    # back to DataSourceNone and can regenerate the SSH host key. Network and
    # authorized_keys must already have been persisted by the first boot.
    test -f /etc/netplan/50-cloud-init.yaml
    test -f /home/ubuntu/.ssh/authorized_keys
    touch /etc/cloud/cloud-init.disabled
    chmod 0644 /etc/cloud/cloud-init.disabled
fi
java -version
pwsh -NoProfile -Command '$PSVersionTable.PSVersion.ToString()'
docker info --format 'Docker {{.ServerVersion}}; cgroup {{.CgroupVersion}}; security {{json .SecurityOptions}}'
printf 'Bootstrap complete: %s\n' "$role"
