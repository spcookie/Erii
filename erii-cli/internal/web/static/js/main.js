(function () {
    'use strict';

    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get('token') || '';
    const requestedCommandText = (urlParams.get('cmd') || '').trim();
    const requestedTheme = document.documentElement.dataset.theme || 'auto';
    const resolvedTheme = requestedTheme === 'dark' || requestedTheme === 'light'
        ? requestedTheme
        : (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');

    const terminalThemes = {
        dark: {
            background: '#050505',
            foreground: '#ededed',
            cursor: '#ffffff',
            selectionBackground: '#333333',
            black: '#171717',
            red: '#ee0000',
            green: '#3291ff',
            yellow: '#f5a623',
            blue: '#3291ff',
            magenta: '#8b5cf6',
            cyan: '#50e3c2',
            white: '#ededed',
            brightBlack: '#a1a1a1',
            brightRed: '#ff6369',
            brightGreen: '#58c26d',
            brightYellow: '#ffc45c',
            brightBlue: '#79b8ff',
            brightMagenta: '#a78bfa',
            brightCyan: '#79ffe1',
            brightWhite: '#ffffff'
        },
        light: {
            background: '#ffffff',
            foreground: '#171717',
            cursor: '#171717',
            selectionBackground: '#d3e5ff',
            black: '#171717',
            red: '#ee0000',
            green: '#0070f3',
            yellow: '#f5a623',
            blue: '#0070f3',
            magenta: '#7928ca',
            cyan: '#50e3c2',
            white: '#4d4d4d',
            brightBlack: '#8f8f8f',
            brightRed: '#c50000',
            brightGreen: '#0070f3',
            brightYellow: '#ab570a',
            brightBlue: '#0761d1',
            brightMagenta: '#eb367f',
            brightCyan: '#50e3c2',
            brightWhite: '#171717'
        }
    };

    const wsProto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = wsProto + '//' + window.location.host + '/ws?token=' + encodeURIComponent(token);

    let ws = null;
    let term = null;
    let fitAddon = null;
    let rendererAddon = null;
    let terminalOpened = false;
    let fitFrame = 0;
    let welcomePending = false;
    let reconnectAttempts = 0;
    let reconnectTimer = 0;
    const MAX_RECONNECT_DELAY = 10000;
    const MIN_COLS = 40;
    const SIDEBAR_GROUP_STORAGE_KEY = 'erii.console.sidebar.expanded.v2';
    let activeCmd = null;
    let activeCommandKey = null;
    let runtimeEvents = null;
    let runtimeState = 'checking';
    let cliConnected = false;
    let autoCommand = null;
    let autoCommandStarted = false;
    let runtimeHasPid = false;
    let activeCommandRun = 0;

    // DOM elements
    const terminalContainer = document.getElementById('terminal-container');
    const terminalMount = document.getElementById('terminal-mount');
    const terminalWelcome = document.getElementById('terminal-welcome');
    const welcomeShuffleElement = document.getElementById('welcome-shuffle');
    const statusDot = document.getElementById('status-dot');
    const statusText = document.getElementById('status-text');
    const coreStatus = document.getElementById('core-status');
    const coreStatusDot = document.getElementById('core-status-dot');
    const coreStatusText = document.getElementById('core-status-text');
    const currentCmdEl = document.getElementById('current-cmd');
    const sidebarItems = document.querySelectorAll('.sidebar-item');
    const sidebarGroups = Array.from(document.querySelectorAll('.sidebar-group'));
    const sidebarGroupToggles = Array.from(document.querySelectorAll('.sidebar-group-toggle'));
    const coreDependentItems = document.querySelectorAll('[data-requires-core="true"]');
    const setupCommand = document.getElementById('setup-command');
    const resizeHandle = document.getElementById('resize-handle');
    const serverControl = document.querySelector('.server-control');
    const serverMenuButton = document.getElementById('server-menu-button');
    const serverMenu = document.getElementById('server-menu');
    const serverActionButtons = Array.from(document.querySelectorAll('[data-server-action]'));
    const debugLogButton = document.getElementById('debug-log-button');
    const pluginSendPanel = document.getElementById('plugin-send-modal');
    const pluginSendForm = document.getElementById('plugin-send-form');
    const pluginCommandInput = document.getElementById('plugin-command-input');
    const pluginSendButton = document.getElementById('plugin-send-button');
    const pluginMatchResults = document.getElementById('plugin-match-results');
    const pluginMatchCount = document.getElementById('plugin-match-count');
    const pluginModalClose = document.getElementById('plugin-modal-close');
    const pluginSendResult = document.getElementById('plugin-send-result');
    const pluginResultStatus = document.getElementById('plugin-result-status');
    const pluginResultInput = document.getElementById('plugin-result-input');
    const pluginResultReply = document.getElementById('plugin-result-reply');
    const welcomeShuffle = window.Shuffle && welcomeShuffleElement
        ? new window.Shuffle(welcomeShuffleElement, {
            shuffleDirection: 'right',
            duration: 0.35,
            animationMode: 'evenodd',
            shuffleTimes: 1,
            stagger: 0.03,
            scrambleCharset: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789',
            respectReducedMotion: true,
            triggerOnHover: true
        })
        : null;

    // Default header content (shown when no command is active)
    const defaultHeaderHTML = currentCmdEl.innerHTML;
    let expandedSidebarGroup = readExpandedSidebarGroup();

    function readExpandedSidebarGroup() {
        try {
            return window.localStorage.getItem(SIDEBAR_GROUP_STORAGE_KEY) || null;
        } catch (error) {
            return null;
        }
    }

    function saveExpandedSidebarGroup() {
        try {
            if (expandedSidebarGroup) {
                window.localStorage.setItem(SIDEBAR_GROUP_STORAGE_KEY, expandedSidebarGroup);
            } else {
                window.localStorage.removeItem(SIDEBAR_GROUP_STORAGE_KEY);
            }
        } catch (error) {
            // The accordion still works when persistent storage is unavailable.
        }
    }

    function setSidebarGroupCollapsed(group, collapsed, persist) {
        const toggle = group.querySelector('.sidebar-group-toggle');
        if (!toggle) return;

        const groupKey = group.dataset.sidebarGroup;
        const groupLabel = toggle.querySelector('span')?.textContent.trim() || 'section';

        if (!collapsed) {
            sidebarGroups.forEach(function (otherGroup) {
                if (otherGroup !== group) {
                    setSidebarGroupCollapsed(otherGroup, true, false);
                }
            });
        }

        group.classList.toggle('collapsed', collapsed);
        toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
        toggle.title = (collapsed ? 'Expand ' : 'Collapse ') + groupLabel;

        Array.from(group.children).forEach(function (child) {
            if (child.classList && child.classList.contains('sidebar-item')) {
                child.hidden = collapsed;
            }
        });

        if (!persist || !groupKey) return;
        expandedSidebarGroup = collapsed ? null : groupKey;
        saveExpandedSidebarGroup();
    }

    function initializeSidebarGroups() {
        const hasSavedGroup = sidebarGroups.some(function (group) {
            return group.dataset.sidebarGroup === expandedSidebarGroup;
        });
        if (!hasSavedGroup) expandedSidebarGroup = null;

        sidebarGroups.forEach(function (group) {
            setSidebarGroupCollapsed(
                group,
                group.dataset.sidebarGroup !== expandedSidebarGroup,
                false
            );
        });
    }

    function revealSidebarItem(item) {
        const group = item.closest('.sidebar-group');
        if (group && group.classList.contains('collapsed')) {
            setSidebarGroupCollapsed(group, false, true);
        }
    }

    function setStatus(state) {
        cliConnected = state === 'connected' || state === 'running';
        statusDot.className = 'status-dot';
        switch (state) {
            case 'connecting':
                statusText.textContent = 'Connecting';
                break;
            case 'reconnecting':
                statusText.textContent = 'Reconnecting';
                break;
            case 'connected':
                statusDot.classList.add('connected');
                statusText.textContent = 'Connected';
                break;
            case 'running':
                statusDot.classList.add('connected');
                statusText.textContent = 'Connected';
                break;
            case 'disconnected':
                statusText.textContent = 'Disconnected';
                break;
            default:
                statusText.textContent = 'Offline';
        }
        serverMenuButton.disabled = !cliConnected;
        debugLogButton.disabled = !cliConnected;
        pluginSendButton.disabled = !cliConnected;
        if (!cliConnected) closeServerMenu();
        updateServerActions();
        maybeRunAutoCommand();
    }

    function updateServerActions() {
        const runtimeCanBeManaged = runtimeHasPid &&
            (runtimeState === 'starting' || runtimeState === 'unavailable' || runtimeState === 'online');
        serverActionButtons.forEach(function (button) {
            const action = button.dataset.serverAction;
            if (action === 'start') {
                button.disabled = !cliConnected || runtimeState !== 'offline' || runtimeHasPid;
                button.title = button.disabled && cliConnected && runtimeHasPid
                    ? 'A server process already exists; use Restart'
                    : '';
            } else {
                button.disabled = !cliConnected || !runtimeCanBeManaged;
                button.title = '';
            }
        });
        serverMenuButton.title = cliConnected
            ? 'Manage Erii server (' + runtimeState + ')'
            : 'CLI connection is required to manage the server';
    }

    function closeServerMenu(restoreFocus) {
        if (serverMenu.hidden) return;
        serverMenu.hidden = true;
        serverMenuButton.setAttribute('aria-expanded', 'false');
        if (restoreFocus) serverMenuButton.focus();
    }

    function positionServerMenu() {
        if (serverMenu.hidden) return;
        const anchor = serverMenuButton.getBoundingClientRect();
        const viewportPadding = 8;
        const gap = 7;
        const menuWidth = serverMenu.offsetWidth;
        const menuHeight = serverMenu.offsetHeight;
        const maxLeft = Math.max(viewportPadding, window.innerWidth - viewportPadding - menuWidth);
        const left = Math.min(maxLeft, Math.max(viewportPadding, anchor.right - menuWidth));
        let top = anchor.bottom + gap;
        if (top + menuHeight > window.innerHeight - viewportPadding) {
            top = Math.max(viewportPadding, anchor.top - gap - menuHeight);
        }
        serverMenu.style.left = left + 'px';
        serverMenu.style.top = top + 'px';
    }

    function openServerMenu(focusFirst) {
        if (serverMenuButton.disabled) return;
        serverMenu.hidden = false;
        positionServerMenu();
        serverMenuButton.setAttribute('aria-expanded', 'true');
        if (focusFirst) {
            const firstEnabled = serverActionButtons.find(function (button) {
                return !button.disabled;
            });
            if (firstEnabled) firstEnabled.focus();
        }
    }

    function setCommandTag(item, text, tone) {
        let tag = item.querySelector('.command-tag');
        if (!text) {
            if (tag) tag.remove();
            return;
        }
        if (!tag) {
            tag = document.createElement('span');
            item.appendChild(tag);
        }
        tag.className = 'command-tag ' + tone;
        tag.textContent = text;
    }

    function applyRuntimeStatus(status) {
        const knownStates = ['checking', 'offline', 'starting', 'unavailable', 'online'];
        const state = knownStates.indexOf(status.state) === -1 ? 'unavailable' : status.state;
        runtimeState = state;
        runtimeHasPid = status.pidFile === true;
        const coreAvailable = state === 'online' && status.coreReachable;
        let statusLabel = 'Unavailable';
        let commandLabel = 'UNAVAILABLE';

        switch (state) {
            case 'checking':
                statusLabel = 'Checking';
                commandLabel = 'CHECKING';
                break;
            case 'offline':
                statusLabel = 'Offline';
                commandLabel = 'CORE OFFLINE';
                break;
            case 'starting':
                statusLabel = 'Starting';
                commandLabel = 'STARTING';
                break;
            case 'online':
                statusLabel = 'Online';
                commandLabel = '';
                break;
        }

        coreStatus.className = 'topbar-status ' + state;
        coreStatus.title = status.message || 'Erii core status';
        coreStatusText.textContent = statusLabel;
        coreStatusDot.setAttribute('aria-label', statusLabel);

        coreDependentItems.forEach(function (item) {
            item.disabled = !coreAvailable;
            if (coreAvailable) {
                item.removeAttribute('title');
                setCommandTag(item, '', '');
            } else {
                item.title = status.message || 'Erii core is not available';
                setCommandTag(item, commandLabel, 'unavailable');
            }
        });

        if (status.setupRequired) {
            setupCommand.title = 'Run setup to create the Erii core connection configuration';
            setCommandTag(setupCommand, 'START HERE', 'guide');
            revealSidebarItem(setupCommand);
        } else {
            setupCommand.removeAttribute('title');
            setCommandTag(setupCommand, '', '');
        }
        updateServerActions();
        maybeRunAutoCommand();
    }

    function connectRuntimeStatus() {
        if (runtimeEvents) runtimeEvents.close();
        runtimeEvents = new EventSource('/api/runtime/events?token=' + encodeURIComponent(token));
        runtimeEvents.addEventListener('status', function (event) {
            try {
                applyRuntimeStatus(JSON.parse(event.data));
            } catch (e) {
                console.warn('Invalid Erii runtime status event', e);
            }
        });
        runtimeEvents.onerror = function () {
            applyRuntimeStatus({
                state: 'unavailable',
                pidFile: false,
                coreReachable: false,
                setupRequired: false,
                message: 'Erii runtime status stream is reconnecting'
            });
        };
    }

    function itemCommandKey(item) {
        const args = item.dataset.args || '';
        return item.dataset.cmd + (args ? ' ' + args : '');
    }

    function commandKey(cmd, args) {
        return cmd + (args && args.length ? ' ' + args.join(' ') : '');
    }

    function findCommandItem(cmd, args) {
        const key = commandKey(cmd, args || []);
        for (let i = 0; i < sidebarItems.length; i++) {
            if (itemCommandKey(sidebarItems[i]) === key) return sidebarItems[i];
        }
        return null;
    }

    function setActiveCmd(cmd, args, title) {
        hidePluginSendPanel(false);
        activeCmd = cmd;
        const logActive = cmd === 'log';
        debugLogButton.setAttribute('aria-pressed', logActive ? 'true' : 'false');
        debugLogButton.setAttribute('aria-label', logActive ? 'Close Erii logs' : 'Open Erii logs');
        debugLogButton.title = logActive ? 'Close Erii logs' : 'Open Erii logs';
        activeCommandKey = cmd ? commandKey(cmd, args || []) : null;
        let matched = false;
        sidebarItems.forEach(function (item) {
            if (itemCommandKey(item) === activeCommandKey) {
                matched = true;
                revealSidebarItem(item);
                item.classList.add('active');
                currentCmdEl.innerHTML = item.innerHTML;
                currentCmdEl.querySelectorAll('.command-tag').forEach(function (tag) {
                    tag.remove();
                });
            } else {
                item.classList.remove('active');
            }
        });
        if (!cmd) {
            currentCmdEl.innerHTML = defaultHeaderHTML;
        } else if (!matched) {
            currentCmdEl.innerHTML = defaultHeaderHTML;
            const label = currentCmdEl.querySelector('span');
            if (label) label.textContent = title || cmd;
        }
    }

    function setActivePanel(panelName) {
        activeCmd = null;
        debugLogButton.setAttribute('aria-pressed', 'false');
        debugLogButton.setAttribute('aria-label', 'Open Erii logs');
        debugLogButton.title = 'Open Erii logs';
        activeCommandKey = 'panel:' + panelName;
        let matched = false;
        sidebarItems.forEach(function (item) {
            if (item.dataset.panel === panelName) {
                matched = true;
                revealSidebarItem(item);
                item.classList.add('active');
                currentCmdEl.innerHTML = item.innerHTML;
            } else {
                item.classList.remove('active');
            }
        });
        if (!matched) currentCmdEl.innerHTML = defaultHeaderHTML;
    }

    function hidePluginSendPanel(restoreTerminal) {
        if (!pluginSendPanel || pluginSendPanel.hidden) return;
        pluginSendPanel.hidden = true;
        if (restoreTerminal) scheduleFit(false);
    }

    function showPluginSendPanel() {
        closeServerMenu();
        setActivePanel('plugin-send');
        pluginSendPanel.hidden = false;
        pluginSendButton.disabled = !cliConnected;
        pluginSendButton.dataset.loading = 'false';
        pluginSendButton.textContent = 'Send';
        renderPluginMatches([], pluginCommandInput.value.trim() ? 'Searching...' : 'Type to search registered examples.');
        requestAnimationFrame(function () {
            pluginCommandInput.focus();
            refreshPluginMatches();
        });
    }

    function renderPluginMatches(matches, emptyText) {
        pluginMatchResults.innerHTML = '';
        pluginMatchCount.textContent = String(matches.length);
        if (!matches.length) {
            const empty = document.createElement('div');
            empty.className = 'plugin-match-empty';
            empty.textContent = emptyText || 'No matches.';
            pluginMatchResults.appendChild(empty);
            return;
        }
        matches.forEach(function (match) {
            const button = document.createElement('button');
            button.className = 'plugin-match-item';
            button.type = 'button';
            button.dataset.example = match.example || '';

            const example = document.createElement('span');
            example.className = 'plugin-match-example';
            example.textContent = match.example || '';

            const meta = document.createElement('span');
            meta.className = 'plugin-match-meta';
            meta.textContent = [match.pluginId, match.description].filter(Boolean).join(' · ');

            button.appendChild(example);
            button.appendChild(meta);
            button.addEventListener('click', function () {
                pluginCommandInput.value = this.dataset.example;
                pluginCommandInput.focus();
                refreshPluginMatches();
            });
            pluginMatchResults.appendChild(button);
        });
    }

    let pluginMatchTimer = 0;
    let pluginMatchSeq = 0;

    function decodePluginResponse(response) {
        return response.json().catch(function () {
            return {};
        }).then(function (data) {
            if (!response.ok) {
                throw new Error(data.message || ('HTTP ' + response.status));
            }
            return data;
        });
    }

    function schedulePluginMatch() {
        if (pluginMatchTimer) clearTimeout(pluginMatchTimer);
        pluginMatchTimer = setTimeout(refreshPluginMatches, 180);
    }

    function refreshPluginMatches() {
        if (!pluginSendPanel || pluginSendPanel.hidden) return;
        const query = pluginCommandInput.value.trim();
        if (!query) {
            renderPluginMatches([], 'Type to search registered examples.');
            return;
        }
        const seq = ++pluginMatchSeq;
        const url = '/api/plugin/match?limit=20&query=' + encodeURIComponent(query);
        fetch(url, {
            headers: {'X-Erii-Token': token}
        }).then(decodePluginResponse).then(function (data) {
            if (seq !== pluginMatchSeq) return;
            renderPluginMatches(data.matches || [], 'No matches.');
        }).catch(function (error) {
            if (seq !== pluginMatchSeq) return;
            renderPluginMatches([], error.message || 'Match request failed.');
        });
    }

    function showPluginSendResult(input, response) {
        terminalContainer.hidden = true;
        pluginSendResult.hidden = false;
        const ok = !response || !response.status || response.status.toLowerCase() === 'ok';
        pluginResultStatus.textContent = ok ? 'OK' : response.status.toUpperCase();
        pluginResultStatus.classList.toggle('error', !ok);
        pluginResultInput.textContent = input;
        const reply = response && typeof response.reply === 'string' ? response.reply.trim() : '';
        pluginResultReply.classList.toggle('empty', !reply);
        pluginResultReply.textContent = reply || 'No reply returned';
        setActivePanel('plugin-send');
    }

    function submitPluginSend(input) {
        if (!input || pluginSendButton.disabled || pluginSendButton.dataset.loading === 'true') return;
        pluginSendButton.disabled = true;
        pluginSendButton.dataset.loading = 'true';
        pluginSendButton.textContent = 'Sending';
        fetch('/api/plugin/send', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Erii-Token': token
            },
            body: JSON.stringify({input: input})
        }).then(decodePluginResponse).then(function (data) {
            hidePluginSendPanel(false);
            showPluginSendResult(input, data);
        }).catch(function (error) {
            hidePluginSendPanel(false);
            showPluginSendResult(input, {
                status: 'error',
                reply: error.message || 'send failed'
            });
        }).finally(function () {
            pluginSendButton.dataset.loading = 'false';
            pluginSendButton.textContent = 'Send';
            pluginSendButton.disabled = !cliConnected;
        });
    }

    function hideTerminalWelcome() {
        if (!terminalWelcome || terminalWelcome.hidden) return;
        terminalWelcome.hidden = true;
        welcomeShuffle?.cancel();
    }

    function showTerminalWelcome(text) {
        if (!terminalWelcome || !welcomeShuffleElement) return;
        terminalWelcome.hidden = false;
        if (welcomeShuffle) {
            welcomeShuffle.setText(text);
            welcomeShuffle.play();
        } else {
            welcomeShuffleElement.textContent = text;
            welcomeShuffleElement.classList.add('is-ready');
        }
    }

    function showWelcome() {
        if (!term) return;
        term.reset();
        showTerminalWelcome('ERIIBOT');
    }

    function setRenderer(name) {
        terminalMount.dataset.renderer = name;
    }

    function activateCanvasRenderer(reason) {
        if (!window.CanvasAddon) {
            setRenderer('dom');
            console.warn(reason + '; using the DOM renderer');
            return;
        }

        let canvasAddon = null;
        try {
            canvasAddon = new CanvasAddon.CanvasAddon();
            term.loadAddon(canvasAddon);
            rendererAddon = canvasAddon;
            setRenderer('canvas');
            console.info('xterm renderer: canvas (' + reason + ')');
        } catch (e) {
            if (canvasAddon) canvasAddon.dispose();
            rendererAddon = null;
            setRenderer('dom');
            console.warn('Canvas renderer unavailable; using the DOM renderer', e);
        }
    }

    function activateRenderer() {
        if (!window.WebglAddon) {
            activateCanvasRenderer('WebGL addon unavailable');
            return;
        }

        let webglAddon = null;
        try {
            webglAddon = new WebglAddon.WebglAddon();
            term.loadAddon(webglAddon);
            rendererAddon = webglAddon;
            setRenderer('webgl');
            console.info('xterm renderer: webgl');
            webglAddon.onContextLoss(function () {
                // Disposing while the context-loss event is being dispatched can
                // leave xterm's renderer service between states.
                setTimeout(function () {
                    if (rendererAddon !== webglAddon) return;
                    rendererAddon = null;
                    webglAddon.dispose();
                    activateCanvasRenderer('WebGL context lost');
                    scheduleFit(false);
                }, 0);
            });
        } catch (e) {
            if (webglAddon) webglAddon.dispose();
            rendererAddon = null;
            activateCanvasRenderer('WebGL initialization failed');
        }
    }

    function openTerminalLink(event, uri) {
        let target;
        try {
            target = new URL(uri);
        } catch (e) {
            return;
        }
        if (target.protocol !== 'http:' && target.protocol !== 'https:') return;
        event.preventDefault();
        const opened = window.open(target.href, '_blank', 'noopener,noreferrer');
        if (opened) opened.opener = null;
    }

    function scheduleFit(showBanner) {
        welcomePending = welcomePending || showBanner;
        if (!terminalOpened || fitFrame) return;
        fitFrame = requestAnimationFrame(function () {
            fitFrame = 0;
            fitAddon.fit();
            if (welcomePending) {
                welcomePending = false;
                showWelcome();
            }
        });
    }

    function initTerminal() {
        term = new Terminal({
            cursorBlink: true,
            cursorStyle: 'bar',
            fontSize: 14,
            fontFamily: '"JetBrains Mono", "Erii Nerd Icons", monospace',
            letterSpacing: 0,
            lineHeight: 1.2,
            theme: terminalThemes[resolvedTheme],
            allowProposedApi: true,
        });

        fitAddon = new FitAddon.FitAddon();
        term.loadAddon(fitAddon);

        if (window.Unicode11Addon) {
            const unicodeAddon = new Unicode11Addon.Unicode11Addon();
            term.loadAddon(unicodeAddon);
            term.unicode.activeVersion = '11';
            terminalMount.dataset.unicode = term.unicode.activeVersion;
        }

        if (window.WebLinksAddon) {
            term.loadAddon(new WebLinksAddon.WebLinksAddon(openTerminalLink));
        }

        function doOpen() {
            // open() MUST be called when the container has final dimensions;
            // xterm.js does DOM-based measurements during open().
            term.open(terminalMount);
            terminalOpened = true;
            activateRenderer();
            scheduleFit(true);
            maybeRunAutoCommand();
        }

        function doOpenLater() {
            requestAnimationFrame(function () {
                requestAnimationFrame(doOpen);
            });
        }

        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(doOpenLater);
        } else {
            doOpenLater();
        }

        term.onData(function (data) {
            hideTerminalWelcome();
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: 'input', data: data }));
            }
        });

        term.onResize(function (size) {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({
                    type: 'resize',
                    cols: Math.max(size.cols, MIN_COLS),
                    rows: size.rows
                }));
            }
        });

        if (window.ResizeObserver) {
            var ro = new ResizeObserver(function () {
                scheduleFit(false);
            });
            ro.observe(terminalMount);
        }
        window.addEventListener('resize', function () {
            scheduleFit(false);
        });
    }

    function scheduleReconnect() {
        if (reconnectTimer) clearTimeout(reconnectTimer);
        reconnectAttempts++;
        const exponent = Math.min(reconnectAttempts - 1, 4);
        const delay = Math.min(Math.pow(2, exponent) * 1000, MAX_RECONNECT_DELAY);
        setStatus('reconnecting');
        reconnectTimer = setTimeout(function () {
            reconnectTimer = 0;
            connect();
        }, delay);
    }

    function connect() {
        if (ws) {
            ws.onclose = null;
            ws.close();
            ws = null;
        }

        setStatus(reconnectAttempts > 0 ? 'reconnecting' : 'connecting');
        const socket = new WebSocket(wsUrl);
        ws = socket;
        socket.binaryType = 'arraybuffer';

        socket.onopen = function () {
            if (ws !== socket) return;
            reconnectAttempts = 0;
            socket.send(JSON.stringify({ type: 'auth', token: token }));
            setStatus('connected');
            maybeRunAutoCommand();
        };

        socket.onmessage = function (event) {
            if (ws !== socket) return;
            if (event.data instanceof ArrayBuffer) {
                hideTerminalWelcome();
                if (term) term.write(new Uint8Array(event.data));
                return;
            }
            const msg = JSON.parse(event.data);
            switch (msg.type) {
                case 'exit':
                    const exitedCmd = activeCmd;
                    const exitedRun = activeCommandRun;
                    const finishExit = function () {
                        if (exitedRun !== activeCommandRun || activeCmd !== exitedCmd) return;
                        const hasVisibleOutput = terminalHasVisibleOutput();
                        setActiveCmd(null, []);
                        setStatus('connected');
                        if (tuiCommands.indexOf(exitedCmd) !== -1 && !hasVisibleOutput) {
                            showWelcome();
                        } else {
                            hideTerminalWelcome();
                        }
                    };
                    if (term) {
                        // Queue behind all preceding PTY writes so the final
                        // rendered output is present before deciding what to show.
                        term.write('', finishExit);
                    } else {
                        finishExit();
                    }
                    break;
                case 'error':
                    hideTerminalWelcome();
                    if (term) term.writeln('\r\n\x1b[31m' + msg.data + '\x1b[0m');
                    break;
            }
        };

        socket.onclose = function () {
            if (ws !== socket) return;
            ws = null;
            activeCommandRun++;
            setActiveCmd(null, []);
            scheduleReconnect();
        };

        socket.onerror = function () {
            socket.close();
        };
    }

    // Commands that use BubbleTea TUI (alternate screen)
    const tuiCommands = ['config', 'setup', 'manage', 'status', 'chat', 'usage', 'log'];

    function terminalHasVisibleOutput() {
        if (!term || !term.buffer || !term.buffer.active) return false;
        const buffer = term.buffer.active;
        for (let index = 0; index < buffer.length; index++) {
            const line = buffer.getLine(index);
            if (line && line.translateToString(true).trim()) return true;
        }
        return false;
    }

    function parseArgs(value) {
        if (!value) return [];
        return value.split(/\s+/).filter(Boolean);
    }

    function parseAutoCommand(value) {
        const parts = parseArgs(value);
        if (!parts.length) return null;
        const cmd = parts[0];
        if (!/^[A-Za-z0-9_-]+$/.test(cmd)) return null;
        return {
            raw: value,
            cmd: cmd,
            args: parts.slice(1)
        };
    }

    autoCommand = parseAutoCommand(requestedCommandText);

    function execCmd(cmd, args, title) {
        if (!ws || ws.readyState !== WebSocket.OPEN) return;
        args = args || [];
        hideTerminalWelcome();
        hidePluginSendPanel(true);
        pluginSendResult.hidden = true;
        terminalContainer.hidden = false;
        closeServerMenu();
        activeCommandRun++;
        setActiveCmd(cmd, args, title);
        setStatus('running');
        // Keep the main buffer empty while commands switch. TUI processes use
        // the alternate screen, so pre-filling the main buffer with the welcome
        // banner would expose it briefly when the previous process exits.
        term.reset();
        ws.send(JSON.stringify({
            type: 'exec',
            cmd: cmd,
            args: args,
            theme: resolvedTheme,
            cols: Math.max(term.cols, MIN_COLS),
            rows: term.rows
        }));
        term.focus();
    }

    function maybeRunAutoCommand() {
        if (!autoCommand || autoCommandStarted) return;
        if (!terminalOpened || !cliConnected || !ws || ws.readyState !== WebSocket.OPEN) return;

        const item = findCommandItem(autoCommand.cmd, autoCommand.args);
        if (item && item.disabled) return;

        autoCommandStarted = true;
        const title = item ? item.dataset.title : autoCommand.raw;
        execCmd(autoCommand.cmd, autoCommand.args, title);
    }

    // Sidebar click handlers
    sidebarGroupToggles.forEach(function (toggle) {
        toggle.addEventListener('click', function () {
            const group = this.closest('.sidebar-group');
            if (!group) return;
            const collapse = this.getAttribute('aria-expanded') === 'true';
            setSidebarGroupCollapsed(group, collapse, true);
        });
    });

    sidebarItems.forEach(function (item) {
        item.addEventListener('click', function () {
            if (this.disabled) return;
            if (this.dataset.panel === 'plugin-send') {
                showPluginSendPanel();
                return;
            }
            const cmd = this.dataset.cmd;
            if (cmd) execCmd(cmd, parseArgs(this.dataset.args));
        });
    });

    pluginCommandInput.addEventListener('input', schedulePluginMatch);

    pluginSendForm.addEventListener('submit', function (event) {
        event.preventDefault();
        const input = pluginCommandInput.value.trim();
        submitPluginSend(input);
    });

    pluginModalClose.addEventListener('click', function () {
        if (pluginSendButton.dataset.loading === 'true') return;
        hidePluginSendPanel(false);
    });

    debugLogButton.addEventListener('click', function () {
        if (this.disabled) return;
        if (activeCmd === 'log') {
            ws.send(JSON.stringify({type: 'input', data: '\x1b'}));
            term.focus();
            return;
        }
        execCmd('log', [], 'Logs');
    });

    serverMenuButton.addEventListener('click', function () {
        if (serverMenu.hidden) {
            openServerMenu(false);
        } else {
            closeServerMenu(false);
        }
    });

    serverMenuButton.addEventListener('keydown', function (event) {
        if (event.key === 'ArrowDown') {
            event.preventDefault();
            openServerMenu(true);
        }
    });

    serverActionButtons.forEach(function (button) {
        button.addEventListener('click', function () {
            if (this.disabled) return;
            const action = this.dataset.serverAction;
            const title = 'Server ' + action.charAt(0).toUpperCase() + action.slice(1);
            execCmd('server', [action], title);
        });
    });

    serverMenu.addEventListener('keydown', function (event) {
        const enabled = serverActionButtons.filter(function (button) { return !button.disabled; });
        if (!enabled.length) return;
        const current = enabled.indexOf(document.activeElement);
        let next = current;
        if (event.key === 'ArrowDown') next = (current + 1) % enabled.length;
        else if (event.key === 'ArrowUp') next = (current <= 0 ? enabled.length : current) - 1;
        else if (event.key === 'Home') next = 0;
        else if (event.key === 'End') next = enabled.length - 1;
        else return;
        event.preventDefault();
        enabled[next].focus();
    });

    document.addEventListener('click', function (event) {
        if (!serverControl.contains(event.target) && !serverMenu.contains(event.target)) {
            closeServerMenu(false);
        }
    });

    window.addEventListener('resize', positionServerMenu);

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && pluginSendPanel && !pluginSendPanel.hidden) {
            if (pluginSendButton.dataset.loading === 'true') return;
            event.preventDefault();
            hidePluginSendPanel(false);
            return;
        }
        if (event.key === 'Escape' && !serverMenu.hidden) {
            event.preventDefault();
            closeServerMenu(true);
        }
    });

    // Sidebar resize
    const sidebar = document.getElementById('sidebar');
    let isResizing = false;
    let startX, startWidth;

    resizeHandle.addEventListener('mousedown', function (e) {
        isResizing = true;
        startX = e.clientX;
        startWidth = sidebar.offsetWidth;
        document.body.style.cursor = 'col-resize';
        document.body.style.userSelect = 'none';
    });

    document.addEventListener('mousemove', function (e) {
        if (!isResizing) return;
        const newWidth = startWidth + (e.clientX - startX);
        if (newWidth >= 160 && newWidth <= 400) {
            sidebar.style.width = newWidth + 'px';
        }
    });

    document.addEventListener('mouseup', function () {
        if (isResizing) {
            isResizing = false;
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
            scheduleFit(false);
        }
    });

    // Init
    initializeSidebarGroups();
    applyRuntimeStatus({
        state: 'checking',
        pidFile: false,
        coreReachable: false,
        setupRequired: false,
        message: 'Checking Erii core'
    });
    connectRuntimeStatus();
    initTerminal();
    connect();
})();
