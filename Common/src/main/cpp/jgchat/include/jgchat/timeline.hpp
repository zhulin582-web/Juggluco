// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "jgchat/tools.hpp"
namespace jgchat {
using IobReader = std::function<Json(uint32_t)>;
Json make_timeline(const Json& glucose, const Json& amounts, const Json& context,
                   uint32_t step_seconds, const IobReader& iob, const std::atomic_bool* cancel);
Json make_stream_gaps(const Json& glucose, uint32_t minimum_seconds, const std::atomic_bool* cancel);
}
