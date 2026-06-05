import { Capacitor, CapacitorHttp, registerPlugin } from '@capacitor/core'
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
  downloadUrl: string
  releaseUrl: string
  fileName: string
}

interface ApkUpdaterPlugin {
  canRequestPackageInstalls(): Promise<{ value: boolean }>
  openInstallPermissionSettings(): Promise<void>
  openExternalUrl(options: { url: string }): Promise<void>
  installFromUrl(options: { url: string, fileName?: string }): Promise<void>
}

const apkUpdater = registerPlugin<ApkUpdaterPlugin>('ApkUpdater')

const currentVersion = packageJson.version
const latestReleaseEndpoint = 'https://api.github.com/repos/NemBestil/pos-app/releases/latest'

export function useAppReleaseUpdate() {
  const availableRelease = useState<AvailableRelease | null>('app-release-update-available', () => null)
  const isUpdatePromptOpen = useState('app-release-update-prompt-open', () => false)
  const isUpdateBusyOpen = useState('app-release-update-busy-open', () => false)
  const updateBusyMessage = useState('app-release-update-busy-message', () => 'Please wait')
  const isCheckingForUpdate = useState('app-release-update-checking', () => false)

  async function checkForUpdate() {
    if (!isAndroidNative() || isCheckingForUpdate.value) {
      return
    }

    isCheckingForUpdate.value = true
    addUpdateBreadcrumb('Update check started', {
      currentVersion
    })

    try {
      const response = await CapacitorHttp.get({
        url: latestReleaseEndpoint,
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

      const release = normalizeReleaseResponse(response.data)
      const nextRelease = extractAvailableRelease(release)

      if (!nextRelease || compareVersions(nextRelease.version, currentVersion) <= 0) {
        addUpdateBreadcrumb('No newer app release found', {
          releaseVersion: nextRelease?.version ?? null,
          currentVersion
        })
        return
      }

      availableRelease.value = nextRelease
      isUpdatePromptOpen.value = true
      addUpdateBreadcrumb('New app release found', {
        releaseVersion: nextRelease.version,
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
    checkForUpdate,
    postponeUpdate,
    acceptUpdate
  }
}

function isAndroidNative() {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'
}

function normalizeReleaseResponse(data: unknown) {
  if (typeof data === 'string') {
    return JSON.parse(data) as GithubReleaseResponse
  }

  return data as GithubReleaseResponse
}

function extractAvailableRelease(release: GithubReleaseResponse) {
  if (!release || release.draft || release.prerelease) {
    return null
  }

  const version = extractVersionFromTag(release.tag_name)
  const apkAsset = release.assets.find((asset) => asset.browser_download_url?.toLowerCase().endsWith('.apk'))

  if (!version || !apkAsset) {
    return null
  }

  return {
    version,
    downloadUrl: apkAsset.browser_download_url,
    releaseUrl: release.html_url,
    fileName: apkAsset.name
  } satisfies AvailableRelease
}

function extractVersionFromTag(tagName: string) {
  const match = tagName.match(/^apk-(\d+\.\d+\.\d+)$/)

  return match?.[1] ?? null
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
