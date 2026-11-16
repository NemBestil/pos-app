export function getInstallationBaseUrl(id: string) {
  return id.toUpperCase() === 'NGROK' ? 'https://nbpos3.ngrok.dev' : `https://${id.toLowerCase()}.nbpos.eu`
}

export function migrateInstallationLogoUrl(value: string | null | undefined) {
  if (!value) return null

  try {
    const url = new URL(value)
    if (/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.pos3\.nemkasse\.com$/.test(url.hostname)) {
      url.protocol = 'https:'
      url.hostname = url.hostname.replace(/\.pos3\.nemkasse\.com$/, '.nbpos.eu')
      url.port = ''
      return url.href
    }
  } catch {
    // Relative or invalid logo URLs are left to the image loader.
  }

  return value
}
