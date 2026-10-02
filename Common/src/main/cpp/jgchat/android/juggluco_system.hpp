// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "jgchat/tools.hpp"
#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace jgchatdata {
enum class SensorFamily { unknown, libre, libre3, dexcom, sibionics, accu_chek, aidex_x, caresens_air };
// Deliberately contains no authentication keys, Bluetooth address or raw Info.
struct SensorSnapshot {
    uint32_t index{}, start{}, wear_seconds{}, expected_seconds{}, warmup_seconds{};
    uint32_t last_stream{}, last_scan{}, stream_interval{}, history_interval{}, restart{};
    uint32_t first_glucose{}, last_history{};
    uint32_t stored_wear_seconds{}, stored_history_interval{};
    std::string id, display_name;
    SensorFamily family{SensorFamily::unknown};
    // Libre 2 protocol generation is NOT the marketed Libre model number.
    unsigned libre_protocol_generation{}, si_subtype{};
    bool new_si{}, unused{}, finished{}, hidden{}, available{true};
};
struct SensorPage { bool available{}; uint32_t total{}; std::vector<SensorSnapshot> rows; };
SensorPage read_sensor_page(uint32_t offset, uint32_t limit, const std::atomic_bool* cancel);
SensorPage read_sensors_by_id(const std::vector<std::string>& ids, const std::atomic_bool* cancel);
jgchat::Json sensor_records(uint32_t offset, uint32_t limit, const std::atomic_bool* cancel);
jgchat::Json exact_sensor_records(const std::vector<std::string>& ids, const std::atomic_bool* cancel);
jgchat::Json sensor_record(const SensorSnapshot& sensor, uint32_t now);
struct CalibrationSnapshot {
    uint32_t time{};
    double a{}, b{};
};
struct CalibrationSeries {
    uint32_t stored_count{};
    bool valid_count{true};
    std::vector<CalibrationSnapshot> rows;
};
struct SensorCalibrations {
    std::string status{"unavailable"}, reason;
    std::optional<uint32_t> sensor_index;
    std::vector<uint32_t> matching_indices;
    std::array<CalibrationSeries, 2> sources; // Native order: stream, history.
    bool settings_available{}, enabled{}, past_values{}, has_real_history{};
};
SensorCalibrations read_calibrations(const std::string& id, std::optional<uint32_t> index,
    const std::atomic_bool* cancel);
jgchat::Json calibration_records(const std::string& id, std::optional<uint32_t> index,
    const std::atomic_bool* cancel);
jgchat::Json mirror_configuration(const std::atomic_bool* cancel);
jgchat::Json alarm_configuration(const std::atomic_bool* cancel);
jgchat::Json device_configuration(const std::atomic_bool* cancel);
jgchat::Json settings_configuration(const std::string& section, const std::atomic_bool* cancel);
jgchat::Json activity_configuration(const std::string& section, const std::atomic_bool* cancel);
void update_phone_activity(const jgchat::Json& snapshot);
// JVM publishes only existing Android Wear node-cache fields. Model networking
// and all tools remain native. Sanitized again before retention or model use.
void update_wear_nodes(const jgchat::Json& snapshot);
}
