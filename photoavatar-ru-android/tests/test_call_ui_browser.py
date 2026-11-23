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
                window.__calls = [];
                window.__payloads = [];
                window.__avatarStates = ['PROCESSING','READY'];
                window.PhotoAvatarNative = {
                    request(id, action, raw) {
                        window.__calls.push(action);
                        window.__payloads.push({action, data:JSON.parse(raw||'{}')});
                        let answer;
                        if (action === 'state') answer={ok:true,data:{embeddedKey:true,manualKey:false}};
                        else if (action === 'avatarStatus') answer={ok:true,data:{status:window.__avatarStates.shift()||'READY', previewUri:''}};
                        else if (action === 'detectFace') answer={ok:true,data:{detected:true,facesCount:1,x:.24,y:.24,w:.52,h:.43}};
                        else if (action === 'updateAvatarImage') answer={ok:true,data:{updated:true,id:'"""+SAMPLE_ID+"""'}};
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
            # Regression: video is ready before the SDK's late AvatarVideoReady listener.
            # The player must react to its own playback events.
            page.evaluate("""() => {
                const v = document.querySelector('#avatarVideo');
                Object.defineProperties(v, {
                    srcObject: {configurable:true,get:()=>({getVideoTracks:()=>[{readyState:'live'}]})},
                    videoWidth: {configurable:true,get:()=>640},
                    videoHeight: {configurable:true,get:()=>480},
                    readyState: {configurable:true,get:()=>3}
                });
                v.dispatchEvent(new Event('playing'));
            }""")
            page.wait_for_function("getComputedStyle(document.querySelector('#avatarVideo')).opacity === '1'",timeout=4000)
            assert page.locator("#avatarVideo").evaluate("(v) => v.classList.contains('playing')"), "video must display despite missed SDK event"
            for control in ["speaker","selfCamera","mute","hangup"]:
                assert page.locator("#"+control).is_visible(), f"{control} invisible at {width}"
            assert page.locator("#selfTile").is_visible(), "self camera tile missing"
            assert page.locator("#callBackdrop").count()==1, "background placeholder must be a portrait layer"
            assert page.locator("#callBackdrop").evaluate("(el) => getComputedStyle(el).backgroundColor") != "rgba(0, 0, 0, 0)"
            assert page.locator("#avatarVideo").evaluate("(el) => getComputedStyle(el).opacity") == "0", "remote video must not cover portrait before track is available"
            assert page.locator("#avatarVideo").evaluate("(el) => el.tagName") == "VIDEO", "SDK requires HTMLVideoElement"
            assert page.locator("#callName").inner_text().strip(), "call name absent"
            assert page.locator("#callTime").inner_text() == "00:00", "call duration not initialized"
            page.locator("#callError").wait_for(state="visible",timeout=12000)
            assert page.locator("#avatarVideo").evaluate("(v) => !v.classList.contains('playing')"), "failure should hide stale video"
            assert "Недостаточно кредитов" in page.locator("#callErrorMessage").inner_text()
            calls=page.evaluate("window.__calls")
            assert calls.count("avatarStatus")>=2, "must poll until READY"
            assert calls.index("startSession")>calls.index("avatarStatus"), "must not create session before READY"
            assert not page.locator("#callDiagnostics").is_visible()
            page.locator("#callInfo").click()
            assert page.locator("#callDiagnostics").is_visible()
            page.locator("#callInfo").click()
            assert not page.locator("#callDiagnostics").is_visible()
            assert page.locator("#callErrorStage").inner_text().startswith("Этап:")
            assert page.locator("#callScreen").is_visible(), "Failure overlay should not disappear automatically"
            screenshot=ROOT / ("ui-whatsapp-call-error-%d.png" % width)
            page.screenshot(path=str(screenshot))
            page.locator("#callErrorClose").click()
            page.locator("#callScreen").wait_for(state="hidden")
            assert "Недостаточно кредитов" in page.locator("#status").inner_text(), "Error must persist on setup"
            page.locator("#resetAvatar").click()
            assert not page.locator("#readyCard").is_visible(), "Reset must hide ready card"
            page.locator("#reuseId").fill(SAMPLE_ID)
            page.locator("#reuseAvatar").click()
            assert page.locator("#readyCard").is_visible(), "Existing avatar must be restorable by ID"
            # Create a synthetic vertical phone portrait. No real user image is committed.
            import base64
            data_url=page.evaluate("""() => {
                const c=document.createElement('canvas');
                c.width=866;c.height=1536;
                const g=c.getContext('2d');
                g.fillStyle='#757a7a';g.fillRect(0,0,866,1536);
                g.fillStyle='#c5a38d';g.beginPath();g.ellipse(440,710,225,360,0,0,2*Math.PI);g.fill();
                g.fillStyle='#432d20';g.fillRect(160,1030,560,440);
                return c.toDataURL('image/jpeg',0.8);
            }""")
            page.locator("#photo").set_input_files({
                "name":"synthetic_portrait.jpg","mimeType":"image/jpeg",
                "buffer":base64.b64decode(data_url.split(",",1)[1])
            })
            page.wait_for_function("document.querySelector('#preparedPreview').src.startsWith('data:image/jpeg;base64,')",timeout=15000)
            page.wait_for_function("document.querySelector('#faceStatus').textContent.includes('Лицо найдено')",timeout=15000)
            assert page.locator("#photoTools").is_visible()
            assert page.locator("#preparedPreview").evaluate("(el)=>el.naturalWidth") == 1088
            assert page.locator("#preparedPreview").evaluate("(el)=>el.naturalHeight") == 704
            assert page.evaluate("window.__calls.includes('detectFace')"), "on-device face analysis must be invoked"
            page.locator("#portraitZoom").evaluate("(el) => {el.value='1.2';el.dispatchEvent(new Event('input',{bubbles:true}))}")
            page.locator("#processingMode").select_option("none")
            page.locator("#replaceAvatarConsent").check()
            page.locator("#replaceAvatarImage").click()
            page.wait_for_function("document.querySelector('#status').textContent.includes('Фото заменено')",timeout=16000)
            payload=page.evaluate("window.__payloads.filter(p=>p.action==='updateAvatarImage').at(-1).data")
            assert payload["avatarId"]==SAMPLE_ID
            assert payload["imageProcessing"]=="none"
            assert payload["photo"].startswith("data:image/jpeg;base64,")
            assert page.locator("#readyCard").is_visible(), "updating photo must preserve the avatar ID"
            assert not errors, "Uncaught client JS errors: "+repr(errors)
            print(f"PASS: messenger-style video call at {width}x{height}, controls, diagnostic overlay and persistent error")
            context.close()
        browser.close()

if __name__ == "__main__":
    run_checks()
