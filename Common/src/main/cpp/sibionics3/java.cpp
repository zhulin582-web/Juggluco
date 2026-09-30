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
/*      Wed May 06 21:11:14 CEST 2026                                                */
#ifdef SIBIONICS
#include <stdio.h>
#include <jni.h>
#include <vector>
#include <bit>
#include "fromjava.h"
#include "SensorGlucoseData.hpp"
#include "streamdata.hpp"
#include "jniclass.hpp"
#include "logs.hpp"
#include "datbackup.hpp"

extern jlong gs3Glucose(si3stream &stream,std::vector<uint8_t> &vect,std::string &message,const uint8_t* in_packet, int in_len, uint32_t &nowsecs);

#define javapackage "tk/glucodata/"

extern "C" JNIEXPORT void JNICALL fromjava(gs3SetDeviceInfo)(JNIEnv *env,jclass,jlong dataptr,jstring manufacturer,jstring software) {
    if(!dataptr) return;
    auto *stream=reinterpret_cast<si3stream *>(dataptr);
    stream->gs3={};
    if(software) {
        const char *text=env->GetStringUTFChars(software,nullptr);
        stream->gs3.v3=strstr(text,"_V3.")!=nullptr;
        env->ReleaseStringUTFChars(software,text);
    }
    // siType 4/5 remains a legacy regional choice, not a protocol version.
    // A short NFC record alone does not establish that a sensor is Chinese.
    if(manufacturer) {
        const char *text=env->GetStringUTFChars(manufacturer,nullptr);
        std::string_view name(text);
        if(name.ends_with("GNL")) stream->gs3.legacySubtype=4;
        else if(name.ends_with("CN")) stream->gs3.legacySubtype=5;
        env->ReleaseStringUTFChars(manufacturer,text);
    }
    if(const auto *last=stream->hist->lastValidStream())
        stream->gs3.restore(last->getid(),last->gettime());
}

extern "C" JNIEXPORT jboolean JNICALL fromjava(gs3IsV3)(JNIEnv *,jclass,jlong dataptr) {
    return dataptr&&reinterpret_cast<si3stream *>(dataptr)->gs3.v3;
}


extern "C" JNIEXPORT void JNICALL   fromjava(isChinese)(JNIEnv *env, jclass cl,jlong dataptr) {
      if(auto *sdata=reinterpret_cast<streamdata *>(dataptr)) {
          if(SensorGlucoseData *sens=sdata->hist) {
              sens->getinfo()->siType=5;
             }
          }
    }
extern "C" JNIEXPORT jobject JNICALL   fromjava(gs3Glucose)(JNIEnv *env, jclass cl,jlong dataptr, jbyteArray value,jlong mmsec) {
        if(!value) {
            LOGAR("gs3Glucose value==null");
            return nullptr;
            }
       auto *sdata=reinterpret_cast<si3stream *>(dataptr);
       if(!sdata||!sdata->hist) return nullptr;
       std::vector<uint8_t> vect;
       std::string message;
       uint32_t nowsecs;
       jlong result;
        {
        const CritAr<uint8_t>  bluedata(env,value);
        const auto arlen=env->GetArrayLength(value);
        nowsecs=mmsec/1000;
        result=gs3Glucose(*sdata,vect,message,bluedata.data(),arlen,nowsecs);
        }
       const int uitlen=(int)vect.size();
       jbyteArray jcmd;
       if(uitlen) {
            jcmd=env->NewByteArray(uitlen);
            env->SetByteArrayRegion(jcmd, 0, uitlen,(const jbyte*)vect.data());
            }
       else
            jcmd=nullptr;
       static constexpr const char GS3class[]= javapackage "GS3Data";
       static  jclass  uitcl=  (jclass) env->NewGlobalRef(env->FindClass(GS3class));
       static constexpr const char sig[]= "(J[BLjava/lang/String;J)V";
       static jmethodID iconstruct = env->GetMethodID(uitcl,"<init>",sig);
       jstring jmess=message.size()?env->NewStringUTF(message.data()):nullptr;
       return env->NewObject(uitcl,iconstruct,result,jcmd,jmess,nowsecs*1000LL);
       }


extern bool md5sum(const unsigned char msg[],int msglen,unsigned char out[16]);

