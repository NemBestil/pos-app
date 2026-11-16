<script setup lang="ts">
import { CapacitorHttp } from '@capacitor/core'
import * as Sentry from '@sentry/capacitor'
import packageJson from '../package.json'
import type { InstallationAppVersions } from './utils/app-version'
import { getInstallationBaseUrl, migrateInstallationLogoUrl } from './utils/installation-url'

type Screen = 'launcher' | 'wizard-input' | 'wizard-review' | 'manage'
type WizardMode = 'launch' | 'manage'
type ConnectionStatus = 'checking' | 'available' | 'unavailable'

interface InstallationOrganization {
  companyName: string
  storeName: string
  address: string
  postal: string
  city: string
  country: string
  mainCurrency: string
  vatNumber: string
}

interface InstallationBranding {
  logoFullUrl: string | null
  logoSquareUrl: string | null
  brandColor: string
}

interface InstallationResponse extends InstallationAppVersions {
  organization: InstallationOrganization
  branding: InstallationBranding
}

interface SavedInstallation {
  id: string
  baseUrl: string
  addedAt: string
  lastUsedAt: string | null
  isDefault: boolean
  organization: InstallationOrganization
  branding: InstallationBranding
}

const installationsStorageKey = 'nem-pos3-installations'
const lastUsedStorageKey = 'nem-pos3-last-used-installation'
const installationIdPattern = /^[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?$/
const manageInstallationsValue = '__manage_installations__'
const appVersion = packageJson.version

const hasLoaded = ref(false)
const screen = ref<Screen>('wizard-input')
const wizardMode = ref<WizardMode>('launch')
const installationIdInput = ref('')
const installationIdTouched = ref(false)
const isFetchingInstallation = ref(false)
const isLaunchingInstallation = ref(false)
const fetchError = ref('')
const launcherError = ref('')
const launcherNotice = ref('')
const selectedInstallationId = ref('')
const installations = ref<SavedInstallation[]>([])
const candidateInstallation = ref<SavedInstallation | null>(null)
const connectionStatuses = ref<Record<string, ConnectionStatus>>({})
const pendingConnectionChecks = new Map<string, Promise<Awaited<ReturnType<typeof resolveInstallation>> | null>>()
const connectionHelp = 'Your device may have a network problem, or the POS may be unavailable. Check that Wi-Fi works or the LAN cable is plugged in. Open a website in Chrome or another browser to check internet access. If needed, restart the device and try again.'

const hasInstallations = computed(() => installations.value.length > 0)
const sanitizedInstallationId = computed(() => installationIdInput.value.trim())
const isInstallationIdValid = computed(() => installationIdPattern.test(sanitizedInstallationId.value))

const selectedInstallation = computed(() => {
  return installations.value.find((installation) => installation.id === selectedInstallationId.value) ?? null
})

const installationOptions = computed(() => {
  return [
    installations.value.map((installation) => ({
      label: getInstallationLabel(installation),
      value: installation.id
    })),
    [
      {
        label: 'Manage installations',
        value: manageInstallationsValue
      }
    ]
  ]
})

const launcherSelectionValue = computed({
  get: () => selectedInstallationId.value,
  set: (value: string) => {
    if (value === manageInstallationsValue) {
      openManageScreen()
      return
    }

    selectedInstallationId.value = value
  }
})

const validationMessage = computed(() => {
  if (!installationIdTouched.value || sanitizedInstallationId.value.length === 0) {
    return ''
  }

  if (isInstallationIdValid.value) {
    return ''
  }

  return 'Use A-Z, 0-9 and dashes only. The ID cannot start or end with a dash.'
})

const submitLabel = computed(() => {
  return wizardMode.value === 'manage' ? 'Verify installation' : 'Continue'
})

const reviewConfirmLabel = computed(() => {
  return wizardMode.value === 'manage' ? 'Add installation' : 'Open POS'
})

const appBackground = computed(() => {
  const brandColor = screen.value === 'launcher' ? selectedInstallation.value?.branding.brandColor : null

  if (!brandColor) {
    return 'radial-gradient(circle at top, #fff7ec 0%, #fef3c7 24%, #f8fafc 58%, #e2e8f0 100%)'
  }

  return `
    radial-gradient(circle at 12% 18%, ${hexToRgba(brandColor, 0.34)} 0%, transparent 30%),
    radial-gradient(circle at 86% 12%, ${hexToRgba(brandColor, 0.2)} 0%, transparent 24%),
    radial-gradient(circle at 50% 100%, ${hexToRgba(brandColor, 0.28)} 0%, transparent 32%),
    linear-gradient(140deg, ${hexToRgba(brandColor, 0.46)} 0%, #fff7ed 34%, #f8fafc 68%, #dbeafe 100%)
  `.replace(/\s+/g, ' ').trim()
})

useHead(() => ({
  bodyAttrs: {
    style: `background: ${appBackground.value}; transition: background 400ms ease;`
  }
}))

const brandingInstallation = computed(() => screen.value === 'wizard-review'
  ? candidateInstallation.value
  : screen.value === 'launcher' ? selectedInstallation.value : null)
const selectedConnectionStatus = computed(() => connectionStatuses.value[selectedInstallationId.value] ?? 'checking')
const {
  targetAppVersion,
  minAppVersion,
  isMandatoryUpdate,
  updateError,
  updateMessage,
  startupError,
  isStartupReady,
  isUpdatePromptOpen,
  isUpdateBusyOpen,
  updateBusyMessage,
  initializeUpdatePermissions,
  requestInstallationAccess,
  postponeUpdate,
  acceptUpdate
} = useAppReleaseUpdate()

onMounted(() => {
  if (new URL(window.location.href).searchParams.get('reason') === 'app-version') {
    launcherNotice.value = 'You were logged out because this installation requires a different app version. Install the required app update before continuing.'
    window.history.replaceState(null, '', '/')
  }
  installations.value = readInstallations()
  persistInstallations()

  if (installations.value.length > 0) {
    screen.value = 'launcher'
    selectedInstallationId.value = resolveInitialInstallationId(installations.value)
    setSentryInstallationContext(selectedInstallation.value)
  }

  hasLoaded.value = true
  document.addEventListener('visibilitychange', onLauncherVisibilityChange)
  window.addEventListener('online', recheckLauncherConnection)
  void initializeUpdatePermissions()

  addSentryBreadcrumb('app.lifecycle', 'Shell loaded', {
    screen: screen.value,
    installationCount: installations.value.length,
    selectedInstallationId: selectedInstallationId.value || null
  })
})

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onLauncherVisibilityChange)
  window.removeEventListener('online', recheckLauncherConnection)
})

