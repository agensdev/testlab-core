// Testlab panes for Claude Code: the phone's screen live (keys, swipes, rewind through what was
// done) and a test report chapter by chapter, drawn in the terminal. Talks to the testlab MCP
// server this plugin starts (.mcp.json), with the same app-only tools the MCP Apps views use.
//
// Pictures: a terminal with a graphics protocol (kitty, Ghostty) gets real pixels (Image); every
// other terminal gets half-block cells (Raster, two pixels a cell). [i] switches by hand.

// The client space this copy of the plugin is for: `testlab --space acme claude-plugin` writes its
// name here, so the panes read that space's control.json. Empty: the default space.
const SPACE = ''
const SCREEN = 'testlab-screen'
const REPORT = 'testlab-report'
const MAX_FRAMES = 200
const PANE_TOOLS = new Set(['handsets_view_frame', 'handsets_view_activity', 'handsets_navigate', 'handsets_swipe', 'lab_view_report', 'lab_view_report_frame', 'lab_open_page'])
const DEFAULT_COLOR = 0x01000000
const HALF = 0x2580 // ▀: foreground = the top pixel, background = the bottom one

let server = null // the name $.mcp.call takes, from $.mcp.connect
let connectProblem = ''
let pixels = null // true: Image, false: Raster cells; null until the terminal is known
let fit = { columns: 36, rows: 34 } // the picture's box in cells, from the pane's last layout

// The live screen.
let screen = { serial: null, width: 0, height: 0, open: false, paused: false, busy: false, error: '' }
let frames = [] // { t, frame }
let viewing = null // index into frames; null = live
let calls = []
let generation = 0 // a ring file reused under the same name is a new picture
let fps = { n: 0, at: Date.now(), value: 0 }
let stopFrames = null
let stopActivity = null

// The report.
let report = null // { id, title, page, events, markdown }
let chapter = 0
let chapterFrames = {} // index → frame
let reportError = ''

// --- the MCP server ---------------------------------------------------------------------------

// The local control API (the window or `testlab serve`): the panes' own calls go there, so they
// aren't Claude's tool calls and need no permission (an organization may cap MCP tools at "ask",
// which a mod can't loosen). Without it, the plugin's MCP server.
let api // { url, token } | null once looked for
let apiProblem = '' // why the panes are on MCP and not the control API, for the error line
async function controlApi($) {
  if (api !== undefined) return api
  api = null
  apiProblem = 'no control.json (the Testlab window or `testlab serve` writes it)' 
  const home = (await $.env.get('HOME')) || (await $.env.get('USERPROFILE')) || ''
  const xdg = (await $.env.get('XDG_DATA_HOME')) || home + '/.local/share'
  const appdata = (await $.env.get('APPDATA')) || ''
  const space = SPACE || (await $.env.get('TESTLAB_SPACE')) || ''
  const sub = space && space !== 'default' ? '/spaces/' + space : ''
  const candidates = [
    home + '/Library/Application Support/Testlab' + sub + '/control.json',
    xdg + '/testlab' + sub + '/control.json',
    appdata ? appdata + '/Testlab' + sub + '/control.json' : '',
  ].filter(Boolean)
  for (const path of candidates) {
    try {
      const c = JSON.parse(await $.fs.read(path))
      if (c.api && c.token) { api = { url: c.api, token: c.token, version: c.version || '?' }; apiProblem = ''; break }
    } catch (err) {
      const m = String(err.message || err)
      if (!/ENOENT|no such file|not found|missing/i.test(m)) apiProblem = 'reading ' + path + ': ' + m
    }
  }
  return api
}

