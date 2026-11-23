#!/usr/bin/env python3
"""Offline Playwright checks of the Android WebView HTML / real bundled Runway client.
All Runway native actions are intercepted. No remote API or paid calls.
"""
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
BASE = "https://appassets.androidplatform.net/assets/"
SAMPLE_ID = "11111111-1111-4111-8111-111111111111"

def run_checks():
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, args=["--no-sandbox"])
        for width, height in [(390, 844), (720, 740)]:
            context = browser.new_context(viewport={"width": width, "height": height})
            page = context.new_page()
            errors = []
            page.on("pageerror", lambda err: errors.append(str(err)))
            def intercept(route):
                filename = route.request.url.rsplit("/",1)[-1].split("?")[0]
                if filename in ("index.html","bundle.js"):
                    data=(ASSETS / filename).read_bytes()
                    mime="text/html" if filename=="index.html" else "application/javascript"
                    route.fulfill(status=200,body=data,content_type=mime)
                else:
                    route.abort()
            page.route(BASE+"**",intercept)
            page.add_init_script("""(() => {
                localStorage.setItem('runway_avatar_id', '"""+SAMPLE_ID+"""');
                window.PhotoAvatarNative = {
                    request(id, action) {
                        let answer;
                        if (action === 'state') answer={ok:true,data:{embeddedKey:true,manualKey:false}};
                        else if (action === 'startSession') answer={ok:false,error:'Runway HTTP 402: Недостаточно кредитов (тест)'};
                        else if (action === 'cancelSession') answer={ok:true,data:{cancelled:true}};
                        else answer={ok:true,data:{}};
                        setTimeout(()=>window.__nativeReply(id,answer),120);
                    }
                };
            })()""")
            page.goto(BASE+"index.html",wait_until="load")
            page.locator("#readyCard").wait_for(state="visible")
            page.locator("#consent").check()
            page.locator("#startCall").click()
            page.locator("#callScreen").wait_for(state="visible")
            for control in ["speaker","selfCamera","mute","hangup"]:
                assert page.locator("#"+control).is_visible(), f"{control} invisible at {width}"
            assert page.locator("#selfTile").is_visible(), "self camera tile missing"
            assert page.locator("#avatarVideo").evaluate("(el) => el.tagName") == "VIDEO", "SDK requires HTMLVideoElement"
            assert page.locator("#callName").inner_text().strip(), "call name absent"
            assert page.locator("#callTime").inner_text() == "00:00", "call duration not initialized"
            page.locator("#callError").wait_for(state="visible",timeout=12000)
            assert "Недостаточно кредитов" in page.locator("#callErrorMessage").inner_text()
            assert page.locator("#callDiagnostics").is_visible()
            assert page.locator("#callErrorStage").inner_text().startswith("Этап:")
            assert page.locator("#callScreen").is_visible(), "Failure overlay should not disappear automatically"
            screenshot=ROOT / ("ui-whatsapp-call-error-%d.png" % width)
            page.screenshot(path=str(screenshot))
            page.locator("#callErrorClose").click()
            page.locator("#callScreen").wait_for(state="hidden")
            assert "Недостаточно кредитов" in page.locator("#status").inner_text(), "Error must persist on setup"
            assert not errors, "Uncaught client JS errors: "+repr(errors)
            print(f"PASS: messenger-style video call at {width}x{height}, controls, diagnostic overlay and persistent error")
            context.close()
        browser.close()

if __name__ == "__main__":
    run_checks()
