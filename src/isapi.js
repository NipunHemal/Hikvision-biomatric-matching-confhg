// ISAPI client: RFC 2617 Digest auth + the endpoint wrappers we actually use.
// Follows the conventions in github.com/uchkunr/hikvision-best-practices.
const crypto = require('node:crypto');
const config = require('./config');

const md5 = (s) => crypto.createHash('md5').update(s).digest('hex');

// Minimal XML -> object conversion. Some firmwares ignore ?format=json and
// answer in XML regardless, so responses are normalised rather than rejected.
// Repeated sibling tags collapse into an array; leaves become strings.
function xmlToObject(xml) {
  const parseChildren = (body) => {
    const out = {};
    const tag = /<([\w:.-]+)(?:\s[^>]*?)?(?:\/>|>([\s\S]*?)<\/\1>)/g;
    let m;
    let found = false;

    while ((m = tag.exec(body)) !== null) {
      found = true;
      const [, name, inner = ''] = m;
      const value = inner.includes('<') ? parseChildren(inner) : inner.trim();
      if (name in out) {
        out[name] = Array.isArray(out[name]) ? [...out[name], value] : [out[name], value];
      } else {
        out[name] = value;
      }
    }
    return found ? out : body.trim();
  };

  return parseChildren(xml.replace(/<\?xml[\s\S]*?\?>/, ''));
}

class DeviceError extends Error {
  constructor(message, { status, statusCode, subStatusCode, errorMsg } = {}) {
    super(message);
    this.name = 'DeviceError';
    this.status = status;
    this.statusCode = statusCode;
    this.subStatusCode = subStatusCode;
    this.errorMsg = errorMsg;
  }
}

class DigestAuth {
  constructor(user, pass) {
    this.user = user;
    this.pass = pass;
    this.nc = 0;
  }

  async fetch(url, init = {}) {
    const first = await fetch(url, init);
    if (first.status !== 401) return first;

    const www = first.headers.get('www-authenticate') ?? '';
    const body = await first.text(); // flush the socket before reusing it

    // Repeated bad credentials lock the admin account for ~30 min. Surface that
    // instead of hammering the device and extending the lockout.
    if (body.includes('lockStatus')) {
      const until = body.match(/<unlockTime>(.*?)<\/unlockTime>/)?.[1];
      throw new DeviceError(`Device account is locked; retry in ${until ?? '?'}s`, {
        status: 401,
      });
    }
    if (!www) throw new DeviceError('401 with no WWW-Authenticate challenge', { status: 401 });

    const p = {};
    for (const m of www.matchAll(/(\w+)=(?:"([^"]*)"|([^\s,]+))/g)) p[m[1]] = m[2] ?? m[3];

    this.nc += 1;
    const nc = this.nc.toString(16).padStart(8, '0');
    const cnonce = crypto.randomBytes(8).toString('hex');
    const u = new URL(url);
    const uri = u.pathname + u.search;
    const method = init.method ?? 'GET';

    const ha1 = md5(`${this.user}:${p.realm}:${this.pass}`);
    const ha2 = md5(`${method}:${uri}`);
    const response = md5(`${ha1}:${p.nonce}:${nc}:${cnonce}:${p.qop}:${ha2}`);

    const auth =
      `Digest username="${this.user}", realm="${p.realm}", nonce="${p.nonce}", ` +
      `uri="${uri}", response="${response}", qop=${p.qop}, nc=${nc}, cnonce="${cnonce}"` +
      (p.opaque ? `, opaque="${p.opaque}"` : '');

    return fetch(url, { ...init, headers: { ...init.headers, Authorization: auth } });
  }
}

class IsapiClient {
  constructor(device = config.device) {
    const scheme = device.port === 443 ? 'https' : 'http';
    this.base = `${scheme}://${device.host}:${device.port}`;
    this.auth = new DigestAuth(device.user, device.pass);
  }

