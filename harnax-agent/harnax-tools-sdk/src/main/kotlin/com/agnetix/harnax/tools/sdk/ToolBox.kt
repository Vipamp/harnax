package com.agnetix.harnax.tools.sdk

/**
 * Base class for a toolbox registered as a model-facing tool.
 *
 * It carries no state: measuring and recording the call belongs to `ToolInvocationMiddleware`, which sees
 * every call — including an MCP tool or a shell command, neither of which is a `ToolBox`. A tool that
 * needs to act as the end user takes that value as its own argument, the way the delivered tools already
 * do, rather than through a base-class seam nothing calls.
 */
abstract class ToolBox {
    /** The toolbox's own name; every subclass implements it. */
    abstract fun name(): String
}
