import { readFile } from 'node:fs/promises'

const path = process.argv[2]
if (!path) {
  throw new Error('Usage: node scripts/check-sbom.mjs <bom.json>')
}

const bom = JSON.parse(await readFile(path, 'utf8'))
const components = Array.isArray(bom.components) ? bom.components : []
if (components.length === 0) {
  throw new Error('SBOM contains no resolved dependency components.')
}

const searchable = components
  .map((component) => `${component.group ?? ''}:${component.name ?? ''} ${component.purl ?? ''}`)
  .join('\n')
for (const required of ['androidx.activity:activity-compose', 'androidx.compose.material3:material3']) {
  if (!searchable.includes(required)) {
    throw new Error(`SBOM is missing required runtime component ${required}.`)
  }
}

const allowedLicense = /(apache|\bmit\b|\bbsd\b|eclipse public|\bepl\b|unicode|icu|public domain)/i
const rootGroup = bom.metadata?.component?.group
const isLocalProjectComponent = (component) => {
  const group = component.group
  const purl = component.purl
  return (
    typeof rootGroup === 'string' &&
    typeof group === 'string' &&
    (group === rootGroup || group.startsWith(`${rootGroup}.`)) &&
    typeof purl === 'string' &&
    /[?&]project_path=(?:%3A|:)/i.test(purl)
  )
}
const unlicensed = []
const disallowed = []
for (const component of components) {
  if (isLocalProjectComponent(component)) {
    continue
  }
  const licenses = Array.isArray(component.licenses) ? component.licenses : []
  const labels = licenses
    .flatMap((choice) => [choice?.license?.id, choice?.license?.name, choice?.expression])
    .filter((value) => typeof value === 'string' && value.length > 0)
  const name = `${component.group ?? ''}:${component.name ?? '<unnamed>'}`
  if (labels.length === 0) {
    unlicensed.push(name)
  } else if (!labels.some((label) => allowedLicense.test(label))) {
    disallowed.push(`${name} (${labels.join(', ')})`)
  }
}

if (unlicensed.length > 0) {
  throw new Error(`Dependencies without license metadata:\n${unlicensed.join('\n')}`)
}
if (disallowed.length > 0) {
  throw new Error(`Dependencies outside the reviewed license policy:\n${disallowed.join('\n')}`)
}

console.log(`Verified ${components.length} dependency components and their license metadata.`)
