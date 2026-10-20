/* FileWeave JNI adapter for the MIT-licensed libgocryptfs C API. */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "libgocryptfs.h"

#define API(name) Java_me_zhanghai_android_files_vault_GocryptfsNative_##name

/* JNI modified UTF-8 is not filesystem UTF-8 (notably for emoji). */
static char *utf8(JNIEnv *env, jstring value) {
    jclass cls = (*env)->FindClass(env, "java/lang/String");
    jmethodID method = (*env)->GetMethodID(env, cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    jbyteArray bytes = (*env)->CallObjectMethod(env, value, method, charset);
    if ((*env)->ExceptionCheck(env) || bytes == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, bytes);
    char *result = calloc((size_t)n + 1, 1);
    if (result) (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *)result);
    (*env)->DeleteLocalRef(env, bytes);
    (*env)->DeleteLocalRef(env, charset);
    (*env)->DeleteLocalRef(env, cls);
    return result;
}
static jstring string_utf8(JNIEnv *env, const char *value) {
    jclass cls = (*env)->FindClass(env, "java/lang/String");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "([BLjava/lang/String;)V");
    jsize size = (jsize)strlen(value);
    jbyteArray bytes = (*env)->NewByteArray(env, size);
    (*env)->SetByteArrayRegion(env, bytes, 0, size, (const jbyte *)value);
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    jstring result = (*env)->NewObject(env, cls, ctor, bytes, charset);
    (*env)->DeleteLocalRef(env, bytes);
    (*env)->DeleteLocalRef(env, charset);
    (*env)->DeleteLocalRef(env, cls);
    return result;
}
static GoString go_string(char *s) { return (GoString){s, (GoInt)strlen(s)}; }

JNIEXPORT jint JNICALL API(unlock)(JNIEnv *env, jobject self, jstring root, jbyteArray password, jboolean create) {
    char *directory = utf8(env, root);
    if (!directory) return -1;
    jsize length = (*env)->GetArrayLength(env, password);
    jbyte *bytes = (*env)->GetByteArrayElements(env, password, NULL);
    GoSlice pw = {bytes, length, length}, empty = {NULL, 0, 0};
    GoInt session = -1;
    if (create) {
        unsigned char hash[32] = {0};
        GoSlice key = {hash, sizeof(hash), sizeof(hash)};
        char creator[] = "FileWeave";
        if (gcf_create_volume(go_string(directory), pw, 0, 0, 16, go_string(creator), key))
            session = gcf_init(go_string(directory), empty, key, empty);
        volatile unsigned char *wipe = hash;
        for (size_t i=0; i<sizeof(hash); ++i) wipe[i] = 0;
    } else session = gcf_init(go_string(directory), pw, empty, empty);
    (*env)->ReleaseByteArrayElements(env, password, bytes, JNI_ABORT);
    free(directory);
    return (jint)session;
}
JNIEXPORT void JNICALL API(close)(JNIEnv *env, jobject self, jint session) { gcf_close(session); }
JNIEXPORT jobjectArray JNICALL API(list)(JNIEnv *env, jobject self, jint session, jstring directory) {
    char *path = utf8(env, directory);
    if (!path) return NULL;
    struct gcf_list_dir_return result = gcf_list_dir(session, go_string(path));
    if (result.r0 == NULL) { free(path); return NULL; }
    jclass cls = (*env)->FindClass(env, "me/zhanghai/android/files/vault/VaultEntry");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;IJJ)V");
    jobjectArray entries = (*env)->NewObjectArray(env, result.r2, cls, NULL);
    size_t offset = 0;
    for (int i=0; i<result.r2; ++i) {
        char *name = result.r0 + offset;
        size_t size = strlen(path) + strlen(name) + 2;
        char *full = malloc(size);
        if (!full) break;
        snprintf(full, size, "%s/%s", path, name);
        struct gcf_get_attrs_return attrs = gcf_get_attrs(session, go_string(full));
        free(full);
        jstring jname = string_utf8(env, name);
        jobject entry = (*env)->NewObject(env, cls, ctor, jname, (jint)attrs.r0, (jlong)attrs.r1, (jlong)attrs.r2);
        (*env)->SetObjectArrayElement(env, entries, i, entry);
        (*env)->DeleteLocalRef(env, entry);
        (*env)->DeleteLocalRef(env, jname);
        offset += strlen(name) + 1;
    }
    (*env)->DeleteLocalRef(env, cls);
    free(result.r0); free(result.r1); free(path);
    return entries;
}
JNIEXPORT jlong JNICALL API(size)(JNIEnv *env, jobject self, jint session, jstring path) {
    char *name = utf8(env, path); if (!name) return -1;
    struct gcf_get_attrs_return attrs = gcf_get_attrs(session, go_string(name)); free(name);
    return attrs.r3 == 1 ? (jlong)attrs.r1 : -1;
}
JNIEXPORT jint JNICALL API(open)(JNIEnv *env, jobject self, jint session, jstring path, jboolean write) {
    char *name = utf8(env, path); if (!name) return -1;
    GoInt handle = write ? gcf_open_write_mode(session, go_string(name), 0600) : gcf_open_read_mode(session, go_string(name));
    free(name); return (jint)handle;
}
JNIEXPORT jint JNICALL API(read)(JNIEnv *env, jobject self, jint session, jint handle, jlong position, jbyteArray buffer, jint offset, jint length) {
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    GoSlice slice = {bytes + offset, length, length};
    GoUint32 count = gcf_read_file(session, handle, position, slice);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);
    return (jint)count;
}
JNIEXPORT jint JNICALL API(write)(JNIEnv *env, jobject self, jint session, jint handle, jlong position, jbyteArray buffer, jint length) {
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    GoSlice slice = {bytes, length, length};
    GoUint32 count = gcf_write_file(session, handle, position, slice);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return (jint)count;
}
JNIEXPORT void JNICALL API(closeFile)(JNIEnv *env, jobject self, jint session, jint handle) { gcf_close_file(session, handle); }
JNIEXPORT jboolean JNICALL API(rename)(JNIEnv *env, jobject self, jint session, jstring source, jstring target) {
    char *src = utf8(env, source), *dst = utf8(env, target);
    if (!src || !dst) { free(src); free(dst); return 0; }
    GoUint8 result = gcf_rename(session, go_string(src), go_string(dst)); free(src); free(dst); return result;
}
JNIEXPORT jboolean JNICALL API(remove)(JNIEnv *env, jobject self, jint session, jstring path) {
    char *name = utf8(env, path); if (!name) return 0;
    GoUint8 result = gcf_remove_file(session, go_string(name)); free(name); return result;
}
