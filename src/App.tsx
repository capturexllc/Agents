import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  ArrowDownRight,
  ArrowRight,
  BedDouble,
  Car,
  Check,
  ChevronDown,
  CircleAlert,
  Compass,
  Globe2,
  LoaderCircle,
  MessageCircle,
  Plane,
  Send,
  Sparkles,
} from 'lucide-react';
import { api } from './api';
import { authRequired, ensureSignedIn, signOut } from './auth';

type AgentId = 'destination' | 'stay' | 'transport' | 'taxi';
type AgentStatus = 'idle' | 'running' | 'summarizing' | 'completed' | 'failed';

type SourceReference = {
  title: string;
  url: string;
  excerpt: string;
  retrievedAt: string;
};

type TripEvent = {
  seq: number;
  type: string;
  tripId: string;
  agentId: AgentId | null;
  correlationId: string | null;
  state: AgentStatus | 'partial';
  message: string;
  sources: SourceReference[];
  timestamp: string;
};

type EventsPage = {
  status: 'planning' | 'completed' | 'partial';
  nextAfter: number;
  events: TripEvent[];
};

type AgentState = {
  id: AgentId;
  name: string;
  subtitle: string;
  status: AgentStatus;
  message: string;
  sources: SourceReference[];
  searches: string[];
};

type ChatMessage = { role: 'user' | 'assistant'; content: string };

const agentBlueprint: Omit<AgentState, 'status' | 'message' | 'sources' | 'searches'>[] = [
  { id: 'destination', name: 'Destination scout', subtitle: 'Places, culture, local know-how' },
  { id: 'stay', name: 'Stay & experiences', subtitle: 'Weather, events, hotels, offers' },
  { id: 'transport', name: 'Flights', subtitle: 'Flexible dates, connections, fares' },
  { id: 'taxi', name: 'Taxi & car', subtitle: 'Transfers, ride-hail, rental cars' },
];

const defaultAgents = (): AgentState[] => agentBlueprint.map((agent) => ({
  ...agent,
  status: 'idle',
  message: 'Ready when you are.',
  sources: [],
  searches: [],
}));

const plusDays = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);

function sourceHref(value: string) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.toString() : undefined;
  } catch {
    return undefined;
  }
}

function AgentIcon({ id, size = 18 }: { id: AgentId; size?: number }) {
  if (id === 'stay') return <BedDouble size={size} strokeWidth={1.8} />;
  if (id === 'transport') return <Plane size={size} strokeWidth={1.8} />;
  if (id === 'taxi') return <Car size={size} strokeWidth={1.8} />;
  return <Compass size={size} strokeWidth={1.8} />;
}

