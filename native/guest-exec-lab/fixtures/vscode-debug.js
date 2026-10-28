'use strict';
const vscode = require('vscode');
const assert = require('node:assert/strict');
const net = require('node:net');

async function rendererProcesses() {
    // Portal-spawned renderers need not be descendants of the editor process.
    // Ask this Electron instance, rather than infer identity from a ps tree.
    const fs = require('node:fs');
    const [port, path] = fs.readFileSync(process.env.MD_VSCODE_DATA + '/DevToolsActivePort', 'utf8').trim().split('\n');
    const channel = new WebSocket('ws://127.0.0.1:' + port + path);
    const events = [];
    return new Promise((resolve, reject) => {
        // EVENT_WAIT: exact DevTools response; EOF or deadline fails inspection.
        const timer = setTimeout(() => finish(new Error('Electron process inspection timed out: '
            + JSON.stringify({state: channel.readyState, events}))), 15000);
        const finish = (error, result) => {
            clearTimeout(timer);
            channel.onclose = null;
            channel.close();
            if (error) reject(error); else resolve(result);
        };
        channel.onopen = () => {
            events.push('open');
            channel.send(JSON.stringify({id: 1, method: 'SystemInfo.getProcessInfo'}));
        };
        channel.onmessage = event => {
            const reply = JSON.parse(event.data);
            events.push({id: reply.id, method: reply.method});
            if (reply.id === 1) finish(reply.error ? new Error(JSON.stringify(reply.error)) : null,
                                      reply.result?.processInfo);
        };
        channel.onerror = () => finish(new Error('Electron process inspection connection failed'));
        channel.onclose = () => finish(new Error('Electron process inspection disconnected'));
    });
}

exports.activate = async function (context) {
    if (process.env.MD_VSCODE_RELOAD === '1' && !context.globalState.get('reloaded')) {
        await context.globalState.update('reloaded', true);
        await vscode.commands.executeCommand('workbench.action.reloadWindow');
        return;
    }
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
        const debuggerPath = process.env.MD_DEBUGGER_PATH || '/usr/bin/gdb';
        assert(Object.keys(require('node:os').networkInterfaces()).length > 0);
        send('activated', {version: vscode.version, versions: process.versions, uid: process.getuid()});
        const folder = vscode.workspace.workspaceFolders[0];
        const note = vscode.Uri.joinPath(folder.uri, 'editor.txt');
        const document = await vscode.workspace.openTextDocument(note);
        const editor = await vscode.window.showTextDocument(document);
        assert(await editor.edit(edit => edit.insert(new vscode.Position(0, 0), 'shroot editor\n')));
        assert(await document.save());
        assert.equal(Buffer.from(await vscode.workspace.fs.readFile(note)).toString(), 'shroot editor\n');
        const marker = vscode.Uri.joinPath(folder.uri, 'terminal.txt');
        const watcher = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(folder, '*.txt'));
        // EVENT_WAIT: VS Code starts recursive watchers asynchronously. Observe
        // a real external event before testing a single terminal mutation.
        const probe = vscode.Uri.joinPath(folder.uri, 'watch-ready.txt');
        await new Promise((resolve, reject) => {
            const fs = require('node:fs');
            const changed = uri => {if (uri.toString() === probe.toString()) finish();};
            const created = watcher.onDidCreate(changed);
            const modified = watcher.onDidChange(changed);
            const finish = error => {
                clearInterval(retry);
                clearTimeout(deadline);
                created.dispose();
                modified.dispose();
                try {fs.unlinkSync(probe.fsPath);} catch {}
                if (error) reject(error); else resolve();
            };
            // Protocol stimulus, not a settling delay or a readiness assumption.
            const retry = setInterval(() => {
                try {fs.appendFileSync(probe.fsPath, 'r');} catch (error) {finish(error);}
            }, 100);
            const deadline = setTimeout(() => finish(new Error('Filesystem watcher readiness timed out')), 30000);
        });
        const terminal = vscode.window.createTerminal({name: 'Shroot fixture', cwd: folder.uri.fsPath});
        // EVENT_WAIT: terminal-created file notification; deadline fails the fixture.
        try {
            await new Promise((resolve, reject) => {
                const timer = setTimeout(() => reject(new Error('Terminal filesystem event timed out')), 30000);
                const read = async () => {
                    try {
                        if (Buffer.from(await vscode.workspace.fs.readFile(marker)).toString() === 'shroot terminal\n') {
                            clearTimeout(timer);
                            resolve();
                        }
                    } catch (error) {
                        if (error.code !== 'FileNotFound') {clearTimeout(timer); reject(error);}
                    }
                };
                watcher.onDidCreate(read);
                watcher.onDidChange(read);
                terminal.show();
                terminal.sendText("printf 'shroot terminal\\n' > terminal.txt");
            });
        } finally {
            watcher.dispose();
            terminal.dispose();
        }
        const processes = await rendererProcesses();
        const workspaceChecked = ack();
        send('workspace', {editorSaved: true, terminal: true, fileNotification: true,
                           processes});
        await workspaceChecked;
        const uri = vscode.Uri.joinPath(folder.uri, 'main.c');
        await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(uri));
        vscode.debug.addBreakpoints([new vscode.SourceBreakpoint(new vscode.Location(uri, new vscode.Position(4, 0)))]);
        const started = await vscode.debug.startDebugging(folder, {
            type: 'cppdbg', name: 'Guest GDB', request: 'launch',
            program: folder.uri.fsPath + '/main', cwd: folder.uri.fsPath,
            MIMode: 'gdb', miDebuggerPath: debuggerPath,
            ...(process.env.MD_DEBUG_TRANSPORT === 'pipe' ? {
                pipeTransport: {pipeProgram: '/bin/sh', pipeArgs: ['-c'], debuggerPath}
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
        const close = process.env.MD_VSCODE_CLOSE === '1' ? ack() : null;
        send('passed', {step, value: nextValue, exited});
        if (close) {
            await close;
            await vscode.commands.executeCommand('workbench.action.closeWindow');
        }
    } catch (error) {
        send('failed', {error: error.stack, messages});
    }
};
