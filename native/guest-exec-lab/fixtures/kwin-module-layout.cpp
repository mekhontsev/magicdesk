// SPDX-License-Identifier: GPL-2.0-or-later
#include "opengl/egldisplay.h"
#if MD_KWIN66
#include "compositor.h"
#include "opengl/eglbackend.h"
using AbstractEglBackend = KWin::EglBackend;
#else
#include "platformsupport/scenes/opengl/abstract_egl_backend.h"
#endif
#include "backends/wayland/wayland_backend.h"
#include "scene/itemrenderer_opengl.h"
#include "wayland_server.h"
#include <cstddef>
#include <cstdio>

using namespace KWin;
#define SIZE(T) std::printf(#T " size=%zu align=%zu\n", sizeof(T), alignof(T))
#define FIELD(T, F) std::printf(#T "::" #F "=%zu\n", offsetof(T, F))
int main()
{
#if MD_KWIN66
    SIZE(Compositor);
    FIELD(Compositor, m_backend);
#endif
    SIZE(EglDisplay);
    FIELD(EglDisplay, m_handle);
    FIELD(EglDisplay, m_extensions);
    FIELD(EglDisplay, m_owning);
    FIELD(EglDisplay, m_renderNode);
    FIELD(EglDisplay, m_renderDevNode);
    FIELD(EglDisplay, m_supportsBufferAge);
    FIELD(EglDisplay, m_supportsNativeFence);
    FIELD(EglDisplay, m_importFormats);
    FIELD(EglDisplay, m_functions);
    SIZE(AbstractEglBackend);
    FIELD(AbstractEglBackend, m_display);
    FIELD(AbstractEglBackend, m_context);
    FIELD(AbstractEglBackend, m_clientExtensions);
    FIELD(AbstractEglBackend, m_tranches);
    FIELD(AbstractEglBackend, m_importedBuffers);
    SIZE(Wayland::WaylandBackend);
    FIELD(Wayland::WaylandBackend, m_options);
    FIELD(Wayland::WaylandBackend, m_display);
    FIELD(Wayland::WaylandBackend, m_seat);
    FIELD(Wayland::WaylandBackend, m_eglBackend);
    FIELD(Wayland::WaylandBackend, m_outputs);
    FIELD(Wayland::WaylandBackend, m_pointerLockRequested);
    FIELD(Wayland::WaylandBackend, m_drmDevice);
    FIELD(Wayland::WaylandBackend, m_eglDisplay);
    FIELD(Wayland::WaylandBackend, m_buffers);
    SIZE(ItemRendererOpenGL);
    FIELD(ItemRendererOpenGL, m_blendingEnabled);
    FIELD(ItemRendererOpenGL, m_eglDisplay);
    FIELD(ItemRendererOpenGL, m_releasePoints);
    FIELD(ItemRendererOpenGL, m_debug);
    SIZE(WaylandServer);
    FIELD(WaylandServer, m_display);
    FIELD(WaylandServer, m_linuxDmabuf);
    FIELD(WaylandServer, m_linuxDrmSyncObj);
}
