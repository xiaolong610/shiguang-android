#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include "mobi.h"

static void fail(JNIEnv *env, const char *message) {
    jclass type=(*env)->FindClass(env,"java/lang/IllegalArgumentException");
    if(type)(*env)->ThrowNew(env,type,message);
}
JNIEXPORT jstring JNICALL Java_app_shiguang_mobile_BookMobi_extract(JNIEnv *env,jobject self,jstring source,jstring target) {
    (void)self;
    const char *in=(*env)->GetStringUTFChars(env,source,NULL);
    const char *out=(*env)->GetStringUTFChars(env,target,NULL);
    MOBIData *m=mobi_init();MOBIRawml *raw=NULL;FILE *f=NULL;char *title=NULL;jstring result=NULL;
    if(!in||!out||!m){fail(env,"MOBI memory allocation failed");goto done;}
    f=fopen(in,"rb");if(!f){fail(env,"Cannot open MOBI file");goto done;}
    MOBI_RET ret=mobi_load_file(m,f);fclose(f);f=NULL;
    if(ret!=MOBI_SUCCESS){fail(env,"Invalid MOBI/AZW3 file");goto done;}
    if(mobi_is_encrypted(m)){fail(env,"DRM-protected Kindle books cannot be imported");goto done;}
    if(mobi_get_text_maxsize(m)>240u*1024u*1024u){fail(env,"Decoded Kindle text exceeds 240MiB");goto done;}
    raw=mobi_init_rawml(m);if(!raw){fail(env,"MOBI memory allocation failed");goto done;}
    ret=mobi_parse_rawml(raw,m);if(ret!=MOBI_SUCCESS){fail(env,"Cannot reconstruct Kindle book text");goto done;}
    size_t i=0,total=0;
    for(MOBIPart *p=raw->markup;p;p=p->next){
        total+=p->size;if(total>240u*1024u*1024u||i>=24000){fail(env,"Kindle book content is too large");goto done;}
        char path[4096];if(snprintf(path,sizeof(path),"%s/%05zu.html",out,i++)>=(int)sizeof(path)){fail(env,"MOBI output path is too long");goto done;}
        f=fopen(path,"wb");if(!f){fail(env,"Cannot save Kindle chapter");goto done;}
        size_t wrote=fwrite(p->data,1,p->size,f);fclose(f);f=NULL;
        if(wrote!=p->size){fail(env,"Cannot save Kindle chapter");goto done;}
    }
    if(i==0){fail(env,"Kindle file contains no readable text");goto done;}
    title=mobi_meta_get_title(m);
    /* Titles are obtained from extracted markup in Kotlin to avoid modified-UTF8 issues. */
    result=(*env)->NewStringUTF(env,mobi_is_kf8(m)?"KF8":"MOBI");
done:
    if(f)fclose(f);if(title)free(title);if(raw)mobi_free_rawml(raw);if(m)mobi_free(m);
    if(in)(*env)->ReleaseStringUTFChars(env,source,in);if(out)(*env)->ReleaseStringUTFChars(env,target,out);
    return result;
}
