// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <QObject>
#include <QMetaObject>
#include <unordered_set>

namespace KWin {
class GraphicsBuffer;
class ItemRendererOpenGL;
struct BinaryPointerState final : QObject {
    explicit BinaryPointerState(QObject *owner) : QObject(owner) {}
    bool clientLocked = false;
    bool automaticLock = false;
    QMetaObject::Connection surfaceConnection;
    QMetaObject::Connection clientConnection;
};
BinaryPointerState &binaryPointerState(QObject *owner);
std::unordered_set<GraphicsBuffer *> &binaryRendererReads(ItemRendererOpenGL *owner);
}
