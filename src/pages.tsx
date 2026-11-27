import type { ReactNode } from 'react';

export const COMPANY = 'Vasolai';
export const PRODUCT = 'Dreamhaven';
/** Replace with the address you want visitors to contact. */
export const CONTACT_EMAIL = 'vasolai@outlook.com';
export const LAST_UPDATED = '25 November 2026';

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
        gives you research you can check yourself. Then it’s up to you: pack light, switch off, and enjoy life.
      </p>
      <h2>What {PRODUCT} does</h2>
      <ul>
        <li>Researches destinations, stays, transport, and local transfers from publicly available web sources.</li>
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
      <p>
        Last updated: {LAST_UPDATED}. This policy explains what {PRODUCT} ({COMPANY}) does with the information you enter,
        the information collected when you use the site, and your privacy rights, including under the California
        Consumer Privacy Act (CCPA) as amended by the CPRA.
      </p>

      <h2>Information you should not enter</h2>
      <p>
        Do not enter sensitive personal information into the trip form or the chat. This includes passport, national ID,
        or driver’s license numbers; bank or card numbers; health, religious, or biometric information; precise home
        addresses; and personal information about other people. {PRODUCT} does not need these to plan a trip. Anything
        you type may be sent to our providers and AI models.
      </p>

      <h2>What we collect</h2>
      <ul>
        <li><strong>Trip details you enter:</strong> destination, dates, departure city, trip style, and chat messages.</li>
        <li><strong>Technical information:</strong> your IP address, browser type, and request details, recorded in security and server logs (for example, AWS WAF and CloudWatch) to keep the service safe and working.</li>
        <li><strong>Advertising information:</strong> cookies and identifiers set by Google AdSense (see “Advertising and cookies”).</li>
      </ul>
      <p>
        You can use {PRODUCT} without creating an account. We do not ask for your name, email address, or payment details
        in the planner.
      </p>

      <h2>How we use it</h2>
      <ul>
        <li>To research your trip and show you the results.</li>
        <li>To run, secure, and fix the service.</li>
        <li>To show advertising through Google AdSense (see below).</li>
      </ul>

      <h2>Who processes it</h2>
      <ul>
        <li><strong>Amazon Web Services (AWS)</strong> hosts the service in the United States (US East, N. Virginia) and runs the AI models through Amazon Bedrock.</li>
        <li><strong>Tavily</strong> runs the web searches. Search queries, such as “rental cars in Kyoto”, are sent to Tavily.</li>
        <li><strong>Google (AdSense)</strong> shows ads on this site and uses cookies and similar technologies to do so.</li>
      </ul>

      <h2>Advertising and cookies</h2>
      <p>
        This site shows ads from Google AdSense. Google and its partners use cookies and similar identifiers to show ads
        based on your visits to this and other sites. This may be considered “sharing” personal information for
        advertising under California law.
      </p>
      <ul>
        <li>To control personalized ads, visit <a href="https://adssettings.google.com" target="_blank" rel="noopener noreferrer">adssettings.google.com</a>.</li>
        <li>To opt out of many ad networks at once, visit <a href="https://www.aboutads.info/choices" target="_blank" rel="noopener noreferrer">aboutads.info/choices</a>.</li>
        <li>If you are in the European Economic Area, the United Kingdom, or Switzerland, you may see a consent prompt before personalized ads are shown.</li>
      </ul>
      <p>{PRODUCT} does not use its own advertising or analytics trackers. Google’s cookies are governed by <a href="https://policies.google.com/technologies/partner-sites" target="_blank" rel="noopener noreferrer">Google’s policy for sites that use its services</a>.</p>

      <h2>How long we keep it</h2>
      <ul>
        <li>Trip research and chat messages are stored for about 24 hours, then deleted automatically.</li>
        <li>Server and security logs are kept for about 30 days.</li>
        <li>Backups and advertising cookies are kept as described by their providers.</li>
      </ul>

      <h2>Security</h2>
      <p>
        Data is sent over HTTPS and stored with encryption at rest in our AWS services. No system is perfectly secure, so
        we cannot guarantee that information will never be accessed without authorization. We will notify affected people
        and authorities where the law requires it.
      </p>

      <h2>Your privacy rights (California)</h2>
      <p>If you are a California resident, you have the right to:</p>
      <ul>
        <li>Know what personal information we collect, use, and share about you.</li>
        <li>Request deletion of your personal information, subject to legal exceptions.</li>
        <li>Correct inaccurate personal information.</li>
        <li>Opt out of the sale or sharing of your personal information for advertising (see “Advertising and cookies”).</li>
        <li>Limit the use of sensitive personal information, where applicable.</li>
        <li>Not be discriminated against for exercising these rights.</li>
      </ul>
      <p>
        To make a request, write to <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a> with “Privacy request” in the
        subject. We will verify your request and respond within 45 days, as California law requires. We do not sell
        personal information for money.
      </p>

      <h2>Third-party content and links</h2>
      <p>
        Research results are based on publicly available web pages and third-party sources. We do not control those
        sites, their privacy practices, or their content. Links to other sites are provided for convenience only.
      </p>

      <h2>Children</h2>
      <p>{PRODUCT} is not directed at children under 16, and we do not knowingly collect personal information from them.</p>

      <h2>Changes</h2>
      <p>If we change this policy, we will update the date above. Continued use after a change means you accept the updated policy.</p>

      <h2>Contact</h2>
      <p>Questions about privacy: <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>.</p>
    </Legal>
  );
}