  // State-changing calls answer with {statusCode, statusString, subStatusCode}.
  // statusCode 1 is success; anything else carries subStatusCode for branching.
  async call(
    path,
    { method = 'GET', body, headers = {}, raw = false, noFormat = false, timeoutMs } = {}
  ) {
    // XML endpoints must not carry ?format=json — it makes them reply in JSON.
    const url = noFormat
      ? `${this.base}${path}`
      : `${this.base}${path}${path.includes('?') ? '&' : '?'}format=json`;

    const res = await this.auth.fetch(url, {
      method,
      headers: body ? { 'Content-Type': 'application/json', ...headers } : headers,
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
      ...(timeoutMs && { signal: AbortSignal.timeout(timeoutMs) }),
    });

    // Raw responses are returned even on a non-2xx: the device explains capture
    // failures in the body, and the caller parses that.
    if (raw) return Buffer.from(await res.arrayBuffer());

    const text = await res.text();
    let data;
    if (text.trimStart().startsWith('<')) {
      data = xmlToObject(text);
      // ResponseStatus is the XML equivalent of the JSON status envelope.
      if (data.ResponseStatus) {
        data = { ...data.ResponseStatus, ...data };
        data.statusCode = Number(data.statusCode);
      }
    } else {
      try {
        data = JSON.parse(text);
      } catch {
        throw new DeviceError(`Unparseable reply from ${path}: ${text.slice(0, 200)}`, {
          status: res.status,
        });
      }
    }

    if (data.statusCode !== undefined && data.statusCode !== 1) {
      throw new DeviceError(data.subStatusCode || data.statusString || 'Device rejected request', {
        status: res.status,
        statusCode: data.statusCode,
        subStatusCode: data.subStatusCode,
        errorMsg: data.errorMsg,
      });
    }
    if (!res.ok) throw new DeviceError(`HTTP ${res.status} on ${path}`, { status: res.status });
    return data;
  }

  // --- System ---
  deviceInfo() {
    return this.call('/ISAPI/System/deviceInfo');
  }

  time() {
    return this.call('/ISAPI/System/time');
  }

  // --- Paginated search ---
  // Every Search endpoint shares this cursor contract: hold searchID steady,
  // advance searchResultPosition by numOfMatches, stop on "OK"/"NO_MATCHES".
  async *paginate(path, condKey, resultKey, itemKey, cond = {}) {
    const searchID = `${condKey}-${process.pid}-${this.auth.nc}`;
    let position = 0;

    for (;;) {
      const data = await this.call(path, {
        method: 'POST',
        body: {
          [condKey]: { searchID, searchResultPosition: position, maxResults: 100, ...cond },
        },
      });

      const result = data[resultKey] ?? {};
      for (const item of result[itemKey] ?? []) yield item;

      const got = result.numOfMatches ?? 0;
      if (!got || result.responseStatusStrg !== 'MORE') break;
      position += got;
    }
  }

  // --- Persons ---
  persons(cond) {
    return this.paginate(
      '/ISAPI/AccessControl/UserInfo/Search',
      'UserInfoSearchCond',
      'UserInfoSearch',
      'UserInfo',
      cond
    );
  }

  personCount() {
    return this.call('/ISAPI/AccessControl/UserInfo/Count');
  }

  // Create, falling back to Modify when the employeeNo is already taken.
  async upsertPerson({ employeeNo, name, userType = 'normal', beginTime, endTime, doorNo = 1 }) {
    const UserInfo = {
      employeeNo: String(employeeNo),
      name,
      userType,
      Valid: {
        enable: true,
        beginTime: beginTime || '2026-01-01T00:00:00',
        endTime: endTime || '2030-12-31T23:59:59',
      },
      doorRight: String(doorNo),
      RightPlan: [{ doorNo, planTemplateNo: '1' }],
    };

    try {
      await this.call('/ISAPI/AccessControl/UserInfo/Record', { method: 'POST', body: { UserInfo } });
      return { employeeNo: UserInfo.employeeNo, created: true };
    } catch (err) {
      if (err.subStatusCode !== 'deviceUserAlreadyExist') throw err;
      await this.call('/ISAPI/AccessControl/UserInfo/Modify', { method: 'PUT', body: { UserInfo } });
      return { employeeNo: UserInfo.employeeNo, created: false };
    }
  }

