// GS3 V3 packet codec. GPL-3.0-or-later, like Juggluco.
#pragma once
#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace gs3v3 {
using Bytes = std::vector<std::uint8_t>;
using Block = std::array<std::uint8_t, 16>;
using Address = std::array<std::uint8_t, 6>; // display order: F6 09 FF E2 20 B2
using Account = std::array<std::uint8_t, 12>;
// The adapter performs ONE AES-128 ECB encryption, with no padding.
using EncryptBlock = bool (*)(const Block &key, const Block &input, Block &output);

inline constexpr Block transport_key{
    0x01,0x38,0x0b,0x9a,0x00,0x5b,0x02,0x5d,
    0xcd,0x9e,0xc3,0x99,0x09,0x37,0xaa,0xe8};
inline constexpr Block algorithm_key{
    0x11,0xb8,0xab,0x00,0x01,0x4b,0x2a,0x5f,
    0xcb,0x91,0xf3,0xaa,0x99,0x27,0xbf,0xe0};
// Verified for the GNL sensor in the supplied trace; do not assume every region.
inline constexpr Block global_registration{
    'T','H','E','5','4','4','U','0','T','Y','I','T','E','4','6','1'};

inline Block make_iv(const Address &address) {
    Block iv{};
    std::reverse_copy(address.begin(), address.end(), iv.begin());
    return iv;
}
inline std::uint16_t le16(const std::uint8_t *p) {
    return std::uint16_t(p[0]) | (std::uint16_t(p[1]) << 8);
}
inline std::uint32_t le32(const std::uint8_t *p) {
    return std::uint32_t(p[0]) | (std::uint32_t(p[1]) << 8) |
           (std::uint32_t(p[2]) << 16) | (std::uint32_t(p[3]) << 24);
}
inline void checksum(Bytes &p) {
    std::uint8_t sum=0;
    for (std::size_t i=0; i+1<p.size(); ++i) sum+=p[i];
    p.back()=std::uint8_t(-sum);
}
inline bool valid_frame(const Bytes &p) {
    if (p.size()<4 || p.size()>250 || std::size_t(p[0])+1!=p.size()) return false;
    std::uint8_t sum=0;
    for (auto b:p) sum+=b;
    return sum==0;
}
// Restart OFB at the original IV for EACH packet, not for each connection.
inline bool ofb(const Block &key, const Block &iv, const Bytes &in,
                Bytes &out, EncryptBlock encrypt) {
    if (!encrypt) {out.clear();return false;}
    Block feedback=iv;
    Bytes result(in.size());
    for (std::size_t offset=0; offset<in.size(); offset+=16) {
        Block next{};
        if (!encrypt(key, feedback, next)) {out.clear();return false;}
        feedback=next;
        const auto n=std::min<std::size_t>(16,in.size()-offset);
        for (std::size_t i=0;i<n;++i) result[offset+i]=in[offset+i]^feedback[i];
    }
    out=std::move(result);
    return true;
}
inline bool transport(const Address &address, const Bytes &in, Bytes &out,
                      EncryptBlock encrypt) {
    return ofb(transport_key,make_iv(address),in,out,encrypt);
}
inline bool decrypt_frame(const Address &address, const Bytes &in, Bytes &out,
                          EncryptBlock encrypt) {
    if (in.size()<4 || in.size()>250) {out.clear();return false;}
    if (!transport(address,in,out,encrypt) || !valid_frame(out)) {
        out.clear();return false;
    }
    return true;
}
inline Bytes auth_plain(const Address &address, const Account &account,
                        const Block &registration=global_registration) {
    Bytes p(38,0);
    p[0]=0x25;p[1]=0xe2;p[2]=0; // observed authentication mode
    std::reverse_copy(address.begin(),address.end(),p.begin()+3);
    std::copy(registration.begin(),registration.end(),p.begin()+9);
    std::copy(account.begin(),account.end(),p.begin()+25);
    checksum(p);return p;
}
inline Bytes query_plain(std::uint8_t subcommand) {
    Bytes p{0x03,0xf0,subcommand,0};checksum(p);return p;
}
inline Bytes history_plain(std::uint16_t first, std::uint16_t last=0xffff) {
    Bytes p{0x06,0x39,std::uint8_t(first),std::uint8_t(first>>8),
            std::uint8_t(last),std::uint8_t(last>>8),0};
    checksum(p);return p;
}

struct Record {
    std::uint16_t index;
    std::uint16_t remaining;
    std::uint16_t temperature_wire, dump_wire, current_wire;
    std::uint16_t display_glucose; // 0xff01 is no display result in these captures
    std::uint16_t algorithm_glucose_wire, algorithm_glucose_decoded;
    std::uint8_t trend, present_cstate, algorithm_cstate;
    std::uint8_t tstate, dstate, algorithm_reserved;
    std::uint16_t ce_voltage_wire, re_voltage_wire;
};
inline bool parse_records(const Bytes &p, std::vector<Record> &out,
                          EncryptBlock encrypt) {
    out.clear();
    if (!valid_frame(p) || p.size()<8) return false;
    if (p[1]!=0x32 && p[1]!=0x36 && p[1]!=0x39) return false;
    const unsigned count=p[2];
    if (!count || p.size()!=8+16*count) return false;
    const unsigned start=le16(p.data()+3);
    const unsigned tail=le16(p.data()+p.size()-3);
    if (start+count-1>0xffff || tail+count-1>0xffff) return false;
    Block zero{}, mask{};
    if (!encrypt || !encrypt(algorithm_key,zero,mask)) return false;
    for (unsigned i=0;i<count;++i) {
        const auto *r=p.data()+5+16*i;
        Record v{};
        v.index=start+i;v.remaining=tail+count-1-i;
        v.temperature_wire=le16(r);v.dump_wire=le16(r+2);v.current_wire=le16(r+4);
        v.display_glucose=le16(r+6);v.algorithm_glucose_wire=le16(r+8);
        v.algorithm_glucose_decoded=v.algorithm_glucose_wire^le16(mask.data());
        v.trend=r[10]&7;v.present_cstate=(r[10]>>3)&1;v.algorithm_cstate=r[10]>>4;
        v.tstate=r[11]&3;v.dstate=(r[11]>>2)&7;v.algorithm_reserved=r[11]>>5;
        v.ce_voltage_wire=le16(r+12);v.re_voltage_wire=le16(r+14);
        out.push_back(v);
    }
    return true;
}
struct DeviceTime {
    std::uint16_t startover_time;
    std::uint32_t activation_time, current_time, last_time;
    std::uint16_t last_index;
};
inline bool parse_device_time(const Bytes &p, DeviceTime &out) {
    if (!valid_frame(p) || p.size()!=20 || p[1]!=0xf0 || p[2]!=3) return false;
    out={le16(p.data()+3),le32(p.data()+5),le32(p.data()+9),
         le32(p.data()+13),le16(p.data()+17)};
    return true;
}
} // namespace gs3v3
