/* ============================================================
   HARNAX // ROUTER · CONTROL DECK — runtime script
   - Polls /api/router/monitor/instances every 5s.
   - Submits filter form -> /api/router/monitor/call-logs.
   - Both take Authorization: Bearer <token> (from ?token= once, then localStorage).
   - Renders using vanilla DOM ops; no framework.
   ============================================================ */
(function () {
    "use strict";

    // ---------- Constants ----------
    var POLL_INTERVAL_MS = 5000;
    var DEFAULT_LIMIT = 100;

    // ---------- State ----------
    var state = {
        limit: DEFAULT_LIMIT,
        offset: 0,
        total: 0,
        currentFilters: {},
    };

    // ---------- DOM helpers ----------
    function $(id) { return document.getElementById(id); }
    function el(tag, attrs, children) {
        var node = document.createElement(tag);
        if (attrs) {
            Object.keys(attrs).forEach(function (k) {
                if (k === "class") node.className = attrs[k];
                else if (k === "text") node.textContent = attrs[k];
                else if (k === "html") node.innerHTML = attrs[k];
                else if (k === "style") Object.assign(node.style, attrs[k]);
                else if (k === "data") {
                    Object.keys(attrs[k]).forEach(function (dk) {
                        node.dataset[dk] = attrs[k][dk];
                    });
                }
                else node.setAttribute(k, attrs[k]);
            });
        }
        if (children) {
            (Array.isArray(children) ? children : [children]).forEach(function (c) {
                if (c == null) return;
                node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
            });
        }
        return node;
    }
    function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
    function setText(node, text) { if (node && node.textContent !== text) node.textContent = text; }

    // ---------- Formatting ----------
    function pad(n, w) { var s = String(n); while (s.length < w) s = "0" + s; return s; }

    function fmtClock(d) {
        return pad(d.getHours(), 2) + ":" + pad(d.getMinutes(), 2) + ":" + pad(d.getSeconds(), 2);
    }

    function fmtTime(isoOrLocal) {
        // Accept "2026-06-19T17:23:45" (LocalDateTime toString) or ISO.
        if (!isoOrLocal) return "—";
        var s = String(isoOrLocal);
        // Strip fractional seconds if present.
        s = s.replace(/(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$/, function (m, frac, tz) {
            return (tz || "");
        });
        // Pull HH:MM:SS out of the string for compactness.
        var m = s.match(/T(\d{2}:\d{2}:\d{2})/);
        if (m) {
            var datePart = s.substring(0, 10);
            return datePart + " " + m[1];
        }
        return s;
    }

    function fmtDuration(ms) {
        if (ms == null) return "—";
        var n = Number(ms);
        if (n < 1000) return n + "ms";
        if (n < 60000) return (n / 1000).toFixed(2) + "s";
        var m = Math.floor(n / 60000);
        var s = ((n % 60000) / 1000).toFixed(1);
        return m + "m" + s + "s";
    }

    function fmtAge(ms) {
        if (ms == null || ms < 0) return "—";
        var n = Number(ms);
        if (n < 1000) return n + "ms ago";
        if (n < 60000) return Math.floor(n / 1000) + "s ago";
        if (n < 3600000) return Math.floor(n / 60000) + "m ago";
        return Math.floor(n / 3600000) + "h ago";
    }

    function durClass(ms) {
        if (ms == null) return "";
        if (ms >= 5000) return "dur dur--slow";
        if (ms < 200) return "dur dur--fast";
        return "dur";
    }

    function statusClass(status) {
        if (!status) return "";
        return "instance--" + String(status).toUpperCase();
    }

    function codeClass(code, success) {
        if (success === 1 || success === "1") return "code code--ok";
        if (code >= 500) return "code code--bad";
        if (code >= 400) return "code code--bad";
        return "code";
    }

    function escapeHtml(s) {
        if (s == null) return "";
        return String(s).replace(/[&<>"']/g, function (c) {
            return ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c];
        });
    }

    // ---------- Credential ----------
    // The monitor answers with node addresses and every call the router has logged, so it is not
    // anonymous: the page carries the same bearer token the rest of the API takes. `?token=` is the
    // bootstrap — a developer pastes one into the URL, and it survives the reload from there on.
    var TOKEN_KEY = "harnax.monitor.token";

    function readToken() {
        var fromUrl = new URLSearchParams(window.location.search).get("token");
        if (fromUrl) {
            window.localStorage.setItem(TOKEN_KEY, fromUrl);
            // A query string is kept in history and sent as a referrer. The token has somewhere
            // better to live now that it has been picked up.
            window.history.replaceState({}, document.title, window.location.pathname);
            return fromUrl;
        }
        return window.localStorage.getItem(TOKEN_KEY) || "";
    }

    var authToken = readToken();

    // ---------- Fetchers ----------
    function fetchJSON(url) {
        var headers = { "Accept": "application/json" };
        if (authToken) headers["Authorization"] = "Bearer " + authToken;
        return fetch(url, { headers: headers })
            .then(function (r) {
                if (r.status === 401 || r.status === 403) {
                    var denied = new Error(
                        authToken
                            ? "the router rejected this token (401/403)"
                            : "the router wants a token: open the page as ?token=<jwt or api key>"
                    );
                    denied.unauthorized = true;
                    throw denied;
                }
                if (!r.ok) throw new Error("HTTP " + r.status);
                return r.json();
            })
            .then(function (body) {
                // Router wraps responses as ResultVo { code, data, message }.
                if (body && typeof body === "object" && "data" in body) return body.data;
                return body;
            });
    }

    // ---------- Renderers ----------
    function renderInstances(items) {
        var root = $("instances");
        $("instance-count").textContent = items.length + " node" + (items.length === 1 ? "" : "s");
        $("stat-healthy").textContent = items.filter(function (i) { return i.status === "UP"; }).length;
        var totalSessions = items.reduce(function (s, i) { return s + (i.sessionCount || 0); }, 0);
        $("stat-sessions").textContent = totalSessions;

        // Empty state.
        if (items.length === 0) {
            if (!root.querySelector("[data-empty]")) {
                clear(root);
                root.appendChild(el("div", { class: "instance", data: { empty: "1" } }, [
                    el("div", { class: "instance__head" }, [
                        el("span", { class: "instance__id", text: "no nodes" }),
                    ]),
                    el("div", { class: "instance__addr", text: "awaiting registrations…" }),
                ]));
            }
            return;
        }

        // Remove skeleton / empty-state placeholders on first real render.
        Array.prototype.forEach.call(
            root.querySelectorAll(".instance--skeleton, [data-empty]"),
            function (n) { n.remove(); }
        );

        // Build a lookup of existing cards by instanceId.
        var existing = {};
        Array.prototype.forEach.call(root.querySelectorAll(".instance[data-id]"), function (node) {
            existing[node.dataset.id] = node;
        });

        var newIds = {};
        items.forEach(function (inst, idx) {
            var id = inst.instanceId;
            newIds[id] = true;

            var card = existing[id];
            if (card) {
                // --- In-place update: only touch text that changed. ---
                var cls = "instance " + statusClass(inst.status);
                if (card.className !== cls) card.className = cls;

                var idEl = card.querySelector(".instance__id");
                setText(idEl, inst.instanceId);

                var statusEl = card.querySelector(".instance__status");
                setText(statusEl, inst.status || "UNKNOWN");

                var addrEl = card.querySelector(".instance__addr");
                var addrText = inst.host + ":";
                if (addrEl.childNodes[0] && addrEl.childNodes[0].nodeValue !== addrText) {
                    addrEl.childNodes[0].nodeValue = addrText;
                }
                var portEl = card.querySelector(".instance__addr-port");
                setText(portEl, String(inst.port));

                var vals = card.querySelectorAll(".stat__value");
                if (vals.length >= 2) {
                    setText(vals[0], String(inst.sessionCount || 0));
                    setText(vals[1], fmtAge(inst.lastHeartbeatAgeMs));
                }
            } else {
                // --- Brand-new card: create with enter animation. ---
                var node = el("div", {
                    class: "instance " + statusClass(inst.status),
                    data: { id: id },
                    style: { animationDelay: (idx * 60) + "ms" },
                }, [
                    el("div", { class: "instance__head" }, [
                        el("span", { class: "instance__id", text: inst.instanceId }),
                        el("span", { class: "instance__status", text: inst.status || "UNKNOWN" }),
                    ]),
                    el("div", { class: "instance__addr" }, [
                        document.createTextNode(inst.host + ":"),
                        el("span", { class: "instance__addr-port", text: String(inst.port) }),
                    ]),
                    el("div", { class: "instance__stats" }, [
                        el("div", { class: "stat" }, [
                            el("span", { class: "stat__label", text: "sessions" }),
                            el("span", { class: "stat__value", text: String(inst.sessionCount || 0) }),
                        ]),
                        el("div", { class: "stat" }, [
                            el("span", { class: "stat__label", text: "last beat" }),
                            el("span", { class: "stat__value", text: fmtAge(inst.lastHeartbeatAgeMs) }),
                        ]),
                    ]),
                ]);
                root.appendChild(node);
            }
        });

        // Remove cards that are no longer present (with fade-out).
        Object.keys(existing).forEach(function (oldId) {
            if (!newIds[oldId]) {
                var old = existing[oldId];
                old.classList.add("instance--leaving");
                setTimeout(function () { if (old.parentNode) old.remove(); }, 350);
            }
        });
    }

    function renderLogs(page) {
        var root = $("logs-body");
        clear(root);
        var items = page.items || [];
        state.total = page.total || 0;

        $("logs-count").textContent = state.total + " entries";
        var pageNum = Math.floor(state.offset / state.limit) + 1;
        var totalPages = Math.max(1, Math.ceil(state.total / state.limit));
        $("page-info").textContent = "page " + pageNum + " / " + totalPages;
        $("btn-prev").disabled = state.offset <= 0;
        $("btn-next").disabled = state.offset + state.limit >= state.total;

        if (items.length === 0) {
            root.appendChild(el("tr", { class: "logs__placeholder" }, [
                el("td", { colspan: "8", text: "no matching call records" }),
            ]));
            return;
        }

        items.forEach(function (row, idx) {
            var tr = el("tr", { class: "log-row", style: { animationDelay: (idx * 25) + "ms" } }, [
                el("td", { class: "col-time", text: fmtTime(row.startTime) }),
                el("td", {}, [el("span", { class: durClass(row.durationMs), text: fmtDuration(row.durationMs) })]),
                el("td", {}, [el("span", { class: codeClass(row.statusCode, row.success), text: row.statusCode })]),
                el("td", {}, [el("span", { class: "tag", text: row.instanceId || "—" })]),
                el("td", {}, [el("span", { class: "tag tag--accent", text: row.sessionId || "—" })]),
                el("td", {}, [el("span", { class: "tag", text: row.agentName || "—" })]),
                el("td", {}, [el("span", { class: "tag", text: row.method + " " + row.endpoint })]),
                el("td", {}, [
                    row.errorMessage
                        ? el("span", { class: "err", text: row.errorMessage })
                        : el("span", { class: "err err--none", text: "—" }),
                ]),
            ]);
            root.appendChild(tr);
        });
    }

    // ---------- Polling ----------
    var pollTimer = null;
    var locked = false;

    /**
     * A router that will not identify this browser has nothing to show, and asking it every five
     * seconds is noise. Say what is missing once and stop.
     */
    function lockOut(message) {
        if (locked) return;
        locked = true;
        if (pollTimer !== null) clearInterval(pollTimer);
        $("stat-healthy").textContent = "—";
        $("stat-sessions").textContent = "—";
        $("footer-updated").textContent = "no access";
        clear($("instances"));
        $("instances").appendChild(el("div", { class: "instance" }, [
            el("div", { class: "instance__head" }, [el("span", { class: "instance__id", text: "credential required" })]),
            el("div", { class: "instance__addr", text: message }),
        ]));
        clear($("logs-body"));
        $("logs-body").appendChild(el("tr", { class: "logs__placeholder" }, [
            el("td", { colspan: "8", text: message }),
        ]));
    }

    function pollInstances() {
        if (locked) return;
        fetchJSON("/api/router/monitor/instances")
            .then(function (items) { if (!locked) renderInstances(items); })
            .catch(function (err) {
                if (err.unauthorized) { lockOut(err.message); return; }
                console.error("[pollInstances] failed:", err);
                $("stat-healthy").textContent = "?";
            });
    }

    function queryLogs(filters, offset) {
        if (locked) return;
        state.currentFilters = filters || {};
        state.offset = offset || 0;
        var params = new URLSearchParams();
        Object.keys(state.currentFilters).forEach(function (k) {
            var v = state.currentFilters[k];
            if (v === "" || v == null) return;
            params.set(k, String(v));
        });
        params.set("limit", String(state.limit));
        params.set("offset", String(state.offset));
        fetchJSON("/api/router/monitor/call-logs?" + params.toString())
            .then(function (items) { if (!locked) renderLogs(items); })
            .catch(function (err) {
                if (err.unauthorized) { lockOut(err.message); return; }
                console.error("[queryLogs] failed:", err);
                clear($("logs-body"));
                $("logs-body").appendChild(el("tr", { class: "logs__placeholder" }, [
                    el("td", { colspan: "8", text: "fetch failed: " + err.message }),
                ]));
            });
    }

    // ---------- Wiring ----------
    function readFilters() {
        var form = $("filters");
        var fd = new FormData(form);
        var out = {};
        ["sessionId", "agentName", "instanceId", "success", "minDurationMs"].forEach(function (k) {
            var v = fd.get(k);
            if (v != null && String(v).trim() !== "") out[k] = String(v).trim();
        });
        return out;
    }

    function tickClock() {
        $("clock").textContent = fmtClock(new Date());
    }

    function tickUpdated() {
        if (locked) return;
        var d = new Date();
        $("footer-updated").textContent =
            "last sync " + pad(d.getHours(), 2) + ":" + pad(d.getMinutes(), 2) + ":" + pad(d.getSeconds(), 2);
    }

    function init() {
        tickClock();
        setInterval(tickClock, 1000);

        pollInstances();
        pollTimer = setInterval(pollInstances, POLL_INTERVAL_MS);

        // Default: most recent 100 logs.
        queryLogs({}, 0);

        // Form submission -> new query.
        $("filters").addEventListener("submit", function (e) {
            e.preventDefault();
            queryLogs(readFilters(), 0);
        });
        $("filters").addEventListener("reset", function () {
            // Allow the reset to clear inputs first, then query.
            setTimeout(function () { queryLogs({}, 0); }, 0);
        });

        // Pagination.
        $("btn-prev").addEventListener("click", function () {
            var next = Math.max(0, state.offset - state.limit);
            if (next !== state.offset) queryLogs(state.currentFilters, next);
        });
        $("btn-next").addEventListener("click", function () {
            var next = state.offset + state.limit;
            if (next < state.total) queryLogs(state.currentFilters, next);
        });

        tickUpdated();
        setInterval(tickUpdated, POLL_INTERVAL_MS);
    }

    // Second way in, for anyone who would rather not touch the URL: harnaxMonitor.setToken("<jwt>").
    window.harnaxMonitor = {
        setToken: function (value) {
            window.localStorage.setItem(TOKEN_KEY, String(value || "").trim());
            window.location.href = window.location.pathname;
        },
        clearToken: function () {
            window.localStorage.removeItem(TOKEN_KEY);
            window.location.href = window.location.pathname;
        },
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();