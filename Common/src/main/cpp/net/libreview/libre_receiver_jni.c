#ifndef WEAROS
#define _GNU_SOURCE 1
#include "fromjava.h"
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <pthread.h>
#include "share/logs.hpp"

// ABI copied from the helper that completed the user's live server exchange.
// No JNI_OnLoad on Abbott's lib, no CLI process, no guessed key destructors.
typedef struct { int32_t code; const char *message; } SkbResult;
typedef int32_t (*GetEngine)(void **);
typedef int32_t (*Import)(void *, const void *, int32_t, void **);
typedef SkbResult (*Sign)(void *,void *,const void *,int32_t,void *,int32_t);
typedef SkbResult (*Gcm)(void *,void *,const void *,int32_t,const void *,int32_t,void *,void *);
static const int32_t SKB_OK=0x107fe3ad;
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static void *library, *engine, *private_key, *wrap_key;
static Sign sign_fn;
static Gcm encrypt_fn,decrypt_fn;
static int initialized,init_failed;

static void error(JNIEnv *env,const char *message) {
    LOGGER("libreview_receiver %s\n",message);
    if((*env)->ExceptionCheck(env)) return;
    jclass klass=(*env)->FindClass(env,"java/io/IOException");
    if(klass) (*env)->ThrowNew(env,klass,message);
}
static int result(JNIEnv *env,SkbResult r,const char *what) {
    if(r.code==SKB_OK) return 1;
    char msg[128]; snprintf(msg,sizeof(msg),"%s: SKB status 0x%08x",what,(unsigned)r.code);
    error(env,msg); return 0;
}
static void wipe_free(unsigned char *p,size_t n) {
    if(p) { volatile unsigned char *v=p; while(n--) *v++=0; free(p); }
}
static unsigned char *copy_array(JNIEnv *env,jbyteArray input,jsize *length) {
    if(!input) { error(env,"Missing native input"); return NULL; }
    jsize n=(*env)->GetArrayLength(env,input);
    if(n<0 || n>1024*1024+4096) { error(env,"Native input too large"); return NULL; }
    unsigned char *p=malloc(n?(size_t)n:1);
    if(!p) { error(env,"Native allocation failed"); return NULL; }
    (*env)->GetByteArrayRegion(env,input,0,n,(jbyte *)p);
    if((*env)->ExceptionCheck(env)) { free(p); return NULL; }
    *length=n; return p;
}

JNIEXPORT void JNICALL
fromjava(libreReceiverInit)(
        JNIEnv *env,jclass klass,jstring path,jbyteArray rsa,jbyteArray aes) {
    (void)klass;
    pthread_mutex_lock(&lock);
    if(initialized) goto done;
    if(init_failed) { error(env,"SKB initialization failed earlier in this process"); goto done; }
#if !defined(__ANDROID__) || (!defined(__aarch64__) && !defined(__arm__))
    (void)path; (void)rsa; (void)aes;
    error(env,"This SKB bridge supports Android ARM32 and ARM64 only");
#else
    init_failed=1; // Fail closed after a partial initialization; do not repeatedly import/leak keys.
    if(!path) { error(env,"Missing SKB library path"); goto done; }
    const char *filename=(*env)->GetStringUTFChars(env,path,NULL);
    if(!filename) goto done;
    library=dlopen(filename,RTLD_NOW|RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env,path,filename);
    if(!library) { error(env,dlerror()); goto done; }
    void *s=dlsym(library,"adcskb_pss_sign");
    void *e=dlsym(library,"adcskb_gcm_encrypt");
    void *d=dlsym(library,"adcskb_gcm_decrypt");
    Dl_info info;
    if(!s || !e || !d || !dladdr(s,&info)) { error(env,"Missing SKB symbols"); goto done; }
    uintptr_t base=(uintptr_t)info.dli_fbase;
#if defined(__aarch64__)
    const uintptr_t sign_offset=0x11427dc, encrypt_offset=0x11424bc, decrypt_offset=0x114264c;
    const uintptr_t engine_offset=0x13d5140, import_offset=0x13d5d70;
#else
    const uintptr_t sign_offset=0x1033fdc, encrypt_offset=0x1033c54, decrypt_offset=0x1033e18;
    const uintptr_t engine_offset=0x12e047c, import_offset=0x12e104c;
#endif
    if((uintptr_t)s!=base+sign_offset || (uintptr_t)e!=base+encrypt_offset || (uintptr_t)d!=base+decrypt_offset) {
        error(env,"SKB function offsets differ from the supplied build"); goto done;
    }
    sign_fn=(Sign)s; encrypt_fn=(Gcm)e; decrypt_fn=(Gcm)d;
    GetEngine get=(GetEngine)(base+engine_offset);
    Import import_key=(Import)(base+import_offset);
    if(get(&engine)!=SKB_OK || !engine) { error(env,"SKB engine initialization failed"); goto done; }
    jsize rn=0,an=0;
    unsigned char *rb=copy_array(env,rsa,&rn), *ab=NULL;
    if(rb) ab=copy_array(env,aes,&an);
    if(rb && ab) {
        if(import_key(engine,rb,rn,&private_key)!=SKB_OK || !private_key ||
           import_key(engine,ab,an,&wrap_key)!=SKB_OK || !wrap_key) error(env,"SKB key import failed");
        else { initialized=1; init_failed=0; }
    }
    wipe_free(rb,(size_t)rn); wipe_free(ab,(size_t)an);
    // Deliberately retain the two application keys and Go library for process lifetime.
#endif
done:
    pthread_mutex_unlock(&lock);
}

JNIEXPORT jbyteArray JNICALL
fromjava(libreReceiverCrypt)(
        JNIEnv *env,jclass klass,jint operation,jbyteArray input,jbyteArray ivArray) {
    (void)klass;
    jbyteArray returned=NULL;
    jsize n=0,ivn=0;
    unsigned char *in=NULL,*iv=NULL,*out=NULL;
    size_t outn=0;
    pthread_mutex_lock(&lock);
    if(!initialized) { error(env,"SKB engine has not been initialized"); goto done; }
    in=copy_array(env,input,&n); if(!in) goto done;
    if(operation==0) outn=256;
    else if(operation==1) {
        iv=copy_array(env,ivArray,&ivn); if(!iv) goto done;
        if(ivn!=12) { error(env,"GCM IV must be 12 bytes"); goto done; }
        outn=(size_t)n+28;
    } else if(operation==2) {
        if(n<28) { error(env,"GCM record is too short"); goto done; }
        outn=(size_t)n-28;
    } else { error(env,"Unknown SKB operation"); goto done; }
    out=malloc(outn?outn:1);
    if(!out) { error(env,"Native allocation failed"); goto done; }
    int ok=0;
    if(operation==0) ok=result(env,sign_fn(engine,private_key,in,n,out,256),"Signing");
    else if(operation==1) {
        memcpy(out,iv,12);
        ok=result(env,encrypt_fn(engine,wrap_key,in,n,out,12,out+12,out+12+n),"Encryption");
    } else {
        unsigned char tag[16]; memcpy(tag,in+n-16,16);
        ok=result(env,decrypt_fn(engine,wrap_key,in+12,n-28,in,12,out,tag),"GCM authentication/decryption");
    }
    if(ok) {
        returned=(*env)->NewByteArray(env,(jsize)outn);
        if(returned) (*env)->SetByteArrayRegion(env,returned,0,(jsize)outn,(jbyte *)out);
    }
done:
    wipe_free(in,(size_t)n); wipe_free(iv,(size_t)ivn); wipe_free(out,outn);
    pthread_mutex_unlock(&lock);
    return returned;
}
#endif
