# Linux ISUP SDK libraries (for Docker / Dokploy)

Docker/Dokploy builds a **Linux** container, so it needs the **Linux `.so`**
build of the Hikvision ISUP SDK here (the local `lib/` has Windows `.dll` for dev).

Place these files in this folder and **commit them** (Dokploy builds from git):

    libHCISUPCMS.so      libHCISUPAlarm.so    libHCISUPSS.so     libHCISUPStream.so
    libcrypto.so         libssl.so
    libHCAapSDKCom/ (or the component .so set)
    jna.jar              examples.jar         gson-2.8.9.jar

Source: the same Hikvision ISUP SDK package, **Linux 64-bit build**.
The Dockerfile copies this folder to /app/lib inside the image.
