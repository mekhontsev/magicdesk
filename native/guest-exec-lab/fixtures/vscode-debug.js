'use strict';
const vscode = require('vscode');
const assert = require('node:assert/strict');
const net = require('node:net');

exports.activate = async function (context) {
    const socket = net.createConnection({host: '127.0.0.1', port: Number(process.env.MD_VSCODE_PORT)});
    const send = (stage, data = {}) => socket.write(JSON.stringify({token: process.env.MD_VSCODE_TOKEN, stage, ...data}) + '\n');
    // EVENT_WAIT: test-controller acknowledgement; disconnect/timeout fails the fixture.
    const ack = () => new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Controller acknowledgement timed out')), 60000);
        socket.once('data', () => {clearTimeout(timer); resolve();});
        socket.once('error', reject);
    });
    const messages = [];
    let changed;
    context.subscriptions.push(vscode.debug.registerDebugAdapterTrackerFactory('cppdbg', {
        createDebugAdapterTracker() {
            return {onDidSendMessage(message) {messages.push(message); if (changed) changed();}};
        }
    }));
    // EVENT_WAIT: actual DAP event; deadline is a failure, not debugger readiness.
    const event = name => new Promise((resolve, reject) => {
        const timer = setTimeout(() => {changed = undefined; reject(new Error('Missing DAP event: ' + name));}, 60000);
        const check = () => {
            const index = messages.findIndex(m => m.type === 'event' && m.event === name);
            if (index >= 0) {clearTimeout(timer); changed = undefined; resolve(messages.splice(index, 1)[0].body);}
        };
        changed = check;
        check();
    });
    try {
        send('activated', {version: vscode.version, versions: process.versions, uid: process.getuid()});
        const folder = vscode.workspace.workspaceFolders[0];
        const uri = vscode.Uri.joinPath(folder.uri, 'main.c');
        await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(uri));
        vscode.debug.addBreakpoints([new vscode.SourceBreakpoint(new vscode.Location(uri, new vscode.Position(4, 0)))]);
        const started = await vscode.debug.startDebugging(folder, {
            type: 'cppdbg', name: 'Guest GDB', request: 'launch',
            program: folder.uri.fsPath + '/main', cwd: folder.uri.fsPath,
            MIMode: 'gdb', miDebuggerPath: '/usr/bin/gdb',
            ...(process.env.MD_DEBUG_TRANSPORT === 'pipe' ? {
                pipeTransport: {pipeProgram: '/bin/sh', pipeArgs: ['-c'], debuggerPath: '/usr/bin/gdb'}
            } : {}),
            externalConsole: false, avoidWindowsConsoleRedirection: true,
            stopAtEntry: false, logging: {engineLogging: true}
        });
        assert.equal(started, true);
        const stopped = await event('stopped');
        assert.equal(stopped.reason, 'breakpoint');
        const session = vscode.debug.activeDebugSession;
        const stack = await session.customRequest('stackTrace', {threadId: stopped.threadId});
        const frame = stack.stackFrames[0];
        assert.equal(frame.name, 'main()');
        assert.equal(frame.line, 5);
        const value = await session.customRequest('evaluate', {expression: 'value', frameId: frame.id, context: 'watch'});
        assert.equal(value.result, '40');
        const scopes = await session.customRequest('scopes', {frameId: frame.id});
        const locals = await session.customRequest('variables', {variablesReference: scopes.scopes[0].variablesReference});
        assert(locals.variables.some(v => v.name === 'value' && v.value === '40'));
        const acknowledged = ack();
        send('breakpoint', {stopped, stack, value, locals});
        await acknowledged;
        await session.customRequest('next', {threadId: stopped.threadId});
        const step = await event('stopped');
        assert.equal(step.reason, 'step');
        const nextStack = await session.customRequest('stackTrace', {threadId: step.threadId});
        const nextValue = await session.customRequest('evaluate', {expression: 'value', frameId: nextStack.stackFrames[0].id, context: 'watch'});
        assert.equal(nextValue.result, '42');
        await session.customRequest('continue', {threadId: step.threadId});
        const exited = await event('exited');
        assert.equal(exited.exitCode, 0);
        await event('terminated');
        send('passed', {step, value: nextValue, exited});
    } catch (error) {
        send('failed', {error: error.stack, messages});
    }
};
