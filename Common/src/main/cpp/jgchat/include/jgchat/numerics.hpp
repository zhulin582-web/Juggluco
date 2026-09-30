// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "workspace.hpp"

namespace jgchat {
// Generic numerical operations on explicitly selected saved tables. No live
// health-store access, network, generated code, implicit units or dosing rules.
Json numerical_tool_definitions();
Json fit_analysis_model(const AnalysisTable&, const Json& arguments, const std::atomic_bool&);
Json predict_analysis_model(const AnalysisTable&, const Json& saved_model, const std::atomic_bool&);
Json plot_analysis_table(const AnalysisTable&, const Json& arguments, const std::atomic_bool&);
}