async function viaApi($, tool, args) {
  const a = await controlApi($)
  if (!a) return null
  let res
  try {
    res = await $.http.fetch(a.url + '/view/call', {
      method: 'POST',
      headers: { authorization: 'Bearer ' + a.token, 'content-type': 'application/json' },
      body: JSON.stringify({ tool, args: args || {} }),
    })
  } catch (err) {
    apiProblem = 'calling ' + a.url + ': ' + String(err.message || err)
    api = undefined // the window closed or moved: look again next time, MCP meanwhile
    return null
  }
  if (res.status === 404) { apiProblem = 'the running Testlab (' + a.version + ') has no /view/call: it is too old, upgrade it'; api = undefined; return null }
  if (res.status === 401) { apiProblem = 'control.json has an old token (restart the Testlab window)'; api = undefined; return null }
  let body
  try { body = JSON.parse(res.text) } catch { throw new Error('control API: ' + res.status) }
  if (!res.ok) throw new Error((body.error && body.error.message) || 'control API: ' + res.status)
  return body
}

async function mcp($, tool, args) {
  const viaHttp = await viaApi($, tool, args)
  if (viaHttp) {
    const text = (viaHttp.content || []).filter((c) => c.type === 'text').map((c) => c.text).join('\n')
    if (viaHttp.isError) throw new Error(text || tool + ' failed')
    try { return JSON.parse(text) } catch { return { text } }
  }
  if (!server) {
    // The plugin's own server (.mcp.json) connects on request; a `testlab` server the user added
    // themselves (`claude mcp add … testlab`) works too.
    const c = await $.mcp.connect('testlab')
    server = c.isConnected ? c.server : 'testlab'
    if (!c.isConnected) connectProblem = c.message
  }
  let r
  try {
    r = await $.mcp.call(server, tool, args || {})
  } catch (err) {
    const msg = String(err.message || err)
    const why = connectProblem ? ' (' + connectProblem + ')' : ''
    server = null
    if (apiProblem && /refused|classifier|permission/i.test(msg)) {
      throw new Error('Not using the Testlab control API: ' + apiProblem + '. Over MCP: ' + msg)
    }
    if (/no connected MCP tool/.test(msg)) {
      throw new Error('The Testlab server answering is older than this pane (it has no ' + tool + '). ' +
        'If the Testlab window is open, it answers: upgrade it to 0.12 or later, or quit it. Then /reload-plugins.')
    }
    throw new Error(msg + why)
  }
  const text = (r.content || []).filter((c) => c.type === 'text').map((c) => c.text).join('\n')
  if (r.isError) throw new Error(text || tool + ' failed')
  try { return JSON.parse(text) } catch { return { text } }
}

async function detectPixels($) {
  if (pixels !== null) return
  const term = ((await $.env.get('TERM')) || '').toLowerCase()
  const program = ((await $.env.get('TERM_PROGRAM')) || '').toLowerCase()
  const kitty = await $.env.get('KITTY_WINDOW_ID')
  pixels = term.includes('kitty') || term.includes('ghostty') || program === 'ghostty' || !!kitty
}

// --- pictures ---------------------------------------------------------------------------------

function bytesOf(b64) {
  if (Uint8Array.fromBase64) return Uint8Array.fromBase64(b64)
  const s = atob(b64)
  const out = new Uint8Array(s.length)
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i)
  return out
}

function base64Of(bytes) {
  if (bytes.toBase64) return bytes.toBase64()
  let s = ''
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000))
  return btoa(s)
}

// Half-block cells from raw RGB: each cell is the top pixel over the bottom one.
function cellsOf(frame) {
  const px = bytesOf(frame.data)
  const rows = Math.floor(frame.h / 2)
  const out = new Uint32Array(frame.w * rows * 3)
  let o = 0
  for (let y = 0; y < rows; y++) {
    for (let x = 0; x < frame.w; x++) {
      const top = ((y * 2) * frame.w + x) * 3
      const bottom = ((y * 2 + 1) * frame.w + x) * 3
      out[o++] = HALF
      out[o++] = (px[top] << 16) | (px[top + 1] << 8) | px[top + 2]
      out[o++] = (px[bottom] << 16) | (px[bottom + 1] << 8) | px[bottom + 2]
    }
  }
  return { columns: frame.w, rows, cells: base64Of(new Uint8Array(out.buffer)) }
}

