import type { ReactNode } from 'react';

export const COMPANY = 'Vasolai';
export const PRODUCT = 'Dreamhaven';
/** Replace with the address you want visitors to contact. */
export const CONTACT_EMAIL = 'vasolai@outlook.com';
export const LAST_UPDATED = '24 November 2026';

export type Page = 'home' | 'about' | 'privacy' | 'terms';

function Legal({ title, children }: { title: string; children: ReactNode }) {
  return (
    <article className="legal-page">
      <p className="eyebrow">{PRODUCT.toUpperCase()} · {COMPANY.toUpperCase()}</p>
      <h1>{title}</h1>
      {children}
    </article>
  );
}

export function AboutPage() {
  return (
    <article className="legal-page">
      <p className="eyebrow">ABOUT</p>
      <h1>Your private destination dream haven</h1>
      <p>
        {PRODUCT} is a trip planner from {COMPANY} for people who want to get away from the crowds. Tell us where you
        dream of going, when, and roughly how you like to travel. Our research agents look for quiet coastlines,
        high valleys, lakeside cabins, and the routes that get you there.
      </p>
      <p>
        We believe the best trips are personal. So {PRODUCT} plans around your taste, your pace, and your budget, and it
        gives you sourced research you can check yourself. Then it's up to you: pack light, switch off, and enjoy life.
      </p>
      <h2>What {PRODUCT} does</h2>
      <ul>
        <li>Researches destinations, stays, transport, and local transfers, citing sources for every claim.</li>
        <li>Combines the research into a day-by-day outline you can adjust.</li>
        <li>Answers follow-up questions about your trip.</li>
      </ul>
      <h2>What {PRODUCT} does not do</h2>
      <ul>
        <li>It does not book, buy, or change anything for you.</li>
        <li>It does not guarantee prices, availability, or weather. Confirm details with each provider.</li>
      </ul>
      <p>Questions? Write to <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>.</p>
    </article>
  );
}

export function PrivacyPage() {
  return (
    <Legal title="Privacy policy">
      <p>Last updated: {LAST_UPDATED}. This policy explains what {PRODUCT} ({COMPANY}) does with the information you enter.</p>

      <h2>What we collect</h2>
      <p>
        You can plan a trip without creating an account. When you plan a trip or ask a question about it, we process
        what you type: the destination, your dates, your departure city, your trip style, and your chat messages. We do
        not ask for your name, email address, or payment details.
      </p>

      <h2>How it is used</h2>
      <ul>
        <li>To research your trip and show the results to you.</li>
        <li>To run the service, keep it secure, and fix problems.</li>
      </ul>
      <p>We do not sell your information, and we do not use it for advertising.</p>

      <h2>Who processes it</h2>
      <ul>
        <li><strong>Amazon Web Services</strong> hosts the service in Canada (ca-central-1) and runs the AI models through Amazon Bedrock.</li>
        <li><strong>Tavily</strong> runs the web searches. Search queries, such as "rental cars in Kyoto", are sent to Tavily.</li>
      </ul>
      <p>Anything you type into the trip form or the chat is sent to these providers to produce your research.</p>

      <h2>How long it is kept</h2>
      <ul>
        <li>Trip research and chat messages are stored for about 24 hours, then deleted automatically.</li>
        <li>Server logs are kept for 30 days for security and troubleshooting.</li>
      </ul>

      <h2>Cookies and tracking</h2>
      <p>{PRODUCT} does not set advertising or tracking cookies.</p>

      <h2>Your choices</h2>
      <p>
        Don't enter information you don't want processed. To ask for your data to be deleted sooner, or to ask a question,
        write to <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>.
      </p>

      <h2>Children</h2>
      <p>{PRODUCT} is not directed at children under 16.</p>

      <h2>Changes</h2>
      <p>If we change this policy, we will update the date above.</p>
    </Legal>
  );
}

export function TermsPage() {
  return (
    <Legal title="Terms of use">
      <p>Last updated: {LAST_UPDATED}. By using {PRODUCT}, you agree to these terms.</p>

      <h2>What the service is</h2>
      <p>
        {PRODUCT} gives you research and planning suggestions, produced with AI and live web search. It is a starting
        point for your own decisions.
      </p>

      <h2>No bookings and no guarantees</h2>
      <ul>
        <li>{PRODUCT} does not make reservations, purchases, or payments.</li>
        <li>Prices, availability, opening hours, weather, and entry requirements can change. Check them with the provider before you travel.</li>
        <li>AI can make mistakes. Verify important details, especially visas, health rules, and safety advice.</li>
      </ul>

      <h2>Acceptable use</h2>
      <p>Use {PRODUCT} for personal trip planning. Do not attempt to disrupt the service, scrape it at scale, or use it to harm others.</p>

      <h2>Your content</h2>
      <p>The trip details and messages you enter are yours. You give us permission to process them to provide the service.</p>

      <h2>Limitation of liability</h2>
      <p>
        To the fullest extent the law allows, {COMPANY} is not liable for losses arising from travel decisions made with
        {PRODUCT}'s research, including missed flights, unavailable accommodation, or changed prices.
      </p>

      <h2>Copyright and trademarks</h2>
      <p>
        © {new Date().getFullYear()} {COMPANY}. All rights reserved. The {PRODUCT} name, the artwork on this site, and the
        site's text and design belong to {COMPANY}. Third-party names and sources belong to their owners.
      </p>

      <h2>Contact</h2>
      <p><a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a></p>
    </Legal>
  );
}

export function SiteFooter({ onNavigate }: { onNavigate: (page: Page) => void }) {
  return (
    <footer className="site-footer">
      <nav aria-label="Legal and company">
        <button type="button" onClick={() => onNavigate('about')}>About</button>
        <button type="button" onClick={() => onNavigate('privacy')}>Privacy</button>
        <button type="button" onClick={() => onNavigate('terms')}>Terms</button>
        <a href={`mailto:${CONTACT_EMAIL}`}>Contact</a>
      </nav>
      <p>© {new Date().getFullYear()} {COMPANY}. {PRODUCT} is a service of {COMPANY}. All rights reserved.</p>
    </footer>
  );
}
