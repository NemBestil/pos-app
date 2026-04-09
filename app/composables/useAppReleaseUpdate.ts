import { Capacitor, CapacitorHttp, registerPlugin } from '@capacitor/core'
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

    try {
      const response = await CapacitorHttp.get({
        url: latestReleaseEndpoint,
        headers: {
          Accept: 'application/vnd.github+json',
          'X-GitHub-Api-Version': '2022-11-28'
        }
      })

      if (response.status < 200 || response.status >= 300) {
        return
      }

      const release = normalizeReleaseResponse(response.data)
      const nextRelease = extractAvailableRelease(release)

      if (!nextRelease || compareVersions(nextRelease.version, currentVersion) <= 0) {
        return
      }

      availableRelease.value = nextRelease
      isUpdatePromptOpen.value = true
    } catch {
      availableRelease.value = null
      isUpdatePromptOpen.value = false
    } finally {
      isCheckingForUpdate.value = false
    }
  }

  function postponeUpdate() {
    isUpdatePromptOpen.value = false
  }

  async function acceptUpdate() {
    const release = availableRelease.value

    if (!release) {
      return
    }

    isUpdatePromptOpen.value = false
    isUpdateBusyOpen.value = true

    try {
      const hasInstallPermission = await ensureInstallPermission()

      if (!hasInstallPermission) {
        await openExternalReleaseLink(release.downloadUrl)
        return
      }

      updateBusyMessage.value = 'Please wait'

      await apkUpdater.installFromUrl({
        url: release.downloadUrl,
        fileName: release.fileName
      })
    } catch {
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
      return true
    }

    updateBusyMessage.value = 'Allow app installs for this app, then return here to continue.'

    try {
      await apkUpdater.openInstallPermissionSettings()
    } catch {
      return false
    }

    await waitForAppReturn()

    const nextPermissionStatus = await apkUpdater.canRequestPackageInstalls()

    return nextPermissionStatus.value
  }

  async function openExternalReleaseLink(url: string) {
    if (isAndroidNative()) {
      try {
        await apkUpdater.openExternalUrl({ url })
        return
      } catch {
        window.open(url, '_blank', 'noopener,noreferrer')
        return
      }
    }

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
