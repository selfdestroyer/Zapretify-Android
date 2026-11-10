# Нативная часть Zapretify: byedpi (hufrea/byedpi, MIT) и hev-socks5-tunnel (heiher/hev-socks5-tunnel, MIT)
# собираются из git-подмодулей в native/ в одну библиотеку libzapretify_native.so со своим JNI-слоем
# (zapretify_jni.c). JNI самого hev (src/hev-jni.c) не используется.

ZAP_JNI := $(call my-dir)
NATIVE := $(ZAP_JNI)/../../../../native
HEV := $(NATIVE)/hev-socks5-tunnel
BYEDPI := $(NATIVE)/byedpi

# Зависимости hev-socks5-tunnel — их собственные Android.mk.
include $(HEV)/third-part/yaml/Android.mk
include $(HEV)/third-part/lwip/Android.mk
ENABLE_MEMALLOC_SLICE := 0
include $(HEV)/third-part/hev-task-system/Android.mk

LOCAL_PATH := $(ZAP_JNI)
include $(CLEAR_VARS)
LOCAL_MODULE := zapretify_native

rwildcard = $(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2) $(filter $(subst *,%,$2),$d))
HEV_SRC := $(filter-out $(HEV)/src/hev-jni.c,$(call rwildcard,$(HEV)/src/,*.c *.S))
BYEDPI_SRC := $(addprefix $(BYEDPI)/,packets.c main.c conev.c proxy.c desync.c mpool.c extend.c)

LOCAL_SRC_FILES := zapretify_jni.c zapretify_resolv.c $(HEV_SRC) $(BYEDPI_SRC)
LOCAL_C_INCLUDES := \
    $(HEV)/src $(HEV)/src/misc $(HEV)/src/core/include \
    $(HEV)/third-part/yaml/include $(HEV)/third-part/lwip/src/include \
    $(HEV)/third-part/lwip/src/ports/include $(HEV)/third-part/hev-task-system/include \
    $(BYEDPI)
# hev: как в его Android.mk (режим библиотеки). byedpi: журнал в logcat (ANDROID_APP), main переименован,
# чтобы вызывать его из JNI; getaddrinfo — через zapretify_resolv.c (серверы видео только по IPv4).
LOCAL_CFLAGS := -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY -DCOMMIT_ID=\"2.19.3\" \
    -fno-stack-clash-protection -D_DEFAULT_SOURCE -DANDROID_APP -Dmain=byedpi_main -Dgetaddrinfo=zapretify_getaddrinfo \
    -Wno-unused -Wno-unused-parameter
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
