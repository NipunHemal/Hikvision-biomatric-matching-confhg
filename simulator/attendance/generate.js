// Turns a scenario profile into a list of punches.
//
// Pure and deterministic: the same seed always produces the same month, so a
// failing report can be reproduced exactly rather than "regenerated and hope".
const { SHIFTS } = require('./profile');

// mulberry32 — small, fast, seedable. Node's Math.random cannot be seeded.
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pad = (n) => String(n).padStart(2, '0');

// Local ISO with offset, matching how the terminal stamps events.
function isoLocal(date) {
  const off = -date.getTimezoneOffset();
  const sign = off >= 0 ? '+' : '-';
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}` +
    `${sign}${pad(Math.floor(Math.abs(off) / 60))}:${pad(Math.abs(off) % 60)}`
  );
}

// The seconds field is drawn from the seeded generator too — using
// Math.random() here would silently break reproducibility.
const atTime = (day, hhmm, offsetMin, rand) => {
  const [h, m] = hhmm.split(':').map(Number);
  const d = new Date(day);
  d.setHours(h, m + offsetMin, Math.floor(rand() * 60), 0);
  return d;
};

function buildRoster(profile, rand) {
  if (profile.roster) return profile.roster;

  const { count, employeeNoPrefix, shifts } = profile.staff;
  const FIRST = ['Supun', 'Nimal', 'Kamal', 'Sunil', 'Ruwan', 'Dilini', 'Chamari', 'Hemal',
    'Ishara', 'Nadeesha', 'Tharindu', 'Sanduni', 'Malith', 'Amara', 'Roshan',
    'Piyumi', 'Kasun', 'Nethmi', 'Buddhika', 'Sachini'];
  const LAST = ['Perera', 'Silva', 'Fernando', 'Bandara', 'Jayawardena', 'Rathnayake',
    'Wickramasinghe', 'Gunasekara'];

  return Array.from({ length: count }, (_, i) => ({
    employeeNo: `${employeeNoPrefix}${String(i + 1).padStart(3, '0')}`,
    name: `${FIRST[i % FIRST.length]} ${LAST[Math.floor(rand() * LAST.length)]}`,
    shift: shifts[Math.floor(rand() * shifts.length)],
    // Each person has a usual door; wrongDoor sends them elsewhere occasionally.
    homeDevice: Math.floor(rand() * profile.devices.length),
  }));
}

function pickMethod(methods, rand) {
  const r = rand();
  let acc = 0;
  for (const [name, weight] of methods) {
    acc += weight;
    if (r <= acc) return name;
  }
  return methods[0][0];
}

const between = ([lo, hi], rand) => Math.round(lo + rand() * (hi - lo));

function datesIn(period, endDate) {
  const end = new Date(endDate);
  end.setHours(0, 0, 0, 0);
  const days = period === 'day' ? 1 : period === 'week' ? 7 : 30;
  return Array.from({ length: days }, (_, i) => {
    const d = new Date(end);
    d.setDate(end.getDate() - (days - 1 - i));
    return d;
  });
}

/**
 * @returns {{punches: Array, roster: Array, summary: object}}
 *   punches are sorted by time and tagged with the device they belong to.
 */
function generate({ profile, period = 'month', endDate = new Date(), seed = 42 }) {
  const rand = rng(seed);
  const roster = buildRoster(profile, rand);
  const b = profile.behaviour;
  const punches = [];
  const stats = { expected: 0, absent: 0, late: 0, earlyLeave: 0, overtime: 0,
    missingOut: 0, missingIn: 0, double: 0 };

  const add = (person, when, deviceIdx, kind) => {
    const device = profile.devices[deviceIdx];
    punches.push({
      employeeNo: person.employeeNo,
      name: person.name,
      time: isoLocal(when),
      timestamp: when.getTime(),
      method: pickMethod(profile.methods, rand),
      doorNo: device.doorNo,
      devicePort: device.port,
      deviceName: device.name,
      kind, // 'in' | 'out' — for your own assertions, not sent to the device
      attendanceStatus: kind === 'in' ? 'checkIn' : 'checkOut',
    });
  };

  for (const day of datesIn(period, endDate)) {
    const iso = `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`;
    const isWorkday =
      profile.workdays.includes(day.getDay()) && !profile.holidays.includes(iso);
    if (!isWorkday) continue;

    for (const person of roster) {
      stats.expected += 1;
      const shift = SHIFTS[person.shift];

      if (rand() < b.absent) {
        stats.absent += 1;
        continue;
      }

      const deviceFor = () =>
        rand() < b.wrongDoor
          ? Math.floor(rand() * profile.devices.length)
          : person.homeDevice;

      // --- arrival ---
      let inOffset = between(b.jitter, rand);
      if (rand() < b.late) {
        inOffset += between(b.lateMinutes, rand);
        stats.late += 1;
      }
      const skipIn = rand() < b.missingInPunch;
      if (skipIn) stats.missingIn += 1;
      else {
        const t = atTime(day, shift.in, inOffset, rand);
        add(person, t, deviceFor(), 'in');
        // Accidental second tap moments later.
        if (rand() < b.doublePunch) {
          stats.double += 1;
          add(person, new Date(t.getTime() + 3000 + rand() * 20000), person.homeDevice, 'in');
        }
      }

      // --- departure ---
      let outOffset = between(b.jitter, rand);
      if (rand() < b.earlyLeave) {
        outOffset -= between(b.earlyLeaveMinutes, rand);
        stats.earlyLeave += 1;
      } else if (rand() < b.overtime) {
        outOffset += between(b.overtimeMinutes, rand);
        stats.overtime += 1;
      }

      if (rand() < b.missingOutPunch) {
        stats.missingOut += 1;
        continue;
      }

      const outDay = new Date(day);
      // A night shift ends the following morning.
      if (shift.out < shift.in) outDay.setDate(outDay.getDate() + 1);
      add(person, atTime(outDay, shift.out, outOffset, rand), deviceFor(), 'out');
    }
  }

  punches.sort((a, b2) => a.timestamp - b2.timestamp);

  return {
    punches,
    roster,
    summary: {
      ...stats,
      punchCount: punches.length,
      days: datesIn(period, endDate).length,
      workdays: datesIn(period, endDate).filter(
        (d) =>
          profile.workdays.includes(d.getDay()) &&
          !profile.holidays.includes(
            `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
          )
      ).length,
      from: punches[0]?.time ?? null,
      to: punches.at(-1)?.time ?? null,
    },
  };
}

module.exports = { generate, isoLocal, rng };
