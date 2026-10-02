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

/*
 * Decoder for the Abbott Lingo realtime ("one-minute") reading.
 *
 * Lingo runs the same GKS / FreeStyle Libre 3 stack, but its realtime frame is
 * NOT the Libre 3 one that `struct oneminute` in bluetooth.cpp parses: the
 * plaintext is 51 bytes (packet type 3), carries TWO analyte channels, and puts
 * the per-channel analyte type in a nibble-packed byte.
 *
 * saveLingoMinuteL maps this decoder's glucose, rate and trend fields into the
 * existing Libre 3 storage path. The decoder can also be tested on the host
 * without the authentication libraries.
 *
 * Field layout is reproduced from the decompiled Lingo app and confirmed byte
 * for byte against captured frames, including real warmed-up readings that match
 * the Lingo app's own displayed glucose (see lingo_realtime_test.c); every value
 * carries the sensor's own data-quality flag so an invalid or warm-up reading is
 * never reported as a number.
 */

#ifndef LINGO_REALTIME_H
#define LINGO_REALTIME_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Decrypted realtime plaintext length (PATCH_ISF_RESPONSE_LEN in the app). */
#define LINGO_REALTIME_LEN 51

/* Analyte type codes packed into the type-map byte, from the decompiled
 * GKAnalyteType (INACTIVE=0, GLUCOSE=1, KETONE=2, LACTATE=3). The low nibble is
 * channel 0, the high nibble is channel 1 (GlucoseKetoneSPL.parseOneMinuteISFReading). */
enum {
    LINGO_ANALYTE_INACTIVE = 0,
    LINGO_ANALYTE_GLUCOSE  = 1,
    LINGO_ANALYTE_KETONE   = 2,
    LINGO_ANALYTE_LACTATE  = 3
};

typedef struct {
    uint16_t life_count;           /* minute counter, offset 0            */
    uint16_t historic_life_count;  /* offset 2                            */
    uint8_t  channel0_type;        /* low nibble of type-map byte @34     */
    uint8_t  channel1_type;        /* high nibble of type-map byte @34    */

    bool     glucose_valid;        /* false when the sensor flags the reading
                                      invalid (data-quality bit) or when no
                                      channel is a glucose channel — e.g. during
                                      warm-up.  Never emit a value when false. */
    uint16_t glucose_mgdl;         /* current capped glucose, mg/dL (scale 1) */

    int16_t  rate_of_change;       /* signed 0.01 mg/dL/min; -32768 = unknown */
    uint8_t  trend;                /* 0 unknown, 1 falling quickly, 2 falling,
                                     3 stable, 4 rising, 5 rising quickly;
                                     excludes the actionable bit (0x08) */

    bool     historic_valid;       /* false when the historic reading carries the
                                      data-quality flag; valid even when the
                                      current reading is not (e.g. warm-up). */
    uint16_t historic_mgdl;        /* historic capped glucose at historic_life_count */

    /* Uncapped variants (not clamped to Lingo's 40-200 display range). Juggluco is
     * built with UNCAPPED, so these are the values it stores. */
    bool     uncapped_glucose_valid;
    uint16_t uncapped_glucose_mgdl;
    bool     uncapped_historic_valid;
    uint16_t uncapped_historic_mgdl;

    bool     temperature_valid;    /* false when temperature == 0x8000        */
    int16_t  temperature_centi;    /* temperature in units of 1/100 degC      */
} lingo_realtime_t;

/*
 * Parse a decrypted Lingo realtime frame.
 *   plain : decrypted plaintext (packet type 3)
 *   len   : must be LINGO_REALTIME_LEN
 *   out   : filled on success
 * Returns 0 on success, negative on bad argument / wrong length.
 * A successful return with out->glucose_valid == false means the frame parsed
 * but the sensor reported no usable glucose (warm-up or flagged data-quality).
 */
int lingo_parse_realtime(const uint8_t *plain, size_t len, lingo_realtime_t *out);

#ifdef __cplusplus
}
#endif

#endif /* LINGO_REALTIME_H */
