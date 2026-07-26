package com.hrm.isup.transport;

import com.hrm.isup.Config;
import com.hrm.isup.model.Result;
import com.hrm.isup.sdk.HCISUPCMS;

import java.nio.charset.StandardCharsets;

/**
 * ISAPI-over-ISUP transport, bound to one device's login session.
 *
 * Sends each ISAPI request down the ISUP tunnel with NET_ECMS_ISAPIPassThrough.
 * The URL + JSON body are the ordinary ISAPI shapes; the tunnel is the only
 * difference from talking to the device directly over HTTP.
 */
public final class IsupTransport implements Transport {

    private final HCISUPCMS cms;
    private volatile int loginId;

    public IsupTransport(HCISUPCMS cms, int loginId) {
        this.cms = cms;
        this.loginId = loginId;
    }

    public void setLoginId(int loginId) { this.loginId = loginId; }

    @Override public boolean isAlive() { return loginId >= 0; }

    @Override public Result get(String path)             { return call("GET " + path, null); }
    @Override public Result post(String path, String b)  { return call("POST " + path, b); }
    @Override public Result put(String path, String b)   { return call("PUT " + path, b); }
    @Override public Result delete(String path, String b){ return call("DELETE " + path, b); }

    private synchronized Result call(String requestLine, String jsonBody) {
        if (loginId < 0) return Result.fail("device not online");

        HCISUPCMS.NET_EHOME_PTXML_PARAM p = new HCISUPCMS.NET_EHOME_PTXML_PARAM();
        p.read();

        byte[] urlBytes = requestLine.getBytes(StandardCharsets.UTF_8);
        HCISUPCMS.BYTE_ARRAY url = new HCISUPCMS.BYTE_ARRAY(urlBytes.length + 1);
        System.arraycopy(urlBytes, 0, url.byValue, 0, urlBytes.length);
        url.write();
        p.pRequestUrl = url.getPointer();
        p.dwRequestUrlLen = urlBytes.length;

        HCISUPCMS.BYTE_ARRAY in = null;
        if (jsonBody != null && !jsonBody.isEmpty()) {
            byte[] inBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            in = new HCISUPCMS.BYTE_ARRAY(inBytes.length + 1);
            System.arraycopy(inBytes, 0, in.byValue, 0, inBytes.length);
            in.write();
            p.pInBuffer = in.getPointer();
            p.dwInSize = inBytes.length;
        }

        int outSize = 2 * 1024 * 1024;
        HCISUPCMS.BYTE_ARRAY out = new HCISUPCMS.BYTE_ARRAY(outSize);
        p.pOutBuffer = out.getPointer();
        p.dwOutSize = outSize;
        // The device flushes ISAPI responses on its keepalive cycle (~30s), so a
        // short timeout drops the reply before it arrives ("WriteToCache push
        // Failed"). Wait longer than the keepalive. Configurable via RECV_TIMEOUT_MS.
        p.dwRecvTimeOut = Config.getInt("RECV_TIMEOUT_MS", 45000);
        p.write();

        if (!cms.NET_ECMS_ISAPIPassThrough(loginId, p)) {
            return Result.fail("passthrough failed, err=" + cms.NET_ECMS_GetLastError());
        }
        p.read();
        out.read();
        return Result.ok(new String(out.byValue, StandardCharsets.UTF_8).trim());
    }
}
