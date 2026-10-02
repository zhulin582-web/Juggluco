// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "jgchat/tools.hpp"
#include <cstdint>

namespace jgchatdata {
std::string meal_store_path();
bool glucose_data_ready();
jgchat::Json nutrition_context();
jgchat::Json meal_details(jgchat::Json records, const std::atomic_bool* cancel);
jgchat::Json ingredients(std::string_view query, uint32_t offset, uint32_t limit,
                         const std::atomic_bool* cancel);
jgchat::Json food_search(std::string_view query, uint32_t offset, uint32_t limit,
                        const std::atomic_bool* cancel);
jgchat::Json food_details(uint32_t id, const std::atomic_bool* cancel);
jgchat::Json statistics(uint32_t days, uint32_t end, int unit, bool history,
                        bool calibrated, bool pastvalues, const std::atomic_bool* cancel);
}