// What to ask the server for, for a box of `fit` cells and a picture `aspect` (height / width) tall.
function request(aspect) {
  let columns = fit.columns
  let rows = Math.round((columns * aspect) / 2)
  if (rows > fit.rows) { rows = fit.rows; columns = Math.max(8, Math.round((rows * 2) / aspect)) }
  // Pixels arrive as a PNG file the terminal reads itself (no picture crosses MCP); cells as raw RGB.
  return pixels
    ? { format: 'file', w: columns * 10, h: rows * 20, columns, rows }
    : { format: 'rgb', w: columns, h: rows * 2, columns, rows }
}

function picture(els, key, frame, alt) {
  const { Image, Raster, Text } = els
  if (!frame) return Text({ dimColor: true, children: [alt] })
  if (frame.format === 'file' && Image) {
    return Image({ key, source: { file: frame.data, format: 'png', generation: frame.generation || 0 }, columns: frame.columns, rows: frame.rows, alt })
  }
  if (frame.format === 'png' && Image) {
    return Image({ key, source: { png: frame.data }, columns: frame.columns, rows: frame.rows, alt })
  }
  if (frame.format === 'rgb' && Raster) {
    const c = cellsOf(frame)
    return Raster({ key, columns: c.columns, rows: c.rows, cells: c.cells })
  }
  return Text({ dimColor: true, children: [alt] })
}

// Room for the picture: the pane's height (less `reserved` rows), and its width less `side`
// columns for the controls beside it.
function measure(e, reserved, side) {
  const cols = Math.max(12, (e.props.bodyColumns || 60) - side - 2)
  const bodyRows = (e.props.scroll && e.props.scroll.bodyRows) || 30
  return { columns: Math.min(cols, 80), rows: Math.max(8, Math.min(bodyRows - reserved, 100)) }
}

// Panes ask for this many rows when they sit above the prompt.
const PANE_ROWS = 42
const SIDE = 46

// --- the live screen --------------------------------------------------------------------------

function ago(t) {
  const s = Math.max(0, Math.round((Date.now() - t) / 1000))
  return s < 60 ? s + ' s' : Math.round(s / 60) + ' min'
}

function frameAt(t) {
  let best = 0
  for (let i = 0; i < frames.length; i++) if (frames[i].t <= t) best = i
  return best
}

async function pollFrame($) {
  if (!screen.open || screen.paused || screen.busy) return
  screen.busy = true
  try {
    const aspect = screen.width ? screen.height / screen.width : 2.2
    const want = request(aspect)
    const r = await mcp($, 'handsets_view_frame', { format: want.format, w: want.w, h: want.h, ...(screen.serial ? { serial: screen.serial } : {}) })
    screen.serial = r.serial
    screen.width = r.width
    screen.height = r.height
    screen.error = ''
    frames.push({ t: Date.now(), frame: { format: r.format, w: r.w, h: r.h, data: r.data, columns: want.columns, rows: want.rows, generation: ++generation } })
    if (frames.length > MAX_FRAMES) { frames.shift(); if (viewing !== null) viewing = Math.max(0, viewing - 1) }
    fps.n++
    if (Date.now() - fps.at > 2000) { fps.value = (fps.n * 1000) / (Date.now() - fps.at); fps.n = 0; fps.at = Date.now() }
  } catch (err) {
    screen.error = String(err.message || err)
  } finally {
    screen.busy = false
    $.ui.invalidate('ui.render')
  }
}

async function pollActivity($) {
  if (!screen.open) return
  try {
    const r = await mcp($, 'handsets_view_activity', { limit: 9 })
    calls = (r.calls || []).filter((c) => c.tool !== 'handsets_view_frame')
    $.ui.invalidate('ui.render')
  } catch { /* the frame poll reports errors */ }
}

