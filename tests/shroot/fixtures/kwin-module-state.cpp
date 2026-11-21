// SPDX-License-Identifier: GPL-2.0-or-later
#include "kwin-module-state.h"
#include "core/graphicsbuffer.h"
#include "scene/itemrenderer_opengl.h"
#include "wayland_server.h"
#include "wayland/display.h"
#include "wayland/linuxdmabufv1clientbuffer.h"
#include "wayland/linuxdmabufv1clientbuffer_p.h"
#include "wayland/shmclientbuffer_p.h"
#include "wayland/linux_drm_syncobj_v1.h"
#include "core/drmdevice.h"
#include "core/renderbackend.h"
#include "utils/common.h"
#include "utils/kernel.h"
#include <QVariant>
#include <unordered_map>

Q_LOGGING_CATEGORY(KWIN_CORE, "kwin_core", QtWarningMsg)
Q_LOGGING_CATEGORY(KWIN_OPENGL, "kwin_scene_opengl", QtWarningMsg)
Q_LOGGING_CATEGORY(KWIN_WAYLAND_BACKEND, "kwin_wayland_backend", QtWarningMsg)
#if MD_KWIN66
Q_LOGGING_CATEGORY(KWIN_WAYLAND_CLIENT, "kwin_wayland_client", QtWarningMsg)
#endif

namespace KWin {
GraphicsBuffer *Display::bufferForResource(wl_resource *resource)
{
    if (auto buffer = LinuxDmaBufV1ClientBuffer::get(resource)) {
        return buffer;
    }
    return ShmClientBuffer::get(resource);
}

BinaryPointerState &binaryPointerState(QObject *owner)
{
    auto *state = static_cast<BinaryPointerState *>(owner->property("_mdBinaryPointerState").value<void *>());
    if (!state) {
        state = new BinaryPointerState(owner);
        owner->setProperty("_mdBinaryPointerState", QVariant::fromValue(static_cast<void *>(state)));
    }
    return *state;
}

// Render-node state stays on the compositor thread, outside the stock object layout.
static thread_local std::unordered_map<ItemRendererOpenGL *, std::unordered_set<GraphicsBuffer *>> reads;
std::unordered_set<GraphicsBuffer *> &binaryRendererReads(ItemRendererOpenGL *owner)
{
    return reads[owner];
}
ItemRendererOpenGL::~ItemRendererOpenGL()
{
    auto it = reads.find(this);
    if (it != reads.end()) {
        for (auto *buffer : it->second) {
            buffer->unref();
        }
        reads.erase(it);
    }
}

LinuxDmaBufV1ClientBufferIntegration *WaylandServer::linuxDmabuf()
{
    return linuxDmabuf(true);
}
LinuxDmaBufV1ClientBufferIntegration *WaylandServer::linuxDmabuf(bool feedback)
{
    if (!m_linuxDmabuf) {
        m_linuxDmabuf = new LinuxDmaBufV1ClientBufferIntegration(m_display, feedback);
    }
    return m_linuxDmabuf;
}

void WaylandServer::setRenderBackend(RenderBackend *backend)
{
    const auto device = backend->drmDevice();
    if (device && device->supportsSyncObjTimelines()) {
        const auto version = linuxKernelVersion();
        if (version.majorVersion() < 6 && version.minorVersion() < 6) {
            return;
        }
        if (version.majorVersion() == 6 && (version.minorVersion() == 7
            || (version.minorVersion() == 6 && version.patchVersion() < 19))) {
            return;
        }
        if (!m_linuxDrmSyncObj) {
            m_linuxDrmSyncObj = new LinuxDrmSyncObjV1Interface(m_display, m_display, device);
        }
    } else if (m_linuxDrmSyncObj) {
        m_linuxDrmSyncObj->remove();
        m_linuxDrmSyncObj = nullptr;
    }
}
}
