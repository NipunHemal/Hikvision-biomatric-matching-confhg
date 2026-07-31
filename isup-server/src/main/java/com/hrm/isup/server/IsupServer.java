package com.hrm.isup.server;

import com.hrm.isup.Config;
import com.hrm.isup.device.CardCaptureRegistry;
import com.hrm.isup.device.DeviceManager;
import com.hrm.isup.event.EventPollService;
import com.hrm.isup.event.EventSink;
import com.hrm.isup.model.AccessEvent;
import com.hrm.isup.sdk.HCISUPAlarm;
import com.hrm.isup.sdk.HCISUPCMS;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Boots the ISUP stack: the CMS registration listener (devices dial in) and the
 * alarm listener (punch events arrive). Registration populates the DeviceManager
 * with a ConnectedDevice + adapter per branch terminal.
 *
 * Native-SDK code: compile against jna.jar + examples.jar, run with the ISUP
 * DLLs (Windows) / .so (Linux) on the library path.
 */
public final class IsupServer {

    private final DeviceManager manager;
    private final EventSink eventSink;
    private final EventPollService eventPoll;
    public HCISUPCMS cms;
    public HCISUPAlarm alarm;
    private int cmsHandle = -1;
    private int alarmHandle = -1;
    private HCISUPCMS.DEVICE_REGISTER_CB registerCb;   // keep refs so JNA doesn't GC them
    private HCISUPAlarm.EHomeMsgCallBack alarmCb;

    private boolean available = false;

    public IsupServer() {
        this.manager = new DeviceManager();
        this.eventSink = new EventSink();
        this.eventPoll = new EventPollService(manager, eventSink);
    }

    public DeviceManager manager() { return manager; }

    /** The event sink (HRM webhook). Shared with the API so the simulator can emit. */
    public EventSink eventSink() { return eventSink; }

    /** True once the native ISUP SDK loaded and the listeners started. */
    public boolean isAvailable() { return available; }

    /**
     * Best-effort startup. If the native ISUP SDK cannot be loaded (e.g. the
     * Linux .so files are missing in a container), this logs a clear warning and
     * returns WITHOUT throwing — the HTTP API still comes up so the deployment
     * stays healthy and works fully once the libraries are added.
     */
    public void start() {
        try {
            cms = (HCISUPCMS) Native.loadLibrary("HCISUPCMS", HCISUPCMS.class);
            alarm = (HCISUPAlarm) Native.loadLibrary("HCISUPAlarm", HCISUPAlarm.class);

            // ISUP 5.0 uses TLS — register crypto + SSL libs before init.
            boolean win = System.getProperty("os.name").toLowerCase().contains("win");
            String lib = System.getProperty("user.dir") + File.separator + "lib" + File.separator;
            setInitCfg(0, lib + (win ? "libeay32.dll" : "libcrypto.so"));
            setInitCfg(1, lib + (win ? "ssleay32.dll" : "libssl.so"));

            if (!cms.NET_ECMS_Init()) {
                throw new RuntimeException("NET_ECMS_Init failed: " + cms.NET_ECMS_GetLastError());
            }
            setLocalCfg(5, lib + "HCAapSDKCom");
            // SDK log level: 0-none, 1-error, 2-+info, 3-+debug. Default 1 keeps the
            // console readable so our [event]/[hub] lines aren't buried in MQTT DBG spam.
            cms.NET_ECMS_SetLogToFile(Config.getInt("SdkLogLevel", 1),
                    System.getProperty("user.dir") + "/EHomeSDKLog", false);

            // The alarm SDK is a SEPARATE library and needs its OWN crypto/SSL
            // libs loaded before init — without this, reusing the CMS port for
            // events fails (NET_EALARM_StartListen error 72).
            alarm.NET_EALARM_SetSDKInitCfg(0, bytes(lib + (win ? "libeay32.dll" : "libcrypto.so")));
            alarm.NET_EALARM_SetSDKInitCfg(1, bytes(lib + (win ? "ssleay32.dll" : "libssl.so")));
            alarm.NET_EALARM_Init();
            alarm.NET_EALARM_SetSDKLocalCfg(5, bytes(lib + "HCAapSDKCom"));
            manager.setCms(cms);

            startCmsListen();
            // The push/alarm channel is unreliable on this device (reuse-mode error
            // 72; auto event-host setup times out and causes reconnect churn). Event
            // polling is the reliable path, so the alarm listener is OFF by default.
            // Set ALARM_LISTEN_ENABLED=1 to re-enable the experimental push channel.
            if ("1".equals(Config.get("AlarmListenEnabled"))) {
                startAlarmListen();
            } else {
                System.out.println("[isup] alarm listener disabled — using event polling for events");
            }
            eventPoll.start();   // poll AcsEvent over passthrough (reliable event path)
            available = true;
        } catch (Throwable t) {
            System.err.println("\n[isup] ⚠ native ISUP SDK unavailable — HTTP API will run, "
                    + "but devices cannot register until the SDK libraries are present.");
            System.err.println("[isup]   reason: " + t);
            System.err.println("[isup]   ensure lib/ has the ISUP libraries for this OS "
                    + "(Linux .so in a container).\n");
            available = false;
        }
    }

