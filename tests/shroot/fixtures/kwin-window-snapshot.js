// Read-only snapshot for nested Plasma input and portal diagnostics.
print('SHROOT_KWIN_WINDOWS ' + JSON.stringify(workspace.windowList().map(window => ({
    id: String(window.internalId),
    caption: window.caption,
    active: window.active,
    minimized: window.minimized,
    modal: window.modal,
    transient: window.transient,
    transientFor: window.transientFor ? String(window.transientFor.internalId) : null,
    resourceClass: String(window.resourceClass),
    pid: window.pid,
    geometry: window.frameGeometry
}))));
