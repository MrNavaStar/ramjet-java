FROM alpine:latest

RUN apk add --no-cache \
    build-base \
    musl-dev \
    linux-headers \
    autoconf \
    automake \
    libtool \
    pkgconf \
    gettext-dev \
    git \
    flex \
    bison

WORKDIR /src
RUN git clone --depth 1 https://github.com/util-linux/util-linux.git .

RUN ./autogen.sh
RUN CC=gcc \
    ./configure \
    --prefix=/usr \
    --disable-all-programs \
    --enable-nsenter \
    --disable-nls \
    --without-systemd \
    --without-python \
    --without-ncurses \
    CFLAGS="-Os -ffunction-sections -fdata-sections" \
    LDFLAGS="-static -s -Wl,--gc-sections"

RUN make -j$(nproc)
RUN mkdir -p /out && cp /src/sys-utils/nsenter /out/nsenter