import type { SVGProps } from 'react';

type Variant = 'beach' | 'coast' | 'lagoon' | 'mountain' | 'valley';

type PlaceProps = {
  label: string;
  variant: Variant;
  sky: string;
  sea: string;
  land: string;
  accent: string;
} & Omit<SVGProps<SVGSVGElement>, 'role'>;

/** One illustrated scene. The shape follows the variant, the colours follow the place. */
export function PlaceScene({ label, variant, sky, sea, land, accent, ...props }: PlaceProps) {
  const id = `band-${label.replace(/[^a-zA-Z]/g, '')}`;
  return (
    <svg viewBox="0 0 300 200" preserveAspectRatio="xMidYMid slice" role="img" aria-label={label} {...props}>
      <defs>
        <linearGradient id={`${id}-sky`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={sky} />
          <stop offset="1" stopColor="#fff8ec" />
        </linearGradient>
      </defs>
      <rect width="300" height="200" fill={`url(#${id}-sky)`} />
      {variant === 'mountain' && (
        <>
          <path d="M0 130 L70 40 L120 110 L170 30 L240 120 L300 60 L300 200 L0 200 Z" fill={land} />
          <path d="M170 30 L152 62 L168 56 L180 68 Z M70 40 L54 66 L70 60 L84 70 Z" fill="#fbf6ec" opacity="0.9" />
          <rect y="128" width="300" height="72" fill={sea} />
        </>
      )}
      {variant === 'valley' && (
        <>
          <circle cx="235" cy="50" r="20" fill={accent} opacity="0.9" />
          <path d="M0 110 C60 70 120 100 170 90 S260 70 300 95 L300 200 L0 200 Z" fill={land} />
          <path d="M0 150 C80 120 160 150 230 140 S290 130 300 140 L300 200 L0 200 Z" fill={sea} opacity="0.85" />
        </>
      )}
      {variant === 'coast' && (
        <>
          <circle cx="220" cy="55" r="20" fill={accent} opacity="0.9" />
          <path d="M0 110 L60 78 L110 105 L160 70 L220 100 L300 82 L300 200 L0 200 Z" fill={land} />
          <rect x="40" y="96" width="22" height="22" fill="#ffab6b" />
          <rect x="130" y="84" width="24" height="26" fill="#fff8ec" />
          <rect x="250" y="92" width="22" height="22" fill="#ff6f59" />
          <rect y="150" width="300" height="50" fill={sea} />
        </>
      )}
      {(variant === 'beach' || variant === 'lagoon') && (
        <>
          <circle cx="225" cy="55" r="22" fill={accent} opacity="0.95" />
          <rect y="105" width="300" height="50" fill={sea} />
          <path d="M0 150 C100 135 200 140 300 150 L300 200 L0 200 Z" fill={land} />
          <path d="M248 150 C240 110 246 82 262 60" stroke="#073f55" strokeWidth="6" fill="none" strokeLinecap="round" />
          <path d="M262 60 C252 54 244 58 240 66 M262 60 C272 52 282 56 286 66 M262 60 C258 46 266 38 276 42" stroke="#073f55" strokeWidth="5" fill="none" strokeLinecap="round" />
        </>
      )}
    </svg>
  );
}

export type Place = { name: string; place: string; variant: Variant; sky: string; sea: string; land: string; accent: string };

/** Pool of beaches and places. The band shows a random selection each time the page loads. */
export const placePool: Place[] = [
  { name: 'Italy', place: 'Amalfi Coast', variant: 'coast', sky: '#5fd3d6', sea: '#0f9aa6', land: '#245f4a', accent: '#f6c453' },
  { name: 'Spain', place: 'Costa Brava', variant: 'coast', sky: '#ff9a6b', sea: '#0b5c78', land: '#c8553d', accent: '#fff3cf' },
  { name: 'Mauritius', place: 'Turquoise lagoon', variant: 'lagoon', sky: '#bdeee8', sea: '#0f9aa6', land: '#fbf6ec', accent: '#f6c453' },
  { name: 'Hawaii', place: 'Hidden valley', variant: 'mountain', sky: '#0b5c78', sea: '#0f9aa6', land: '#245f4a', accent: '#fff3cf' },
  { name: 'Maldives', place: 'Private island', variant: 'lagoon', sky: '#bdeee8', sea: '#5fd3d6', land: '#fbf6ec', accent: '#fff3cf' },
  { name: 'Bali', place: 'Rice terrace beach', variant: 'beach', sky: '#ffab6b', sea: '#0f9aa6', land: '#f6e6d0', accent: '#fff3cf' },
  { name: 'Seychelles', place: 'Granite cove', variant: 'beach', sky: '#5fd3d6', sea: '#0b5c78', land: '#fbf6ec', accent: '#f6c453' },
  { name: 'Portugal', place: 'Algarve beaches', variant: 'coast', sky: '#ffd9a8', sea: '#0b5c78', land: '#c8553d', accent: '#fff3cf' },
  { name: 'Mexico', place: 'Tulum shore', variant: 'beach', sky: '#ffab6b', sea: '#0f9aa6', land: '#f6e6d0', accent: '#fff3cf' },
  { name: 'Switzerland', place: 'Alpine lake', variant: 'mountain', sky: '#0b5c78', sea: '#5fd3d6', land: '#dfeef2', accent: '#fff3cf' },
  { name: 'Dolomites', place: 'Hidden valleys', variant: 'valley', sky: '#bdeee8', sea: '#3f8f6e', land: '#7fb8a0', accent: '#f6c453' },
  { name: 'Greece', place: 'Santorini cliffs', variant: 'coast', sky: '#5fd3d6', sea: '#0b5c78', land: '#fbf6ec', accent: '#ff6f59' },
  { name: 'Bora Bora', place: 'Overwater lagoon', variant: 'lagoon', sky: '#5fd3d6', sea: '#0f9aa6', land: '#fbf6ec', accent: '#f6c453' },
  { name: 'Norway', place: 'Fjord coast', variant: 'mountain', sky: '#bdeee8', sea: '#0b5c78', land: '#245f4a', accent: '#fff8ec' },
  { name: 'Thailand', place: 'Krabi beaches', variant: 'beach', sky: '#ffd9a8', sea: '#0f9aa6', land: '#f6e6d0', accent: '#ff6f59' },
  { name: 'Croatia', place: 'Dalmatian islands', variant: 'coast', sky: '#5fd3d6', sea: '#0b5c78', land: '#245f4a', accent: '#f6c453' },
];

/** Returns `count` different places from the pool, in random order. */
export function pickPlaces(count: number): Place[] {
  const pool = [...placePool];
  for (let i = pool.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [pool[i], pool[j]] = [pool[j], pool[i]];
  }
  return pool.slice(0, count);
}
