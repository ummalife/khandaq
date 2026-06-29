/*
 * Supplies tox_group_set_topic JNI when the prebuilt libjni-c-toxcore.so
 * was compiled before this binding existed (UnsatisfiedLinkError on rename).
 * Links against symbols exported from libjni-c-toxcore.so.
 */
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

typedef struct Tox Tox;

typedef enum Tox_Err_Group_Topic_Set {
    TOX_ERR_GROUP_TOPIC_SET_OK = 0,
    TOX_ERR_GROUP_TOPIC_SET_GROUP_NOT_FOUND,
    TOX_ERR_GROUP_TOPIC_SET_TOO_LONG,
    TOX_ERR_GROUP_TOPIC_SET_PERMISSIONS,
    TOX_ERR_GROUP_TOPIC_SET_FAIL_CREATE,
    TOX_ERR_GROUP_TOPIC_SET_FAIL_SEND,
    TOX_ERR_GROUP_TOPIC_SET_DISCONNECTED,
} Tox_Err_Group_Topic_Set;

extern Tox *tox_global;

extern bool tox_group_set_topic(Tox *tox, uint32_t group_number, const uint8_t *topic,
                                size_t length, Tox_Err_Group_Topic_Set *error);

JNIEXPORT jint JNICALL
Java_com_zoffcc_applications_trifa_MainActivity_tox_1group_1set_1topic(JNIEnv *env, jobject thiz,
                                                                       jlong group_number, jstring topic)
{
    (void)thiz;

    if (tox_global == NULL)
    {
        return (jint)-99;
    }

    if (topic == NULL)
    {
        return (jint)-21;
    }

    Tox_Err_Group_Topic_Set error;
    bool res = false;

    const jclass stringClass = (*env)->GetObjectClass(env, topic);
    if (stringClass == NULL)
    {
        return (jint)-21;
    }

    const jmethodID getBytes = (*env)->GetMethodID(env, stringClass, "getBytes", "(Ljava/lang/String;)[B");
    if (getBytes == NULL)
    {
        return (jint)-21;
    }

    const jstring charsetName = (*env)->NewStringUTF(env, "UTF-8");
    if (charsetName == NULL)
    {
        return (jint)-21;
    }

    const jbyteArray stringJbytes = (jbyteArray)(*env)->CallObjectMethod(env, topic, getBytes, charsetName);
    (*env)->DeleteLocalRef(env, charsetName);

    if (stringJbytes == NULL)
    {
        return (jint)-21;
    }

    const jsize plength = (*env)->GetArrayLength(env, stringJbytes);
    if (plength > 512)
    {
        (*env)->DeleteLocalRef(env, stringJbytes);
        return (jint)-2;
    }

    jbyte *pBytes = (*env)->GetByteArrayElements(env, stringJbytes, NULL);
    if (pBytes == NULL)
    {
        (*env)->DeleteLocalRef(env, stringJbytes);
        return (jint)-21;
    }

    res = tox_group_set_topic(tox_global, (uint32_t)group_number, (uint8_t *)pBytes, (size_t)plength, &error);

    (*env)->ReleaseByteArrayElements(env, stringJbytes, pBytes, JNI_ABORT);
    (*env)->DeleteLocalRef(env, stringJbytes);

    if (error != TOX_ERR_GROUP_TOPIC_SET_OK)
    {
        return (jint)(-(error));
    }

    return (jint)(res ? 1 : 0);
}
