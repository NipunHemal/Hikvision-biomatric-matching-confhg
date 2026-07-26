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
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY src ./src
COPY lib ./lib
RUN find src/main/java -name '*.java' > sources.txt \
 && javac -encoding UTF-8 -cp "lib/*" -d out @sources.txt

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Native SDK libraries + jars (Linux .so — see note above)
COPY lib ./lib
# Compiled classes
COPY --from=build /build/out ./out
# Default config (override at runtime with a mounted /app/config.properties)
COPY src/main/resources/config.properties ./config.properties

# JNA and the dynamic linker both need to find the ISUP .so files.
ENV LD_LIBRARY_PATH=/app/lib
ENV JNA_LIBRARY_PATH=/app/lib

# ISUP CMS (registration), alarm TCP/UDP (events), HTTP API
EXPOSE 7660 7663 7662/udp 8090

# Build the classpath from the bundled jars at runtime.
ENTRYPOINT ["sh","-c","java -Djna.library.path=/app/lib -cp \"out:lib/*:.\" com.hrm.isup.App"]
