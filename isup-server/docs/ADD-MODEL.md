# Adding support for a new device model

The adapter architecture makes a new model a small, isolated change: **one class
+ one line**. Existing models and the HRM API are untouched.

## When you need this

- A different terminal model whose ISAPI payloads or capabilities differ from
  DS-K1T808 (e.g. a different fingerprint slot limit, no face module, a changed
  request body).

If a new model speaks standard ISAPI identically, you may not need an adapter at
all — the `GenericIsapiAdapter` fallback already handles it. Add a dedicated
adapter when behaviour or capabilities actually differ.

## Step 1 — Create the adapter

Extend `AbstractIsapiAdapter` and override **only** what differs.

```java
// src/main/java/com/hrm/isup/device/DsK1T343Adapter.java
package com.hrm.isup.device;

import com.hrm.isup.model.Result;
import com.hrm.isup.model.Fingerprint;
import com.hrm.isup.transport.Transport;

public final class DsK1T343Adapter extends AbstractIsapiAdapter {

    public static final String MODEL = "DS-K1T343MFW";

    public DsK1T343Adapter(Transport tx) { super(tx); }

    @Override public String model() { return MODEL; }

    @Override public Capabilities capabilities() {
        Capabilities c = new Capabilities();
        c.persons = true;
        c.cards = true;
        c.fingerprint = true;
        c.face = false;                    // this model has no face module
        c.maxFingerprintsPerPerson = 5;    // model-specific limit
        return c;
    }

    // Override ONLY if a payload differs. Otherwise the base implementation
    // (the same ISAPI used by DS-K1T808) is inherited unchanged. Example:
    //
    // @Override public Result downloadFingerprint(Fingerprint fp) {
    //     // build this model's specific body, then:
    //     return tx.post("/ISAPI/AccessControl/FingerPrintDownload?format=json", json);
    // }
}
```

Everything you don't override — `upsertPerson`, `deletePerson`, `listPersons`,
`assignCard`, `listFingerprints`, `deleteFingerprint`, `controlDoor`,
`deviceInfo` — comes from `AbstractIsapiAdapter` for free.

## Step 2 — Register it in the factory

`device/AdapterFactory.java` — add one line:

```java
public static DeviceAdapter forModel(String model, Transport tx) {
    if (model != null && model.toUpperCase().contains("K1T808")) {
        return new DsK1T808Adapter(tx);
    }
    if (model != null && model.toUpperCase().contains("K1T343")) {   // ← new
        return new DsK1T343Adapter(tx);
    }
    return new GenericIsapiAdapter(tx, model);
}
```

## Step 3 — Done

Rebuild. When a device of that model dials in, `DeviceManager` auto-detects the
model (`/ISAPI/System/deviceInfo`) and `AdapterFactory` picks the new adapter.
The HRM uses the **same** HTTP API — the adapter handles the model differences
underneath.

```
DS-K1T343 dials in → model detected "DS-K1T343MFW" → DsK1T343Adapter
DS-K1T808 dials in → model detected "DS-K1T808MFWX-B" → DsK1T808Adapter
```

A mixed fleet works: different models on one hub, each served by its own adapter.

## Adding a new transport (bonus)

If a future device is reachable by direct HTTP (on the LAN, not ISUP), implement
`transport/Transport` as `DirectHttpTransport` (HTTP + digest auth to the device
IP). Adapters are written against `Transport`, so they work over it unchanged —
you only wire the new transport where the device is registered.

## Checklist

- [ ] New `XxxAdapter extends AbstractIsapiAdapter`, override differences only
- [ ] Set correct `capabilities()`
- [ ] One line in `AdapterFactory.forModel`
- [ ] `build.bat` / rebuild image
- [ ] Connect a device of that model → check `GET /devices` shows the new adapter