async function openScreen($, serial, focus) {
  await detectPixels($)
  if (serial) screen.serial = serial
  screen.open = true
  screen.paused = false
  const pane = { id: SCREEN, title: 'Phone', closeOnEscape: true, rows: PANE_ROWS }
  const placed = await $.ui.open(focus ? { ...pane, focus: true } : pane)
  if (stopFrames) stopFrames.cancel()
  if (stopActivity) stopActivity.cancel()
  stopFrames = $.clock.every(250, () => pollFrame($))
  stopActivity = $.clock.every(2000, () => pollActivity($))
  pollActivity($)
  return placed
}

function closeScreen() {
  screen.open = false
  if (stopFrames) stopFrames.cancel()
  if (stopActivity) stopActivity.cancel()
  stopFrames = null
  stopActivity = null
  frames = []
  viewing = null
}

async function key($, target) {
  try { await mcp($, 'handsets_navigate', { target, ...(screen.serial ? { serial: screen.serial } : {}) }) } catch (err) { screen.error = String(err.message || err) }
}

async function swipe($, direction) {
  try { await mcp($, 'handsets_swipe', { direction, ...(screen.serial ? { serial: screen.serial } : {}) }) } catch (err) { screen.error = String(err.message || err) }
}

function renderScreen($, e) {
  const els = $.ui.resolve(e)
  const { Box, Text, Button } = els
  fit = measure(e, 1, SIDE)
  const redraw = () => $.ui.invalidate('ui.render')
  const shown = viewing === null ? frames[frames.length - 1] : frames[viewing]
  const at = shown && viewing !== null ? calls.find((c) => shown.t >= c.at - c.ms - 200 && shown.t <= c.at + 400) : null
  const status = screen.error
    ? Text({ color: 'red', wrap: 'wrap', children: [screen.error] })
    : Text({ dimColor: true, children: [(screen.serial || 'connecting…') + (screen.width ? ' · ' + screen.width + '×' + screen.height : '') + ' · ' + (screen.paused ? 'paused' : fps.value.toFixed(1) + ' frames/s') + ' · ' + (pixels ? 'pixels' : 'cells')] })
  const when = viewing === null
    ? Text({ color: 'green', children: ['● live'] })
    : Text({ color: 'yellow', children: ['⏪ ' + ago(frames[viewing].t) + ' ago' + (at ? ' · ' + (at.title || at.tool.replace(/^handsets_/, '')) : '') + '  (' + (viewing + 1) + '/' + frames.length + ')'] })
  const btn = (k, label, hotkey, onPress) => Button({ key: k, label, hotkey, plain: true, onPress })
  const step = (d) => () => {
    if (!frames.length) return
    const from = viewing === null ? frames.length - 1 : viewing
    const to = Math.max(0, Math.min(frames.length - 1, from + d))
    viewing = to === frames.length - 1 && d > 0 ? null : to
    redraw()
  }
  // What it did in words (the agent's reason under it), not the raw tool call.
  const actions = calls.slice(0, 6).map((c, i) =>
    Box({
      key: 'call-' + c.at,
      flexDirection: 'column',
      children: [
        Box({ flexDirection: 'row', columnGap: 1, children: [
          Button({
            key: 'act-' + c.at,
            label: (c.ok ? '✓ ' : '✗ ') + (c.title || c.tool.replace(/^handsets_|^lab_/, '') + (c.summary ? ' ' + c.summary : '')),
            hotkey: String(i + 1),
            plain: true,
            dimColor: !(at && at.at === c.at),
            onPress: () => {
              if (!frames.length || c.at < frames[0].t) { screen.error = 'that was before this pane kept frames'; redraw(); return }
              viewing = frameAt(c.at - c.ms - 50)
              redraw()
            },
          }),
          Text({ dimColor: true, children: [ago(c.at)] }),
        ] }),
        ...(c.why ? [Text({ color: 'cyan', wrap: 'wrap', children: ['   ' + c.why] })] : []),
      ],
    }),
  )
  return Box({ flexDirection: 'row', columnGap: 2, children: [
    picture(els, 'screen', shown && shown.frame, 'waiting for the first frame'),
    Box({
    flexDirection: 'column',
    width: SIDE,
    children: [
      status,
      when,
      Text({ children: [' '] }),
      Box({ flexDirection: 'row', columnGap: 2, flexWrap: 'wrap', children: [
        btn('back', 'back', 'b', () => key($, 'back')),
        btn('home', 'home', 'h', () => key($, 'home')),
        btn('recents', 'apps', 'r', () => key($, 'recents')),
        btn('up', 'scroll ↓', 'u', () => swipe($, 'up')),
        btn('down', 'scroll ↑', 'd', () => swipe($, 'down')),
      ] }),
      Box({ flexDirection: 'row', columnGap: 2, flexWrap: 'wrap', children: [
        btn('prev', '‹ back in time', 'j', step(-1)),
        btn('next', 'forward ›', 'k', step(1)),
        btn('live', 'live', 'l', () => { viewing = null; redraw() }),
        btn('pause', screen.paused ? 'resume' : 'pause', 'p', () => { screen.paused = !screen.paused; redraw() }),
        btn('pixels', pixels ? 'cells' : 'pixels', 'i', () => { pixels = !pixels; frames = []; viewing = null; redraw() }),
      ] }),
      Text({ children: [' '] }),
      Text({ dimColor: true, wrap: 'wrap', children: [calls.length ? 'Actions: press 1–6 to see the screen then' : 'No actions yet: Claude\'s taps and keys show up here.'] }),
      ...actions,
    ],
  }),
  ] })
}

