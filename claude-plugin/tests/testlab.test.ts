import { expect, mock, test } from 'claude-code/testing'

// A 4×4 picture, raw RGB: red on top, blue below.
const RGB = (() => {
  const px = new Uint8Array(4 * 4 * 3)
  for (let i = 0; i < 16; i++) px.set(i < 8 ? [255, 0, 0] : [0, 0, 255], i * 3)
  return px.toBase64()
})()

const PANE = (id: string) => ({
  plugin: 'testlab',
  component: 'Pane',
  requestId: id,
  surface: 'terminal',
  viewport: { columns: 160, rows: 50 },
  props: { title: 'x', isFocused: true, bodyColumns: 40, placement: 'dock', scroll: { offset: 0, bodyRows: 48 }, view: {} },
}) as const

const text = (r: unknown) => ({ value: { content: [{ type: 'text', text: JSON.stringify(r) }], isError: false } })

function server(on: any, calls: { tool: string; args: any; server: string }[], now: () => number) {
  on('mcp.connect', () => ({ value: { isConnected: true, server: 'plugin:testlab:testlab' } }))
  on('fs.read', () => ({ deny: 'no such file' })) // no window running: MCP
  on('mcp.call', ($: any, e: any) => {
    calls.push({ tool: e.tool, args: e.args, server: e.server })
    if (e.server !== 'plugin:testlab:testlab') return { deny: 'no such server' }
    switch (e.tool) {
      case 'handsets_view_frame':
        return text({ serial: 'emulator-5554', width: 1080, height: 2400, format: e.args.format, w: 4, h: 4, data: e.args.format === 'file' ? '/tmp/frames/emulator-5554-0.png' : RGB })
      case 'handsets_view_activity':
        return text({ calls: [{ at: now() - 100, tool: 'handsets_tap_xy', summary: 'x=1 y=2', ok: true, ms: 80 }] })
      case 'lab_view_report':
        if (e.args.html !== false) return text({ html: 'x'.repeat(100000) }) // the pane never asks for the page's HTML
        return text({ id: 'r1', title: 'Checkout', page: 'file:/tmp/r1/index.html', markdown: 'All fine.\n\n![Cart](01-cart.jpg)', events: [{ t: 0, kind: 'chapter', title: 'Open the cart', text: 'Open the cart' }, { t: 4.5, kind: 'chapter', title: 'Pay', text: 'Pay' }] })
      case 'lab_view_report_frame':
        return text({ source: 'video', t: e.args.t, format: e.args.format, w: 4, h: 4, data: RGB })
      default:
        return text({ text: 'ok' })
    }
  })
}

test('/testlab-screen opens a pane that polls frames, draws cells and rewinds to an action', async ($, on) => {
  const clock = mock.clock(on, { now: 100_000 })
  mock.env(on, { TERM: 'xterm-256color', TERM_PROGRAM: 'Apple_Terminal' })
  const calls: { tool: string; args: any; server: string }[] = []
  server(on, calls, () => clock.now())
  const opened: any[] = []
  on('ui.open', ($, e) => { opened.push(e); return { value: { isPlaced: true } } })
  on('command.register', () => ({ value: undefined }))
  on('session.start', () => ({ cwd: '/work' }))

  await $.session.start({ surface: 'terminal', isInteractive: true, cwd: '/work' })
  await $.command.run({ command: 'testlab-screen', args: '' })
  expect(opened[0].id).toBe('testlab-screen')
  await clock.advance(1000)
  await clock.advance(2000)

  const frames = calls.filter((c) => c.tool === 'handsets_view_frame')
  expect(frames.length > 2).toBe(true)
  expect(frames[0].args.format).toBe('rgb') // no graphics protocol: half-block cells
  expect(calls.every((c) => c.server === 'plugin:testlab:testlab')).toBe(true) // the name $.mcp.connect gave

  const ui = await $.ui.mount(PANE('testlab-screen'))
  const raster = await ui.find({ key: 'screen' })
  expect(raster?.type).toBe('Raster')
  expect(raster?.props.columns).toBe(4)
  expect(raster?.props.rows).toBe(2)
  expect(await ui.find({ type: 'Text', text: /● live/ })).toBeDefined()

  await ui.press({ key: 'back' })
  expect(calls.at(-1)?.tool === 'handsets_navigate' || calls.some((c) => c.tool === 'handsets_navigate' && c.args.target === 'back')).toBe(true)

  const act = calls.length // the action button for the tap rewinds
  await ui.press({ key: 'prev' })
  expect(await ui.find({ type: 'Text', text: /ago/ })).toBeDefined()
  await ui.press({ key: 'live' })
  expect(await ui.find({ type: 'Text', text: /● live/ })).toBeDefined()
  expect(calls.length >= act).toBe(true)
  await ui.unmount()
})