    public void stop() {
        eventPoll.stop();
        if (cmsHandle >= 0) cms.NET_ECMS_StopListen(cmsHandle);
        if (alarmHandle >= 0) alarm.NET_EALARM_StopListen(alarmHandle);
        if (alarm != null) alarm.NET_EALARM_Fini();
        if (cms != null) cms.NET_ECMS_Fini();
    }

    // --- CMS (registration) ---

    private void startCmsListen() {
        HCISUPCMS.NET_EHOME_CMS_LISTEN_PARAM listen = new HCISUPCMS.NET_EHOME_CMS_LISTEN_PARAM();
        putIp(listen.struAddress.szIP, Config.get("CmsServerIP"));
        listen.struAddress.wPort = (short) Config.getInt("CmsServerPort", 7660);
        registerCb = new RegisterCallback();
        listen.fnCB = registerCb;
        listen.write();

        cmsHandle = cms.NET_ECMS_StartListen(listen);
        if (cmsHandle < 0) {
            throw new RuntimeException("NET_ECMS_StartListen failed: " + cms.NET_ECMS_GetLastError());
        }
        System.out.println("[isup] CMS listening on " + Config.get("CmsServerIP") + ":"
                + Config.getInt("CmsServerPort", 7660));
    }

    private final class RegisterCallback implements HCISUPCMS.DEVICE_REGISTER_CB {
        @Override
        public boolean invoke(int lUserID, int dwDataType, Pointer pOutBuffer, int dwOutLen,
                              Pointer pInBuffer, int dwInLen, Pointer pUser) {
            switch (dwDataType) {
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_OFF: {
                    // Device dropped (ISUP keepalive timeout / disconnect).
                    String deviceId = "";
                    try { deviceId = trim(readRegInfo(pOutBuffer).struRegInfo.byDeviceID); }
                    catch (Throwable ignored) { }
                    System.out.println("[isup] ENUM_DEV_OFF device=" + deviceId);
                    if (!deviceId.isEmpty()) manager.offline(deviceId);
                    return true;
                }
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_ON: {
                    HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 reg = readRegInfo(pOutBuffer);
                    String deviceId = trim(reg.struRegInfo.byDeviceID);

                    HCISUPCMS.NET_EHOME_SERVER_INFO_V50 srv = new HCISUPCMS.NET_EHOME_SERVER_INFO_V50();
                    srv.read();
                    putIp(srv.struUDPAlarmSever.szIP, Config.get("AlarmServerIP"));
                    putIp(srv.struTCPAlarmSever.szIP, Config.get("AlarmServerIP"));
                    srv.dwAlarmServerType = Config.getInt("AlarmServerType", 2);
                    srv.struTCPAlarmSever.wPort = (short) Config.getInt("AlarmServerTCPPort", 7663);
                    srv.struUDPAlarmSever.wPort = (short) Config.getInt("AlarmServerUDPPort", 7662);
                    putIp(srv.struPictureSever.szIP, Config.get("PicServerIP"));
                    srv.struPictureSever.wPort = (short) Config.getInt("PicServerPort", 6011);
                    srv.dwPicServerType = Config.getInt("PicServerType", 0);
                    srv.write();
                    int n = srv.size();
                    pInBuffer.write(0, srv.getPointer().getByteArray(0, n), 0, n);

                    System.out.println("[isup] ENUM_DEV_ON device=" + deviceId + " lUserID=" + lUserID);
                    manager.online(deviceId, lUserID);
                    return true;
                }
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_AUTH: {
                    readRegInfo(pOutBuffer);
                    byte[] key = Config.get("ISUPKey").getBytes(StandardCharsets.UTF_8);
                    pInBuffer.write(0, key, 0, key.length);
                    return true;
                }
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_SESSIONKEY: {
                    HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 reg = readRegInfo(pOutBuffer);
                    HCISUPCMS.NET_EHOME_DEV_SESSIONKEY sk = new HCISUPCMS.NET_EHOME_DEV_SESSIONKEY();
                    System.arraycopy(reg.struRegInfo.byDeviceID, 0, sk.sDeviceID, 0,
                            reg.struRegInfo.byDeviceID.length);
                    System.arraycopy(reg.struRegInfo.bySessionKey, 0, sk.sSessionKey, 0,
                            reg.struRegInfo.bySessionKey.length);
                    sk.write();
                    boolean ok = cms.NET_ECMS_SetDeviceSessionKey(sk.getPointer());
                    boolean okAlarm = alarm.NET_EALARM_SetDeviceSessionKey(sk.getPointer());
                    System.out.println("[isup] ENUM_DEV_SESSIONKEY device="
                            + trim(reg.struRegInfo.byDeviceID) + " cmsKey=" + ok
                            + " alarmKey=" + okAlarm
                            + " (alarm channel needs alarmKey=true to decode events)");
                    return true;
                }
                case HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_DAS_REQ: {
                    // Redirect the device to the DAS (this same server). Address
                    // must be one the device can reach — not 0.0.0.0/127.0.0.1.
                    String das = "{\"Type\":\"DAS\",\"DasInfo\":{"
                            + "\"Address\":\"" + Config.get("DasServerIP") + "\","
                            + "\"Domain\":\"\",\"ServerID\":\"\","
                            + "\"Port\":" + Config.getInt("DasServerPort", 7660) + ",\"UdpPort\":0}}";
                    byte[] b = das.getBytes(StandardCharsets.UTF_8);
                    pInBuffer.write(0, b, 0, b.length);
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    // --- Alarm (events) ---

    private void startAlarmListen() {
        HCISUPAlarm.NET_EHOME_ALARM_LISTEN_PARAM listen = new HCISUPAlarm.NET_EHOME_ALARM_LISTEN_PARAM();
        alarmCb = new AlarmCallback();
        listen.fnMsgCb = alarmCb;

        // This DS-K1T808 (and ISUP 5.0 MQTT devices generally) keeps ONE MQTT
        // connection to the CMS port (7660) and multiplexes EVERYTHING over it —
        // registration, ISAPI passthrough AND events. It never opens a separate
        // link to 7663, so a standalone alarm listener there never fires. Reusing
        // the CMS port hooks the alarm callback into that single connection so
        // card taps / punches are delivered. Set ALARM_REUSE_CMS_PORT=0 to fall
        // back to a separate MQTT listener on 7663 (for devices that do connect
        // out to an alarm host).
        boolean reuse = !"0".equals(Config.get("AlarmReuseCmsPort"));
        int cmsPort = Config.getInt("CmsServerPort", 7660);
        if (reuse) {
            putIp(listen.struAddress.szIP, "127.0.0.1"); // loopback when reusing CMS port
            listen.struAddress.wPort = (short) cmsPort;
            listen.byUseCmsPort = 1;                     // protocol type ignored in this mode
        } else {
            putIp(listen.struAddress.szIP, "0.0.0.0");
            listen.struAddress.wPort = (short) Config.getInt("AlarmServerTCPPort", 7663);
            listen.byProtocolType = 2;                   // 0-TCP, 1-UDP, 2-MQTT
            listen.byUseCmsPort = 0;
        }
        listen.write();

        alarmHandle = alarm.NET_EALARM_StartListen(listen);
        if (alarmHandle < 0) {
            System.out.println("[isup] alarm listen failed: " + alarm.NET_EALARM_GetLastError());
        } else if (reuse) {
            System.out.println("[isup] alarm listening (reusing CMS port " + cmsPort
                    + "; events arrive over the device's MQTT connection)");
        } else {
            System.out.println("[isup] alarm listening on " + Config.get("AlarmServerIP") + ":"
                    + Config.getInt("AlarmServerTCPPort", 7663));
        }
    }

    private final class AlarmCallback implements HCISUPAlarm.EHomeMsgCallBack {
        @Override
        public boolean invoke(int iHandle, HCISUPAlarm.NET_EHOME_ALARM_MSG pAlarmMsg, Pointer pUser) {
            // Unconditional: proves the alarm channel is delivering something.
            // If a card tap produces NO "[alarm] callback" line, the device's
            // events are not reaching this server (firewall on 7663/7662, or the
            // device isn't reporting to the alarm host).
            System.out.println("[alarm] callback fired, cmd=" + pAlarmMsg.dwAlarmType
                    + " xmlLen=" + pAlarmMsg.dwXmlBufLen);
            AccessEvent evt = new AccessEvent();
            try {
                evt.deviceId = trim(pAlarmMsg.sSerialNumber);
                if (pAlarmMsg.pXmlBuf != null && pAlarmMsg.dwXmlBufLen > 0) {
                    evt.raw = new String(pAlarmMsg.pXmlBuf.getByteArray(0, pAlarmMsg.dwXmlBufLen),
                            StandardCharsets.UTF_8).trim();
                }
            } catch (Throwable ignored) {
                evt.deviceId = "unknown";
            }
            evt.eventName = "accessEvent";
            // Log the raw payload so the exact event format (card/employee fields)
            // can be mapped from a live tap, and pull common fields generically.
            if (evt.raw != null) {
                System.out.println("[event] raw from " + evt.deviceId + ": "
                        + evt.raw.substring(0, Math.min(600, evt.raw.length())));
                evt.cardNo = firstMatch(evt.raw, "cardNo");
                evt.employeeNo = firstMatch(evt.raw, "employeeNoString", "employeeNo");
                evt.personName = firstMatch(evt.raw, "name");
            }
            // Feed a live card tap to any pending capture waiter for this device.
            if (evt.cardNo != null && !evt.cardNo.isEmpty())
                CardCaptureRegistry.offer(evt.deviceId, evt.cardNo);
            eventSink.accept(evt);
            return true;
        }
    }

    // --- helpers ---

    private void setInitCfg(int type, String path) { cms.NET_ECMS_SetSDKInitCfg(type, bytes(path)); }
    private void setLocalCfg(int type, String path) { cms.NET_ECMS_SetSDKLocalCfg(type, bytes(path)); }

    private Pointer bytes(String s) {
        HCISUPCMS.BYTE_ARRAY arr = new HCISUPCMS.BYTE_ARRAY(256);
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, arr.byValue, 0, b.length);
        arr.write();
        return arr.getPointer();
    }

    private static void putIp(byte[] dst, String ip) {
        byte[] b = ip.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, dst, 0, Math.min(b.length, dst.length));
    }

    private static String trim(byte[] b) {
        return new String(b, StandardCharsets.UTF_8).trim().replace("\0", "");
    }

    /** Find the first value for any of {@code keys} in a JSON or XML payload. */
    private static String firstMatch(String raw, String... keys) {
        if (raw == null) return null;
        for (String k : keys) {
            var j = java.util.regex.Pattern
                    .compile("\"" + k + "\"\\s*:\\s*\"?([^\",}\\]]+)").matcher(raw);
            if (j.find()) return j.group(1).trim();
            var x = java.util.regex.Pattern
                    .compile("<" + k + ">([^<]+)</" + k + ">").matcher(raw);
            if (x.find()) return x.group(1).trim();
        }
        return null;
    }

    private HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 readRegInfo(Pointer pOutBuffer) {
        HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 reg = new HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12();
        reg.write();
        reg.getPointer().write(0, pOutBuffer.getByteArray(0, reg.size()), 0, reg.size());
        reg.read();
        return reg;
    }
}