// --- the report -------------------------------------------------------------------------------

function chapters() {
  const ev = (report && report.events) || []
  const ch = ev.filter((x) => x.kind === 'chapter')
  return ch.length ? ch : ev
}

async function loadChapterFrame($, i) {
  const ch = chapters()[i]
  if (!report || !ch || chapterFrames[i]) return
  chapterFrames[i] = 'loading'
  try {
    const want = request(2.2)
    const r = await mcp($, 'lab_view_report_frame', { report_id: report.id, t: ch.t, caption: ch.title || '', format: want.format, w: want.w, h: want.h })
    chapterFrames[i] = { format: r.format, w: r.w, h: r.h, data: r.data, columns: want.columns, rows: want.rows, source: r.source, generation: ++generation }
  } catch (err) {
    chapterFrames[i] = null
    reportError = String(err.message || err)
  }
  $.ui.invalidate('ui.render')
}

async function openReport($, id, focus) {
  await detectPixels($)
  reportError = ''
  chapter = 0
  chapterFrames = {}
  try {
    report = await mcp($, 'lab_view_report', { report_id: id || '', html: false })
  } catch (err) {
    report = null
    reportError = String(err.message || err)
  }
  const pane = { id: REPORT, title: 'Report', closeOnEscape: true, rows: PANE_ROWS }
  const placed = await $.ui.open(focus ? { ...pane, focus: true } : pane)
  if (report) loadChapterFrame($, 0)
  return placed
}

// The full report in the default web browser, opened by Testlab: a file:// link from the terminal
// goes to whichever app owns .html files (often Safari), not the browser the person chose.
async function openFullReport($) {
  try { await mcp($, 'lab_open_page', { page: 'report', id: report.id }) } catch (err) { reportError = String(err.message || err) }
  $.ui.invalidate('ui.render')
}

function fmt(t) {
  const s = Math.max(0, Math.floor(t))
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0')
}

