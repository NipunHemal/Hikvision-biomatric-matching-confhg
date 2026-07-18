# Attendance test data generator

Produces a day, a week, or a month of realistic attendance punches across a
fleet of simulated terminals — so you can build and test HRM reports without
waiting for real staff to clock in for a month.

Requires the [device simulator](SIMULATOR.md).

---

## Quick start

Three terminals.

```bash
# 1 — four simulated doors
npm run fleet
```

```bash
# 2 — the backend
npm start
```

```bash
# 3 — generate a month for 20 employees
npm run attendance -- --period month --employees 20
```

Then look at what landed:

```bash
curl 'http://localhost:8080/events?limit=20'
curl 'http://localhost:8080/events?employeeNo=EMP001'
```

> Note the `--` before the flags. npm needs it to pass arguments through to the
> script rather than consuming them itself.

---

## Always dry-run first

Prints the plan and sends nothing. A month for 20 people is ~800 punches; worth
seeing the shape before committing it to your database.

```bash
npm run attendance -- --period month --employees 20 --dry-run
```

```
Plan:
  window            2026-06-20T08:54:03+05:30 -> 2026-07-19T18:41:22+05:30
  days / workdays   30 / 22
  punches           812
  expected shifts   440
  absent            17
  late arrivals     79
  early leaves      35
  overtime          66
  missing out-punch 13
  missing in-punch  4
  double taps       12
```

---

## Options

| Flag | Default | Purpose |
| --- | --- | --- |
| `--period` | `month` | `day` · `week` · `month` |
| `--employees` | `20` | roster size |
| `--devices` | `4` | how many doors to spread punches across |
| `--preset` | `default` | `default` · `messy` · `clean` |
| `--seed` | `42` | same seed ⇒ byte-identical dataset |
| `--end` | today | last day of the window, `YYYY-MM-DD` |
| `--dry-run` | off | print the plan, send nothing |
| `--skip-enrol` | off | don't re-create persons on each device |

---

## Presets

| Preset | Use it for |
| --- | --- |
| `default` | Realistic day-to-day data. ~4% absent, 18% late, occasional missed punches. |
| `messy` | Stress-testing. 15% absent, 40% late, 15% missing out-punches, 12% double taps. |
| `clean` | Verifying report arithmetic. Everyone present, on time, every day — so any total you compute has a known correct answer. |

```bash
npm run attendance -- --preset clean --period week --employees 10
npm run attendance -- --preset messy --period month
```

---

## What gets simulated

The messy cases are the point. An HRM system that only ever sees tidy in/out
pairs has not been tested.

| Behaviour | Why your HRM needs to handle it |
| --- | --- |
| **Missing out-punch** | An open shift with no end. Do you leave it open, auto-close it, or flag it? |
| Missing in-punch | An orphan check-out with no start. |
| Double tap | Two punches seconds apart. Must not count as two shifts. |
| Late arrival | Lateness reports, deductions. 5–45 min after shift start. |
| Early leave | Short hours. |
| Overtime | 15–120 min past shift end. |
| Absent | No punches at all that day — distinct from "missing punch". |
| Wrong door | Employee used a terminal that isn't their usual one. |
| **Night shift** | Clock-out lands on the *next calendar day*. Breaks naive `GROUP BY date`. |

Credential mix: 70% fingerprint, 20% card, 10% face.

Shift types: `day` (09:00–17:30), `early` (06:00–14:30), `night` (22:00–06:00,
crosses midnight), `split` (08:00–20:00).

---

## Reproducibility

The generator is deterministic. The same `--seed` always produces the same
dataset, down to the second on each punch.

This matters when a report is wrong: you can reproduce the exact data that broke
it instead of regenerating and hoping the bug reappears.

```bash
npm run attendance -- --period month --seed 7 --dry-run    # inspect
npm run attendance -- --period month --seed 7              # send
# ... find a bug in your report ...
npm run attendance -- --period month --seed 7              # identical data again
```

Change `--seed` to get a different but equally reproducible dataset.

---

## Customising scenarios

Edit `simulator/attendance/profile.js`.

```js
workdays: [1, 2, 3, 4, 5],        // 0 = Sunday. Use [0,1,2,3,4] for Sun-Thu.
holidays: ['2026-07-15'],         // no attendance expected on these dates

devices: [
  { port: 8100, name: 'Main Entrance', doorNo: 1 },
  { port: 8101, name: 'Rear Door',     doorNo: 1 },
],

staff: {
  count: 20,
  employeeNoPrefix: 'EMP',
  shifts: ['day', 'day', 'day', 'early', 'night'],   // weighted by repetition
},

behaviour: {
  late: 0.18,
  lateMinutes: [5, 45],
  missingOutPunch: 0.03,
  // ...
},
```

Add your own preset by exporting another object from `PRESETS`.

### Using a real roster

Supply `roster` on the profile and the generated staff list is ignored:

```js
roster: [
  { employeeNo: 'E1001', name: 'Supun Perera', shift: 'day',   homeDevice: 0 },
  { employeeNo: 'E1002', name: 'Nimal Silva',  shift: 'night', homeDevice: 1 },
]
```

---

## Suggested test workflow

**1 — Baseline the arithmetic.** Clean data has a known correct answer, so any
mismatch is your report, not the data.

```bash
npm run attendance -- --preset clean --period week --employees 10 --seed 1
```
With 10 employees × 5 workdays you should compute exactly 50 complete shifts,
zero late, zero absent.

**2 — Add realism.**

```bash
npm run attendance -- --preset default --period month --seed 2
```
Compare your report's late/absent/overtime counts against the printed plan
summary. They should agree.

**3 — Break it on purpose.**

```bash
npm run attendance -- --preset messy --period month --seed 3
```
This is where open shifts, orphan check-outs and double taps surface. If your
report crashes or silently drops rows, you've found the bug you were looking for.

**4 — Test the outage path.** Take a door offline mid-generation and confirm
nothing is lost once it returns:

```bash
curl -X POST http://127.0.0.1:8100/sim/offline
npm run attendance -- --period day --seed 4
curl -X POST http://127.0.0.1:8100/sim/online
```

---

## Starting from a clean slate

The generator adds to whatever is already stored. To start empty:

```bash
curl -X DELETE 'http://localhost:8080/events?all=true&confirm=true'
```

Or keep your real punches and drop only generated ones by date:

```bash
curl -X DELETE 'http://localhost:8080/events?before=2026-07-01&confirm=true'
```

Both are dry-run by default — drop `&confirm=true` to preview first.

---

## Known limit

The runner enrols the roster on **every** device directly, so multi-device
enrolment works here. But the **backend still manages a single device**:
`DEVICE_HOST` is one value, so `/persons`, `/device/*` and backfill only ever
talk to that one terminal.

Event *ingestion* is already multi-device — every punch is stored with its
`device_ip`, which is how a four-door fleet works today. Converting the backend
config to a device list is outstanding work, and worth doing before building HRM
reports that assume fleet-wide enrolment.
