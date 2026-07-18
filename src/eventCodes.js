// Major/minor event codes, per the ISAPI event category tables.
const MAJOR = {
  1: 'alarm',
  2: 'exception',
  3: 'operation',
  5: 'event',
};

// Minor codes under MAJOR_EVENT (5). The minor code — not currentVerifyMode —
// is what authoritatively says which credential was used.
const MINOR = {
  27: { name: 'exitButtonPressed', method: 'button', success: true },
  38: { name: 'cardAuthSuccess', method: 'card', success: true },
  75: { name: 'faceAuthSuccess', method: 'face', success: true },
  76: { name: 'faceAuthFail', method: 'face', success: false },
  113: { name: 'fingerprintAuthSuccess', method: 'fingerprint', success: true },
};

function describe(major, minor) {
  const entry = major === 5 ? MINOR[minor] : undefined;
  return {
    majorName: MAJOR[major] ?? null,
    minorName: entry?.name ?? null,
    method: entry?.method ?? null,
    success: entry?.success ?? null,
  };
}

module.exports = { MAJOR, MINOR, describe };