  deletePerson(employeeNo) {
    return this.call('/ISAPI/AccessControl/UserInfo/Delete', {
      method: 'PUT',
      body: { UserInfoDelCond: { EmployeeNoList: [{ employeeNo: String(employeeNo) }] } },
    });
  }

  // --- Cards ---
  assignCard(employeeNo, cardNo, cardType = 'normalCard') {
    return this.call('/ISAPI/AccessControl/CardInfo/Record', {
      method: 'POST',
      body: { CardInfo: { employeeNo: String(employeeNo), cardNo, cardType } },
    });
  }

  // --- Face library ---
  // The device requires a hand-built multipart body: JSON metadata part first,
  // raw JPEG second, with Content-Length on each part. Order is strict.
  async uploadFace(employeeNo, jpegBuf) {
    if (jpegBuf.length > 200 * 1024) {
      throw new DeviceError('Face image exceeds the device limit of 200 KB');
    }

    const meta = JSON.stringify({ faceLibType: 'blackFD', FDID: '1', FPID: String(employeeNo) });
    const boundary = `----hik${crypto.randomBytes(8).toString('hex')}`;
    const CRLF = '\r\n';

    const head =
      `--${boundary}${CRLF}` +
      `Content-Disposition: form-data; name="FaceDataRecord";${CRLF}` +
      `Content-Type: application/json${CRLF}` +
      `Content-Length: ${meta.length}${CRLF}${CRLF}${meta}` +
      `${CRLF}--${boundary}${CRLF}` +
      `Content-Disposition: form-data; name="FaceImage";${CRLF}` +
      `Content-Type: image/jpeg${CRLF}` +
      `Content-Length: ${jpegBuf.length}${CRLF}${CRLF}`;
    const tail = `${CRLF}--${boundary}--${CRLF}`;
    const body = Buffer.concat([Buffer.from(head), jpegBuf, Buffer.from(tail)]);

    // Content-Length is deliberately not set here: fetch derives it from the
    // buffer, and setting it explicitly is a forbidden header that aborts the
    // request. The per-part Content-Length headers above are still required.
    return this.call('/ISAPI/Intelligent/FDLib/FaceDataRecord', {
      method: 'POST',
      headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
      body,
    });
  }

  // --- Fingerprints ---
  //
  // Field shapes below come from Hikvision's own "Hik DeviceGateway" C# sample
  // and the ISAPI Developer Guide. CaptureFingerPrint is XML-only in practice:
  // its JSON form is undocumented and firmware support is inconsistent.

  // Asks the terminal's sensor to scan a finger now. Blocks until the person
  // presents a finger or the device times out, so allow a generous timeout.
  async captureFingerprint(fingerNo = 1) {
    const xml =
      '<?xml version="1.0" encoding="UTF-8"?>' +
      '<CaptureFingerPrintCond xmlns="http://www.isapi.org/ver20/XMLSchema" version="2.0">' +
      `<fingerNo>${fingerNo}</fingerNo>` +
      '</CaptureFingerPrintCond>';

    // The reply is multipart/form-data (an XML part plus a fingerprint image),
    // so it is read as bytes and the fields are pulled out of the XML part.
    const buf = await this.call('/ISAPI/AccessControl/CaptureFingerPrint', {
      method: 'POST',
      headers: { 'Content-Type': 'application/xml' },
      body: xml,
      raw: true,
      noFormat: true,
      timeoutMs: 60_000, // the device waits for a finger; don't cut it short
    });

    const text = buf.toString('binary');
    const pick = (tag) => text.match(new RegExp(`<${tag}>([^<]*)</${tag}>`))?.[1];

    const fingerData = pick('fingerData');
    if (!fingerData) {
      // Documented failures: deviceBusy, captureTimeout, fingerPrintLowQulity
      // (Hikvision's own spelling of "Quality").
      const err = pick('subStatusCode') || pick('statusString') || 'no fingerData returned';
      throw new DeviceError(`Fingerprint capture failed: ${err}`);
    }

    return {
      fingerNo: Number(pick('fingerNo') ?? fingerNo),
      quality: Number(pick('fingerPrintQuality') ?? 0),
      fingerData,
    };
  }

