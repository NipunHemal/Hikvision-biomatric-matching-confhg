// Attendance scenario configuration.
//
// Everything the generator needs to produce a realistic month of punches:
// who works, when, at which door, and how often reality departs from the plan.
// Departures are the point — an HRM system that only ever sees clean in/out
// pairs has not been tested.

const SHIFTS = {
  day: { name: 'Day', in: '09:00', out: '17:30' },
  early: { name: 'Early', in: '06:00', out: '14:30' },
  night: { name: 'Night', in: '22:00', out: '06:00' }, // crosses midnight
  split: { name: 'Split', in: '08:00', out: '20:00' },
};

const DEFAULT = {
  // Devices the generator will spread punches across. Ports must match running
  // simulators; see simulator/fleet.js.
  devices: [
    { port: 8100, name: 'Main Entrance', doorNo: 1 },
    { port: 8101, name: 'Rear Door', doorNo: 1 },
    { port: 8102, name: 'Warehouse', doorNo: 1 },
    { port: 8103, name: 'Office Floor 2', doorNo: 1 },
  ],

  // Mon-Fri. 0 = Sunday.
  workdays: [1, 2, 3, 4, 5],

  // Dates with no expected attendance, as YYYY-MM-DD.
  holidays: [],

  // How employees are generated when a roster is not supplied explicitly.
  staff: {
    count: 20,
    employeeNoPrefix: 'EMP',
    shifts: ['day', 'day', 'day', 'early', 'night'], // weighted by repetition
  },

  // Probability of each departure from a clean day. Tuned to be visible in a
  // month of data without drowning the normal case.
  behaviour: {
    absent: 0.04, // no punches at all
    late: 0.18, // arrives after shift start
    lateMinutes: [5, 45], // range when late
    earlyLeave: 0.08,
    earlyLeaveMinutes: [10, 60],
    overtime: 0.15,
    overtimeMinutes: [15, 120],
    missingOutPunch: 0.03, // forgot to punch out — the classic HRM headache
    missingInPunch: 0.01,
    doublePunch: 0.03, // tapped twice; the backend should dedup or show both
    wrongDoor: 0.1, // used a door other than their usual one
    // Normal variation around the scheduled time, in minutes.
    jitter: [-8, 12],
  },

  // Credential mix. Most punches are fingerprint on these terminals.
  methods: [
    ['fingerprint', 0.7],
    ['card', 0.2],
    ['face', 0.1],
  ],
};

// Smaller and noisier: useful for exercising edge-case handling quickly.
const MESSY = {
  ...DEFAULT,
  staff: { ...DEFAULT.staff, count: 5 },
  behaviour: {
    ...DEFAULT.behaviour,
    absent: 0.15,
    late: 0.4,
    missingOutPunch: 0.15,
    missingInPunch: 0.08,
    doublePunch: 0.12,
    earlyLeave: 0.2,
  },
};

// Everyone present and on time — a baseline for verifying reports.
const CLEAN = {
  ...DEFAULT,
  behaviour: {
    absent: 0,
    late: 0,
    lateMinutes: [0, 0],
    earlyLeave: 0,
    earlyLeaveMinutes: [0, 0],
    overtime: 0,
    overtimeMinutes: [0, 0],
    missingOutPunch: 0,
    missingInPunch: 0,
    doublePunch: 0,
    wrongDoor: 0,
    jitter: [-2, 2],
  },
};

const PRESETS = { default: DEFAULT, messy: MESSY, clean: CLEAN };

module.exports = { SHIFTS, PRESETS, DEFAULT };
