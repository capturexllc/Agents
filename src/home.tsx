import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Pause, Play } from 'lucide-react';
import { AlpineLake, HillsValley, OceanSunset, TurquoiseLagoon } from './scenes';

export const destinationSlides = [
  { name: 'Amalfi Coast, Italy', blurb: 'Cliffside villages, lemon groves, and a sea that glows at dawn.', scene: OceanSunset },
  { name: 'Swiss Alps', blurb: 'Snow peaks, mirror lakes, and cable cars that stop time.', scene: AlpineLake },
  { name: 'Dolomites, Italy', blurb: 'Hidden valleys and pastel walls that look painted on.', scene: HillsValley },
  { name: 'Maldives lagoon', blurb: 'Turquoise water, a private island, and nothing on your calendar.', scene: TurquoiseLagoon },
];

const AUTOPLAY_MS = 6000;

/**
 * Rotating destination showcase. Autoplays unless the visitor pauses it or prefers reduced motion. Choosing a slide
 * fills in the trip form's destination.
 */
export function DestinationCarousel({ onPick }: { onPick: (name: string) => void }) {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(() =>
    typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const count = destinationSlides.length;

  useEffect(() => {
    if (paused) return;
    const timer = window.setInterval(() => setIndex((current) => (current + 1) % count), AUTOPLAY_MS);
    return () => window.clearInterval(timer);
  }, [paused, count]);

  const move = (step: number) => setIndex((current) => (current + step + count) % count);
  const slide = destinationSlides[index];

  return (
    <section className="dh-carousel" aria-roledescription="carousel" aria-label="Dream destinations"
      onKeyDown={(event) => {
        if (event.key === 'ArrowRight') move(1);
        if (event.key === 'ArrowLeft') move(-1);
      }}
      tabIndex={0}>
      <div className="dh-stage">
        {destinationSlides.map((item, position) => (
          <div key={item.name} className={`dh-slide ${position === index ? 'active' : ''}`} aria-hidden={position !== index}>
            <item.scene label={item.name} className="dh-art" />
          </div>
        ))}
        <div className="dh-caption" key={slide.name}>
          <span className="dh-kicker">Tonight's daydream</span>
          <h2>{slide.name}</h2>
          <p>{slide.blurb}</p>
          <button type="button" className="dh-pick" onClick={() => onPick(slide.name)}>
            Plan a trip here <ChevronRight size={16} />
          </button>
        </div>
        <button type="button" className="dh-arrow dh-prev" onClick={() => move(-1)} aria-label="Previous destination">
          <ChevronLeft size={20} />
        </button>
        <button type="button" className="dh-arrow dh-next" onClick={() => move(1)} aria-label="Next destination">
          <ChevronRight size={20} />
        </button>
      </div>
      <div className="dh-controls">
        <div className="dh-dots" role="group" aria-label="Choose a destination">
          {destinationSlides.map((item, position) => (
            <button key={item.name} type="button" className={position === index ? 'active' : ''}
              onClick={() => setIndex(position)} aria-label={`Show ${item.name}`} aria-current={position === index} />
          ))}
        </div>
        <button type="button" className="dh-pause" onClick={() => setPaused((value) => !value)}
          aria-pressed={paused} aria-label={paused ? 'Resume slideshow' : 'Pause slideshow'}>
          {paused ? <Play size={14} /> : <Pause size={14} />} {paused ? 'Play' : 'Pause'}
        </button>
      </div>
    </section>
  );
}
