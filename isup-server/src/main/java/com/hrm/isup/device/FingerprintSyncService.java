package com.hrm.isup.device;

import com.hrm.isup.model.Fingerprint;
import com.hrm.isup.model.Result;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-branch fingerprint sync — the reason the hub exists.
 *
 * A person enrolls once at any branch; the hub reads their template(s) from that
 * terminal and pushes them to the others, so the same finger works everywhere.
 * Hub-and-spoke: the hub is the source of truth, each device a replica.
 */
public final class FingerprintSyncService {

    private final DeviceManager manager;

    public FingerprintSyncService(DeviceManager manager) {
        this.manager = manager;
    }

    public static final class SyncReport {
        public String employeeNo;
        public String sourceDeviceId;
        public int templatesFound;
        public List<TargetResult> targets = new ArrayList<>();
    }

    public static final class TargetResult {
        public String deviceId;
        public boolean ok;
        public int pushed;
        public String detail;
    }

    /**
     * Read the person's fingerprints from the source device and push them to
     * every other online device (or to explicit targets if given).
     */
    public SyncReport sync(String sourceDeviceId, String employeeNo, List<String> targetIds) {
        SyncReport report = new SyncReport();
        report.employeeNo = employeeNo;
        report.sourceDeviceId = sourceDeviceId;

        ConnectedDevice source = manager.get(sourceDeviceId);
        if (source == null) throw new IllegalStateException("source device not online: " + sourceDeviceId);

        List<Fingerprint> templates = source.adapter.listFingerprints(employeeNo);
        report.templatesFound = templates.size();
        if (templates.isEmpty()) return report;

        // Ensure the person exists on each target before pushing templates, then
        // download every template.
        for (ConnectedDevice target : manager.all()) {
            if (!target.online) continue;
            if (target.deviceId.equals(sourceDeviceId)) continue;
            if (targetIds != null && !targetIds.isEmpty() && !targetIds.contains(target.deviceId)) continue;

            TargetResult tr = new TargetResult();
            tr.deviceId = target.deviceId;
            int pushed = 0;
            StringBuilder detail = new StringBuilder();
            boolean ok = true;

            for (Fingerprint fp : templates) {
                Fingerprint copy = new Fingerprint(employeeNo, fp.fingerPrintID, fp.fingerData);
                copy.fingerType = fp.fingerType;
                Result r = target.adapter.downloadFingerprint(copy);
                if (r.ok && !r.body.toLowerCase().contains("error")) {
                    pushed++;
                } else {
                    ok = false;
                    detail.append("fp").append(fp.fingerPrintID).append(":")
                          .append(shorten(r.body)).append("; ");
                }
            }
            tr.ok = ok;
            tr.pushed = pushed;
            tr.detail = detail.toString();
            report.targets.add(tr);
        }
        return report;
    }

    private String shorten(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
