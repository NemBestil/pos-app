import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.nembestil.pos3.app',
  appName: 'NemBestil POS',
  webDir: '.output/public',
  appendUserAgent: 'NemBestil/POS',
  server: {
    allowNavigation: [
      '*.pos3.nemkasse.com',
      '*.nemkasse.com'
    ]
  },
  plugins: {
    CapacitorHttp: {
      enabled: true
    }
  }
}

export default config