function App() {
  const [destination, setDestination] = useState('');
  const [origin, setOrigin] = useState('');
  const [departureDate, setDepartureDate] = useState(plusDays(30));
  const [returnDate, setReturnDate] = useState(plusDays(35));
  const [budget, setBudget] = useState('');
  const [tripId, setTripId] = useState<string | null>(null);
  const [tripSummary, setTripSummary] = useState('');
  const [agents, setAgents] = useState(defaultAgents);
  const [tripStatus, setTripStatus] = useState<'idle' | 'planning' | 'completed' | 'partial' | 'error'>('idle');
  const [error, setError] = useState('');
  const [chatInput, setChatInput] = useState('');
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [chatBusy, setChatBusy] = useState(false);
  const [showDetails, setShowDetails] = useState(false);
  const [ready, setReady] = useState(!authRequired);
  const [pollKey, setPollKey] = useState(0);
  const cursorRef = useRef(0);
  const pendingChats = useRef(new Set<string>());

  useEffect(() => {
    if (!authRequired) return;
    ensureSignedIn()
      .then((signedIn) => { if (signedIn) setReady(true); })
      .catch((cause: unknown) => setError(cause instanceof Error ? cause.message : 'Sign-in failed.'));
  }, []);

  // Polls the trip's event log. The cursor lives in a ref so restarting the loop (for example after a chat
  // question) never replays events that were already applied.
  useEffect(() => {
    if (!tripId) return;
    let stopped = false;
    let timer: number | undefined;

    const applyEvent = (event: TripEvent) => {
      if (event.type === 'chat_answer') {
        pendingChats.current.delete(event.correlationId ?? '');
        setChatBusy(pendingChats.current.size > 0);
        setChatMessages((current) => [...current, { role: 'assistant', content: event.message }]);
        return;
      }
      if (event.type === 'trip_summary') {
        setTripSummary(event.message);
        return;
      }
      if (event.type === 'trip_complete') {
        setTripStatus(event.state === 'partial' ? 'partial' : 'completed');
        return;
      }
      const agentId = event.agentId;
      if (!agentId) return;
      setAgents((current) => current.map((agent) => {
        if (agent.id !== agentId) return agent;
        if (event.type === 'agent_tool') {
          return { ...agent, status: 'running', message: `Searching: ${event.message}`, searches: [...agent.searches, event.message] };
        }
        return {
          ...agent,
          status: event.state as AgentStatus,
          message: event.message,
          sources: event.sources?.length ? event.sources : agent.sources,
        };
      }));
    };

    const poll = async () => {
      try {
        const page = await api<EventsPage>(`/api/trips/${tripId}/events?after=${cursorRef.current}`);
        if (stopped) return;
        cursorRef.current = page.nextAfter;
        page.events.forEach(applyEvent);
        if (page.status !== 'planning' && pendingChats.current.size === 0) return;
      } catch (cause) {
        if (stopped) return;
        setError(cause instanceof Error ? cause.message : 'Lost connection to VamsDest. Retrying…');
      }
      if (!stopped) timer = window.setTimeout(poll, 1500);
    };
    void poll();

    return () => {
      stopped = true;
      window.clearTimeout(timer);
    };
  }, [tripId, pollKey]);

  async function planTrip(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setTripStatus('planning');
    setTripId(null);
    setTripSummary('');
    cursorRef.current = 0;
    pendingChats.current.clear();
    setAgents(defaultAgents().map((agent) => ({ ...agent, status: 'running', message: 'Joining the research queue.' })));
    setChatMessages([{ role: 'user', content: `Plan a trip to ${destination} from ${departureDate} to ${returnDate}.` }]);

    try {
      const payload = await api<{ tripId: string }>('/api/trips', {
        method: 'POST',
        body: JSON.stringify({
          destination: destination.trim(),
          origin: origin.trim() || null,
          departureDate,
          returnDate,
          budget: budget || null,
        }),
      });
      setTripId(payload.tripId);
    } catch (cause) {
      setTripStatus('error');
      setAgents(defaultAgents());
      setError(cause instanceof Error ? cause.message : 'Could not connect to VamsDest.');
    }
  }

  async function sendMessage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!tripId || !chatInput.trim() || chatBusy) return;
    const question = chatInput.trim();
    setChatInput('');
    setChatMessages((current) => [...current, { role: 'user', content: question }]);
    setChatBusy(true);
    try {
      const payload = await api<{ requestId: string }>(`/api/trips/${tripId}/chat`, {
        method: 'POST',
        body: JSON.stringify({ message: question }),
      });
      pendingChats.current.add(payload.requestId);
      setPollKey((key) => key + 1);
    } catch (cause) {
      setChatBusy(false);
      setChatMessages((current) => [...current, {
        role: 'assistant',
        content: cause instanceof Error ? cause.message : 'The travel assistant could not respond.',
      }]);
    }
  }

  if (!ready) {
    return (
      <div className="auth-gate" role="status">
        <LoaderCircle className="spin" size={20} />
        <p>{error || 'Signing you in…'}</p>
      </div>
    );
  }

  const runningCount = agents.filter((agent) => agent.status === 'running' || agent.status === 'summarizing').length;
  const isBusy = tripStatus === 'planning' && (runningCount > 0 || !tripId);

  return (
    <div className="app-shell">
      <header className="topbar">
        <a className="brand" href="#top" aria-label="VamsDest home">
          <span className="brand-mark"><Plane size={19} fill="currentColor" /></span>
          <span className="brand-name">vams<span>dest</span></span>
          <span className="brand-divider" />
          <span className="brand-caption">TRAVEL STUDIO</span>
        </a>
        <div className="topbar-right">
          <span className="live-indicator"><span /> {tripStatus === 'planning' ? 'RESEARCH IN PROGRESS' : 'YOUR TRAVEL DESK'}</span>
          {authRequired && (
            <button className="signin-link" type="button" onClick={signOut}>Sign out <ArrowRight size={14} /></button>
          )}
          <button className="avatar" type="button" aria-label="Traveler profile">V</button>
        </div>
      </header>

      <main id="top" className="main-content">
        <section className="workspace-intro">
          <div className="intro-copy">
            <p className="eyebrow"><Sparkles size={14} /> YOUR NEXT CHAPTER, WELL PLANNED</p>
            <h1>Where to <em>next?</em></h1>
            <p className="intro-subtitle">Give us a place and a few dates. We’ll get the details moving.</p>
          </div>
          <div className="season-note"><span>01</span><span className="season-rule" /><span>THE WORLD IS OPEN</span></div>
        </section>

        <section className="workspace-grid" aria-label="Trip planning workspace">
          <div className="left-column">
            <form className="trip-form" onSubmit={planTrip}>
              <div className="form-heading">
                <div>
                  <span className="section-kicker">START WITH THE BASICS</span>
                  <h2>Build your trip</h2>
                </div>
                <Globe2 className="heading-globe" size={22} />
              </div>

              <label className="field-label" htmlFor="destination">Where are you headed?</label>
              <div className="destination-field">
                <Compass size={19} />
                <input
                  id="destination"
                  name="destination"
                  autoComplete="off"
                  placeholder="A city, country, or coastline"
                  value={destination}
                  onChange={(event) => setDestination(event.target.value)}
                  maxLength={120}
                  required
                />
                <span className="field-optional">DESTINATION</span>
              </div>

              <div className="field-row">
                <label className="field-block" htmlFor="origin">
                  <span className="field-label">Flying from <small>OPTIONAL</small></span>
                  <span className="input-wrap"><Plane size={16} /><input id="origin" placeholder="City or airport" value={origin} onChange={(event) => setOrigin(event.target.value)} maxLength={120} /></span>
                </label>
                <label className="field-block" htmlFor="budget">
                  <span className="field-label">Trip style <small>OPTIONAL</small></span>
                  <span className="input-wrap select-wrap">
                    <Sparkles size={16} />
                    <select id="budget" value={budget} onChange={(event) => setBudget(event.target.value)}>
                      <option value="">Any budget</option>
                      <option value="value">Good value</option>
                      <option value="comfort">Comfort</option>
                      <option value="premium">Make it special</option>
                    </select>
                    <ChevronDown className="select-chevron" size={15} />
                  </span>
                </label>
              </div>

              <div className="field-row date-row">
                <label className="field-block" htmlFor="departureDate">
                  <span className="field-label">Leaving</span>
                  <span className="input-wrap"><input id="departureDate" type="date" value={departureDate} onChange={(event) => setDepartureDate(event.target.value)} required /></span>
                </label>
                <label className="field-block" htmlFor="returnDate">
                  <span className="field-label">Coming back</span>
                  <span className="input-wrap"><input id="returnDate" type="date" value={returnDate} min={departureDate} onChange={(event) => setReturnDate(event.target.value)} required /></span>
                </label>
              </div>

              <div className="form-footer">
                <span className="form-footnote"><span className="footnote-dot" /> FOUR SPECIALISTS, ONE PLAN</span>
                <button className="submit-button" type="submit" disabled={isBusy}>
                  {isBusy ? <><LoaderCircle className="spin" size={17} /> Finding your way</> : <>Plan my trip <ArrowRight size={17} /></>}
                </button>
              </div>
              {error && <div className="form-error" role="alert"><CircleAlert size={16} /> {error}</div>}
            </form>

            <div className="photo-feature">
              <img
                src="https://images.unsplash.com/photo-1533105079780-92b9be482077?auto=format&fit=crop&w=1400&q=85"
                alt="White coastal buildings above the blue Aegean Sea"
              />
              <div className="photo-shade" />
              <div className="photo-caption"><span>FIELD NOTES · 01</span><strong>Leave room for the unexpected.</strong></div>
              <div className="photo-index">36° 23' N&nbsp;&nbsp; 25° 28' E</div>
            </div>
          </div>

          <aside className="agent-panel" aria-label="Travel research agents">
            <div className="agent-panel-header">
              <div>
                <span className="section-kicker">YOUR RESEARCH DESK</span>
                <h2>On the ground</h2>
              </div>
              <span className={`agent-count ${tripStatus === 'planning' ? 'is-live' : ''}`}>
                {tripStatus === 'planning' ? `${agents.length - runningCount}/${agents.length}` : tripStatus === 'completed' || tripStatus === 'partial' ? 'DONE' : '04'}
              </span>
            </div>
            <p className="agent-panel-copy">Four focused agents, working in parallel on your trip.</p>

            <div className="agent-list">
              {agents.map((agent, index) => (
                <article className={`agent-row ${agent.status}`} key={agent.id}>
                  <div className="agent-icon"><AgentIcon id={agent.id} /></div>
                  <div className="agent-details">
                    <div className="agent-title-row"><h3>{agent.name}</h3><span className={`status-label ${agent.status}`}>{statusLabel(agent.status)}</span></div>
                    <p>{agent.status === 'idle' ? agent.subtitle : agent.message}</p>
                    {agent.sources.length > 0 && (
                      <div className="source-line">
                        {agent.sources.slice(0, 2).map((source, sourceIndex) => {
                          const href = sourceHref(source.url);
                          return href
                            ? <a key={`${source.title}-${sourceIndex}`} href={href} target="_blank" rel="noreferrer">{source.title}<ArrowDownRight size={12} /></a>
                            : <span className="plain-source" key={`${source.title}-${sourceIndex}`}>{source.title}</span>;
                        })}
                      </div>
                    )}
                  </div>
                  <div className="agent-step">0{index + 1}</div>
                </article>
              ))}
            </div>

            <div className="agent-status-bar">
              <span className={`status-orb ${isBusy ? 'pulse' : tripStatus === 'completed' ? 'done' : ''}`}>{tripStatus === 'completed' ? <Check size={12} /> : <span />}</span>
              <span>{tripStatus === 'completed' ? 'Research complete' : tripStatus === 'partial' ? 'Some agents need a retry' : tripStatus === 'planning' ? `${runningCount || agents.length} agents working now` : 'Waiting for your destination'}</span>
              {tripId && <span className="trip-ref">TRIP {tripId.slice(0, 6).toUpperCase()}</span>}
            </div>

          </aside>
        </section>

        <section className="results-section" aria-live="polite">
          <div className="results-heading">
            <div><span className="section-kicker">LIVE BRIEFING</span><h2>{tripStatus === 'completed' ? 'Your trip, taking shape' : 'Your travel brief'}</h2></div>
            <button className="text-button" type="button" onClick={() => setShowDetails((current) => !current)}>
              {showDetails ? 'Hide details' : 'How this works'} <ChevronDown className={showDetails ? 'rotate' : ''} size={16} />
            </button>
          </div>
          {showDetails && <p className="details-note">Each specialist is an AI agent that chooses its own live web searches, reads the results, and keeps searching until it has enough evidence. A coordinator then merges their findings. Every recommendation links its sources with retrieval times. Prices and availability are research leads, not confirmed offers. VamsDest never books or purchases travel.</p>}
          {tripSummary && <article className="package-summary"><span className="section-kicker">COORDINATED PACKAGE BRIEF</span><p>{tripSummary}</p></article>}

          {tripStatus === 'idle' && !tripId ? (
            <div className="empty-brief"><span className="empty-symbol"><ArrowDownRight size={20} /></span><p>Your agents’ notes will gather here.</p><span>Start with a destination and dates above.</span></div>
          ) : (
            <div className="brief-grid">
              {agents.map((agent) => (
                <article className={`brief-card ${agent.status}`} key={agent.id}>
                  <div className="brief-card-heading"><span><AgentIcon id={agent.id} size={16} /></span><span>{agent.name}</span>{agent.status === 'running' || agent.status === 'summarizing' ? <LoaderCircle className="spin brief-spinner" size={15} /> : agent.status === 'completed' ? <Check size={15} className="brief-check" /> : null}</div>
                  {agent.status === 'idle' ? <p className="brief-placeholder">Notes will appear while the agent researches.</p> : <p className="brief-summary">{agent.message}</p>}
                  {agent.searches.length > 0 && (
                    <ul className="search-trail" aria-label={`${agent.name} searches`}>
                      {agent.searches.map((query, index) => <li key={`${agent.id}-search-${index}`}>{query}</li>)}
                    </ul>
                  )}
                  {agent.sources.length > 0 && (
                    <div className="source-list">
                      {agent.sources.map((source, index) => {
                        const href = sourceHref(source.url);
                        return <div className="source-item" key={`${source.title}-brief-${index}`}>
                          {href ? <a href={href} target="_blank" rel="noreferrer">{source.title}<ArrowRight size={13} /></a> : <span>{source.title}</span>}
                          <p>{source.excerpt}</p>
                          {source.retrievedAt && <time dateTime={source.retrievedAt}>Retrieved {new Date(source.retrievedAt).toLocaleString()}</time>}
                        </div>;
                      })}
                    </div>
                  )}
                </article>
              ))}
            </div>
          )}
        </section>

        <section className="chat-section" aria-label="Chat with the travel assistant">
          <div className="chat-heading">
            <span className="chat-icon"><MessageCircle size={18} /></span>
            <div><span className="section-kicker">YOUR TRAVEL COMPANION</span><h2>Keep the conversation going</h2></div>
          </div>
          <div className="chat-messages" aria-live="polite">
            {chatMessages.length === 0 ? (
              <p className="chat-empty">Start a trip to ask follow-up questions about the research.</p>
            ) : chatMessages.map((message, index) => (
              <div className={`chat-message ${message.role}`} key={`${message.role}-${index}`}>
                <span>{message.role === 'user' ? 'YOU' : 'VAMSDEST'}</span><p>{message.content}</p>
              </div>
            ))}
            {chatBusy && <div className="chat-message assistant"><span>VAMSDEST</span><p><LoaderCircle className="spin" size={16} /> Reviewing your trip notes…</p></div>}
          </div>
          <form className="chat-form" onSubmit={sendMessage}>
            <input
              value={chatInput}
              onChange={(event) => setChatInput(event.target.value)}
              placeholder={tripId ? 'Ask about your trip research…' : 'Your conversation begins with a destination'}
              maxLength={600}
              disabled={!tripId || tripStatus === 'planning' || chatBusy}
              aria-label="Message VamsDest"
            />
            <button type="submit" aria-label="Send message" title="Send message" disabled={!tripId || chatBusy || !chatInput.trim()}><Send size={17} /></button>
          </form>
          <div className="chat-footnote">VamsDest uses your trip research to answer. Verify time-sensitive travel details with the provider.</div>
        </section>
      </main>

      <footer className="page-footer"><span>VAMSDEST <i>·</i> TRAVEL, CONSIDERED</span><span>Research is a starting point. Your journey is yours.</span></footer>
    </div>
  );
}

function statusLabel(status: AgentStatus) {
  if (status === 'running') return 'SEARCHING';
  if (status === 'summarizing') return 'THINKING';
  if (status === 'completed') return 'READY';
  if (status === 'failed') return 'RETRY';
  return 'STANDBY';
}

export default App;