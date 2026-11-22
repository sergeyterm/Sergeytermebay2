#!/usr/bin/env python3
"""Optional Chromium smoke test. Mocks D-ID; does not test an actual videoconference."""
from pathlib import Path
from playwright.sync_api import sync_playwright

asset = (Path(__file__).resolve().parents[1] / 'app/src/main/assets/agent.html')
raw = asset.read_text(encoding='utf-8').replace('__AGENT_ID__','agt_smoke')\
    .replace('__CLIENT_KEY__','cli_smoke')

with sync_playwright() as pw:
    browser = pw.chromium.launch(executable_path='/usr/bin/chromium', args=['--no-sandbox'])
    for width, height in [(490,1142), (900,820)]:
        page = browser.new_page(viewport={'width':width,'height':height})
        page.route('https://agent.d-id.com/**',lambda r: r.abort())
        page.add_init_script('''
            window.__calls = {};
            window.DID_AGENTS_API = {
              configure(opts) {window.__calls.configure = opts},
              functions: {
                toggleMicState(){window.__calls.mic = true; return Promise.resolve()},
                toggleSpeakerState(){window.__calls.speaker = true; return Promise.resolve()}
              },
              events: {on(name,fn) {window.__calls[name] = fn;return () => {} }}
            };
        ''')
        page.goto('about:blank')
        page.set_content(raw, wait_until='domcontentloaded')
        page.wait_for_function("typeof window.__calls.connection === 'function'", timeout=8000)
        page.evaluate("window.__calls.connection({state:'connected'})")
        assert page.locator('#agent-status').inner_text() == 'На связи'
        assert page.evaluate("window.__avatarConnectionState") == 'connected'
        page.evaluate("window.__calls.connection({state:'fail'})")
        assert page.locator('#agent-status').inner_text() == 'Ошибка соединения'
        page.evaluate("window.__calls.error({error:{code:'NETWORK_ERROR'}})")
        assert 'Ошибка видеосвязи' in page.locator('#agent-status').inner_text()
        assert page.evaluate("window.__calls.configure.showMicToggle") is False
        assert page.locator('#agent-container').count()==1
        print(f'PASS browser mock: {width}x{height}, status/events/controls')
        page.close()
    browser.close()