  // Per-module outcomes reported by FingerPrintProgress.
  static READER_STATUS = {
    0: 'connecting failed',
    1: 'connected',
    2: 'module offline',
    3: 'fingerprint quality poor, try again',
    4: 'memory full',
    5: 'fingerprint already exists',
    6: 'fingerprint ID already exists',
    7: 'invalid fingerprint ID',
    8: 'module already configured',
    10: 'module version too old to support this employee No.',
  };

  fingerprintProgress() {
    return this.call('/ISAPI/AccessControl/FingerPrintProgress');
  }

  // Applies a captured template to a person. enableCardReader is an array of
  // reader numbers; fingerPrintID is the finger slot (1-10).
  //
  // FingerPrintDownload is ASYNCHRONOUS: a 200 here only means the job was
  // accepted. The real outcome arrives via FingerPrintProgress, which is polled
  // until totalStatus is 1 (applied).
  async applyFingerprint({ employeeNo, fingerData, fingerPrintID = 1, cardReaders = [1] }) {
    await this.call('/ISAPI/AccessControl/FingerPrintDownload', {
      method: 'POST',
      body: {
        FingerPrintCfg: {
          employeeNo: String(employeeNo),
          enableCardReader: cardReaders,
          fingerPrintID,
          fingerType: 'normalFP',
          fingerData,
        },
      },
    });

    for (let attempt = 0; attempt < 30; attempt += 1) {
      const { FingerPrintStatus = {} } = await this.fingerprintProgress();

      if (FingerPrintStatus.totalStatus === 1) {
        const readers = (FingerPrintStatus.StatusList ?? []).map((s) => ({
          module: s.id,
          code: s.cardReaderRecvStatus,
          detail: IsapiClient.READER_STATUS[s.cardReaderRecvStatus] ?? s.errorMsg ?? 'unknown',
        }));
        // 1 = connected/applied. Anything else means this module rejected it,
        // even though the overall job reports "applied".
        const failed = readers.filter((r) => r.code !== 1);
        if (failed.length) {
          throw new DeviceError(
            `Fingerprint not applied: ${failed.map((f) => `module ${f.module}: ${f.detail}`).join('; ')}`
          );
        }
        return { applied: true, readers };
      }

      await new Promise((r) => setTimeout(r, 500));
    }

    throw new DeviceError('Timed out waiting for the device to apply the fingerprint');
  }

  // Lists a person's enrolled fingerprints. This search does NOT use the
  // numOfMatches/responseStatusStrg cursor the other endpoints use — it pages
  // until FingerPrintInfo.status is "NoFP".
  async listFingerprints(employeeNo) {
    const searchID = `fp-${process.pid}-${this.auth.nc}`;
    const out = [];

    for (let guard = 0; guard < 20; guard += 1) {
      const data = await this.call('/ISAPI/AccessControl/FingerPrintUpload', {
        method: 'POST',
        body: { FingerPrintCond: { searchID, employeeNo: String(employeeNo) } },
      });

      const info = data.FingerPrintInfo ?? {};
      for (const fp of info.FingerPrintList ?? []) {
        out.push({
          fingerPrintID: fp.fingerPrintID,
          fingerType: fp.fingerType,
          cardReaderNo: fp.cardReaderNo,
          hasTemplate: Boolean(fp.fingerData),
        });
      }
      if (info.status !== 'OK') break; // "NoFP" = no more pages
    }

    return out;
  }

