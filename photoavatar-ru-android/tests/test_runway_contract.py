#!/usr/bin/env python3
"""Runway Characters integration contracts. No real API calls or secrets."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT/'app/src/main'
JAVA = MAIN/'java/ru/photoavatarru/app'
RUNWAY = (JAVA/'RunwayApi.java').read_text()
ACTIVITY = (JAVA/'RunwayActivity.java').read_text()
HTML = (MAIN/'assets/index.html').read_text()
CLIENT = (ROOT/'frontend/main.js').read_text()

class RunwayContracts(unittest.TestCase):
    def test_launcher_is_runway(self):
        ns = '{http://schemas.android.com/apk/res/android}'
        app = ET.parse(MAIN/'AndroidManifest.xml').getroot().find('application')
        activities = app.findall('activity')
        self.assertEqual('.RunwayActivity', activities[0].attrib.get(ns+'name'))
        self.assertEqual('true', activities[0].attrib.get(ns+'exported'))
        self.assertEqual('false', app.attrib.get(ns+'allowBackup'))

    def test_provider_protocol(self):
        for word in ['https://api.dev.runwayml.com','2024-11-06',
                     '"/v1/voices"','"/v1/avatars"','"/v1/realtime_sessions"',
                     '"sessionKey"',
                     '"gwm1_avatars"','"custom"','"runway-live-preset"',
                     '"referenceImage"','"personality"','"startScript"',
                     'thread']: # no expected thread literal, verify separately
            if word != 'thread': self.assertIn(word,RUNWAY)
        self.assertIn('Bearer ',RUNWAY)
        self.assertIn('Thread.sleep(1000)',RUNWAY)
        self.assertIn('Thread.sleep(2000)',RUNWAY)
        self.assertNotIn('"/consume"', RUNWAY)
        self.assertIn('.put("sessionKey", sessionKey)', RUNWAY)

    def test_client_browser_and_controls(self):
        self.assertIn("from '@runwayml/avatars'", CLIENT)
        self.assertIn('video:false', CLIENT)
        self.assertIn('session.attachAudio',CLIENT)
        self.assertIn('AvatarEvent.LocalVideoReady',CLIENT)
        self.assertIn('id="avatarVideo" autoplay muted playsinline',HTML)
        for token in ['streamTo({credentials:', 'AvatarEvent.Transcript',
                      'currentCall.mic.toggle()', 'oldCall.end()',
                      'tile%3','Math.floor(tile/3)','toDataURL',
                      "'startSession'","'createAvatar'","'createVoice'"]:
            self.assertIn(token,CLIENT)
        for id in ['photo','tiles','topic','persona','voiceFile','voiceMode',
                   'status','callScreen','avatarVideo','hangup','mute','speaker',
                   'startCall','callName','connecting']:
            self.assertIn(f'id="{id}"',HTML)

    def test_private_key_not_in_webview(self):
        self.assertIn('getAssets().open("runway.key")',RUNWAY)
        self.assertNotIn('apiKey',CLIENT)
        self.assertNotIn('RUNWAYML_API_SECRET',CLIENT+HTML)
        self.assertIn('appassets.androidplatform.net',ACTIVITY)
        self.assertIn('setAllowFileAccess(false)',ACTIVITY)
        self.assertIn('setAllowContentAccess(true)',ACTIVITY) # scoped to user-picked files
        self.assertIn('PermissionRequest.RESOURCE_AUDIO_CAPTURE',ACTIVITY)
        self.assertIn('request.deny()',ACTIVITY)
        self.assertIn('Manifest.permission.CAMERA',ACTIVITY)
        self.assertIn('PermissionRequest.RESOURCE_VIDEO_CAPTURE',ACTIVITY)
        self.assertIn('case "cancelSession":',ACTIVITY)
        self.assertIn('request("DELETE"',RUNWAY)
        self.assertIn('new RunwayApi(RunwayActivity.this)',ACTIVITY)

    def test_no_secret_in_public_repo(self):
        for path in ROOT.rglob('*'):
            if path.is_file() and path.suffix.lower() in {'.java','.js','.html','.json','.xml','.gradle','.py','.md','.yml','.key'}:
                if 'node_modules' in path.parts: continue
                data=path.read_text(errors='ignore')
                self.assertNotRegex(data, r'key_[0-9a-fA-F]{128}',str(path))
        self.assertIn('INSERT_PRIVATE_KEY_IN_SIGNING_STEP_ONLY',
                      (MAIN/'assets/runway.key').read_text())

    def test_android_target(self):
        data=(ROOT/'app/build.gradle').read_text()
        self.assertRegex(data,r'compileSdk\s+35')
        self.assertRegex(data,r'minSdk\s+26')
        self.assertIn("versionName '1.4.0'",data)

if __name__=='__main__':
    unittest.main()
