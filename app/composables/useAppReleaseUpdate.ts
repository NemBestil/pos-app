import {
  Capacitor,
  CapacitorHttp,
  registerPlugin,
  type PluginListenerHandle
} from '@capacitor/core'
import * as Sentry from '@sentry/capacitor'
import packageJson from '../../package.json'

interface GithubReleaseAsset {
  name: string
  browser_download_url: string
}

interface GithubReleaseResponse {
  tag_name: string
  html_url: string
  draft: boolean
  prerelease: boolean
  assets: GithubReleaseAsset[]
}

interface AvailableRelease {
  version: string
  prerelease: boolean
  downloadUrl: string
  releaseUrl: string
  fileName: string
}

interface UpdateNotificationAction {
  release: AvailableRelease
  accept: boolean
}

interface ApkUpdaterPlugin {
  getReleaseInfo(): Promise<{ prerelease: boolean }>
  schedulePeriodicChecks(): Promise<void>
  getPendingUpdateAction(): Promise<{ action?: UpdateNotificationAction }>
  addListener(
    eventName: 'updateNotificationAction',
    listener: (action: UpdateNotificationAction) => void
  ): Promise<PluginListenerHandle>
  canRequestPackageInstalls(): Promise<{ value: boolean }>
  openInstallPermissionSettings(): Promise<void>
  openExternalUrl(options: { url: string }): Promise<void>
  installFromUrl(options: { url: string, fileName?: string }): Promise<void>
}

const apkUpdater = registerPlugin<ApkUpdaterPlugin>('ApkUpdater')

const currentVersion = packageJson.version
const stableReleaseEndpoint = 'https://api.github.com/repos/NemBestil/pos-app/releases/latest'
const prereleaseEndpoint = 'https://api.github.com/repos/NemBestil/pos-app/releases?per_page=100'
let updateNotificationListener: PluginListenerHandle | null = null
let initializationPromise: Promise<void> | null = null
let hasHandledUpdateNotificationAction = false