function onLauncherVisibilityChange() {
  if (document.visibilityState === 'visible') recheckLauncherConnection()
}

function recheckLauncherConnection() {
  if (screen.value === 'launcher') void checkSelectedConnection()
}

watch(screen, (nextScreen, previousScreen) => {
  addSentryBreadcrumb('app.screen', 'Screen changed', {
    from: previousScreen,
    to: nextScreen
  })
})

watch([screen, selectedInstallationId], () => {
  if (screen.value === 'launcher') void checkSelectedConnection()
})

watch(selectedInstallation, (installation) => {
  setSentryInstallationContext(installation)
})

function sanitizeInstallationId(value: string) {
  return value.toUpperCase().replace(/[^A-Z0-9-]/g, '')
}

function onInstallationIdInput(event: Event) {
  const nextValue = sanitizeInstallationId((event.target as HTMLInputElement).value)

  installationIdInput.value = nextValue
  fetchError.value = ''
}

function startWizard(mode: WizardMode) {
  addSentryBreadcrumb('app.installation', 'Wizard started', { mode })
  wizardMode.value = mode
  installationIdInput.value = ''
  installationIdTouched.value = false
  fetchError.value = ''
  launcherError.value = ''
  candidateInstallation.value = null
  screen.value = 'wizard-input'
}

async function verifyInstallation() {
  installationIdTouched.value = true
  fetchError.value = ''

  if (!isInstallationIdValid.value) {
    return
  }

  isFetchingInstallation.value = true

  const id = sanitizedInstallationId.value
  const preferredBaseUrl = getInstallationBaseUrl(id)

  addSentryBreadcrumb('app.installation', 'Installation verification started', {
    installationId: id,
    baseUrl: preferredBaseUrl
  })

  try {
    const { baseUrl, details } = await resolveInstallation(id)

    candidateInstallation.value = {
      id,
      baseUrl,
      addedAt: new Date().toISOString(),
      lastUsedAt: null,
      isDefault: false,
      organization: details.organization,
      branding: details.branding
    }

    screen.value = 'wizard-review'
    addSentryBreadcrumb('app.installation', 'Installation verification succeeded', {
      installationId: id,
      baseUrl
    })
  } catch (error) {
    addSentryBreadcrumb('app.installation', 'Installation verification failed', {
      installationId: id,
      baseUrl: preferredBaseUrl,
      error: getErrorMessage(error)
    }, 'warning')
    fetchError.value = `Could not connect to ${preferredBaseUrl}. Check the installation ID. ${connectionHelp}`
  } finally {
    isFetchingInstallation.value = false
  }
}

function goBackFromWizard() {
  addSentryBreadcrumb('app.navigation', 'Wizard back pressed', {
    mode: wizardMode.value
  })
  fetchError.value = ''
  screen.value = wizardMode.value === 'manage' ? 'manage' : 'wizard-input'
}

function goBackToInput() {
  addSentryBreadcrumb('app.navigation', 'Review back pressed', {
    mode: wizardMode.value
  })
  screen.value = 'wizard-input'
}

async function confirmInstallation() {
  if (!candidateInstallation.value) {
    return
  }

  const now = new Date().toISOString()
  const existingIndex = installations.value.findIndex(
    (installation) => installation.id === candidateInstallation.value?.id
  )
  const existingInstallation = existingIndex >= 0 ? installations.value[existingIndex] : null

  const nextInstallation: SavedInstallation = {
    ...candidateInstallation.value,
    addedAt: existingInstallation?.addedAt ?? now,
    isDefault: existingInstallation?.isDefault ?? installations.value.length === 0,
    lastUsedAt:
      wizardMode.value === 'launch'
        ? now
        : existingInstallation?.lastUsedAt ?? null
  }

  if (existingIndex >= 0) {
    installations.value = installations.value.map((installation, index) => {
      return index === existingIndex ? nextInstallation : installation
    })
  } else {
    installations.value = [...installations.value, nextInstallation]
  }

  persistInstallations()
  selectedInstallationId.value = nextInstallation.id
  setSentryInstallationContext(nextInstallation)
  addSentryBreadcrumb('app.installation', existingInstallation ? 'Installation updated' : 'Installation added', {
    installationId: nextInstallation.id,
    mode: wizardMode.value,
    isDefault: nextInstallation.isDefault
  })

  if (wizardMode.value === 'manage') {
    candidateInstallation.value = null
    screen.value = 'launcher'
    return
  }

  screen.value = 'launcher'
  await openSelectedInstallation()
}