export function TermsPage() {
  return (
    <Legal title="Terms of use">
      <p>Last updated: {LAST_UPDATED}. By using {PRODUCT}, you agree to these terms. If you do not agree, do not use the site.</p>

      <h2>Who may use the service</h2>
      <p>You must be at least 18 years old, or the age of majority where you live, to use {PRODUCT}.</p>

      <h2>What the service is</h2>
      <p>
        {PRODUCT} gives you research and planning suggestions produced with AI and live web search. It is general
        information only, not professional travel, legal, medical, or financial advice, and not a substitute for your own
        judgment.
      </p>

      <h2>Information from the web</h2>
      <ul>
        <li>Research results come from third-party websites and automated search. {COMPANY} does not create or verify most of this content.</li>
        <li>Third-party names, sites, and sources belong to their owners. Including them does not mean they endorse {PRODUCT} or that we endorse them.</li>
        <li>Information may be out of date, incomplete, or wrong. AI can make mistakes.</li>
      </ul>

      <h2>No bookings and no guarantees</h2>
      <ul>
        <li>{PRODUCT} does not make reservations, purchases, or payments.</li>
        <li>Prices, availability, opening hours, weather, visa rules, and entry requirements can change. Check them with the provider or the official government source before you travel.</li>
        <li>Verify important details, especially visas, health rules, insurance, and safety advice.</li>
      </ul>

      <h2>Your content and responsibilities</h2>
      <ul>
        <li>You are responsible for what you enter. Do not enter sensitive personal information or personal information about others (see our Privacy policy).</li>
        <li>Use {PRODUCT} for personal trip planning only. Do not attempt to disrupt the service, scrape it at scale, or use it to harm others or break the law.</li>
        <li>You give us permission to process what you enter in order to provide the service.</li>
      </ul>

      <h2>Advertising</h2>
      <p>The site shows third-party advertising, including Google AdSense. We do not endorse advertised products or services.</p>

      <h2>Disclaimer of warranties</h2>
      <p>
        {PRODUCT} is provided “as is” and “as available”, without warranties of any kind, express or implied, including
        accuracy, reliability, fitness for a particular purpose, and non-infringement.
      </p>

      <h2>Limitation of liability</h2>
      <p>
        To the fullest extent the law allows, {COMPANY}, its operators, and its service providers are not responsible for
        any information on the site or for any loss, injury, cost, or damage arising from your use of it or from travel
        decisions based on it. This includes direct, indirect, incidental, and consequential damages, missed flights,
        unavailable accommodation, changed prices, and third-party content. Because {PRODUCT} is provided free of charge,
        the total liability of {COMPANY} to you for any claim is limited to zero dollars, except where the law does not
        allow this limit.
      </p>
      <p>
        Nothing in these terms limits rights that cannot be waived under applicable law, including non-waivable rights
        under California consumer law.
      </p>

      <h2>Indemnity</h2>
      <p>You agree to hold {COMPANY} harmless from claims arising from your misuse of the service or your violation of these terms.</p>

      <h2>Governing law</h2>
      <p>These terms are governed by the laws of the State of California, United States, without regard to conflict-of-law rules.</p>

      <h2>Changes and termination</h2>
      <p>We may change these terms or stop the service at any time. Continued use after a change means you accept the updated terms.</p>

      <h2>Copyright and trademarks</h2>
      <p>
        © {new Date().getFullYear()} {COMPANY}. All rights reserved. The {PRODUCT} name, the artwork on this site, and the
        site’s text and design belong to {COMPANY}. Third-party names and sources belong to their owners.
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
        <a href="https://adssettings.google.com" target="_blank" rel="noopener noreferrer">Ad choices</a>
        <a href={`mailto:${CONTACT_EMAIL}`}>Contact</a>
      </nav>
      <p>© {new Date().getFullYear()} {COMPANY}. {PRODUCT} is a service of {COMPANY}. All rights reserved.</p>
    </footer>
  );
}
