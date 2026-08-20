package io.github.mekhontsev.magicdesk;

/** HTTP-independent result of processing one JSON-RPC message. */
final class McpJsonRpcResponse {
    final int httpStatus;
    final String body;

    McpJsonRpcResponse(final int httpStatus, final String body) {
        this.httpStatus = httpStatus;
        this.body = body == null ? "" : body;
    }

    boolean hasBody() {
        return !body.isEmpty();
    }
}