function renderReport($, e) {
  const els = $.ui.resolve(e)
  const { Box, Text, Button, Markdown } = els
  fit = measure(e, 1, SIDE)
  const redraw = () => $.ui.invalidate('ui.render')
  if (!report) return Text({ color: 'red', wrap: 'wrap', children: [reportError || 'Loading…'] })
  const list = chapters()
  const go = (i) => () => { chapter = i; loadChapterFrame($, i); redraw() }
  const f = chapterFrames[chapter]
  const ch = list[chapter]
  const text = (report.markdown || '').replace(/!\[[^\]]*\]\([^)]*\)/g, '').trim()
  return Box({ flexDirection: 'row', columnGap: 2, children: [
    list.length
      ? picture(els, 'chapter', f && f !== 'loading' ? f : null, f === 'loading' ? 'loading the frame…' : 'no picture')
      : Text({ dimColor: true, children: ['No chapters.'] }),
    Box({
    flexDirection: 'column',
    width: SIDE,
    children: [
      Text({ bold: true, wrap: 'wrap', children: [report.title] }),
      report.page ? Button({ key: 'rfull', label: 'Open the full report in your browser (video, screenshots)', hotkey: 'o', plain: true, onPress: () => { openFullReport($) } }) : Text({ children: [' '] }),
      reportError ? Text({ color: 'red', wrap: 'wrap', children: [reportError] }) : Text({ children: [' '] }),
      ch ? Text({ wrap: 'wrap', children: [fmt(ch.t) + '  ' + (ch.text || ch.title || '')] }) : Text({ children: [' '] }),
      Box({ flexDirection: 'row', columnGap: 2, children: [
        Button({ key: 'rprev', label: '‹ previous', hotkey: 'j', plain: true, onPress: go(Math.max(0, chapter - 1)) }),
        Button({ key: 'rnext', label: 'next ›', hotkey: 'k', plain: true, onPress: go(Math.min(list.length - 1, chapter + 1)) }),
      ] }),
      ...list.slice(0, 9).map((c, i) => Button({ key: 'ch-' + i, label: fmt(c.t) + ' ' + (c.title || c.text || 'chapter ' + (i + 1)), hotkey: String(i + 1), plain: true, dimColor: i !== chapter, onPress: go(i) })),
      text ? Markdown({ text }) : Text({ children: [' '] }),
    ],
  }),
  ] })
}

// --- hooks ------------------------------------------------------------------------------------

export function register(on) {
  on('session.start', async ($, e, next) => {
    await $.command.register({ name: 'testlab-screen', description: "Show the phone's screen live: keys, swipes, rewind through what was done", argumentHint: '[serial]', immediate: true })
    await $.command.register({ name: 'testlab-report', description: 'Show a test report chapter by chapter (newest when no id)', argumentHint: '[report id]', immediate: true })
    return next(e)
  })

  on('command.run', { command: 'testlab-screen' }, async ($, e) => {
    await openScreen($, (e.args || '').trim() || null, true)
    return {}
  })

  on('command.run', { command: 'testlab-report' }, async ($, e) => {
    await openReport($, (e.args || '').trim(), true)
    return {}
  })

  // When Claude asks for the views (the MCP Apps tools), open the panes here too.
  on('tool.call', async ($, e, next) => {
    const name = String(e.tool || '')
    try {
      if (name.endsWith('__handsets_show_screen')) {
        const placed = await openScreen($, e.serial || null, false)
        if (placed && placed.isPlaced === false) $.ui.toast('Testlab: /testlab-screen shows the phone')
      } else if (name.endsWith('__lab_show_report')) {
        const placed = await openReport($, e.report_id || '', false)
        if (placed && placed.isPlaced === false) $.ui.toast('Testlab: /testlab-report shows the report')
      }
    } catch { /* a pane that can't open never holds up the tool */ }
    return next(e)
  })

  // The panes' own calls (frames, activity, the report, their keys) run without a prompt each time:
  // only when this plugin makes them, and only these tools.
  on('tool.check', async ($, e, next) => {
    const m = /^mcp__plugin_testlab_testlab__(.+)$/.exec(String(e.tool || ''))
    if (m && PANE_TOOLS.has(m[1]) && next.origin && next.origin.plugin === 'testlab') return { decision: 'allow' }
    return next(e)
  })

  on('ui.close', async ($, e, next) => {
    try {
      if (e.id === SCREEN) closeScreen()
      if (e.id === REPORT) { report = null; chapterFrames = {} }
    } catch { /* closing always goes ahead */ }
    return next(e)
  })

  on('ui.render', { component: 'Pane' }, async ($, e, next) => {
    if (e.requestId === SCREEN) return renderScreen($, e)
    if (e.requestId === REPORT) return renderReport($, e)
    return next(e)
  })
}
