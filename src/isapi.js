// ISAPI client: RFC 2617 Digest auth + the endpoint wrappers we actually use.
// Follows the conventions in github.com/uchkunr/hikvision-best-practices.
const crypto = require('node:crypto');
const config = require('./config');

const md5 = (s) => crypto.createHash('md5').update(s).digest('hex');

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
  async call(path, { method = 'GET', body, headers = {}, raw = false } = {}) {
    const url = `${this.base}${path}${path.includes('?') ? '&' : '?'}format=json`;
    const res = await this.auth.fetch(url, {
      method,
      headers: body ? { 'Content-Type': 'application/json', ...headers } : headers,
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
    });

    if (raw) {
      if (!res.ok) throw new DeviceError(`HTTP ${res.status} on ${path}`, { status: res.status });
      return Buffer.from(await res.arrayBuffer());
    }

    const text = await res.text();
    let data;
    try {
      data = JSON.parse(text);
    } catch {
      throw new DeviceError(`Non-JSON reply from ${path}: ${text.slice(0, 200)}`, {
        status: res.status,
      });
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
  registerHttpHost({ id = 1, host, port, path, user, pass }) {
    return this.call(`/ISAPI/Event/notification/httpHosts/${id}`, {
      method: 'PUT',
      body: {
        HttpHostNotification: {
          id: String(id),
          url: path,
          protocolType: 'HTTP',
          parameterFormatType: 'JSON',
          addressingFormatType: 'ipaddress',
          ipAddress: host,
          portNo: port,
          // Must stay "basic": the webhook verifies Authorization: Basic.
          // Setting MD5digest here makes the device sign requests this server
          // cannot validate, and every event silently 401s.
          httpAuthenticationMethod: user ? 'basic' : 'none',
          ...(user && { userName: user, password: pass }),
        },
      },
    });
  }

  httpHosts() {
    return this.call('/ISAPI/Event/notification/httpHosts');
  }
}

module.exports = { IsapiClient, DigestAuth, DeviceError };
