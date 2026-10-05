export interface AppVersionRange {
  minAppVersion: string
  targetAppVersion: string
}

export interface InstallationAppVersions {
  minAppVersion?: string
  targetAppVersion?: string
}

export function compareAppVersions(left: string, right: string) {
  const parse = (version: string) => {
    if (!/^\d+\.\d+\.\d+$/.test(version)) {
      throw new Error(`Invalid Android app version: ${version}`)
    }
    return version.split('.').map(Number)
  }
  const leftParts = parse(left)
  const rightParts = parse(right)
  for (let index = 0; index < 3; index++) {
    const difference = leftParts[index]! - rightParts[index]!
    if (difference !== 0) return Math.sign(difference)
  }
  return 0
}

export function resolveAppVersionRange(versions: InstallationAppVersions): AppVersionRange {
  // Installations predating this API contract require exactly app 1.3.9.
  if (versions.minAppVersion === undefined && versions.targetAppVersion === undefined) {
    return { minAppVersion: '1.3.9', targetAppVersion: '1.3.9' }
  }
  if (typeof versions.minAppVersion !== 'string' || typeof versions.targetAppVersion !== 'string') {
    throw new Error('The installation must define both minAppVersion and targetAppVersion.')
  }
  const range = { minAppVersion: versions.minAppVersion, targetAppVersion: versions.targetAppVersion }
  if (compareAppVersions(range.minAppVersion, range.targetAppVersion) > 0) {
    throw new Error('minAppVersion must not exceed targetAppVersion.')
  }
  return range
}

export function isAppVersionInRange(version: string, range: AppVersionRange) {
  return (
    compareAppVersions(version, range.minAppVersion) >= 0 && compareAppVersions(version, range.targetAppVersion) <= 0
  )
}
