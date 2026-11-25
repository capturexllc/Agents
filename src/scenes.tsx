/**
 * Original illustrations for Dreamhaven. Drawn as SVG in the site palette, so they need no image files and no external
 * requests (the Content-Security-Policy allows inline SVG). To use photographs or AI-generated images instead, place
 * files in public/images/ and render them with <img> in place of these components.
 */

type SceneProps = { label: string; className?: string };

const frame = { viewBox: '0 0 1200 600', preserveAspectRatio: 'xMidYMid slice' } as const;

export function OceanSunset({ label, className }: SceneProps) {
  return (
    <svg {...frame} role="img" aria-label={label} className={className}>
      <defs>
        <linearGradient id="sky-sunset" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#0b5c78" />
          <stop offset="0.55" stopColor="#5fd3d6" />
          <stop offset="0.8" stopColor="#ffab6b" />
          <stop offset="1" stopColor="#ff6f59" />
        </linearGradient>
        <radialGradient id="sun-glow">
          <stop offset="0" stopColor="#f6c453" stopOpacity="0.95" />
          <stop offset="1" stopColor="#f6c453" stopOpacity="0" />
        </radialGradient>
      </defs>
      <rect width="1200" height="600" fill="url(#sky-sunset)" />
      <circle cx="860" cy="330" r="220" fill="url(#sun-glow)" />
      <circle cx="860" cy="330" r="82" fill="#fff3cf" />
      <path d="M0 360 C180 300 320 330 480 360 S760 390 900 350 S1120 300 1200 330 L1200 420 L0 420 Z" fill="#073f55" opacity="0.55" />
      <path d="M0 400 C200 360 360 400 540 410 S860 380 1000 405 S1150 420 1200 400 L1200 470 L0 470 Z" fill="#0b5c78" opacity="0.8" />
      <path d="M0 440 C220 420 380 455 580 452 S900 430 1060 448 S1170 452 1200 446 L1200 600 L0 600 Z" fill="#073f55" />
      <path d="M820 452 L900 452 M760 470 L960 470 M700 490 L1000 490" stroke="#fff3cf" strokeWidth="4" strokeLinecap="round" opacity="0.6" />
    </svg>
  );
}

export function HillsValley({ label, className }: SceneProps) {
  return (
    <svg {...frame} role="img" aria-label={label} className={className}>
      <defs>
        <linearGradient id="sky-valley" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#bdeee8" />
          <stop offset="1" stopColor="#fbf6ec" />
        </linearGradient>
      </defs>
      <rect width="1200" height="600" fill="url(#sky-valley)" />
      <circle cx="210" cy="150" r="54" fill="#f6c453" opacity="0.9" />
      <path d="M0 330 C160 240 300 260 440 320 S700 250 860 300 S1100 230 1200 280 L1200 600 L0 600 Z" fill="#7fb8a0" />
      <path d="M0 400 C220 330 380 370 560 400 S900 330 1050 380 S1150 360 1200 370 L1200 600 L0 600 Z" fill="#3f8f6e" />
      <path d="M0 470 C200 430 420 470 640 460 S980 430 1200 460 L1200 600 L0 600 Z" fill="#245f4a" />
      <path d="M520 600 C560 520 640 480 760 470" stroke="#fbf6ec" strokeWidth="10" fill="none" opacity="0.7" />
    </svg>
  );
}

export function TurquoiseLagoon({ label, className }: SceneProps) {
  return (
    <svg {...frame} role="img" aria-label={label} className={className}>
      <defs>
        <linearGradient id="sea-lagoon" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#5fd3d6" />
          <stop offset="1" stopColor="#0f9aa6" />
        </linearGradient>
      </defs>
      <rect width="1200" height="600" fill="#bdeee8" />
      <rect y="250" width="1200" height="350" fill="url(#sea-lagoon)" />
      <path d="M0 250 C200 230 420 240 620 262 S1000 250 1200 240 L1200 270 C1000 280 800 270 600 290 S200 275 0 290 Z" fill="#fbf6ec" opacity="0.7" />
      <ellipse cx="720" cy="430" rx="230" ry="46" fill="#fbf6ec" opacity="0.9" />
      <path d="M1040 260 C1000 200 1020 140 1060 110" stroke="#073f55" strokeWidth="10" fill="none" strokeLinecap="round" />
      <path d="M1060 110 C1010 100 980 110 960 130 M1060 110 C1100 90 1130 100 1150 130 M1060 110 C1040 70 1060 50 1090 60" stroke="#073f55" strokeWidth="8" fill="none" strokeLinecap="round" />
      <path d="M0 520 C300 500 520 540 800 520 S1100 500 1200 520 L1200 600 L0 600 Z" fill="#0b5c78" opacity="0.5" />
    </svg>
  );
}

export function AlpineLake({ label, className }: SceneProps) {
  return (
    <svg {...frame} role="img" aria-label={label} className={className}>
      <defs>
        <linearGradient id="sky-alpine" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#0b5c78" />
          <stop offset="1" stopColor="#bdeee8" />
        </linearGradient>
      </defs>
      <rect width="1200" height="600" fill="url(#sky-alpine)" />
      <path d="M0 360 L180 170 L300 300 L430 120 L600 330 L740 150 L900 320 L1040 130 L1200 330 L1200 600 L0 600 Z" fill="#dfeef2" />
      <path d="M430 120 L380 200 L430 185 L470 210 Z M1040 130 L990 210 L1040 195 L1080 215 Z M180 170 L140 240 L185 228 L220 245 Z" fill="#fbf6ec" />
      <path d="M0 420 L240 340 L460 400 L700 330 L960 410 L1200 350 L1200 600 L0 600 Z" fill="#245f4a" />
      <rect y="470" width="1200" height="130" fill="#0f9aa6" opacity="0.85" />
      <path d="M160 500 H430 M520 530 H780 M840 490 H1090" stroke="#fbf6ec" strokeWidth="4" strokeLinecap="round" opacity="0.5" />
    </svg>
  );
}
