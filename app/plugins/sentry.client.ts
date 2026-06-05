import { Capacitor } from '@capacitor/core'
import * as Sentry from '@sentry/capacitor'
import { init as sentryVueInit } from '@sentry/vue'
import packageJson from '../../package.json'

const sentryDsn = 'https://534af6751847b8e11bc0cffcd157440c@sentry.nembestil.dev/2'
const appRelease = `nembestil-pos3-app@${packageJson.version}`

export default defineNuxtPlugin((nuxtApp) => {
  const platform = Capacitor.getPlatform()

  Sentry.init({
    dsn: sentryDsn,
    release: appRelease,
    environment: import.meta.dev ? 'development' : 'production',
    attachStacktrace: true,
    enableAutoSessionTracking: true,
    enableNative: Capacitor.isNativePlatform(),
    enableNativeCrashHandling: true,
    beforeBreadcrumb: (breadcrumb) => {
      if (breadcrumb.data?.url && typeof breadcrumb.data.url === 'string') {
        breadcrumb.data.url = stripUrlDetails(breadcrumb.data.url)
      }

      return breadcrumb
    },
    siblingOptions: {
      vueOptions: {
        app: nuxtApp.vueApp,
        attachErrorHandler: true,
        attachProps: false,
        tracingOptions: {
          trackComponents: false
        }
      }
    }
  }, sentryVueInit)

  Sentry.setTag('app.platform', platform)
  Sentry.setTag('app.native', String(Capacitor.isNativePlatform()))
  Sentry.setContext('app', {
    name: packageJson.name,
    version: packageJson.version,
    release: appRelease,
    platform,
    native: Capacitor.isNativePlatform()
  })
  Sentry.addBreadcrumb({
    category: 'app.lifecycle',
    message: 'Shell started',
    level: 'info',
    data: {
      platform,
      native: Capacitor.isNativePlatform()
    }
  })
})

function stripUrlDetails(url: string) {
  try {
    const parsedUrl = new URL(url)

    return `${parsedUrl.origin}${parsedUrl.pathname}`
  } catch {
    return url
  }
}
