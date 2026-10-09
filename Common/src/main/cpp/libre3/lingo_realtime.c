/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */

#include "lingo_realtime.h"

/*
 * Frame layout (offsets into the 51-byte decrypted plaintext), confirmed
 * against the app's parser and captured frames:
 *
 *   @0    uint16 LE  lifeCount
 *   @2    uint16 LE  historicLifeCount
 *   @4    15 bytes   analyte block, channel 0
 *   @19   15 bytes   analyte block, channel 1
 *   @34   uint8      analyte-type map: low nibble = ch0 type, high nibble = ch1 type
 *   @37   uint16 LE  temperature (1/100 degC, 0x8000 = invalid)
 *
 * Glucose is reported in whichever channel's type == GLUCOSE. Within an analyte
 * block (offsets relative to its start), cappedReading is at RESULT_CAPPED (0):
 *   value & 0x0FFF        -> reading, mg/dL (scale 1)
 *   (value & 0x6000) >> 13 -> result range
 *   value & 0x8000        -> data-quality / invalid flag
 *
 * These offsets, the channel iteration, the analyte type codes and the bit layout
 * are taken verbatim from the decompiled GlucoseKetoneSPL.parseOneMinuteISFReading /
 * parseAnalyte / parseMeasurement, and the glucose value is validated against real
 * warmed-up captures (201 mg/dL, matching the Lingo app) in lingo_realtime_test.c.
 * For glucose, rate is a signed little-endian short in 0.01 mg/dL/min, with
 * 0x8000 meaning unknown. The trend is the low three bits at ACTIONABLE_TREND;
 * bit 3 is a separate actionable flag. These fields use the same units/codes
 * as Libre 3 and are confirmed by GlucoseKetoneSPL.parseAnalyte in Lingo 6725.
 */
#define OFF_LIFECOUNT        0
#define OFF_HIST_LIFECOUNT   2
#define OFF_TYPEMAP          34
#define OFF_TEMPERATURE      37

#define FIRST_ANALYTE_OFFSET 4
#define ANALYTE_LEN          15

/* Analyte-block-relative field offsets (GlucoseKetoneSPL constants).
 *   RESULT_CAPPED=0 RATE_OF_CHANGE=2 EXTENDED_CODE=4 PROJECTED=6
 *   HISTORIC_CAPPED=8 ACTIONABLE_TREND=10 RESULT_UNCAPPED=11 HISTORIC_UNCAPPED=13 */
#define ANALYTE_RESULT_CAPPED     0
#define ANALYTE_RATE_OF_CHANGE    2
#define ANALYTE_HISTORIC_CAPPED   8
#define ANALYTE_ACTIONABLE_TREND  10
#define ANALYTE_RESULT_UNCAPPED   11
#define ANALYTE_HISTORIC_UNCAPPED 13

#define READING_MASK      0x0FFFu
#define DATA_QUALITY_FLAG 0x8000u
#define TEMPERATURE_INVALID 0x8000u

static uint16_t rd_u16le(const uint8_t *p) {
    return (uint16_t)(p[0] | ((uint16_t)p[1] << 8));
}

int lingo_parse_realtime(const uint8_t *plain, size_t len, lingo_realtime_t *out) {
    if (plain == NULL || out == NULL) return -1;
    if (len != LINGO_REALTIME_LEN) return -2;

    out->life_count = rd_u16le(plain + OFF_LIFECOUNT);
    out->historic_life_count = rd_u16le(plain + OFF_HIST_LIFECOUNT);

    const uint8_t typemap = plain[OFF_TYPEMAP];
    out->channel0_type = (uint8_t)(typemap & 0x0F);
    out->channel1_type = (uint8_t)((typemap >> 4) & 0x0F);

    /* Find the channel whose analyte type is GLUCOSE and read its cappedReading
     * from that channel's own block (ch0 @4, ch1 @19), matching the app's
     * parseOneMinuteISFReading. This is correct regardless of which channel
     * carries glucose. */
    out->glucose_valid = false;
    out->glucose_mgdl = 0;
    out->rate_of_change = -32768;
    out->trend = 0;
    out->historic_valid = false;
    out->historic_mgdl = 0;
    out->uncapped_glucose_valid = false;
    out->uncapped_glucose_mgdl = 0;
    out->uncapped_historic_valid = false;
    out->uncapped_historic_mgdl = 0;
    for (int ch = 0; ch < 2; ch++) {
        const uint8_t type = (ch == 0) ? out->channel0_type : out->channel1_type;
        if (type != LINGO_ANALYTE_GLUCOSE) continue;
        const int start = FIRST_ANALYTE_OFFSET + ch * ANALYTE_LEN;
        const uint16_t capped = rd_u16le(plain + start + ANALYTE_RESULT_CAPPED);
        if ((capped & DATA_QUALITY_FLAG) == 0) {
            out->glucose_valid = true;
            out->glucose_mgdl = (uint16_t)(capped & READING_MASK);
        }
        out->rate_of_change = (int16_t)rd_u16le(plain + start + ANALYTE_RATE_OF_CHANGE);
        const uint8_t trend = plain[start + ANALYTE_ACTIONABLE_TREND] & 0x07u;
        out->trend = trend <= 5 ? trend : 0;
        /* Historic capped reading; valid during warm-up even when the current
         * reading is not, so a fresh session can still show recent history. */
        const uint16_t hist = rd_u16le(plain + start + ANALYTE_HISTORIC_CAPPED);
        if ((hist & DATA_QUALITY_FLAG) == 0) {
            out->historic_valid = true;
            out->historic_mgdl = (uint16_t)(hist & READING_MASK);
        }
        const uint16_t unc = rd_u16le(plain + start + ANALYTE_RESULT_UNCAPPED);
        if ((unc & DATA_QUALITY_FLAG) == 0) {
            out->uncapped_glucose_valid = true;
            out->uncapped_glucose_mgdl = (uint16_t)(unc & READING_MASK);
        }
        const uint16_t unch = rd_u16le(plain + start + ANALYTE_HISTORIC_UNCAPPED);
        if ((unch & DATA_QUALITY_FLAG) == 0) {
            out->uncapped_historic_valid = true;
            out->uncapped_historic_mgdl = (uint16_t)(unch & READING_MASK);
        }
        break;
    }

    const uint16_t temp = rd_u16le(plain + OFF_TEMPERATURE);
    if (temp == TEMPERATURE_INVALID) {
        out->temperature_valid = false;
        out->temperature_centi = 0;
    } else {
        out->temperature_valid = true;
        out->temperature_centi = temp;
    }

    return 0;
}
