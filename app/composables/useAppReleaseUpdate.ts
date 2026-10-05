import { Capacitor, CapacitorHttp, registerPlugin } from '@capacitor/core'
import * as Sentry from '@sentry/capacitor'
import packageJson from '../../package.json'
import { isAppVersionInRange, resolveAppVersionRange, type InstallationAppVersions } from '../utils/app-version'

interface GithubRelease {
  tag_name: string
  draft: boolean
  prerelease: boolean
  assets: { name: string; browser_download_url: string }[]
}

interface ApkUpdaterPlugin {
  getReleaseInfo(): Promise<{ prerelease: boolean }>
  canRequestPackageInstalls(): Promise<{ value: boolean }>
  openInstallPermissionSettings(): Promise<void>
  installFromUrl(options: { url: string; fileName: string; version: string }): Promise<void>
}

const apkUpdater = registerPlugin<ApkUpdaterPlugin>('ApkUpdater')
const releasesEndpoint = 'https://api.github.com/repos/NemBestil/pos-app-releases/releases'

export function useAppReleaseUpdate() {
  const isUpdatePromptOpen = ref(false)
  const isUpdateBusyOpen = ref(false)
  const updateBusyMessage = ref('Please wait')
  const targetAppVersion = ref('')
  const minAppVersion = ref('')
  const isMandatoryUpdate = ref(false)
  const updateError = ref('')
  const updateMessage = ref('')
  const startupError = ref('')
  const isStartupReady = ref(false)
  let startupPromise: Promise<void> | null = null
  let resolveAccess: ((allowed: boolean) => void) | null = null

  function initializeUpdatePermissions() {
    if (!startupPromise) {
      startupPromise = (async () => {
        try {
          if (isAndroidNative()) await ensureInstallPermission()
        } catch (error) {
          startupError.value = getErrorMessage(error)
        } finally {
          isStartupReady.value = true
        }
      })()
    }
    return startupPromise
  }

  async function requestInstallationAccess(versions: InstallationAppVersions): Promise<boolean> {
    await initializeUpdatePermissions()
    if (!isAndroidNative()) return true

    const range = resolveAppVersionRange(versions)
    if (packageJson.version === range.targetAppVersion) return true

    minAppVersion.value = range.minAppVersion
    targetAppVersion.value = range.targetAppVersion
    isMandatoryUpdate.value = !isAppVersionInRange(packageJson.version, range)
    updateError.value = ''
    updateMessage.value = ''
    isUpdatePromptOpen.value = true
    Sentry.addBreadcrumb({
      category: 'app.update',
      message: 'Installation app version checked',
      data: { installedVersion: packageJson.version, ...range, mandatory: isMandatoryUpdate.value },
    })

    return new Promise<boolean>((resolve) => {
      resolveAccess = resolve
    })
  }

  function postponeUpdate() {
    const allowed = !isMandatoryUpdate.value
    isUpdatePromptOpen.value = false
    resolveAccess?.(allowed)
    resolveAccess = null
  }

  async function acceptUpdate() {
    if (isUpdateBusyOpen.value) return
    isUpdatePromptOpen.value = false
    isUpdateBusyOpen.value = true
    updateError.value = ''
    updateMessage.value = ''

    try {
      if (!(await ensureInstallPermission())) {
        throw new Error('Allow this app to install updates in Android settings, then try again.')
      }
      updateBusyMessage.value = `Downloading app version ${targetAppVersion.value}…`
      const release = await fetchTargetRelease(targetAppVersion.value)
      await apkUpdater.installFromUrl({
        url: release.browser_download_url,
        fileName: release.name,
        version: targetAppVersion.value,
      })
      updateMessage.value = 'Complete the installation in Android, then reopen the app.'
    } catch (error) {
      updateError.value = getErrorMessage(error)
      Sentry.addBreadcrumb({
        category: 'app.update',
        message: 'App update failed',
        level: 'warning',
        data: { error: updateError.value },
      })
    } finally {
      isUpdateBusyOpen.value = false
      isUpdatePromptOpen.value = true
    }
  }

  async function ensureInstallPermission() {
    const permission = await apkUpdater.canRequestPackageInstalls()
    if (permission.value) return true
    updateBusyMessage.value = 'Allow app installs for NemBestil POS, then return to the app.'
    // Attach the visibility listener before opening Android's settings.
    const returned = waitForAppReturn()
    try {
      await apkUpdater.openInstallPermissionSettings()
      await returned.promise
    } finally {
      returned.cancel()
    }
    return (await apkUpdater.canRequestPackageInstalls()).value
  }

  return {
    isUpdatePromptOpen,
    isUpdateBusyOpen,
    updateBusyMessage,
    targetAppVersion,
    minAppVersion,
    isMandatoryUpdate,
    updateError,
    updateMessage,
    startupError,
    isStartupReady,
    initializeUpdatePermissions,
    requestInstallationAccess,
    postponeUpdate,
    acceptUpdate,
  }
}

async function fetchTargetRelease(version: string) {
  const stableTag = `apk-${version}`
  const fetchTag = (tag: string) =>
    CapacitorHttp.get({
      url: `${releasesEndpoint}/tags/${tag}`,
      headers: { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' },
    })
  const [{ prerelease }, stableResponse] = await Promise.all([apkUpdater.getReleaseInfo(), fetchTag(stableTag)])
  const response = stableResponse.status === 404 && prerelease ? await fetchTag(`${stableTag}-pre`) : stableResponse
  if (response.status === 404) {
    throw new Error(`App version ${version} is not available for download. Contact NemBestil or try again later.`)
  }
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`Could not fetch app release (HTTP ${response.status}). Try again.`)
  }
  const release = (typeof response.data === 'string' ? JSON.parse(response.data) : response.data) as GithubRelease
  const isTargetRelease =
    (release.tag_name === stableTag && !release.prerelease) ||
    (prerelease && release.tag_name === `${stableTag}-pre` && release.prerelease)
  const asset = release.assets.find((candidate) => candidate.browser_download_url.toLowerCase().endsWith('.apk'))
  if (release.draft || !isTargetRelease || !asset) {
    throw new Error(`App version ${version} has no valid APK release. Contact NemBestil or try again later.`)
  }
  return asset
}

function isAndroidNative() {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'
}

function waitForAppReturn() {
  let finish!: () => void
  const promise = new Promise<void>((resolve) => {
    let wasHidden = document.visibilityState === 'hidden'
    finish = () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      window.removeEventListener('focus', onFocus)
      window.clearTimeout(timeoutId)
      resolve()
    }
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') wasHidden = true
      else if (wasHidden) finish()
    }
    const onFocus = () => {
      if (wasHidden) finish()
    }
    const timeoutId = window.setTimeout(finish, 120_000)
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('focus', onFocus)
  })
  return { promise, cancel: finish }
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}
