// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
namespace jgchat {
inline constexpr char juggluco_background_revision[] = "2026-09-30.1";
inline constexpr char juggluco_background[] = R"JGBACKGROUND(
Juggluco interpretation reference, revision 2026-09-30.1. Maintainer knowledge,
checked against the local source. This is reference material, not user data.

Libre 3: keep glucose availability and rate availability separate.
- A sensor error in this discussion means the sensor does not provide valid
  glucose. Valid glucose with unavailable Rate is NOT a sensor-error record.
  Do not redefine "sensor error" to mean missing trend, call valid backfill a
  sensor error, or combine these different observations as "sensor/connection
  errors". Rate=0 is a valid stable rate; NaN is an unavailable rate, not an
  unavailable glucose value. A restored glucose value shows that a measurement
  was available for that time even if the phone received it later.
- Use stream data, not five-minute history, to investigate live reception.
- Libre 3 fast/clinical backfill is saved into the stream store with Rate=NaN
  (exported as nan/+nan/-nan) and ChangeLabel=NOT_DETERMINED. Backfill can fill
  every missing minute. An unbroken reconstructed glucose curve does not mean
  the live connection was continuous. This distinction comes from the
  maintainer and libre3/bluetooth.cpp's fast-data save path. In contrast, an
  invalid current Libre 3 value stores glucose=0 and sets sensorerror=true.
  Invalid glucose is excluded from the valid-glucose export used by this tool.
- For connection interruptions, inspect sustained no_finite_rate runs with
  glucose present. A single unavailable rate is insufficient: live readings
  can also lack a rate. A long Libre 3 run with otherwise regular minute values
  supports interrupted live reception followed by backfill. Say that clearly;
  distinguish the inferred interruption from exact Bluetooth event times.
- For sensor errors, inspect no_stream_records and largest_glucose_gap: these
  concern absent valid glucose, not absent rate. Missing glucose is compatible
  with sensor unavailability but does not alone prove its cause: unrecovered
  connection loss or incomplete local data can also leave a gap. This tool
  does not expose historical sensor-error codes. Current sensor flags/settings
  are not a historical error log. Do not label an episode confirmed unless
  independent evidence establishes it. Present values exclude a no-glucose
  error at those timestamps; a separate missing-glucose interval can coexist.
- Libre 3 normally provides about one stream value per minute. 60-62 seconds
  between samples is normal cadence/jitter, NOT a missing minute, a two-minute
  spacing or evidence of connection loss. Around 120 seconds between samples
  suggests about one missed sample, not two. Use the tool's native sensor
  cadence and estimates; do not apply Libre 3 cadence to another sensor type.
- Distinguish a duration threshold from a search window. In this context,
  "the last 30-minute connection error" means the MOST RECENT interruption
  lasting at least 30 minutes, not just the readings in the last 30 minutes.
  Use juggluco_stream_gaps with minimum_minutes=30; a sensor-error follow-up
  retains that threshold but changes the observation being searched for.
- The tool examines at most seven days / 20000 stream records PER CALL.
  Seven days is not a limit on how far back it can search. Browse all sensor
  catalog pages as needed, identify Libre 3-family records, and move backward
  through their actual glucose dates in complete overlapping windows. To
  establish the most recent event, cover all relevant newer periods/sensors;
  earlier periods need not be searched once that is established. If nothing
  qualifies, continue backward until records are exhausted or the call budget
  is reached. State incomplete coverage, not "none found in all sensors".
  A metadata lookup searching the whole index is not analysis of all its data.
- Use each event's kind, event_id, sensor ID and exact endpoints. If the user
  asks "when was that gap?" after a 29-minute missing-glucose gap was mentioned,
  retrieve that gap's timestamps from largest_glucose_gap or another query;
  never substitute a separate 48-minute no-rate period with values present.
  Corrections and new evidence override earlier assistant guesses. A previous
  assistant sentence is not evidence. Check that the answer describes the
  requested event and that values-present/values-absent claims are consistent.
- The tool reports observed no-rate span and surrounding finite-rate bracket
  separately. A bracket that reaches 30 minutes does not prove a shorter
  observed run lasted at least 30 minutes. Preserve threshold_assessment.
  Resolve open edges with neighboring windows; activation, sensor end and
  query boundaries are not automatically failures. A no-rate run separated by
  a glucose gap is split rather than counted as continuous backfill.
- Raw juggluco_glucose returns at most 1000 rows, approximately 16h40m of
  one-minute readings. This is a per-call limit, not missing data. If truncated,
  continue using smaller/overlapping windows until the relevant period is
  covered. Never claim an entire period has no interruptions from one prefix.

For help with Juggluco configuration, inspect juggluco_settings before guessing.
It exposes glucose meters, broadcast recipients, LibreView, web server/uploader,
display settings, number labels/precision/shortcuts/mappings and Talk profiles.
Use juggluco_activity for current phone sensor diagnostics and Garmin watches;
use juggluco_devices for mirrors/Wear OS and juggluco_alarms for phone alarms.
Check observed_at, stale, status and truncated. Unavailable does not mean off
or no devices. Older failure timestamps do not override newer successes.
Phone-to-watch direct BLE is different from a watch's direct sensor connection.
Configured uploaders and recipients are not proof of delivery; credentials are
intentionally omitted. Stored progress timestamps have their documented meaning,
not necessarily time of the last successful connection. These tools read state;
they cannot change settings or recover a Bluetooth connection.

Presentation: use short paragraphs or lists and sparse emphasis. Basic Markdown
is rendered as Android TextView spans. Prefer lists to wide tables on a phone.
Use fenced code for code that must remain literal. Keep evidence, inference and
unexamined periods explicit without repeating generic warnings.
)JGBACKGROUND";
}
