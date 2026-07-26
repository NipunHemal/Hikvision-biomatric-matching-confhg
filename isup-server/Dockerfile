# Biometric Device Hub — Docker image (Linux)
#
# ⚠️ IMPORTANT: the ISUP SDK is native. This LINUX image needs the LINUX build
# of the ISUP SDK (.so files) in lib/ — NOT the Windows .dll files used for local
# dev. Before building, replace lib/ with the Linux ISUP SDK:
#     lib/libHCISUPCMS.so  libHCISUPAlarm.so  libHCISUPSS.so  libHCISUPStream.so
#     lib/libcrypto.so     libssl.so          libHCAapSDKCom/ (or its .so set)
#     lib/jna.jar          examples.jar       gson-2.8.9.jar
# The Linux ISUP SDK comes from the same Hikvision SDK package, Linux build.
# ---------------------------------------------------------------------------

# ---- build stage: compile the Java sources ----
# Uses lib-linux/ (git-tracked, Linux .so + jars) — NOT the local dev lib/
# which holds Windows .dll and is gitignored (invisible to Dokploy's git build).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY src ./src
COPY lib-linux ./lib
RUN find src/main/java -name '*.java' > sources.txt \
 && javac -encoding UTF-8 -cp "lib/*" -d out @sources.txt

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Native SDK libraries + jars (Linux .so from lib-linux/)
COPY lib-linux ./lib

# The Hikvision .so and the (old) JNA jnidispatch are built requiring an
# EXECUTABLE STACK, which modern kernels refuse ("cannot enable executable
# stack as shared object requires"). Clear that flag with execstack. For
# jnidispatch we pre-extract a cleared copy and pin it via jna.boot.library.path
# so JNA does not re-extract a still-flagged copy to /tmp.
RUN apt-get update \
 && apt-get install -y --no-install-recommends execstack unzip \
 && mkdir -p /app/jni \
 && unzip -o lib/jna.jar 'com/sun/jna/linux-amd64/libjnidispatch.so' -d /tmp/jna \
 && cp /tmp/jna/com/sun/jna/linux-amd64/libjnidispatch.so /app/jni/libjnidispatch.so \
 && execstack -c /app/jni/libjnidispatch.so \
 && execstack -c lib/*.so lib/HCAapSDKCom/*.so \
 && rm -rf /tmp/jna /var/lib/apt/lists/*

# Compiled classes
COPY --from=build /build/out ./out
# Default config (override with env vars or a mounted /app/config.properties)
COPY src/main/resources/config.properties ./config.properties

# JNA and the dynamic linker both need to find the ISUP .so files.
ENV LD_LIBRARY_PATH=/app/lib
ENV JNA_LIBRARY_PATH=/app/lib

# ISUP CMS (registration), alarm TCP/UDP (events), HTTP API
EXPOSE 7660 7663 7662/udp 8090

# jna.boot.library.path → the exec-stack-cleared jnidispatch.
ENTRYPOINT ["sh","-c","java -Djna.library.path=/app/lib -Djna.boot.library.path=/app/jni -cp \"out:lib/*:.\" com.hrm.isup.App"]