test('pixels in kitty: PNG files the terminal reads, in an Image', async ($, on) => {
  const clock = mock.clock(on, { now: 5_000 })
  mock.env(on, { TERM: 'xterm-kitty' })
  const calls: { tool: string; args: any; server: string }[] = []
  server(on, calls, () => clock.now())
  on('ui.open', () => ({ value: { isPlaced: true } }))
  await $.command.run({ command: 'testlab-screen', args: 'emulator-5554' })
  await clock.advance(600)
  const f = calls.find((c) => c.tool === 'handsets_view_frame')
  expect(f?.args.format).toBe('file')
  expect(f?.args.serial).toBe('emulator-5554')
  const ui = await $.ui.mount(PANE('testlab-screen'))
  const img = await ui.find({ key: 'screen' })
  expect(img?.type).toBe('Image')
  expect(img?.props.source.file).toBe('/tmp/frames/emulator-5554-0.png')
  await ui.unmount()
})

test('/testlab-report shows chapters, a frame per chapter, and opens the page in the browser', async ($, on) => {
  mock.clock(on)
  mock.env(on, {})
  const calls: { tool: string; args: any; server: string }[] = []
  server(on, calls, () => 0)
  on('ui.open', () => ({ value: { isPlaced: true } }))
  await $.command.run({ command: 'testlab-report', args: '' })
  const ui = await $.ui.mount(PANE('testlab-report'))
  expect(await ui.find({ type: 'Text', text: 'Checkout' })).toBeDefined()
  await ui.press({ key: 'rfull' })
  const opened = calls.filter((c) => c.tool === 'lab_open_page').at(-1)
  expect(opened?.args).toEqual({ page: 'report', id: 'r1' })
  expect(await ui.find({ key: 'chapter' })).toBeDefined()
  await ui.press({ key: 'ch-1' })
  const frame = calls.filter((c) => c.tool === 'lab_view_report_frame').at(-1)
  expect(frame?.args.t).toBe(4.5)
  expect(frame?.args.caption).toBe('Pay')
  expect(await ui.find({ type: 'Text', text: /0:04\s+Pay/ })).toBeDefined()
  await ui.unmount()
})

test("Claude's handsets_show_screen opens the pane too, and the tool still runs", async ($, on) => {
  mock.clock(on)
  mock.env(on, {})
  const calls: { tool: string; args: any; server: string }[] = []
  server(on, calls, () => 0)
  const opened: any[] = []
  on('ui.open', ($, e) => { opened.push(e); return { value: { isPlaced: true } } })
  on('tool.call', () => ({ result: 'shown' }))
  const out = await $.tool.call({ tool: 'mcp__plugin_testlab_testlab__handsets_show_screen' })
  expect(out).toEqual({ result: 'shown' })
  expect(opened[0]?.id).toBe('testlab-screen')
  expect(opened[0]?.focus).toBeUndefined() // not asked for: it doesn't take the keyboard
})

test('an older server: the pane says what to do instead of a raw error', async ($, on) => {
  const clock = mock.clock(on)
  mock.env(on, {})
  on('mcp.connect', () => ({ value: { isConnected: true, server: 'plugin:testlab:testlab' } }))
  on('fs.read', () => ({ deny: 'no such file' })) // no window running: MCP
  on('mcp.call', () => ({ deny: 'no connected MCP tool "handsets_view_frame" on a server named "plugin:testlab:testlab"' }))
  on('ui.open', () => ({ value: { isPlaced: true } }))
  await $.command.run({ command: 'testlab-screen', args: '' })
  await clock.advance(600)
  const ui = await $.ui.mount(PANE('testlab-screen'))
  expect(await ui.find({ type: 'Text', text: /older than this pane/ })).toBeDefined()
  await ui.unmount()
})