export function useAppReleaseUpdate() {
  const availableRelease = useState<AvailableRelease | null>('app-release-update-available', () => null)
  const isUpdatePromptOpen = useState('app-release-update-prompt-open', () => false)
  const isUpdateBusyOpen = useState('app-release-update-busy-open', () => false)
  const updateBusyMessage = useState('app-release-update-busy-message', () => 'Please wait')
  const isCheckingForUpdate = useState('app-release-update-checking', () => false)

  async function initializeUpdateChecks() {
    if (!isAndroidNative()) {
      return
    }

    if (!initializationPromise) {
      initializationPromise = initializeNativeUpdateChecks()
    }

    await initializationPromise

    if (!hasHandledUpdateNotificationAction) {
      await checkForUpdate()
    }
  }

  async function initializeNativeUpdateChecks() {
    updateNotificationListener = await apkUpdater.addListener(
      'updateNotificationAction',
      handleUpdateNotificationAction
    )

    await apkUpdater.schedulePeriodicChecks()

    const pendingAction = await apkUpdater.getPendingUpdateAction()
    if (pendingAction.action) {
      handleUpdateNotificationAction(pendingAction.action)
    }
  }

  function handleUpdateNotificationAction(action: UpdateNotificationAction) {
    hasHandledUpdateNotificationAction = true
    availableRelease.value = action.release

    if (action.accept) {
      void acceptUpdate()
      return
    }

    isUpdatePromptOpen.value = true
  }

  async function checkForUpdate() {
    if (!isAndroidNative() || isCheckingForUpdate.value) {
      return
    }

    const { prerelease } = await apkUpdater.getReleaseInfo()
    isCheckingForUpdate.value = true
    addUpdateBreadcrumb('Update check started', {
      currentVersion,
      channel: prerelease ? 'prerelease' : 'stable'
    })

    try {
      const response = await CapacitorHttp.get({
        url: prerelease ? prereleaseEndpoint : stableReleaseEndpoint,
        headers: {
          Accept: 'application/vnd.github+json',
          'X-GitHub-Api-Version': '2022-11-28'
        }
      })

      if (response.status < 200 || response.status >= 300) {
        addUpdateBreadcrumb('Update check returned non-success status', {
          status: response.status
        }, 'warning')
        return
      }

      const releases = normalizeReleaseResponse(response.data)
      const releaseCandidates = releases
        .map((release) => extractAvailableRelease(release, prerelease))
        .filter((release): release is AvailableRelease => release !== null)
      const nextRelease = selectNextRelease(releaseCandidates, currentVersion, prerelease)

      if (!nextRelease) {
        addUpdateBreadcrumb('No newer app release found', {
          currentVersion
        })
        return
      }

      availableRelease.value = nextRelease
      isUpdatePromptOpen.value = true
      addUpdateBreadcrumb('New app release found', {
        releaseVersion: nextRelease.version,
        releaseChannel: nextRelease.prerelease ? 'prerelease' : 'stable',
        currentVersion
      })
    } catch (error) {
      addUpdateBreadcrumb('Update check failed', {
        error: getErrorMessage(error)
      }, 'warning')
      availableRelease.value = null
      isUpdatePromptOpen.value = false
    } finally {
      isCheckingForUpdate.value = false
    }
  }

  function postponeUpdate() {
    addUpdateBreadcrumb('Update postponed', {
      releaseVersion: availableRelease.value?.version ?? null
    })
    isUpdatePromptOpen.value = false
  }

  async function acceptUpdate() {
    const release = availableRelease.value

    if (!release) {
      return
    }

    isUpdatePromptOpen.value = false
    isUpdateBusyOpen.value = true
    addUpdateBreadcrumb('Update accepted', {
      releaseVersion: release.version
    })

    try {
      const hasInstallPermission = await ensureInstallPermission()

      if (!hasInstallPermission) {
        addUpdateBreadcrumb('Update install permission unavailable', {
          releaseVersion: release.version
        }, 'warning')
        await openExternalReleaseLink(release.downloadUrl)
        return
      }

      updateBusyMessage.value = 'Please wait'

      await apkUpdater.installFromUrl({
        url: release.downloadUrl,
        fileName: release.fileName
      })
      addUpdateBreadcrumb('Update install started', {
        releaseVersion: release.version,
        fileName: release.fileName
      })
    } catch (error) {
      addUpdateBreadcrumb('Update install failed, opening external link', {
        releaseVersion: release.version,
        error: getErrorMessage(error)
      }, 'warning')
      await openExternalReleaseLink(release.downloadUrl)
    } finally {
      isUpdateBusyOpen.value = false
    }
  }

  async function ensureInstallPermission() {
    if (!isAndroidNative()) {
      return false
    }

    const permissionStatus = await apkUpdater.canRequestPackageInstalls()

    if (permissionStatus.value) {
      addUpdateBreadcrumb('Update install permission already granted')
      return true
    }

    updateBusyMessage.value = 'Allow app installs for this app, then return here to continue.'
    addUpdateBreadcrumb('Opening update install permission settings')

    try {
      await apkUpdater.openInstallPermissionSettings()
    } catch (error) {
      addUpdateBreadcrumb('Could not open update install permission settings', {
        error: getErrorMessage(error)
      }, 'warning')
      return false
    }

    await waitForAppReturn()

    const nextPermissionStatus = await apkUpdater.canRequestPackageInstalls()

    addUpdateBreadcrumb('Update install permission rechecked', {
      granted: nextPermissionStatus.value
    })

    return nextPermissionStatus.value
  }

  async function openExternalReleaseLink(url: string) {
    if (isAndroidNative()) {
      try {
        await apkUpdater.openExternalUrl({ url })
        addUpdateBreadcrumb('Opened update link externally', {
          url
        })
        return
      } catch (error) {
        addUpdateBreadcrumb('Native external update link failed', {
          error: getErrorMessage(error)
        }, 'warning')
        window.open(url, '_blank', 'noopener,noreferrer')
        return
      }
    }

    addUpdateBreadcrumb('Opened update link in browser', {
      url
    })
    window.open(url, '_blank', 'noopener,noreferrer')
  }

  return {
    availableRelease,
    isUpdatePromptOpen,
    isUpdateBusyOpen,
    updateBusyMessage,
    initializeUpdateChecks,
    checkForUpdate,
    postponeUpdate,
    acceptUpdate
  }
}

function isAndroidNative() {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'
}

