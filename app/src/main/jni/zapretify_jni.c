/*
 * JNI-слой Zapretify: запуск и остановка byedpi и hev-socks5-tunnel из Kotlin (engine/NativeBridge.kt).
 *
 * byedpi рассчитан на запуск как программа: глобальные params, getopt, бесконечный цикл событий.
 * Поэтому перед каждым запуском params возвращается к исходным значениям (снимок при загрузке библиотеки),
 * getopt сбрасывается, а остановка — штатная для byedpi: shutdown() слушающего сокета (как его обработчик SIGINT).
 */
#include <jni.h>
#include <getopt.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

#include "params.h"
#include "hev-main.h"

int byedpi_main(int argc, char **argv);
extern int server_fd;

static struct params params_defaults;
static pthread_mutex_t byedpi_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int byedpi_running;
/* hev запоминает «стоп», пришедший до запуска, и тогда следующий запуск сразу завершается.
 * Поэтому останавливаем туннель, только когда он действительно запущен. */
static volatile int tunnel_running;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    memcpy(&params_defaults, &params, sizeof(params));
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_io_github_selfdestroyer_zapretify_engine_NativeBridge_runByedpi(JNIEnv *env, jobject thiz, jobjectArray jargs) {
    int argc = (*env)->GetArrayLength(env, jargs);
    char **argv = calloc(argc + 1, sizeof(char *));
    if (!argv) return -1;
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(c ? c : "");
        if (c) (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }

    pthread_mutex_lock(&byedpi_lock); /* одновременно работает только один экземпляр */
    memcpy(&params, &params_defaults, sizeof(params));
    optind = 1;
    optreset = 1;
    server_fd = -1;
    byedpi_running = 1;
    int rc = byedpi_main(argc, argv);
    byedpi_running = 0;
    server_fd = -1;
    pthread_mutex_unlock(&byedpi_lock);

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return rc;
}

JNIEXPORT void JNICALL
Java_io_github_selfdestroyer_zapretify_engine_NativeBridge_stopByedpi(JNIEnv *env, jobject thiz) {
    int fd = server_fd;
    if (byedpi_running && fd > 0) shutdown(fd, SHUT_RDWR);
}

JNIEXPORT jint JNICALL
Java_io_github_selfdestroyer_zapretify_engine_NativeBridge_runTunnel(JNIEnv *env, jobject thiz, jstring jconfig, jint fd) {
    const char *cfg = (*env)->GetStringUTFChars(env, jconfig, NULL);
    if (!cfg) return -1;
    char *copy = strdup(cfg);
    (*env)->ReleaseStringUTFChars(env, jconfig, cfg);
    if (!copy) return -1;
    tunnel_running = 1;
    int rc = hev_socks5_tunnel_main_from_str((const unsigned char *)copy, (unsigned int)strlen(copy), fd);
    tunnel_running = 0;
    free(copy);
    return rc;
}

JNIEXPORT void JNICALL
Java_io_github_selfdestroyer_zapretify_engine_NativeBridge_stopTunnel(JNIEnv *env, jobject thiz) {
    if (tunnel_running) hev_socks5_tunnel_quit();
}