async function openSelectedInstallation() {
  const installation = selectedInstallation.value
  if (!installation || isLaunchingInstallation.value || !isStartupReady.value) {
    return
  }

  launcherError.value = ''
  isLaunchingInstallation.value = true
  addSentryBreadcrumb('app.launch', 'Selected installation launch started', {
    installationId: installation.id,
    baseUrl: installation.baseUrl
  })

  try {
    const resolved = await checkInstallationConnection(installation.id)
    if (resolved) await openInstallation(resolved.baseUrl, resolved.details)
  } catch (error) {
    addSentryBreadcrumb('app.launch', 'Selected installation launch failed', {
      installationId: installation.id,
      baseUrl: installation.baseUrl,
      error: getErrorMessage(error)
    }, 'warning')
    launcherError.value = getErrorMessage(error)
  } finally {
    isLaunchingInstallation.value = false
  }
}

function openManageScreen() {
  addSentryBreadcrumb('app.navigation', 'Manage installations opened', {
    installationCount: installations.value.length
  })
  screen.value = 'manage'
}

function leaveManageScreen() {
  addSentryBreadcrumb('app.navigation', 'Manage installations closed', {
    installationCount: installations.value.length
  })
  screen.value = hasInstallations.value ? 'launcher' : 'wizard-input'
}

function removeInstallation(id: string) {
  const removedInstallation = installations.value.find((installation) => installation.id === id)
  let remainingInstallations = installations.value.filter((installation) => installation.id !== id)

  if (removedInstallation?.isDefault && remainingInstallations.length > 0) {
    remainingInstallations = remainingInstallations.map((installation, index) => ({
      ...installation,
      isDefault: index === 0
    }))
  }

  installations.value = remainingInstallations
  persistInstallations()
  launcherError.value = ''
  addSentryBreadcrumb('app.installation', 'Installation removed', {
    installationId: id,
    remainingInstallationCount: remainingInstallations.length
  })

  if (selectedInstallationId.value === id) {
    selectedInstallationId.value = resolveInitialInstallationId(remainingInstallations)
  }

  if (readLastUsedInstallationId() === id) {
    if (remainingInstallations[0]?.id) {
      localStorage.setItem(lastUsedStorageKey, remainingInstallations[0].id)
    } else {
      localStorage.removeItem(lastUsedStorageKey)
    }
  }
}

function setDefaultInstallation(id: string) {
  installations.value = installations.value.map((installation) => ({
    ...installation,
    isDefault: installation.id === id
  }))

  selectedInstallationId.value = id
  persistInstallations()
  addSentryBreadcrumb('app.installation', 'Default installation changed', {
    installationId: id
  })
}

function markInstallationAsLastUsed(id: string) {
  const now = new Date().toISOString()

  installations.value = installations.value.map((installation) => {
    if (installation.id !== id) {
      return installation
    }

    return {
      ...installation,
      lastUsedAt: now
    }
  })

  selectedInstallationId.value = id
  persistInstallations()
  localStorage.setItem(lastUsedStorageKey, id)
  addSentryBreadcrumb('app.installation', 'Installation marked as last used', {
    installationId: id
  })
}

async function openInstallation(url: string, details: InstallationResponse) {
  if (!(await requestInstallationAccess(details))) {
    launcherError.value = `This app is not the correct version for this installation. Install app version ${targetAppVersion.value} to continue.`
    return
  }
  markInstallationAsLastUsed(selectedInstallationId.value)
  addSentryBreadcrumb('app.launch', 'Handing off to POS webview', {
    url,
    installationId: selectedInstallationId.value || null
  })
  window.location.assign(url)
}

async function resolveInstallation(id: string) {
  const baseUrl = getInstallationBaseUrl(id)
  return {
    baseUrl,
    details: await fetchInstallationDetails(baseUrl)
  }
}

async function fetchInstallationDetails(baseUrl: string) {
  addSentryBreadcrumb('app.api', 'Installation details request started', {
    url: `${baseUrl}/api/public/installation`
  })

  const response = await CapacitorHttp.get({
    url: `${baseUrl}/api/public/installation`,
    headers: { 'Cache-Control': 'no-cache' },
    connectTimeout: 5_000,
    readTimeout: 5_000
  })

  addSentryBreadcrumb('app.api', 'Installation details response received', {
    url: `${baseUrl}/api/public/installation`,
    status: response.status
  }, response.status >= 400 ? 'warning' : 'info')

  if (response.status !== 200) {
    throw new Error('Installation lookup failed')
  }

  const data = normalizeInstallationResponse(response.data)

  if (!isRecord(data.organization) || !isRecord(data.branding)) {
    throw new Error('Installation response is incomplete')
  }

  return {
    ...data,
    branding: normalizeInstallationBranding(data.branding as unknown as InstallationBranding)
  } as unknown as InstallationResponse
}