function normalizeReleaseResponse(data: unknown) {
  const normalized = typeof data === 'string' ? JSON.parse(data) : data

  if (Array.isArray(normalized)) {
    return normalized as GithubReleaseResponse[]
  }

  return [normalized as GithubReleaseResponse]
}

function extractAvailableRelease(release: GithubReleaseResponse, includePrereleases: boolean) {
  if (!release || release.draft || (!includePrereleases && release.prerelease)) {
    return null
  }

  const version = extractVersionFromTag(release.tag_name, release.prerelease)
  const apkAsset = release.assets.find((asset) => asset.browser_download_url?.toLowerCase().endsWith('.apk'))

  if (!version || !apkAsset) {
    return null
  }

  return {
    version,
    prerelease: release.prerelease,
    downloadUrl: apkAsset.browser_download_url,
    releaseUrl: release.html_url,
    fileName: apkAsset.name
  } satisfies AvailableRelease
}

function extractVersionFromTag(tagName: string, prerelease: boolean) {
  const match = tagName.match(/^apk-(\d+\.\d+\.\d+)(-pre)?$/)

  if (!match || Boolean(match[2]) !== prerelease) {
    return null
  }

  return match[1]
}

function selectNextRelease(
  releases: AvailableRelease[],
  installedVersion: string,
  installedPrerelease: boolean
) {
  const newerReleases = releases.filter((release) => {
    const versionComparison = compareVersions(release.version, installedVersion)
    return versionComparison > 0
      || (versionComparison === 0 && installedPrerelease && !release.prerelease)
  })

  if (installedPrerelease) {
    const stableRelease = findLatestRelease(newerReleases.filter((release) => !release.prerelease))
    if (stableRelease) {
      return stableRelease
    }
  }

  return findLatestRelease(newerReleases)
}

function findLatestRelease(releases: AvailableRelease[]) {
  return releases.reduce<AvailableRelease | null>((latestRelease, release) => {
    if (!latestRelease) {
      return release
    }

    const versionComparison = compareVersions(release.version, latestRelease.version)
    if (versionComparison !== 0) {
      return versionComparison > 0 ? release : latestRelease
    }

    return latestRelease.prerelease && !release.prerelease ? release : latestRelease
  }, null)
}

function compareVersions(left: string, right: string) {
  const leftParts = left.split('.').map((part) => Number.parseInt(part, 10))
  const rightParts = right.split('.').map((part) => Number.parseInt(part, 10))

  for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
    const leftValue = leftParts[index] ?? 0
    const rightValue = rightParts[index] ?? 0

    if (leftValue > rightValue) {
      return 1
    }

    if (leftValue < rightValue) {
      return -1
    }
  }

  return 0
}

function waitForAppReturn(timeoutMs = 120000) {
  return new Promise<boolean>((resolve) => {
    let wasHidden = document.visibilityState === 'hidden'

    const finish = (value: boolean) => {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      window.removeEventListener('focus', handleFocus)
      window.clearTimeout(timeoutId)
      resolve(value)
    }

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        wasHidden = true
        return
      }

      if (wasHidden && document.visibilityState === 'visible') {
        finish(true)
      }
    }

    const handleFocus = () => {
      if (wasHidden && document.visibilityState === 'visible') {
        finish(true)
      }
    }

    const timeoutId = window.setTimeout(() => finish(false), timeoutMs)

    document.addEventListener('visibilitychange', handleVisibilityChange)
    window.addEventListener('focus', handleFocus)
  })
}

function addUpdateBreadcrumb(
  message: string,
  data?: Record<string, unknown>,
  level: Sentry.SeverityLevel = 'info'
) {
  Sentry.addBreadcrumb({
    category: 'app.update',
    message,
    level,
    data: data ? scrubSentryData(data) : undefined
  })
}

function scrubSentryData(data: Record<string, unknown>) {
  return Object.fromEntries(
    Object.entries(data).map(([key, value]) => {
      if (key === 'url' && typeof value === 'string') {
        return [key, stripUrlDetails(value)]
      }

      return [key, value]
    })
  )
}

function stripUrlDetails(url: string) {
  try {
    const parsedUrl = new URL(url)

    return `${parsedUrl.origin}${parsedUrl.pathname}`
  } catch {
    return url
  }
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}
