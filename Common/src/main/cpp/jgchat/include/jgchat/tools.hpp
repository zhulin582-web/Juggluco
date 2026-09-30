// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <nlohmann/json.hpp>
#include <atomic>
#include <functional>
#include <string>
#include <string_view>

namespace jgchat {
using Json = nlohmann::json;
using ToolHandler = std::function<Json(std::string_view, const Json&)>;
Json data_tool_definitions();
// Fixed data/plot operations and file preparation; file writes happen later in
// the Android bridge after a successful answer. Throws for invalid arguments.
Json execute_data_tool(std::string_view name, const Json& arguments,
                       const std::atomic_bool* cancel = nullptr);
}
