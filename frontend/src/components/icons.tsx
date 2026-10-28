/** Minimal inline SVG icon set - no icon dependency required. */

import type { SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement> & { size?: number }

function Base({ size = 18, children, ...rest }: IconProps & { children: React.ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      {...rest}
    >
      {children}
    </svg>
  )
}

export const IconShield = (p: IconProps) => (
  <Base {...p}>
    <path d="M12 3 5 6v5c0 4.4 2.9 8.3 7 9.6 4.1-1.3 7-5.2 7-9.6V6l-7-3Z" />
    <path d="m9.2 12 2 2 3.6-3.8" />
  </Base>
)

export const IconGauge = (p: IconProps) => (
  <Base {...p}>
    <path d="M4 18a8 8 0 1 1 16 0" />
    <path d="M12 18 15.5 10.5" />
    <circle cx="12" cy="18" r="1.4" />
  </Base>
)

export const IconNetwork = (p: IconProps) => (
  <Base {...p}>
    <circle cx="12" cy="5" r="2.2" />
    <circle cx="5" cy="18" r="2.2" />
    <circle cx="19" cy="18" r="2.2" />
    <path d="M12 7.2v4.3M12 11.5 6.6 16.4M12 11.5l5.4 4.9" />
  </Base>
)

export const IconChip = (p: IconProps) => (
  <Base {...p}>
    <rect x="7" y="7" width="10" height="10" rx="2" />
    <path d="M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4" />
  </Base>
)

export const IconRadar = (p: IconProps) => (
  <Base {...p}>
    <circle cx="12" cy="12" r="8.5" />
    <circle cx="12" cy="12" r="4.5" />
    <path d="M12 12 18 6.5" />
    <circle cx="15.6" cy="8.6" r="1.1" fill="currentColor" stroke="none" />
  </Base>
)

export const IconBell = (p: IconProps) => (
  <Base {...p}>
    <path d="M6.5 9.5a5.5 5.5 0 0 1 11 0c0 4 1.5 5.5 1.5 5.5H5s1.5-1.5 1.5-5.5Z" />
    <path d="M10.2 18.5a2 2 0 0 0 3.6 0" />
  </Base>
)

export const IconReport = (p: IconProps) => (
  <Base {...p}>
    <path d="M6 3h8l4 4v14H6z" />
    <path d="M14 3v4h4M9.5 17v-3M12 17v-5.5M14.5 17v-2" />
  </Base>
)

export const IconGear = (p: IconProps) => (
  <Base {...p}>
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 13.5a7.6 7.6 0 0 0 0-3l1.6-1.2-1.8-3.1-1.9.7a7.6 7.6 0 0 0-2.6-1.5L14.3 3H9.7l-.4 2.4a7.6 7.6 0 0 0-2.6 1.5l-1.9-.7-1.8 3.1L4.6 10.5a7.6 7.6 0 0 0 0 3L3 14.7l1.8 3.1 1.9-.7a7.6 7.6 0 0 0 2.6 1.5l.4 2.4h4.6l.4-2.4a7.6 7.6 0 0 0 2.6-1.5l1.9.7 1.8-3.1-1.6-1.2Z" />
  </Base>
)

export const IconSearch = (p: IconProps) => (
  <Base {...p}>
    <circle cx="11" cy="11" r="6.5" />
    <path d="m16 16 4 4" />
  </Base>
)

export const IconUpload = (p: IconProps) => (
  <Base {...p}>
    <path d="M12 16V5" />
    <path d="m8 9 4-4 4 4" />
    <path d="M4.5 15v2.5A2.5 2.5 0 0 0 7 20h10a2.5 2.5 0 0 0 2.5-2.5V15" />
  </Base>
)

export const IconDownload = (p: IconProps) => (
  <Base {...p}>
    <path d="M12 4v11" />
    <path d="m8 11 4 4 4-4" />
    <path d="M4.5 16v1.5A2.5 2.5 0 0 0 7 20h10a2.5 2.5 0 0 0 2.5-2.5V16" />
  </Base>
)

export const IconGlobe = (p: IconProps) => (
  <Base {...p}>
    <circle cx="12" cy="12" r="8.5" />
    <path d="M3.5 12h17M12 3.5c2.2 2.3 3.3 5.2 3.3 8.5S14.2 18.2 12 20.5c-2.2-2.3-3.3-5.2-3.3-8.5S9.8 5.8 12 3.5Z" />
  </Base>
)

export const IconMenu = (p: IconProps) => (
  <Base {...p}>
    <path d="M4 7h16M4 12h16M4 17h16" />
  </Base>
)

export const IconClose = (p: IconProps) => (
  <Base {...p}>
    <path d="m6 6 12 12M18 6 6 18" />
  </Base>
)

export const IconRefresh = (p: IconProps) => (
  <Base {...p}>
    <path d="M20 12a8 8 0 1 1-2.6-5.9" />
    <path d="M20 4v4h-4" />
  </Base>
)

export const IconAlert = (p: IconProps) => (
  <Base {...p}>
    <path d="M12 4.5 3 19.5h18L12 4.5Z" />
    <path d="M12 10v4M12 17h.01" />
  </Base>
)

export const IconCheck = (p: IconProps) => (
  <Base {...p}>
    <path d="m5 13 4 4L19 7" />
  </Base>
)

export const IconInfo = (p: IconProps) => (
  <Base {...p}>
    <circle cx="12" cy="12" r="8.5" />
    <path d="M12 11v5M12 8h.01" />
  </Base>
)

export const IconDatabase = (p: IconProps) => (
  <Base {...p}>
    <ellipse cx="12" cy="6" rx="7" ry="3" />
    <path d="M5 6v6c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" />
  </Base>
)

export const IconPulse = (p: IconProps) => (
  <Base {...p}>
    <path d="M3 12h4l2-6 4 12 2-6h6" />
  </Base>
)

export const IconPlay = (p: IconProps) => (
  <Base {...p}>
    <polygon points="8,5 19,12 8,19" fill="currentColor" stroke="none" />
  </Base>
)

export const IconStop = (p: IconProps) => (
  <Base {...p}>
    <rect x="7" y="7" width="10" height="10" rx="1.5" fill="currentColor" stroke="none" />
  </Base>
)