extern "C" JNIEXPORT jstring JNICALL   fromjava(md5sum)(JNIEnv *env, jclass cl,jstring jinput) {
   const char *input = env->GetStringUTFChars( jinput, nullptr);
   destruct   dest([jinput,input,env]() {env->ReleaseStringUTFChars(jinput, input);});
   const int inputlen= env->GetStringUTFLength( jinput);
   constexpr const int md5len=16;
   unsigned char md5b[md5len];
   md5sum((const unsigned char *)input,inputlen,md5b);
   char uitstring[md5len*2+1];
   int uitlen=0;
   for(unsigned char  ch : md5b) {
        uitlen+=sprintf(uitstring+uitlen,"%02x",ch);
        }
   uitstring[uitlen]='\0';
   return env->NewStringUTF(uitstring);
   }
//GJ,GS3*-BEABMA,GNL,AAC25B18AAFZ,E2AFF9F01F19,PG291,HT
//GJ,04CS1260309004V,6,190SRN"
#include <ranges>
#include <string_view>

constexpr std::string_view nth_field(std::string_view s, std::size_t n, char d = ',') {
    auto parts = s | std::views::split(d);
    auto it = parts.begin();
    std::ranges::advance(it, n, parts.end());
    return it == parts.end() ? std::string_view{} : std::string_view{*it};
}

// usage
extern void    sendstreaming(SensorGlucoseData *hist);
extern "C" JNIEXPORT jstring JNICALL   fromjava(gs3nfc)(JNIEnv *env, jclass cl, jbyteArray jscan) {
     SensorGlucoseData *sens;
     {
       const CritAr<char>  scan(env,jscan);
       const char *buf=scan.data();
       const int size=env->GetArrayLength(jscan);
       if(size<1) return nullptr;
       const int status = buf[0] & 0xFF;
       const int startpos = 1+(status & 0x3F);
       if(startpos>size) return nullptr;
       const size_t len=size-startpos;
       const char *start=buf+startpos;
       LOGGER("gs3nfc %.*s\n",len,start);
     std::string_view scanview(start,len);
     const char *blueToothNum;
     int siType;
     if(len<6) return nullptr;
     if(!memcmp("GS3",start+3,3)) {
         std::string_view deviceName=nth_field(scanview,3, ',');
         int devlen=deviceName.size();
         if(!devlen) {
              LOGAR("ns3nfc no 3th ,");
              return nullptr;
              }
         if(devlen<6) {
            LOGGER("ns3nfc %d too small\n",devlen); 
            return nullptr;
            }
        blueToothNum=deviceName.end()-6;
        siType=4;
        LOGGER("device name %.*s blueToothNum %.*s\n",deviceName.size(),deviceName.data(),6,blueToothNum);
        }
     else {
        // Keep the existing stored subtype; metadata/authentication selects V3.
        const auto suffix=nth_field(scanview,3,',');
        if(suffix.size()!=6) return nullptr;
        siType=5;
        blueToothNum=suffix.data();
        }
     auto [sensorindex,sensin]= sensors->genMakeSI3sensorIndex(blueToothNum,scanview,time(nullptr),siType);
     sens=sensin;
    }
    if(sens) {
      const char *name=sens->shortsensorname()->data();
      LOGGER("gen3nfc=%s\n",name);
      sendstreaming(sens);  //TODO??
      backup->resendResetDevices();
      backup->wakebackup(wakeall);
      return env->NewStringUTF(name);
      }
    return nullptr;
    }

extern "C" JNIEXPORT void  JNICALL   fromjava(saveGS3id)(JNIEnv *env, jclass cl,jlong id) {
    const uint64_t bigendian=std::byteswap(static_cast<uint64_t>(id));
    memcpy(settings->data()->gs3id,&bigendian,sizeof(bigendian));
    memset(settings->data()->gs3id+sizeof(bigendian),0,4);
    settings->updated();
    }
extern "C" JNIEXPORT jlong  JNICALL   fromjava(getGS3id)(JNIEnv *env, jclass cl) {
    uint64_t bigendian;
    memcpy(&bigendian,settings->data()->gs3id,sizeof(bigendian));
    return std::byteswap(bigendian);
    }
#endif