  deleteFingerprint(employeeNo, fingerPrintID) {
    return this.call('/ISAPI/AccessControl/FingerPrintDelete', {
      method: 'PUT',
      body: {
        FingerPrintDelete: {
          mode: 'byEmployeeNo',
          EmployeeNoDetail: {
            employeeNo: String(employeeNo),
            ...(fingerPrintID !== undefined && { fingerPrintID: [Number(fingerPrintID)] }),
          },
        },
      },
    });
  }

  // --- Doors ---
  controlDoor(doorNo, cmd) {
    const allowed = ['open', 'close', 'alwaysOpen', 'alwaysClose', 'resume'];
    if (!allowed.includes(cmd)) {
      throw new DeviceError(`Unsupported door command "${cmd}" (use ${allowed.join(', ')})`);
    }
    return this.call(`/ISAPI/AccessControl/RemoteControl/door/${doorNo}`, {
      method: 'PUT',
      body: { RemoteControlDoor: { cmd } },
    });
  }

  // --- Stored access events (used to backfill webhook gaps) ---
  acsEvents({ startTime, endTime, major = 5, minor }) {
    return this.paginate(
      '/ISAPI/AccessControl/AcsEvent',
      'AcsEventCond',
      'AcsEvent',
      'InfoList',
      { major, ...(minor !== undefined && { minor }), startTime, endTime }
    );
  }

  // --- Event subscription ---
  // Sent as XML: this endpoint rejects a JSON body with "badXmlFormat" on
  // V3.25.x firmware, regardless of ?format=json.
  //
  // httpAuthenticationMethod must stay "basic" — the webhook verifies
  // Authorization: Basic, so MD5digest would make every event silently 401.
  registerHttpHost({ id = 1, host, port, path, user, pass }) {
    const xml =
      '<?xml version="1.0" encoding="UTF-8"?>' +
      '<HttpHostNotification version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">' +
      `<id>${id}</id>` +
      `<url>${path}</url>` +
      '<protocolType>HTTP</protocolType>' +
      '<parameterFormatType>JSON</parameterFormatType>' +
      '<addressingFormatType>ipaddress</addressingFormatType>' +
      `<ipAddress>${host}</ipAddress>` +
      `<portNo>${port}</portNo>` +
      `<httpAuthenticationMethod>${user ? 'basic' : 'none'}</httpAuthenticationMethod>` +
      (user ? `<userName>${user}</userName><password>${pass}</password>` : '') +
      '</HttpHostNotification>';

    return this.call(`/ISAPI/Event/notification/httpHosts/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/xml' },
      body: xml,
      noFormat: true,
    });
  }

  // This endpoint ignores ?format=json on some firmwares and answers in XML,
  // so it is read raw and the fields are pulled out either way.
  async httpHosts() {
    const buf = await this.call('/ISAPI/Event/notification/httpHosts', {
      raw: true,
      noFormat: true,
    });
    const text = buf.toString('utf8');

    if (text.trimStart().startsWith('{')) {
      const data = JSON.parse(text);
      const list = data.HttpHostNotificationList?.HttpHostNotification ?? [];
      return Array.isArray(list) ? list : [list];
    }

    // Split on each <HttpHostNotification> block, then read its child tags.
    return [...text.matchAll(/<HttpHostNotification>([\s\S]*?)<\/HttpHostNotification>/g)].map(
      ([, block]) => {
        const pick = (tag) => block.match(new RegExp(`<${tag}>([^<]*)</${tag}>`))?.[1] ?? null;
        return {
          id: pick('id'),
          url: pick('url'),
          protocolType: pick('protocolType'),
          parameterFormatType: pick('parameterFormatType'),
          addressingFormatType: pick('addressingFormatType'),
          ipAddress: pick('ipAddress'),
          portNo: pick('portNo'),
          httpAuthenticationMethod: pick('httpAuthenticationMethod'),
          userName: pick('userName'),
        };
      }
    );
  }
}

module.exports = { IsapiClient, DigestAuth, DeviceError, xmlToObject };