test('with the window running, the panes call its control API (no permission prompts)', async ($, on) => {
  const clock = mock.clock(on)
  mock.env(on, { HOME: '/Users/sam', TERM: 'xterm' })
  const posted: any[] = []
  on('fs.read', ($, e) => e.path === '/Users/sam/Library/Application Support/Testlab/control.json'
    ? { value: JSON.stringify({ api: 'http://127.0.0.1:8378/api/v1', token: 'tok', version: '0.12.2' }) }
    : { deny: 'no such file' })
  on('http.fetch', ($, e) => {
    posted.push(e)
    const b = JSON.parse(e.init.body)
    const r = b.tool === 'handsets_view_frame'
      ? { serial: 'R5CT', width: 1080, height: 2400, format: 'rgb', w: 4, h: 4, data: RGB }
      : { calls: [] }
    return { value: { status: 200, ok: true, headers: {}, text: JSON.stringify({ isError: false, content: [{ type: 'text', text: JSON.stringify(r) }] }) } }
  })
  on('mcp.call', () => ({ deny: 'the pane should not call MCP when the control API answers' }))
  on('ui.open', () => ({ value: { isPlaced: true } }))
  await $.command.run({ command: 'testlab-screen', args: '' })
  await clock.advance(600)
  expect(posted[0].url).toBe('http://127.0.0.1:8378/api/v1/view/call')
  expect(posted[0].init.headers.authorization).toBe('Bearer tok')
  const ui = await $.ui.mount(PANE('testlab-screen'))
  expect((await ui.find({ key: 'screen' }))?.type).toBe('Raster')
  expect(await ui.find({ type: 'Text', text: /R5CT/ })).toBeDefined()
  await ui.unmount()
})

test('in a client space (TESTLAB_SPACE), the panes use that space\'s server, not the default one', async ($, on) => {
  const clock = mock.clock(on)
  mock.env(on, { HOME: '/Users/sam', TERM: 'xterm', TESTLAB_SPACE: 'acme' })
  const posted: any[] = []
  on('fs.read', ($, e) => e.path === '/Users/sam/Library/Application Support/Testlab/spaces/acme/control.json'
    ? { value: JSON.stringify({ api: 'http://127.0.0.1:8379/api/v1', token: 'acme-tok', version: '0.16.0' }) }
    : e.path.endsWith('Testlab/control.json')
      ? { value: JSON.stringify({ api: 'http://127.0.0.1:8378/api/v1', token: 'default-tok', version: '0.16.0' }) }
      : { deny: 'no such file' })
  on('http.fetch', ($, e) => {
    posted.push(e)
    return { value: { status: 200, ok: true, headers: {}, text: JSON.stringify({ isError: false, content: [{ type: 'text', text: JSON.stringify({ calls: [] }) }] }) } }
  })
  on('mcp.call', () => ({ deny: 'no MCP here' }))
  on('ui.open', () => ({ value: { isPlaced: true } }))
  await $.command.run({ command: 'testlab-screen', args: '' })
  await clock.advance(600)
  expect(posted.length).toBeGreaterThan(0)
  expect(posted.every((p) => p.url.startsWith('http://127.0.0.1:8379/') && p.init.headers.authorization === 'Bearer acme-tok')).toBe(true)
})

test('an older window without /view/call: the pane says so when MCP is refused too', async ($, on) => {
  const clock = mock.clock(on)
  mock.env(on, { HOME: '/Users/sam' })
  on('fs.read', ($, e) => e.path.endsWith('Library/Application Support/Testlab/control.json')
    ? { value: JSON.stringify({ api: 'http://127.0.0.1:8378/api/v1', token: 'tok', version: '0.12.1' }) }
    : { deny: 'ENOENT: no such file' })
  on('http.fetch', () => ({ value: { status: 404, ok: false, headers: {}, text: '{"error":{"message":"not found"}}' } }))
  on('mcp.connect', () => ({ value: { isConnected: true, server: 'plugin:testlab:testlab' } }))
  on('mcp.call', () => ({ deny: 'refused: The server-side auto mode classifier gave no verdict' }))
  on('ui.open', () => ({ value: { isPlaced: true } }))
  await $.command.run({ command: 'testlab-screen', args: '' })
  await clock.advance(600)
  const ui = await $.ui.mount(PANE('testlab-screen'))
  expect(await ui.find({ type: 'Text', text: /\(0\.12\.1\) has no \/view\/call/ })).toBeDefined()
  await ui.unmount()
})
