#pragma once
#include <array>
#include <cstdint>
#include <string_view>

// Display order. Do not accept a partial address or truncate an octet.
inline bool sibionicsAddress(std::string_view text, std::array<uint8_t,6> &out) {
    out={};
    if(text.size()!=17) return false;
    const auto digit=[](char c) -> int {
        if(c>='0'&&c<='9') return c-'0';
        if(c>='A'&&c<='F') return c-'A'+10;
        if(c>='a'&&c<='f') return c-'a'+10;
        return -1;
    };
    std::array<uint8_t,6> parsed{};
    for(unsigned i=0;i<6;++i) {
        const auto hi=digit(text[i*3]), lo=digit(text[i*3+1]);
        if(hi<0||lo<0||(i<5&&text[i*3+2]!=':')) return false;
        parsed[i]=static_cast<uint8_t>((hi<<4)|lo);
    }
    out=parsed;
    return true;
}
