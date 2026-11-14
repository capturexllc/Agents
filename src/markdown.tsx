import { Fragment, type ReactNode } from 'react';

// Agent text is untrusted model output. This renderer builds React elements only (no innerHTML), and it only
// turns links into anchors when they are http or https.

export function cleanAgentText(raw: string): string {
  return raw
    .replace(/<thinking>[\s\S]*?<\/thinking>/gi, '')
    .replace(/<\/?(thinking|answer)>/gi, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** One-line plain-text preview for compact cards: no markdown symbols, no links, first ~160 characters. */
export function plainPreview(raw: string, max = 160): string {
  const plain = cleanAgentText(raw)
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/[*_#`>]+/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  return plain.length > max ? `${plain.slice(0, max).trimEnd()}…` : plain;
}

function safeHref(value: string): string | undefined {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.toString() : undefined;
  } catch {
    return undefined;
  }
}

function renderInline(text: string): ReactNode[] {
  const parts = text.split(/(\*\*[^*]+\*\*|\[[^\]]+\]\([^)\s]+\))/g);
  return parts.map((part, index) => {
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
      return <strong key={index}>{part.slice(2, -2)}</strong>;
    }
    const link = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(part);
    if (link) {
      const href = safeHref(link[2]);
      return href
        ? <a key={index} href={href} target="_blank" rel="noreferrer">{link[1]}</a>
        : <Fragment key={index}>{link[1]}</Fragment>;
    }
    return <Fragment key={index}>{part}</Fragment>;
  });
}

export function Markdown({ text }: { text: string }) {
  const lines = cleanAgentText(text).split('\n');
  const blocks: ReactNode[] = [];
  let bullets: string[] = [];
  let paragraph: string[] = [];

  const flushParagraph = () => {
    if (paragraph.length === 0) return;
    const key = blocks.length;
    blocks.push(<p key={`p-${key}`}>{renderInline(paragraph.join(' '))}</p>);
    paragraph = [];
  };
  const flushBullets = () => {
    if (bullets.length === 0) return;
    const key = blocks.length;
    blocks.push(
      <ul key={`ul-${key}`}>
        {bullets.map((item, index) => <li key={index}>{renderInline(item)}</li>)}
      </ul>,
    );
    bullets = [];
  };

  for (const rawLine of lines) {
    const line = rawLine.trimEnd();
    const heading = /^\s*#{1,4}\s+(.*)$/.exec(line);
    const bullet = /^\s*[-*•]\s+(.*)$/.exec(line);

    if (!line.trim()) {
      flushParagraph();
      flushBullets();
    } else if (heading) {
      flushParagraph();
      flushBullets();
      const key = blocks.length;
      blocks.push(<h4 key={`h-${key}`} className="md-heading">{renderInline(heading[1])}</h4>);
    } else if (bullet) {
      flushParagraph();
      bullets.push(bullet[1]);
    } else {
      flushBullets();
      paragraph.push(line.trim());
    }
  }
  flushParagraph();
  flushBullets();

  return <div className="md">{blocks}</div>;
}
