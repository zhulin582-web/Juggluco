/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
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
/*                                                                                   */
/*      Fri Nov 21 11:08:14 CET 2025                                                 */
#pragma once 
#include <vector>
#include <string_view>
#include <string>
#include <span>
#include <openssl/ssl.h>

using namespace std::literals;
// Borrowed only for one connection. Never installed on the shared SSL_CTX.
struct HttpsClientIdentity {
    X509 *certificate;
    EVP_PKEY *privateKey;
    X509 *issuer;
};
class ContextHTTPS {
private:
    SSL_CTX* ctx=nullptr;
    bool error=false;
static bool initLibrary();
public:

    static ContextHTTPS &getContext() ;
    ContextHTTPS();
    ~ContextHTTPS();
// Location is optional output; following redirects is the caller's decision.
std::pair<std::vector<char>,int>request(const std::string_view host,int port,const std::string_view path,const std::string_view TYPE,const std::span<const char> input, const std::string_view header={}, std::string *location=nullptr, const HttpsClientIdentity *client=nullptr);
std::pair<std::vector<char>,int>   getRequest(const std::string_view host,int port,const std::string_view path,const std::span<const char> input={}, const std::string_view header={})  {
    return  request(host, port,path,"GET"sv, input,header) ;
    }
std::pair<std::vector<char>,int>  putRequest(const std::string_view host,int port,const std::string_view path,const std::span<const char> input={}, const std::string_view header={})  {
    return  request(host, port,path,"PUT"sv, input,header) ;
    }
std::pair<std::vector<char>,int>  postRequest(const std::string_view host,int port,const std::string_view path,const std::span<const char> input={}, const std::string_view header={})  {
    return  request(host, port,path,"POST"sv, input,header) ;
    }
 };
