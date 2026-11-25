#include "fud_offline_voice.h"
#include <jni.h>

namespace {
struct utf {
    JNIEnv *env;
    jstring value;
    const char *str;
    utf(JNIEnv *e, jstring s) : env(e), value(s), str(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~utf() { if (str) env->ReleaseStringUTFChars(value, str); }
};
}

#define FUD_JNI(method) Java_com_apoorvdarshan_calorietracker_services_ondevice_OfflineVoiceNative_##method

extern "C" JNIEXPORT jlong JNICALL FUD_JNI(create)(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(fud_voice_create());
}
extern "C" JNIEXPORT void JNICALL FUD_JNI(cancel)(JNIEnv *, jobject, jlong turn) {
    fud_voice_cancel(reinterpret_cast<fud_voice_turn *>(turn));
}
extern "C" JNIEXPORT void JNICALL FUD_JNI(destroy)(JNIEnv *, jobject, jlong turn) {
    fud_voice_destroy(reinterpret_cast<fud_voice_turn *>(turn));
}
extern "C" JNIEXPORT jint JNICALL FUD_JNI(reply)(JNIEnv *env, jobject, jlong turn, jstring model,
                                                  jstring projector, jstring input, jstring output) {
    const utf m(env, model), p(env, projector), i(env, input), o(env, output);
    return fud_voice_reply(reinterpret_cast<fud_voice_turn *>(turn), m.str, p.str, i.str, o.str, false);
}

extern "C" JNIEXPORT jint JNICALL FUD_JNI(agentReply)(JNIEnv *env, jobject, jlong turn, jstring model,
    jstring projector, jstring input, jstring output, jstring tools, jstring continuation, jstring text) {
    const utf m(env, model), p(env, projector), i(env, input), o(env, output), t(env, tools),
              c(env, continuation), x(env, text);
    return fud_voice_agent_reply(reinterpret_cast<fud_voice_turn *>(turn), m.str, p.str, i.str, o.str,
                                 false, t.str, c.str, x.str);
}
