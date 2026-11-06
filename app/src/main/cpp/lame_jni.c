/* JNI bridge: de.lanixx.chorstimme.core.Mp3Encoder -> LAME */
#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include "lame/lame.h"

JNIEXPORT jlong JNICALL
Java_de_lanixx_chorstimme_core_Mp3Encoder_nativeInit(JNIEnv *env, jclass cls,
                                                     jint sampleRate, jint channels, jint kbps) {
    lame_global_flags *gf = lame_init();
    if (gf == NULL) return 0;
    lame_set_in_samplerate(gf, sampleRate);
    lame_set_out_samplerate(gf, sampleRate);
    lame_set_num_channels(gf, channels);
    lame_set_mode(gf, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(gf, kbps);
    lame_set_quality(gf, 5);
    if (lame_init_params(gf) < 0) {
        lame_close(gf);
        return 0;
    }
    return (jlong) (intptr_t) gf;
}

/* interleaved: short[] with frames*channels samples */
JNIEXPORT jint JNICALL
Java_de_lanixx_chorstimme_core_Mp3Encoder_nativeEncode(JNIEnv *env, jclass cls, jlong handle,
                                                       jshortArray pcm, jint frames, jbyteArray out) {
    lame_global_flags *gf = (lame_global_flags *) (intptr_t) handle;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *buf = (*env)->GetByteArrayElements(env, out, NULL);
    jsize outSize = (*env)->GetArrayLength(env, out);
    int n;
    if (lame_get_num_channels(gf) == 2)
        n = lame_encode_buffer_interleaved(gf, in, frames, (unsigned char *) buf, outSize);
    else
        n = lame_encode_buffer(gf, in, in, frames, (unsigned char *) buf, outSize);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_de_lanixx_chorstimme_core_Mp3Encoder_nativeFlush(JNIEnv *env, jclass cls, jlong handle,
                                                      jbyteArray out) {
    lame_global_flags *gf = (lame_global_flags *) (intptr_t) handle;
    jbyte *buf = (*env)->GetByteArrayElements(env, out, NULL);
    jsize outSize = (*env)->GetArrayLength(env, out);
    int n = lame_encode_flush(gf, (unsigned char *) buf, outSize);
    (*env)->ReleaseByteArrayElements(env, out, buf, 0);
    return n;
}

JNIEXPORT void JNICALL
Java_de_lanixx_chorstimme_core_Mp3Encoder_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    lame_global_flags *gf = (lame_global_flags *) (intptr_t) handle;
    if (gf != NULL) lame_close(gf);
}