function checkSelectedConnection() {
  launcherError.value = ''
  if (selectedInstallationId.value) return checkInstallationConnection(selectedInstallationId.value)
}

function checkInstallationConnection(id: string) {
  const pending = pendingConnectionChecks.get(id)
  if (pending) return pending

  connectionStatuses.value[id] = 'checking'
  const request = resolveInstallation(id).then((resolved) => {
    installations.value = installations.value.map((installation) => installation.id === id
      ? {
          ...installation,
          baseUrl: resolved.baseUrl,
          organization: resolved.details.organization,
          branding: resolved.details.branding
        }
      : installation)
    persistInstallations()
    connectionStatuses.value[id] = 'available'
    return resolved
  }).catch((error: unknown) => {
    connectionStatuses.value[id] = 'unavailable'
    addSentryBreadcrumb('app.installation', 'Installation connection check failed', {
      installationId: id,
      baseUrl: getInstallationBaseUrl(id),
      error: getErrorMessage(error)
    }, 'warning')
    return null
  }).finally(() => {
    pendingConnectionChecks.delete(id)
  })

  pendingConnectionChecks.set(id, request)
  return request
}

function normalizeInstallationResponse(data: unknown) {
  const normalizedData = typeof data === 'string' ? JSON.parse(data) : data

  if (!isRecord(normalizedData)) {
    throw new Error('Installation response is not valid JSON')
  }

  return normalizedData
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readInstallations() {
  const rawValue = localStorage.getItem(installationsStorageKey)

  if (!rawValue) {
    return [] as SavedInstallation[]
  }

  try {
    return normalizeSavedInstallations(JSON.parse(rawValue) as Partial<SavedInstallation>[])
  } catch {
    return []
  }
}

function persistInstallations() {
  localStorage.setItem(installationsStorageKey, JSON.stringify(installations.value))
}

function readLastUsedInstallationId() {
  return localStorage.getItem(lastUsedStorageKey)
}

function resolveInitialInstallationId(savedInstallations: SavedInstallation[]) {
  const defaultInstallationId = savedInstallations.find((installation) => installation.isDefault)?.id

  if (defaultInstallationId) {
    return defaultInstallationId
  }

  const lastUsedInstallationId = readLastUsedInstallationId()

  if (lastUsedInstallationId && savedInstallations.some((installation) => installation.id === lastUsedInstallationId)) {
    return lastUsedInstallationId
  }

  return savedInstallations[0]?.id ?? ''
}

function getInstallationLabel(installation: SavedInstallation) {
  if (installation.organization.storeName && installation.organization.storeName !== installation.organization.companyName) {
    return `${installation.organization.storeName} · ${installation.organization.companyName}`
  }

  return installation.organization.companyName
}

function getInstallationSubtitle(installation: SavedInstallation) {
  return `${installation.organization.address}, ${installation.organization.postal} ${installation.organization.city}`
}

function normalizeSavedInstallations(savedInstallations: Partial<SavedInstallation>[]) {
  const lastUsedInstallationId = readLastUsedInstallationId()
  const hasStoredDefault = savedInstallations.some((installation) => installation.isDefault)
  const fallbackDefaultId =
    (lastUsedInstallationId && savedInstallations.find((installation) => installation.id === lastUsedInstallationId)?.id) ||
    savedInstallations[0]?.id ||
    ''

  return savedInstallations
    .map((installation) => ({
      id: installation.id ?? '',
      baseUrl: getInstallationBaseUrl(installation.id ?? ''),
      addedAt: installation.addedAt ?? new Date().toISOString(),
      lastUsedAt: installation.lastUsedAt ?? null,
      isDefault: hasStoredDefault ? installation.isDefault === true : installation.id === fallbackDefaultId,
      organization: installation.organization as InstallationOrganization,
      branding: installation.branding ? normalizeInstallationBranding(installation.branding) : null
    }))
    .filter((installation): installation is SavedInstallation => !!(installation.id && installation.organization && installation.branding))
}

function normalizeInstallationBranding(branding: InstallationBranding): InstallationBranding {
  return {
    ...branding,
    logoFullUrl: migrateInstallationLogoUrl(branding.logoFullUrl),
    logoSquareUrl: migrateInstallationLogoUrl(branding.logoSquareUrl)
  }
}

function hexToRgba(hex: string, alpha: number) {
  const normalizedHex = hex.replace('#', '')
  const safeHex = normalizedHex.length === 3
    ? normalizedHex.split('').map((char) => `${char}${char}`).join('')
    : normalizedHex

  const red = Number.parseInt(safeHex.slice(0, 2), 16)
  const green = Number.parseInt(safeHex.slice(2, 4), 16)
  const blue = Number.parseInt(safeHex.slice(4, 6), 16)

  return `rgba(${red}, ${green}, ${blue}, ${alpha})`
}

function addSentryBreadcrumb(
  category: string,
  message: string,
  data?: Record<string, unknown>,
  level: Sentry.SeverityLevel = 'info'
) {
  Sentry.addBreadcrumb({
    category,
    message,
    level,
    data: data ? scrubSentryData(data) : undefined
  })
}

function setSentryInstallationContext(installation: SavedInstallation | null) {
  if (!installation) {
    Sentry.setContext('installation', null)
    Sentry.setTag('installation.id', '')
    return
  }

  Sentry.setTag('installation.id', installation.id)
  Sentry.setContext('installation', {
    id: installation.id,
    baseUrl: stripUrlDetails(installation.baseUrl),
    isDefault: installation.isDefault,
    organization: installation.organization.companyName,
    store: installation.organization.storeName
  })
}

function scrubSentryData(data: Record<string, unknown>) {
  return Object.fromEntries(
    Object.entries(data).map(([key, value]) => {
      if ((key === 'url' || key === 'baseUrl') && typeof value === 'string') {
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
</script>

<template>
  <UApp>
    <NuxtRouteAnnouncer />

    <div
      class="relative min-h-dvh overflow-x-clip font-['Avenir_Next',_'Segoe_UI',_sans-serif] text-slate-900 transition duration-500"
      :style="{ background: appBackground }"
    >
      <div class="pointer-events-none absolute inset-0">
        <div class="absolute -left-20 top-16 h-64 w-64 rounded-full bg-amber-300/25 blur-3xl" />
        <div class="absolute right-0 top-0 h-72 w-72 rounded-full bg-emerald-300/20 blur-3xl" />
        <div class="absolute bottom-0 left-1/2 h-80 w-80 -translate-x-1/2 rounded-full bg-sky-300/20 blur-3xl" />
      </div>

      <main class="relative flex min-h-dvh items-center justify-center px-4 py-[clamp(0.75rem,3dvh,2rem)] sm:px-6">
        <div class="w-full max-w-6xl">
          <UAlert
            v-if="launcherNotice"
            color="warning"
            variant="soft"
            icon="i-lucide-log-out"
            title="App update required"
            :description="launcherNotice"
            class="mb-6"
          />
          <UAlert v-if="startupError" color="error" :title="startupError" class="mb-6" />
          <div
            class="grid items-center gap-[clamp(1rem,4dvh,3rem)]"
            :class="screen !== 'manage' ? 'min-[600px]:grid-cols-[2fr_3fr]' : ''"
          >
            <aside
              class="flex min-w-0 flex-col items-center justify-center gap-[clamp(0.5rem,3dvh,1.5rem)] text-center"
              :class="screen === 'manage' ? 'flex-row gap-3' : ''"
            >
              <CustomerLogo
                v-if="brandingInstallation"
                :src="brandingInstallation.branding.logoFullUrl"
                :alt="brandingInstallation.organization.companyName"
                class="max-h-[16dvh] w-auto max-w-full object-contain min-[600px]:max-h-[min(30dvh,14rem)]"
              />
              <div class="flex flex-col items-center gap-[clamp(0.25rem,1dvh,0.75rem)]">
                <img
                  src="/logo-singleline.svg"
                  alt="NemBestil"
                  class="h-[clamp(1.5rem,6dvh,3.5rem)] max-w-full object-contain"
                >
                <p class="text-[11px] font-semibold uppercase tracking-[0.5em] text-slate-600">POS</p>
                <p class="text-[11px] font-semibold tracking-wide text-slate-500">App version {{ appVersion }}</p>
              </div>
            </aside>

            <Transition
              mode="out-in"
              enter-active-class="transform transition duration-400 ease-out"
              enter-from-class="translate-y-4 opacity-0"
              enter-to-class="translate-y-0 opacity-100"
              leave-active-class="transform transition duration-300 ease-in"
              leave-from-class="translate-y-0 opacity-100"
              leave-to-class="-translate-y-2 opacity-0"
            >
              <section
                v-if="hasLoaded"
                :key="screen"
                class="mx-auto"
                :class="screen === 'launcher'
                  ? 'w-full min-w-0'
                  : 'w-full min-w-0 rounded-[2rem] border border-white/70 bg-white/80 p-[clamp(1rem,3dvh,2rem)] shadow-[0_24px_80px_rgba(15,23,42,0.12)] backdrop-blur'"
              >
                <div v-if="screen === 'wizard-input'" class="mx-auto max-w-2xl">
                  <div class="space-y-3 text-center">
                    <p class="text-sm font-semibold uppercase tracking-[0.3em] text-emerald-700">Configuration</p>
                    <h1 class="text-3xl font-semibold tracking-tight text-slate-950 sm:text-4xl">Welcome to POS</h1>
                    <p class="text-balance text-base leading-7 text-slate-600">
                      Enter the installation ID provided by NemBestil ApS. Use A-Z, 0-9 and dashes.
                    </p>
                  </div>

                  <form class="mt-[clamp(1rem,4dvh,2rem)] space-y-4" @submit.prevent="verifyInstallation">
                    <label class="block space-y-2">
                      <span class="text-sm font-medium text-slate-700">Installation ID</span>
                      <input
                        :value="installationIdInput"
                        type="text"
                        inputmode="text"
                        autocomplete="off"
                        spellcheck="false"
                        placeholder="ABC-123"
                        class="w-full rounded-2xl border border-slate-200 bg-white px-5 py-4 text-lg font-medium uppercase tracking-[0.18em] text-slate-950 outline-none transition duration-200 placeholder:text-slate-400 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-100"
                        :class="validationMessage || fetchError ? 'border-rose-300 focus:border-rose-500 focus:ring-rose-100' : ''"
                        @input="onInstallationIdInput"
                        @blur="installationIdTouched = true"
                      >
                    </label>

                    <p v-if="validationMessage" class="text-sm text-rose-600">
                      {{ validationMessage }}
                    </p>
                    <p v-else-if="fetchError" class="text-sm text-rose-600">
                      {{ fetchError }}
                    </p>
                    <p v-else class="text-sm text-slate-500">
                      Example: <span class="font-medium text-slate-700">STORE-42</span>
                    </p>

                    <div class="flex flex-col gap-3 pt-3 sm:flex-row sm:justify-center">
                      <button
                        v-if="wizardMode === 'manage'"
                        type="button"
                        class="inline-flex min-h-13 items-center justify-center rounded-2xl border border-slate-200 px-6 text-sm font-semibold text-slate-700 transition duration-200 hover:border-slate-300 hover:bg-slate-50"
                        @click="goBackFromWizard"
                      >
                        Back
                      </button>
                      <button
                        type="submit"
                        class="inline-flex min-h-13 items-center justify-center rounded-2xl bg-slate-950 px-6 text-sm font-semibold text-white transition duration-200 hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-300"
                        :disabled="isFetchingInstallation"
                      >
                        {{ isFetchingInstallation ? 'Checking installation...' : submitLabel }}
                      </button>
                    </div>
                  </form>
                </div>

                <div v-else-if="screen === 'wizard-review' && candidateInstallation" class="mx-auto max-w-4xl space-y-8">
                  <div class="space-y-3 text-center">
                    <p class="text-sm font-semibold uppercase tracking-[0.3em] text-emerald-700">Verify installation</p>
                    <h1 class="text-3xl font-semibold tracking-tight text-slate-950 sm:text-4xl">
                      Confirm the organization details
                    </h1>
                    <p class="text-base leading-7 text-slate-600">
                      Make sure this matches the organization connected to <span class="font-semibold text-slate-800">{{ candidateInstallation.id }}</span>.
                    </p>
                  </div>

                  <div class="grid gap-4 min-[1200px]:grid-cols-[1.15fr_0.85fr]">
                    <div class="overflow-hidden rounded-[1.75rem] border border-slate-200 bg-white shadow-sm">
                      <div class="space-y-5 p-6">
                        <div>
                          <p class="text-sm font-semibold uppercase tracking-[0.24em] text-slate-500">Organization</p>
                          <h2 class="mt-2 text-2xl font-semibold text-slate-950">
                            {{ candidateInstallation.organization.storeName }}
                          </h2>
                          <p class="mt-1 text-base text-slate-600">
                            {{ candidateInstallation.organization.companyName }}
                          </p>
                        </div>

                        <dl class="grid gap-4 sm:grid-cols-2">
                          <div class="rounded-2xl bg-slate-50 p-4">
                            <dt class="text-xs font-semibold uppercase tracking-[0.24em] text-slate-500">Address</dt>
                            <dd class="mt-2 text-sm leading-6 text-slate-700">
                              {{ candidateInstallation.organization.address }}<br>
                              {{ candidateInstallation.organization.postal }} {{ candidateInstallation.organization.city }}<br>
                              {{ candidateInstallation.organization.country }}
                            </dd>
                          </div>
                          <div class="rounded-2xl bg-slate-50 p-4">
                            <dt class="text-xs font-semibold uppercase tracking-[0.24em] text-slate-500">Business</dt>
                            <dd class="mt-2 text-sm leading-6 text-slate-700">
                              VAT {{ candidateInstallation.organization.vatNumber }}<br>
                              Currency {{ candidateInstallation.organization.mainCurrency }}<br>
                              ID {{ candidateInstallation.id }}
                            </dd>
                          </div>
                        </dl>
                      </div>
                    </div>

                    <div class="flex flex-col justify-between gap-6 rounded-[1.75rem] border border-slate-200 bg-slate-50 p-6">
                      <div class="space-y-4">
                        <p class="text-sm font-semibold uppercase tracking-[0.24em] text-slate-500">What happens next</p>
                        <p class="text-base leading-7 text-slate-700">
                          {{
                            wizardMode === 'manage'
                              ? 'Confirm to save this installation to the device. You will return to the home screen with this installation selected.'
                              : 'Confirm to open this installation and continue into the POS.'
                          }}
                        </p>
                      </div>

                      <div class="flex flex-col gap-3">
                        <button
                          type="button"
                          class="inline-flex min-h-13 items-center justify-center rounded-2xl border border-slate-200 bg-white px-6 text-sm font-semibold text-slate-700 transition duration-200 hover:border-slate-300 hover:bg-slate-100"
                          @click="goBackToInput"
                        >
                          Go back
                        </button>
                        <button
                          type="button"
                          class="inline-flex min-h-13 items-center justify-center rounded-2xl bg-slate-950 px-6 text-sm font-semibold text-white transition duration-200 hover:bg-slate-800"
                          :disabled="!isStartupReady || isLaunchingInstallation"
                          @click="confirmInstallation"
                        >
                          {{ reviewConfirmLabel }}
                        </button>
                      </div>
                    </div>
                  </div>
                </div>

                <div
                  v-else-if="screen === 'launcher' && selectedInstallation"
                  class="space-y-[clamp(0.75rem,2dvh,1.25rem)] rounded-[2rem] border border-white/70 bg-white/80 p-[clamp(1rem,3dvh,2rem)] shadow-[0_24px_80px_rgba(15,23,42,0.12)] backdrop-blur"
                >
                  <USelect
                    v-model="launcherSelectionValue"
                    aria-label="Select installation or manage installations"
                    :disabled="isLaunchingInstallation"
                    :items="installationOptions"
                    size="xl"
                    color="neutral"
                    variant="none"
                    class="w-full"
                    :ui="{
                      base: 'min-h-11 px-0 py-0 text-left shadow-none ring-0',
                      value: 'text-xl font-semibold tracking-tight text-slate-950 sm:text-2xl',
                      trailing: 'pe-0',
                      trailingIcon: 'size-6 text-slate-500',
                      content: 'rounded-2xl'
                    }"
                  />

                  <div class="space-y-1 text-sm text-slate-600">
                    <p>{{ getInstallationSubtitle(selectedInstallation) }}</p>
                    <p v-if="selectedInstallation.organization.storeName !== selectedInstallation.organization.companyName">
                      {{ selectedInstallation.organization.companyName }}
                    </p>
                    <p class="break-all font-medium text-slate-800">{{ selectedInstallation.baseUrl }}</p>
                    <p class="text-xs">
                      VAT {{ selectedInstallation.organization.vatNumber }} · {{ selectedInstallation.organization.mainCurrency }}
                    </p>
                  </div>

                  <div
                    v-if="selectedConnectionStatus === 'unavailable'"
                    role="alert"
                    class="rounded-2xl bg-rose-50 px-4 pt-3 text-sm text-rose-950 ring-1 ring-rose-200"
                  >
                    <p class="font-semibold">Cannot connect to your POS</p>
                    <p class="mt-1 leading-5">{{ connectionHelp }}</p>
                    <button
                      type="button"
                      class="inline-flex min-h-11 items-center gap-2 font-semibold underline underline-offset-4"
                      @click="checkSelectedConnection"
                    >
                      <Icon name="lucide:refresh-cw" class="size-4" />
                      Try again
                    </button>
                  </div>
                  <p v-else role="status" class="flex items-center gap-2 text-sm text-slate-600">
                    <Icon
                      :name="selectedConnectionStatus === 'checking' ? 'lucide:loader-circle' : 'lucide:circle-check'"
                      class="size-5 shrink-0"
                      :class="selectedConnectionStatus === 'checking' ? 'animate-spin' : 'text-success'"
                    />
                    {{ selectedConnectionStatus === 'checking' ? 'Checking connection to your POS…' : 'Connected to your POS' }}
                  </p>

                  <p v-if="launcherError" role="alert" class="text-sm text-rose-600">{{ launcherError }}</p>

                  <button
                    type="button"
                    class="inline-flex min-h-13 w-full items-center justify-center rounded-2xl bg-slate-950 px-6 text-sm font-semibold text-white transition duration-200 hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-300"
                    :disabled="isLaunchingInstallation || !isStartupReady || selectedConnectionStatus !== 'available'"
                    @click="openSelectedInstallation"
                  >
                    {{ isLaunchingInstallation ? 'Checking installation...' : 'Open POS' }}
                  </button>
                </div>

                <div v-else-if="screen === 'manage'" class="mx-auto max-w-5xl space-y-8">
                  <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
                    <div class="space-y-2">
                      <p class="text-sm font-semibold uppercase tracking-[0.3em] text-emerald-700">Manage installations</p>
                      <h1 class="text-3xl font-semibold tracking-tight text-slate-950 sm:text-4xl">Saved installations</h1>
                      <p class="text-base leading-7 text-slate-600">
                        Add a new installation or remove one from this device.
                      </p>
                    </div>

                    <div class="flex flex-col gap-3 sm:flex-row">
                      <button
                        v-if="hasInstallations"
                        type="button"
                        class="inline-flex min-h-13 items-center justify-center rounded-2xl border border-slate-200 px-6 text-sm font-semibold text-slate-700 transition duration-200 hover:border-slate-300 hover:bg-slate-50"
                        @click="leaveManageScreen"
                      >
                        Back
                      </button>
                      <button
                        type="button"
                        class="inline-flex min-h-13 items-center justify-center rounded-2xl bg-slate-950 px-6 text-sm font-semibold text-white transition duration-200 hover:bg-slate-800"
                        @click="startWizard('manage')"
                      >
                        Add installation
                      </button>
                    </div>
                  </div>

                  <div class="overflow-hidden rounded-[1.75rem] border border-slate-200 bg-white shadow-sm">
                    <div v-if="installations.length > 0" class="overflow-x-auto">
                      <table class="min-w-full divide-y divide-slate-200">
                        <thead class="bg-slate-50">
                          <tr class="text-left">
                            <th class="px-6 py-4 text-xs font-semibold uppercase tracking-[0.24em] text-slate-500">Organization</th>
                            <th class="px-6 py-4 text-xs font-semibold uppercase tracking-[0.24em] text-slate-500">Actions</th>
                          </tr>
                        </thead>
                        <tbody class="divide-y divide-slate-100">
                          <tr
                            v-for="installation in installations"
                            :key="installation.id"
                            class="align-top"
                          >
                            <td class="px-6 py-5">
                              <div class="flex items-start gap-4">
                                <CustomerLogo
                                  :src="installation.branding.logoFullUrl"
                                  :alt="installation.organization.companyName"
                                  :style="{ backgroundColor: installation.branding.brandColor }"
                                  class="max-h-14 w-20 shrink-0 rounded-2xl px-3 py-2 object-contain"
                                />
                                <div>
                                  <p class="font-semibold text-slate-900">
                                    {{ getInstallationLabel(installation) }}
                                  </p>
                                  <p class="mt-1 text-sm text-slate-600">{{ getInstallationSubtitle(installation) }}</p>
                                  <p class="mt-1 break-all text-sm font-medium text-slate-700">{{ installation.baseUrl }}</p>
                                </div>
                              </div>
                            </td>
                            <td class="px-6 py-5">
                              <div class="flex flex-wrap gap-3">
                                <button
                                  type="button"
                                  class="inline-flex min-h-11 items-center justify-center rounded-2xl border px-4 text-sm font-semibold transition duration-200"
                                  :class="installation.isDefault ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 text-slate-700 hover:border-slate-300 hover:bg-slate-50'"
                                  @click="setDefaultInstallation(installation.id)"
                                >
                                  {{ installation.isDefault ? 'Default' : 'Set as default' }}
                                </button>
                                <button
                                  type="button"
                                  class="inline-flex min-h-11 items-center justify-center rounded-2xl border border-rose-200 px-4 text-sm font-semibold text-rose-700 transition duration-200 hover:bg-rose-50"
                                  @click="removeInstallation(installation.id)"
                                >
                                  Remove
                                </button>
                              </div>
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>

                    <div v-else class="space-y-3 px-6 py-12 text-center">
                      <p class="text-lg font-semibold text-slate-900">No installations saved yet</p>
                      <p class="text-sm leading-6 text-slate-600">
                        Add an installation to make this device ready for POS.
                      </p>
                    </div>
                  </div>
                </div>
              </section>
            </Transition>
          </div>
        </div>
      </main>
    </div>

    <UModal
      v-model:open="isUpdatePromptOpen"
      :dismissible="false"
      :close="false"
      :ui="{
        content: 'rounded-[1.75rem] border border-white/80 bg-white shadow-[0_24px_80px_rgba(15,23,42,0.18)]',
        header: 'px-6 pt-6',
        body: 'px-6 pb-4',
        footer: 'px-6 pb-6 pt-0'
      }"
      :title="isMandatoryUpdate ? 'App version required' : 'App update recommended'"
    >
      <template #body>
        <div class="space-y-3 text-sm leading-6 text-slate-600">
          <p v-if="isMandatoryUpdate">
            This app is not the correct version for this installation. Supported versions are {{ minAppVersion }}–{{ targetAppVersion }}.
            Install version {{ targetAppVersion }} before you can open the POS.
          </p>
          <p v-else>
            We recommend installing app version {{ targetAppVersion }} for this installation. You can postpone and continue with your current version.
          </p>
          <p class="space-y-1 font-medium">
            <span class="block text-warning">Current version {{ appVersion }}.</span>
            <span class="block text-primary">Required target version {{ targetAppVersion }}.</span>
          </p>
          <p v-if="updateError" role="alert" class="text-error">{{ updateError }}</p>
          <p v-if="updateMessage" role="status">{{ updateMessage }}</p>
        </div>
      </template>

      <template #footer>
        <div class="flex w-full flex-col gap-3 sm:flex-row sm:justify-end">
          <button
            type="button"
            class="inline-flex min-h-12 items-center justify-center rounded-2xl border border-slate-200 px-5 text-sm font-semibold text-slate-700 transition duration-200 hover:border-slate-300 hover:bg-slate-50"
            @click="postponeUpdate"
          >
            {{ isMandatoryUpdate ? 'Cancel' : 'Postpone' }}
          </button>
          <button
            type="button"
            class="inline-flex min-h-12 items-center justify-center rounded-2xl bg-slate-950 px-5 text-sm font-semibold text-white transition duration-200 hover:bg-slate-800"
            @click="acceptUpdate"
          >
            Install version {{ targetAppVersion }}
          </button>
        </div>
      </template>
    </UModal>

    <UModal
      :open="isUpdateBusyOpen"
      :dismissible="false"
      :close="false"
      :ui="{
        content: 'rounded-[1.75rem] border border-white/80 bg-white shadow-[0_24px_80px_rgba(15,23,42,0.18)]',
        header: 'px-6 pt-6',
        body: 'px-6 pb-6'
      }"
      title="Please wait"
      description="The app update is being prepared."
    >
      <template #body>
        <div class="flex items-start gap-4">
          <div class="mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-600">
            <Icon name="lucide:loader-circle" class="size-5 animate-spin" />
          </div>
          <div class="space-y-2">
            <p class="text-sm font-medium text-slate-900">{{ updateBusyMessage }}</p>
            <p class="text-sm leading-6 text-slate-600">
              Do not close the app while the installer is being prepared.
            </p>
          </div>
        </div>
      </template>
    </UModal>
  </UApp>
</template>
