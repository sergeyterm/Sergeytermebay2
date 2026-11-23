#!/usr/bin/env python3
"""Offline source safety and integration-contract tests; live provider API is not simulated."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main'
JAVA = SRC / 'java/ru/photoavatarru/app'
MAIN = (JAVA / 'MainActivity.java').read_text(encoding='utf-8')
DID = (JAVA / 'DIdApi.java').read_text(encoding='utf-8')
ELEVEN = (JAVA / 'ElevenLabsApi.java').read_text(encoding='utf-8')
HTML = (SRC / 'assets/agent.html').read_text(encoding='utf-8')

class AndroidProjectContract(unittest.TestCase):
    def test_manifest_permissions(self):
        root = ET.parse(SRC / 'AndroidManifest.xml').getroot()
        ns = '{http://schemas.android.com/apk/res/android}'
        names = {x.attrib.get(ns+'name') for x in root.findall('uses-permission')}
        self.assertIn('android.permission.INTERNET', names)
        self.assertIn('android.permission.RECORD_AUDIO', names)
        self.assertIn('android.permission.CAMERA', names)
        self.assertNotIn('android.permission.READ_EXTERNAL_STORAGE', names)
        app = root.find('application')
        self.assertEqual('false', app.attrib.get(ns+'usesCleartextTraffic'))
        self.assertEqual('false', app.attrib.get(ns+'allowBackup'))
        activity = app.find('activity')
        self.assertEqual('true', activity.attrib.get(ns+'resizeableActivity'))

    def test_one_photo_crop_not_fake_multiview(self):
        self.assertIn('MAX_PHOTOS = 1', MAIN)
        self.assertIn('selectedPhotoTile', MAIN)
        self.assertIn('voice_reference_uri', MAIN)
        self.assertIn('photoJpeg(chosen, tileForAvatar)', MAIN)
        self.assertIn('selectedCell / 3', MAIN)
        self.assertIn('selectedCell % 3', MAIN)
        self.assertIn('takePersistableUriPermission', MAIN)
        self.assertIn('Intent.ACTION_OPEN_DOCUMENT', MAIN)
        self.assertIn('"source_url", photoUrl', DID)
        self.assertNotIn('"reference_photos"', DID)

    def test_supported_api_endpoints(self):
        for term in ('"/images"', '"/agents"', '"/agents/client-key"',
                     '"/agents/" + agentId', '"status"', 'ru-RU-SvetlanaNeural',
                     'ru-RU-DmitryNeural', 'Всегда говори по-русски'):
            self.assertIn(term, MAIN + DID)
        self.assertIn('"https://api.elevenlabs.io/v1/voices/add"', ELEVEN)
        self.assertIn('name=\\"files[]\\"', ELEVEN)
        self.assertIn('"eleven_flash_v2_5"', DID)
        self.assertIn('"x-api-key-external"', DID)
        self.assertIn('"elevenlabs"', DID)

    def test_secure_webview_and_bounded_input(self):
        self.assertIn('https://appassets.androidplatform.net/', MAIN)
        self.assertIn('https://appassets.androidplatform.net', DID)
        self.assertIn('setAllowFileAccess(false)', MAIN)
        self.assertIn('setAllowContentAccess(false)', MAIN)
        self.assertIn('PermissionRequest.RESOURCE_AUDIO_CAPTURE', MAIN)
        self.assertIn('req.deny()', MAIN)
        self.assertIn('"https".equalsIgnoreCase', MAIN)
        self.assertIn('"__AGENT_ID__"', MAIN)
        self.assertIn('"__CLIENT_KEY__"', MAIN)
        self.assertIn('DID_AGENTS_API', HTML)
        self.assertIn('toggleMicState', MAIN)
        self.assertIn('toggleSpeakerState', MAIN)
        self.assertIn('data-auto-connect="true"', HTML)
        self.assertIn('consent.isChecked()', MAIN)

    def test_no_provider_secrets_in_files(self):
        for file in ROOT.rglob('*'):
            if file.is_file() and file.suffix in {'.java','.gradle','.html','.xml','.md','.yml','.py'}:
                content=file.read_text(encoding='utf-8')
                self.assertNotRegex(content, r'nk_[A-Za-z0-9_-]{20,}', file.as_posix())
                self.assertNotRegex(content, r'sk_[A-Za-z0-9_-]{20,}', file.as_posix())

    def test_gradle_settings(self):
        build = (ROOT / 'app/build.gradle').read_text()
        min_sdk = int(re.search(r'minSdk\s+(\d+)',build).group(1))
        self.assertLessEqual(min_sdk,37)
        self.assertIn('targetSdk 35',build)
        self.assertIn('compileSdk 35',build)
        self.assertIn("versionName '1.6.1'",build)

    def test_all_3x3_tile_coordinates(self):
        for w,h in [(900,900),(1201,1811),(1001,2000)]:
            rectangles=[]
            for index in range(9):
                row,col=divmod(index,3)
                x,y=col*w//3,row*h//3
                right,bottom=(col+1)*w//3,(row+1)*h//3
                rectangles.append((x,y,right,bottom))
                self.assertLess(x,right)
                self.assertLess(y,bottom)
            covered=sum((r-l)*(b-t) for l,t,r,b in rectangles)
            self.assertEqual(covered,w*h)
            for i,a in enumerate(rectangles):
                for b in rectangles[i+1:]:
                    intersection=max(0,min(a[2],b[2])-max(a[0],b[0]))*max(0,min(a[3],b[3])-max(a[1],b[1]))
                    self.assertEqual(intersection,0)

if __name__ == '__main__':
    unittest.main(verbosity=2)
