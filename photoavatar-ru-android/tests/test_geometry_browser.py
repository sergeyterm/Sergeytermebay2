#!/usr/bin/env python3
"""Real local MediaPipe inference and application geometry controls; no provider calls."""
from pathlib import Path
from playwright.sync_api import sync_playwright
import mimetypes
import base64

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
FIXTURE=ROOT/'app/src/androidTest/assets/portrait.jpg'
BASE='https://appassets.androidplatform.net/assets/'
def run():
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,args=['--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader'])
        page=browser.new_page(viewport={'width':390,'height':844})
        errors=[]
        page.on('pageerror',lambda err:errors.append(str(err)))
        def route(r):
            if not r.request.url.startswith(BASE):return r.abort()
            path=(ASSETS/r.request.url[len(BASE):].split('?',1)[0]).resolve()
            if ASSETS.resolve() not in path.parents or not path.is_file():return r.abort()
            mime='application/wasm' if path.suffix=='.wasm' else 'application/octet-stream' if path.suffix=='.task' else mimetypes.guess_type(path.name)[0] or 'application/octet-stream'
            r.fulfill(status=200,path=path,content_type=mime)
        page.route('**/*',route)
        page.add_init_script("""window.PhotoAvatarNative={request(id,action,raw){
            let data={};
            if(action==='state')data={embeddedKey:false,manualKey:false};
            if(action==='qualityState')data={codeformerConfigured:true};
            if(action==='detectFace')data={detected:false,facesCount:0};
            if(action==='enhanceCodeFormer')data={photo:JSON.parse(raw).photo};
            setTimeout(()=>window.__nativeReply(id,{ok:true,data}),10);
        }};""")
        page.goto(BASE+'index.html',wait_until='load')
        page.locator('#photo').set_input_files({'name':'portrait.jpg','mimeType':'image/jpeg','buffer':FIXTURE.read_bytes()})
        page.wait_for_function("!document.querySelector('#photo').disabled && document.querySelector('#preparedPreview').src.startsWith('data:image/jpeg')",timeout=20000)
        page.locator('#geometryDetails').evaluate('el=>el.open=true')
        page.locator('#geometryAnalyze').click()
        try:
            page.wait_for_function("document.querySelector('#geometryStatus').textContent.includes('✓')",timeout=60000)
        except Exception:
            raise AssertionError('MediaPipe failed: '+page.locator('#geometryStatus').inner_text()+' / '+repr(errors))
        assert '478 точек' in page.locator('#geometryMetrics').inner_text()
        assert '52 коэффициентов' in page.locator('#geometryMetrics').inner_text()
        assert page.locator('#geometryCanvas').is_visible()
        page.locator('#geometryFullMesh').uncheck()
        page.locator('#geometryFullMesh').check()
        page.locator('#geometryCompare').click()
        page.wait_for_function("document.querySelector('#geometryCandidates .geometry-row button')",timeout=30000)
        assert '/100' in page.locator('#geometryCandidates').inner_text()
        page.locator('#proQualityDetails').evaluate('el=>el.open=true')
        page.locator('#codeFormerDetails').evaluate('el=>el.open=true')
        page.locator('#codeformerConsent').check()
        page.locator('#runCodeFormer').click()
        page.wait_for_function("document.querySelector('#codeformerState').textContent.includes('Реставрация проверена')",timeout=60000)
        page.wait_for_function("document.querySelectorAll('#variantsGrid .quality-item').length===3",timeout=30000)
        assert not page.locator('#acceptRestoration').is_visible()
        page.locator('#geometryAnalyze').click()
        page.wait_for_function("document.querySelector('#geometryStatus').textContent.includes('✓')",timeout=30000)
        page.locator('#geometryAlign').click()
        assert 'Выравнивание применено' in page.locator('#geometryStatus').inner_text()
        assert not errors,repr(errors)
        print('PASS: real offline MediaPipe model, 478 landmarks, 52 blendshapes, mesh toggles, multi-photo geometry, unchanged restoration guard, alignment')
        browser.close()
if __name__=='__main__':run()
