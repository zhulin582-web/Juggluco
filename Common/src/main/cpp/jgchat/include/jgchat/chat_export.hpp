// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "tools.hpp"

namespace jgchat {
// Only the visible transcript is supplied, never credentials or tool history.
// HTML embeds plots as inert image resources; SVG exports one original plot.
std::string render_chat_export(const Json& messages, std::string_view pending,
    bool busy, std::string_view format, int message = -1, int plot = -1,
    const Json& labels = Json::object());
// Borrowed descriptors: support document-provider pipes, do not seek or close.
std::size_t write_chat_export(int output, std::string_view contents);
std::size_t copy_chat_export(int input, int output);
}
