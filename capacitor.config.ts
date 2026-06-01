import type { CapacitorConfig } from '@capacitor/cli'
import pkg from './package.json' with { type: 'json' }

const config: CapacitorConfig = {
  appId: 'com.nembestil.pos3.app',
  appName: 'NemBestil POS',
  webDir: '.output/public',
  appendUserAgent: `NemBestil/POS/${pkg.version}`,
  server: {
    allowNavigation: [
      '*.pos3.nemkasse.com',
      '*.nemkasse.com',
      'nbpos3.ngrok.dev',
    ]
  },
  plugins: {
    CapacitorHttp: {
      enabled: true
    }
  }
}

export default config
